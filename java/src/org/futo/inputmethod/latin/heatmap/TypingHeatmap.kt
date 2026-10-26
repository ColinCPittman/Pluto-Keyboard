package org.futo.inputmethod.latin.heatmap

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import org.futo.inputmethod.accessibility.AccessibilityUtils
import org.futo.inputmethod.keyboard.Key
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.settings.SettingsValues
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.uix.Tuning
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Binds a [HeatmapLayout] to the [Key] objects of one [Keyboard]. Built on the UI thread and
 * used by [org.futo.inputmethod.keyboard.KeyDetector] on every touch event.
 */
class HeatmapKeyboardBinding internal constructor(
    val keyboard: Keyboard,
    val modelVersion: Int,
    /** Null when the heatmap should not be applied to this keyboard. */
    val layout: HeatmapLayout?,
    private val slotByKey: IdentityHashMap<Key, Int>,
    private val keyBySlot: Array<Key?>
) {
    val isActive: Boolean get() = layout != null

    // Scratch buffers, since key detection only ever happens on the UI thread.
    private val scratchSlots = IntArray(MAX_CANDIDATES)
    private val scratchBoosted = BooleanArray(MAX_CANDIDATES)

    /**
     * Detects the key for a touch (keyboard coordinates). Returns null when the touch should be
     * handled by the stock detector (the heatmap is inactive, the touch is on a non-letter key
     * (those keep exact hitboxes), or the touch isn't on any key).
     */
    fun detect(touchX: Int, touchY: Int, nearestKeys: List<Key>, boostedCodePoints: Set<Int>?): Key? {
        val layout = layout ?: return null
        var onLetterKey = false
        for (i in nearestKeys.indices) {
            val key = nearestKeys[i]
            if (key.isOnKey(touchX, touchY)) {
                if (slotByKey[key] == null) return null
                onLetterKey = true
            }
        }
        if (!onLetterKey) return null

        val boosting = boostedCodePoints != null && boostedCodePoints.isNotEmpty()
        var count = 0
        for (i in nearestKeys.indices) {
            if (count >= MAX_CANDIDATES) break
            val key = nearestKeys[i]
            val slot = slotByKey[key] ?: continue
            scratchSlots[count] = slot
            scratchBoosted[count] = boosting && boostedCodePoints!!.contains(key.code)
                    && key.distanceToEdge(touchX, touchY) <= min(key.width, key.height) * 0.5f
            count++
        }
        // Evaluate at the pixel centre so that boundaries match Rect.contains() semantics.
        val slot = layout.select(touchX + 0.5f, touchY + 0.5f, scratchSlots,
            if (boosting) scratchBoosted else null, count)
        return if (slot < 0) null else keyBySlot[slot]
    }

    companion object {
        private const val MAX_CANDIDATES = 32
    }
}

/**
 * Adaptive typing heatmap. It learns where this user actually taps for each letter key and adapts
 * key detection (and the tap coordinates given to the suggestion engines) accordingly.
 *
 * Threading: learning, key detection and UI all run on the main thread, and the model is guarded by
 * [lock] so it can be loaded/saved on a background thread. The hot path (key detection) only
 * reads immutable [HeatmapKeyboardBinding]s.
 */
object TypingHeatmap {
    private const val TAG = "TypingHeatmap"
    private const val FILE_NAME = "typing_heatmap.json"
    private const val SAVE_EVERY_N_WORDS = 25
    /** Committed words held back so later corrections can relabel them (about two sentences). */
    private const val RECENT_WORDS = 24

    private val lock = Any()
    private var appContext: Context? = null
    private var model = HeatmapModel() // guarded by lock
    private var loaded = false // guarded by lock
    private var loading = false // guarded by lock
    private var generation = 0 // guarded by lock and bumped on reset
    private var dirty = false // guarded by lock
    private var wordsSinceSave = 0 // guarded by lock

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TypingHeatmapIO").apply { isDaemon = true }
    }

    @Volatile
    private var modelVersion = 0

    /** Binding last used by the main keyboard's detector, used for coordinate correction. */
    @Volatile
    private var currentBinding: HeatmapKeyboardBinding? = null

    @Volatile
    private var lastAlphabetKeyboard: Keyboard? = null
    @Volatile
    private var lastLandscape = false

    /** Keyboard and heatmap profile a buffered word was typed on, and its path if it was swiped. */
    private class WordContext(val profileId: String, val keyboard: Keyboard, val path: SwipePath? = null)

    /**
     * The last [RECENT_WORDS] committed words, held back (with their raw taps) so that a later
     * correction of one of them relabels its taps before they are learned. Main thread only.
     */
    private val recentWords = RecentWordBuffer<WordContext>(RECENT_WORDS)

    /** True between committing a word and starting the next one (a delete then edits that word). */
    private var newestWordOpen = false

    /** Text of the existing word the user resumed for editing, until that word is committed. */
    private var resumedFrom: String? = null

    /**
     * The word being typed as it was right before its first backspace ("sebt" before fixing it to "sent"),
     * with its taps, and paired with the finished word on commit. Main thread only.
     */
    private var originalAttempt: Pair<String, List<RecordedTap>>? = null

    @JvmStatic
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        loadAsyncIfNeeded()
    }

    @JvmStatic
    fun getModelVersion(): Int = modelVersion

    /**
     * Logs a learning detail, words included, but only when developer mode and Developer > "Log typing and
     * swipe learning" are both on. These logs are for tuning, and keyboard input is private, so nothing is
     * logged otherwise.
     */
    internal fun devLog(tag: String, message: () -> String) {
        val ctx = appContext ?: return
        Tuning.learningLog(ctx, tag, message)
    }

    private fun devLog(message: () -> String) = devLog(TAG, message)

    private fun settings(): SettingsValues? = try {
        Settings.getInstance().current
    } catch (e: Exception) {
        null
    }

    @JvmStatic
    fun isEnabled(settingsValues: SettingsValues?): Boolean =
        settingsValues?.mUseTypingHeatmap ?: false

    private fun isUnlocked(): Boolean {
        val ctx = appContext ?: return false
        return try {
            ctx.isDirectBootUnlocked
        } catch (e: Exception) {
            false
        }
    }

    private fun dataFile(): File? {
        val ctx = appContext ?: return null
        if (!isUnlocked()) return null
        return File(ctx.filesDir, FILE_NAME)
    }

    // ---------------------------------------------------------------------------------------
    // Persistence

    private fun loadAsyncIfNeeded() {
        val gen: Int
        synchronized(lock) {
            if (loaded || loading) return
            if (!isUnlocked()) return // Credential-protected storage not available yet
            loading = true
            gen = generation
        }
        ioExecutor.execute { loadNow(gen) }
    }

    private fun loadNow(gen: Int) {
        var parsed: HeatmapModel? = null
        try {
            val file = dataFile()
            if (file != null && file.exists()) {
                parsed = HeatmapModel.fromJson(file.readText())
                if (parsed == null) {
                    Log.w(TAG, "Discarding corrupt or incompatible heatmap data")
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load heatmap data", e)
        }
        synchronized(lock) {
            loading = false
            if (!loaded && gen == generation) {
                if (parsed != null) model = parsed
                loaded = true
                modelVersion++
            }
        }
    }

    /** Blocks until the model has been loaded (for the settings UI, so call off the main thread). */
    fun ensureLoadedBlocking(context: Context) {
        init(context)
        try {
            // Tasks run in order, so once this no-op runs any pending load has completed.
            ioExecutor.submit { }.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Timed out waiting for heatmap load", e)
        }
    }

    private fun scheduleSave() {
        val text: String
        val gen: Int
        synchronized(lock) {
            if (!dirty || !loaded) return
            text = try {
                model.toJson()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize heatmap", e)
                return
            }
            dirty = false
            wordsSinceSave = 0
            gen = generation
        }
        ioExecutor.execute { writeFile(gen, text) }
    }

    /** Writes [text] to the data file unless the model was replaced or reset since [gen]. IO thread only. */
    private fun writeFile(gen: Int, text: String) {
        synchronized(lock) { if (gen != generation) return }
        val file = dataFile() ?: return
        try {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) Log.e(TAG, "Failed to move heatmap file into place")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save heatmap data", e)
        }
    }

    /** Deletes all learned data. */
    fun reset() {
        synchronized(lock) {
            model = HeatmapModel()
            loaded = true
            dirty = false
            wordsSinceSave = 0
            generation++
            modelVersion++
        }
        recentWords.clear()
        newestWordOpen = false
        resumedFrom = null
        currentBinding = null
        ioExecutor.execute {
            val file = dataFile() ?: return@execute
            file.delete()
            File(file.parentFile, "$FILE_NAME.tmp").delete()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings backup (Settings > Miscellaneous > Export / Import configuration)

    /** Name of the heatmap entry in a settings backup. */
    const val BACKUP_ENTRY_NAME = FILE_NAME

    /**
     * The learned data as JSON for a settings backup, or null when nothing has been learned.
     * Uses the in-memory model, so words learned since the last save are included. Call off the
     * main thread.
     */
    fun exportJson(context: Context): String? {
        ensureLoadedBlocking(context)
        synchronized(lock) {
            if (loaded) {
                if (model.isEmpty()) return null
                return try {
                    model.toJson()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to serialize heatmap for backup", e)
                    null
                }
            }
        }
        // Not loaded yet (should not happen while settings are open), so fall back to the saved file.
        return try {
            dataFile()?.takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read heatmap file for backup", e)
            null
        }
    }

    /**
     * Replaces all learned data with [json] from a settings backup and saves it. The keyboard
     * picks up the new model on the next key press. Returns false (and keeps the current data)
     * when [json] is not valid heatmap data. Call off the main thread.
     */
    fun importJson(context: Context, json: String): Boolean {
        init(context)
        val parsed = HeatmapModel.fromJson(json)
        if (parsed == null) {
            Log.w(TAG, "Backup contains invalid heatmap data; keeping the current data")
            return false
        }
        val text = try {
            parsed.toJson()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize imported heatmap", e)
            return false
        }
        val gen: Int
        synchronized(lock) {
            model = parsed
            loaded = true
            dirty = false
            wordsSinceSave = 0
            generation++ // also stops a pending load or save from overwriting the imported model
            gen = generation
            modelVersion++
        }
        currentBinding = null
        // Words still held back were typed against the old model, so drop them (main thread state).
        Handler(Looper.getMainLooper()).post {
            recentWords.clear()
            newestWordOpen = false
            resumedFrom = null
            originalAttempt = null
        }
        ioExecutor.execute { writeFile(gen, text) }
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Keyboard binding & key detection

    @JvmStatic
    fun profileIdFor(keyboard: Keyboard, landscape: Boolean): String {
        val id = keyboard.mId
        val sb = StringBuilder(id.mKeyboardLayoutSetName ?: "unknown")
        sb.append(if (landscape) "|landscape" else "|portrait")
        if (id.mIsSplitLayout) sb.append("|split")
        return sb.toString()
    }

    private fun isLetterKey(key: Key): Boolean =
        !key.isSpacer && key.width > 0 && key.height > 0 && Character.isLetter(key.code)

    /** Called by MainKeyboardView whenever a keyboard is set. */
    @JvmStatic
    fun onMainKeyboardChanged(keyboard: Keyboard, landscape: Boolean) {
        lastLandscape = landscape
        if (keyboard.mId.isAlphabetKeyboard) lastAlphabetKeyboard = keyboard
        loadAsyncIfNeeded()
    }

    /**
     * Builds the binding for a keyboard with the current model. Always returns a binding (which
     * may be inactive), so that callers can cache it by (keyboard, model version).
     */
    @JvmStatic
    fun createBinding(keyboard: Keyboard, landscape: Boolean): HeatmapKeyboardBinding {
        val version = modelVersion
        val empty = HeatmapKeyboardBinding(keyboard, version, null, IdentityHashMap(), arrayOf())
        if (!keyboard.mId.isAlphabetKeyboard) return empty
        // Explore-by-touch users rely on keys being exactly where they are announced
        val touchExploration = try {
            AccessibilityUtils.getInstance().isTouchExplorationEnabled
        } catch (e: Exception) {
            false
        }
        if (touchExploration) return empty

        val params: Map<Int, EffectiveKeyParams> = synchronized(lock) {
            if (!loaded) return empty
            val profile = model.profile(profileIdFor(keyboard, landscape)) ?: return empty
            profile.effectiveParams()
        }
        if (params.isEmpty()) return empty

        val keys = ArrayList<Key>()
        val layoutKeys = ArrayList<LayoutKey>()
        for (key in keyboard.sortedKeys) {
            if (!isLetterKey(key)) continue
            val hb = key.hitBox
            keys.add(key)
            layoutKeys.add(LayoutKey(Character.toLowerCase(key.code),
                hb.left, hb.top, hb.right, hb.bottom, key.width, key.height))
        }
        val layout = HeatmapLayout.build(layoutKeys) { params[it] }
        if (!layout.hasAdaptation) return empty

        val slotByKey = IdentityHashMap<Key, Int>(keys.size * 2)
        keys.forEachIndexed { i, k -> slotByKey[k] = i }
        return HeatmapKeyboardBinding(keyboard, version, layout, slotByKey, keys.toTypedArray())
    }

    @JvmStatic
    fun setCurrentBinding(binding: HeatmapKeyboardBinding?) {
        currentBinding = binding
    }

    /** Whether the latest touch on the keyboard came from a real input device (see [onKeyboardTouch]). */
    @JvmStatic
    var lastTouchFromScreen = true
        private set

    /**
     * Called for every touch on the main keyboard. Taps injected over adb (`input tap`, test scripts)
     * come from the virtual device (id -1, or 0) rather than the touchscreen, so their words are not
     * learned, since they would pull the heatmap toward the script's tap points.
     */
    @JvmStatic
    fun onKeyboardTouch(event: MotionEvent) {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return
        val fromScreen = event.deviceId > 0
        if (fromScreen != lastTouchFromScreen) {
            devLog { if (fromScreen) "Learning from taps again" else "Not learning from injected taps (device ${event.deviceId})" }
        }
        lastTouchFromScreen = fromScreen
    }

    /**
     * Maps a tap on a letter key (keyboard coordinates) into the stock region of that key, so the
     * suggestion engines see the tap where this user "means" it. Returns the coordinates packed
     * with [HeatmapLayout.packXY], and unchanged when no adaptation applies.
     */
    @JvmStatic
    fun correctTapCoordinates(codePoint: Int, x: Int, y: Int): Long {
        val layout = currentBinding?.layout
        if (layout == null || x < 0 || y < 0) return HeatmapLayout.packXY(x, y)
        val slot = layout.slotForCode(codePoint)
        if (slot < 0) return HeatmapLayout.packXY(x, y)
        return layout.correct(slot, x, y)
    }

    // ---------------------------------------------------------------------------------------
    // Learning

    private fun canLearn(sv: SettingsValues?): Boolean = isEnabled(sv) && canLearnAnything(sv)

    private fun canLearnSwipes(sv: SettingsValues?): Boolean = AdaptiveSwipe.isEnabled(sv) && canLearnAnything(sv)

    /** Privacy checks shared by taps and swipes (no password, incognito or no-learning fields). */
    private fun canLearnAnything(sv: SettingsValues?): Boolean {
        if (sv == null) return false
        val ia = sv.mInputAttributes ?: return false
        if (ia.mNoLearning || ia.mIsPasswordField || !ia.mShouldShowSuggestions) return false
        if (!sv.isPersonalizationEnabled) return false
        return isUnlocked()
    }

    private fun keyGeometry(keyboard: Keyboard, codePoint: Int): KeyGeometry? {
        val key = keyboard.getKey(codePoint) ?: keyboard.getKey(Character.toUpperCase(codePoint))
            ?: return null
        if (key.width <= 0 || key.height <= 0) return null
        return KeyGeometry(
            key.x + key.width / 2f, key.y + key.height / 2f,
            key.width.toFloat(), key.height.toFloat()
        )
    }

    /** A symbol other than an apostrophe or hyphen (digits and letters are fine). */
    private fun hasTypedSymbol(word: CharSequence): Boolean =
        word.any { !it.isLetterOrDigit() && it != '\'' && it != '’' && it != '-' && !Character.isSurrogate(it) }

    /** Called by WordComposer when a word is committed. */
    @JvmStatic
    fun onWordCommitted(
        typedWord: String, committedWord: CharSequence?, taps: List<RecordedTap>?,
        isBatchMode: Boolean, isResumed: Boolean, swipePath: SwipePath?
    ) {
        if (appContext == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val committed = committedWord?.toString() ?: ""
        val from = resumedFrom
        resumedFrom = null
        val attempt = originalAttempt
        originalAttempt = null
        val sv = settings()
        val learnTaps = canLearn(sv)
        val learnSwipes = canLearnSwipes(sv)
        if (!learnTaps && !learnSwipes) return
        // A word with symbols typed into it ("hello!", "Tr0ub4dor&x", see InputLogic
        // shouldAttachSymbolToWord) is not a word to learn letter taps from.
        if (hasTypedSymbol(typedWord) || hasTypedSymbol(committed)) {
            newestWordOpen = false
            return
        }

        if (isResumed) {
            // The user went back to an existing word (or backspaced into the one just typed). Its
            // original taps (if it was typed recently) are relabeled with the corrected word, and the
            // resumed text itself has no usable taps. An emptied word is not a correction.
            if (from != null && committed.any { Character.isLetter(it) }) {
                val entry = recentWords.relabel(from, committed)
                if (from != committed) {
                    devLog {
                        if (entry != null) "Relabeled a recent word after a correction: \"$from\" -> \"$committed\""
                        else "Corrected word \"$from\" -> \"$committed\" was not in the recent-word buffer"
                    }
                }
            }
            newestWordOpen = false
            return
        }

        val keyboard = lastAlphabetKeyboard ?: return
        newestWordOpen = true
        // A word typed where words were just deleted with backspace replaces the oldest of them
        // (delete "dent", type "sent") and RecentWordBuffer relabels its taps.
        val profileId = profileIdFor(keyboard, lastLandscape)
        val entry = if (isBatchMode) {
            // A swiped word keeps its path (in memory only) so it is learned under the word it finally becomes.
            if (learnSwipes && swipePath != null && lastTouchFromScreen) {
                devLog { "Swiped \"$typedWord\", committed as \"$committed\"" }
                RecentWordBuffer.Entry(WordContext(profileId, keyboard, swipePath), typedWord, committed, emptyList())
            } else {
                devLog {
                    "Swiped \"$typedWord\" not kept for learning (" + when {
                        !learnSwipes -> "adaptive swipe is off"
                        swipePath == null -> "no single-finger path"
                        else -> "injected input"
                    } + ")"
                }
                null
            }
        } else if (!learnTaps || taps.isNullOrEmpty()) null else
            RecentWordBuffer.Entry(WordContext(profileId, keyboard), typedWord, committed, taps)
        // The word was fixed with backspace while typing it, so its original taps are learned under the
        // finished word (the retyped letters are learned from the word's own taps).
        if (attempt != null && attempt.first != committed && !isBatchMode && learnTaps) {
            learn(recentWords.add(RecentWordBuffer.Entry(
                WordContext(profileIdFor(keyboard, lastLandscape), keyboard), attempt.first, committed, attempt.second)))
            devLog { "Kept the original attempt \"${attempt.first}\" of a word fixed while typing to \"$committed\"" }
        }
        val pending = recentWords.deletedCount
        learn(recentWords.onWordTyped(committed, entry))
        if (pending > 0) devLog { "\"$committed\" replaces a deleted word (${pending - 1} deleted left)" }
    }

    /** Called by WordComposer before a backspace inside the word being typed is applied. */
    @JvmStatic
    fun onComposingDelete(typedWord: String, taps: List<RecordedTap>?) {
        // WordComposer is also used by the spell checker on binder threads
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (originalAttempt == null && taps != null) originalAttempt = typedWord to taps
    }

    /**
     * Called when a swipe starts. The previous word was committed, and a backspace now deletes the
     * swiped word (see [onSwipeRejected]) rather than starting to delete committed words.
     */
    @JvmStatic
    fun onGestureStarted() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        recentWords.onTypingResumed()
        newestWordOpen = false
    }

    /**
     * Called when a swiped word that was still being composed is deleted with one backspace. It is set
     * aside like a deleted word, so the word swiped or typed in its place relabels its path.
     */
    @JvmStatic
    fun onSwipeRejected(word: String, path: SwipePath?) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (path == null || word.isEmpty() || !lastTouchFromScreen) return
        if (!canLearnSwipes(settings())) return
        val keyboard = lastAlphabetKeyboard ?: return
        val added = recentWords.addDeleted(RecentWordBuffer.Entry(
            WordContext(profileIdFor(keyboard, lastLandscape), keyboard, path), word, word, emptyList()))
        devLog { if (added) "Set aside the deleted swipe \"$word\"" else "Deleted swipe \"$word\" not set aside (other deleted words are waiting)" }
    }

    /** Called for every input event before it is processed. */
    @JvmStatic
    fun onInputEvent(keyCode: Int, codePoint: Int) {
        if (keyCode == Constants.CODE_DELETE) {
            // Backspacing over committed words sets them aside. If the user types replacements, their
            // taps are relabeled with those, otherwise they are dropped (it may have been an undo).
            val before = recentWords.deletedCount
            recentWords.onDelete(startsRun = newestWordOpen)
            newestWordOpen = false
            if (recentWords.deletedCount > before) devLog { "Set aside a deleted word (${recentWords.deletedCount} pending)" }
        } else if (codePoint > 0 && Character.isLetter(codePoint)) {
            // Next word started.
            recentWords.onTypingResumed()
            newestWordOpen = false
        }
    }

    /**
     * Called when an existing word is resumed for editing (e.g. the cursor moved into it, or a
     * suggestion for it is about to replace it). [codePoints] is the word's current text.
     */
    @JvmStatic
    fun onWordResumed(codePoints: IntArray) {
        // WordComposer is also used by the spell checker on binder threads, but only the IME's own
        // composer (main thread) is relevant.
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val text = String(codePoints, 0, codePoints.size)
        resumedFrom = text
        originalAttempt = null
        recentWords.onWordResumed(text)
        newestWordOpen = false
    }

    /** Called when the keyboard is hidden, so buffered words are final. */
    @JvmStatic
    fun onKeyboardHidden() {
        originalAttempt = null
        val words = recentWords.drain()
        if (words.isNotEmpty()) devLog { "Keyboard closed, learning ${words.size} buffered words" }
        learn(words)
        newestWordOpen = false
        resumedFrom = null
        scheduleSave()
    }

    /** Aligns final words with their taps and adds the resulting samples to the model. */
    private fun learn(words: List<RecentWordBuffer.Entry<WordContext>>) {
        if (words.isEmpty()) return
        // The field checks (password, incognito...) were done when each word was buffered. They aren't
        // repeated here, since the field is often gone already when the keyboard closes.
        val sv = settings()
        val learnTaps = isEnabled(sv)
        val learnSwipes = AdaptiveSwipe.isEnabled(sv)
        if ((!learnTaps && !learnSwipes) || sv?.isPersonalizationEnabled != true || !isUnlocked()) {
            devLog { "Dropped ${words.size} buffered words (learning is off)" }
            return
        }
        var save = false
        for (word in words) {
            val keyboard = word.context.keyboard
            val path = word.context.path
            val profileId: String
            val samples: List<TapSample>
            if (path != null) {
                if (!learnSwipes) continue
                val result = SwipeAligner.align(word.committedWord, path) { keyGeometry(keyboard, it) }
                AdaptiveSwipe.countResult(result, word.typedWord, word.committedWord)
                samples = result.samples ?: continue
                profileId = AdaptiveSwipe.swipeProfileId(word.context.profileId)
            } else {
                if (!learnTaps) continue
                val result = TapAligner.align(word.typedWord, word.committedWord, word.taps,
                    false, false) { keyGeometry(keyboard, it) }
                samples = result.samples ?: continue
                profileId = word.context.profileId
            }
            synchronized(lock) {
                if (!loaded) return
                model.addSamples(profileId, samples)
                val profile = model.profile(profileId)
                if (profile != null && profile.schematic == null) {
                    profile.schematic = buildSchematic(keyboard)
                }
                dirty = true
                wordsSinceSave++
                if (wordsSinceSave >= SAVE_EVERY_N_WORDS) save = true
                modelVersion++
            }
        }
        if (save) scheduleSave()
    }

    /** The learned statistics of a profile's keys, copied (for building a swipe bend field), or null. */
    internal fun copyProfileKeys(profileId: String): Map<Int, KeyTapStats>? = synchronized(lock) {
        if (!loaded) return null
        model.profile(profileId)?.keys?.mapValues { it.value.copy() }
    }

    /** Deletes the profiles whose id matches [predicate] (e.g. only the swipe profiles). */
    fun resetProfiles(predicate: (String) -> Boolean) {
        synchronized(lock) {
            if (!loaded) return
            if (model.profiles.keys.removeAll(predicate)) {
                dirty = true
                modelVersion++
            }
        }
        scheduleSave()
    }

    internal fun lastAlphabetKeyboard(): Keyboard? = lastAlphabetKeyboard
    internal fun lastLandscape(): Boolean = lastLandscape

    internal fun letterKeyGeometries(keyboard: Keyboard): List<SwipeBendField.Companion.Key> =
        keyboard.sortedKeys.filter { isLetterKey(it) }.mapNotNull { key ->
            val code = Character.toLowerCase(key.code)
            keyGeometry(keyboard, code)?.let { SwipeBendField.Companion.Key(code, it) }
        }.distinctBy { it.code }

    private fun buildSchematic(keyboard: Keyboard): KeyboardSchematic? {
        val w = keyboard.mOccupiedWidth.toFloat()
        val h = keyboard.mOccupiedHeight.toFloat()
        if (w <= 0f || h <= 0f) return null
        val keys = keyboard.sortedKeys.filter { !it.isSpacer && it.width > 0 && it.height > 0 }
            .map {
                val label = if (Character.isLetter(it.code)) {
                    String(Character.toChars(Character.toLowerCase(it.code)))
                } else ""
                SchematicKey(it.code, label, it.x / w, it.y / h, it.width / w, it.height / h)
            }
        return KeyboardSchematic(w / h, keys)
    }

    // ---------------------------------------------------------------------------------------
    // UI support

    class ProfileSnapshot(
        val id: String,
        val profile: HeatmapProfile,
        val effective: Map<Int, EffectiveKeyParams>
    )

    /** Returns a deep copy of the learned data for display. */
    fun snapshot(): List<ProfileSnapshot> = synchronized(lock) {
        model.profiles.entries
            .filter { it.value.keys.isNotEmpty() }
            .sortedByDescending { it.value.totalSamples() }
            .map { (id, p) ->
                val copy = p.copy()
                ProfileSnapshot(id, copy, copy.effectiveParams())
            }
    }
}
