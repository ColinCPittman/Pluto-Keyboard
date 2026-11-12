package org.futo.inputmethod.latin.uix

import org.futo.inputmethod.latin.uix.settings.pages.ActionBarDisplayedSetting
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.TypingTextEdit
import org.futo.inputmethod.latin.inputlogic.InputLogic
import org.futo.inputmethod.latin.settings.Settings
import org.futo.voiceinput.shared.context.OwnTypingPair
import org.futo.voiceinput.shared.context.TypingConfusions
import org.futo.voiceinput.shared.context.TypingCorrectionConfig
import org.futo.voiceinput.shared.context.TypingCorrector
import org.futo.voiceinput.shared.context.TypingPairEdits
import java.util.Locale

/** Smart corrections for typing (Text prediction settings) is off by default. */
val TYPING_SMART_CORRECTIONS = SettingsKey(
    key = booleanPreferencesKey("typing_smart_corrections"),
    default = false
)

/** How readily Smart corrections for typing offers a fix, -2 (fewest) .. 2 (most), and 0 is the default. */
val TYPING_SMART_CORRECTIONS_SENSITIVITY = SettingsKey(
    key = intPreferencesKey("typing_smart_corrections_sensitivity"),
    default = 0
)

/** Fix typos that are real words ("salt an pepper"), from the typing corrections list. */
val TYPING_FIX_REAL_WORDS = SettingsKey(
    key = booleanPreferencesKey("typing_fix_real_words"),
    default = true
)

/** Attempt to fix missed typos, meaning words no dictionary knows ("ststy" -> "start"), from the letters and the sentence. */
val TYPING_FIX_MISSED_TYPOS = SettingsKey(
    key = booleanPreferencesKey("typing_fix_missed_typos"),
    default = true
)

/**
 * Fix missing, doubled and leftover words and two words in the wrong order, the slips of editing a
 * message ("a cup coffee" -> "a cup of coffee", "to to" -> "to").
 */
val TYPING_FIX_MISSING_WORDS = SettingsKey(
    key = booleanPreferencesKey("typing_fix_missing_words"),
    default = true
)

/** Fix a word whose ending doesn't fit the words around it ("a long lists" -> "a long list"). */
val TYPING_FIX_WORD_ENDINGS = SettingsKey(
    key = booleanPreferencesKey("typing_fix_word_endings"),
    default = true
)

/** Where the strip shows the typing fix chip, either on the right (next to the pinned actions) or the left. */
val TYPING_FIX_CHIP_ON_RIGHT = SettingsKey(
    key = booleanPreferencesKey("typing_fix_chip_on_right"),
    default = true
)

/**
 * Your changes to the typing corrections list. These are your own pairs, built-in pairs you switched off and
 * words that are never changed (org.futo.voiceinput.shared.context.TypingPairEdits). Empty = no changes.
 */
val TYPING_PAIRS_LIST = SettingsKey(
    key = stringPreferencesKey("typing_pairs_list"),
    default = ""
)

/** Whether the built-in typing pairs are used. When off, only your own are (your list is kept). */
val TYPING_PAIRS_BUILT_IN = SettingsKey(
    key = booleanPreferencesKey("typing_pairs_built_in"),
    default = true
)

/**
 * A fix Smart corrections for typing made in the current text field. [original] at [start] was
 * replaced by [replacement] (either may be several words, like "to to" -> "to"). [before] / [after]
 * are the characters around it when it was made, so a revert can tell whether the text there was
 * edited since ([stale]). [kind] is the engine's ("pair", "typo", "add", "remove", "ending",
 * "order" or "join").
 */
data class TypingFix(
    val id: Long,
    val start: Int,
    val original: String,
    val replacement: String,
    val before: String,
    val after: String,
    val stale: Boolean = false,
    val kind: String = "pair",
) {
    val end: Int get() = start + replacement.length

    /**
     * Whether undoing it offers Stop. Only a swap of one word for another (a pair or a missed
     * typo) has a pair to switch off or a word to never change. (Every kind's undo teaches caution.)
     */
    val canStop: Boolean get() = kind == "pair" || kind == "typo"

    /**
     * Whether [now] (the text from [before]'s start up to the cursor) still shows this fix as it
     * was made, since the text after it may have been cut short by the cursor but not edited.
     */
    fun matches(now: String): Boolean {
        val expected = before + replacement + after
        return now.startsWith(expected) ||
            (now.length < expected.length && now.length >= before.length + replacement.length && expected.startsWith(now))
    }
}

/** [fixes] after a change of length [delta] at [at], with the fixes after it moved. */
fun shiftFixes(fixes: List<TypingFix>, at: Int, delta: Int): List<TypingFix> =
    if (delta == 0) fixes else fixes.map { if (it.start > at) it.copy(start = it.start + delta) else it }

/** What the suggestion strip shows, which is the fixes of the current text field (newest last). */
object TypingFixes {
    val fixes = mutableStateOf<List<TypingFix>>(emptyList())

    /** The chip gives way while a word is being typed. */
    val hiddenWhileTyping = mutableStateOf(false)

    /** The review list is open. */
    val listOpen = mutableStateOf(false)

    /** The one word that is selected (for the "Add correction" chip), or null. */
    val selectedWord = mutableStateOf<String?>(null)

    /** A fix just undone, where the chip offers to stop that fix everywhere for a few seconds. */
    val stopOffer = mutableStateOf<TypingFix?>(null)

    /**
     * What last changed the text ("keys", "swipe", "voice", or "app" for anything else), for the
     * log line when an app drops a fix's underline.
     */
    @Volatile var lastInput = "app"

    /**
     * Set when dictation takes over the text field for a moment ([IMEManager.createInputTransaction]),
     * so the typing side keeps its fixes instead of treating it as the field closing.
     */
    @Volatile var dictationHandoff = false

    /**
     * The fix the chip shows: the closest one before the cursor, or the closest after it when
     * there's none before. It follows the cursor as you move it around the text.
     */
    val focusId = mutableStateOf<Long?>(null)

    /** The fix whose word is selected right now to show where it is ([show]), or null. */
    val showing = mutableStateOf<TypingFix?>(null)

    internal var controller: TypingCorrections? = null

    fun revert(fix: TypingFix) {
        controller?.revert(fix)
    }

    /**
     * Shows where [fix] is by selecting its word in the text for a moment. An underline is up to
     * the app (some drop it), but every text box draws a selection and scrolls to it.
     */
    fun show(fix: TypingFix) {
        controller?.show(fix)
    }

    /** Switches off [fix]'s pair ("pour → pout") in the typing corrections list. */
    fun stopFix(context: Context, fix: TypingFix) {
        stopOffer.value = null
        if (!fix.canStop) return
        val typed = fix.original.lowercase()
        val meant = fix.replacement.lowercase()
        // a missed typo has no pair to switch off, so the typed word goes on the never-change list
        val fromPair = meant in ContextModels.typingPairs(context).candidates(typed)
        controller?.launch {
            context.dataStore.edit { p ->
                val e = TypingPairEdits.parse(p[TYPING_PAIRS_LIST.key] ?: "").withoutPair(typed, meant, TypingConfusions.builtIn)
                p[TYPING_PAIRS_LIST.key] = (if (fromPair) e else e.copy(never = (e.never + typed).distinct())).serialize()
            }
            Toast.makeText(context, context.getString(R.string.typing_fix_stopped, typed, meant), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Saves your pair [typed] -> [meant] ("Add correction"), and puts [meant] in place of the
     * selected word once the keyboard is back on the text field.
     */
    fun addCorrection(context: Context, typed: String, meant: String) {
        val c = controller ?: return
        c.launch {
            context.dataStore.edit { p ->
                val e = TypingPairEdits.parse(p[TYPING_PAIRS_LIST.key] ?: "").withOwn(OwnTypingPair(typed, listOf(meant)))
                p[TYPING_PAIRS_LIST.key] = e.copy(never = e.never - typed).serialize()
            }
            // the window's own text box gives the text field back first
            delay(150)
            c.replaceSelectedWord(typed, meant)
        }
    }

    /** Marks fixes whose surroundings changed (when the list opens). */
    fun refreshStale() {
        controller?.refreshStale()
    }
}

/**
 * What Smart corrections for typing learns from you, per swap ("an>and") and per swap in a
 * context (a hash of the words before and after), but never the text itself. An undone fix makes the
 * same swap more careful next time, and a missed one you fixed yourself ("pour" changed to "pout")
 * makes it more ready, within limits. Each cancels one of the other.
 */
class TypingRevertMemory(private val get: (String) -> Int, private val put: (Map<String, Int>) -> Unit) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private constructor(prefs: android.content.SharedPreferences) : this(
        { prefs.getInt(it, 0) },
        { m -> prefs.edit().apply { m.forEach { (k, v) -> putInt(k, v) } }.apply() },
    )

    private fun ctx(prev: String, next: String) = Integer.toHexString("$prev|$next".hashCode())

    private fun record(key: String, other: String, from: String, to: String, prev: String, next: String) {
        val pair = "$from>$to"
        val inContext = "$pair@${ctx(prev, next)}"
        val out = HashMap<String, Int>()
        for (k in listOf(pair, inContext)) {
            // one of the other kind is cancelled first
            val o = get(other + k)
            if (o > 0) out[other + k] = o - 1 else out[key + k] = get(key + k) + 1
        }
        put(out)
    }

    fun recordRevert(from: String, to: String, prev: String, next: String) = record("", MISS, from, to, prev, next)

    /**
     * An undone fix left its sentence as [result] ([TypingPlan.resultHash], a number, never the
     * text). A fix that would make that same sentence again, in whatever form ("to to" -> "to" or
     * "to call" -> "call"), isn't made ([refusesResult]), also later and in other fields.
     */
    fun recordRefusedResult(result: Long) = put(mapOf(RESULT + java.lang.Long.toHexString(result) to 1))

    fun refusesResult(result: Long): Boolean = get(RESULT + java.lang.Long.toHexString(result)) > 0

    /** You changed [from] into [to] yourself where it wasn't fixed. */
    fun recordMiss(from: String, to: String, prev: String, next: String) = record(MISS, "", from, to, prev, next)

    /** Extra log-prob margin for this swap here (less than 0 means it is more ready). */
    fun caution(from: String, to: String, prev: String, next: String): Double {
        val pair = "$from>$to"
        val inContext = "$pair@${ctx(prev, next)}"
        val careful = PAIR_STEP * get(pair) + CONTEXT_STEP * get(inContext)
        val ready = MISS_PAIR_STEP * get(MISS + pair) + MISS_CONTEXT_STEP * get(MISS + inContext)
        return (careful - ready).coerceIn(-MAX_READY, MAX)
    }

    companion object {
        const val PAIR_STEP = 1.5
        const val CONTEXT_STEP = 4.0
        const val MAX = 15.0
        const val MISS_PAIR_STEP = 1.0
        const val MISS_CONTEXT_STEP = 2.0
        /** At most this much more ready (the corrector never goes below the floor of your own pairs). */
        const val MAX_READY = 4.0
        private const val MISS = "miss:"
        private const val RESULT = "result:"
        const val PREFS = "typing_reverts"

        /**
         * The words just before and after a change (lowercase, "" at an edge), from the text
         * around it ([before] / [after]), which is the context the corrector asks [caution] about.
         * A change of several words ("to to" -> "to") is kept by its whole strings, spaces and all.
         */
        fun neighbours(before: String, after: String): Pair<String, String> {
            // (like the corrector, a word before a sentence end isn't the previous word)
            val prev = Regex("([A-Za-z']+)([^A-Za-z']*)$").find(before)?.takeIf { m -> m.groupValues[2].none { it in ".?!\n" } }
                ?.groupValues?.get(1)?.lowercase() ?: ""
            val next = Regex("^\\W*([A-Za-z']+)").find(after)?.groupValues?.get(1)?.lowercase() ?: ""
            return prev to next
        }
    }
}

/**
 * Smart corrections for typing in one text field. It judges the text around the cursor as it is,
 * however it got there (typed, dictated, pasted, edited later): each sentence that changed since it
 * was last found fine is checked again in the background with the context model
 * ([ContextModels.checkTyping]), the one at the cursor first. A fix is applied where it is, before
 * or after the cursor, with an auto-correction [SuggestionSpan] (an underline offering the original,
 * where the app supports it), and listed in [TypingFixes] for the strip's chip and review list until
 * the field closes.
 */
class TypingCorrections(private val helper: IMEHelper, private val inputLogic: InputLogic) {
    private val context = helper.context
    private val memory = TypingRevertMemory(context)
    private var open = false

    /** The field's settings are read when typing, because when a field opens they may be the last field's. */
    private val fieldOk: Boolean
        get() {
            if (!open) return false
            val settings = Settings.getInstance().current
            val attrs = settings.mInputAttributes
            if (settings.mLocale.language != locale.language) modelOk = null
            locale = settings.mLocale
            return !attrs.mNoLearning && !attrs.mIsPasswordField && attrs.mShouldShowSuggestions &&
                !attrs.mInputTypeNoAutoCorrect && !attrs.mIsUriField && !attrs.mIsEmailField
        }
    private var modelOk: Boolean? = null

    /**
     * Read on every event, because right after the keyboard starts, settings may not be loaded yet when
     * the first field opens. Whether a model is installed is looked up once per field. Fixes pause
     * while the suggestion bar is hidden, since its fix button is the only way to see and undo them
     * (the setting itself stays as it is, so they come back with the bar).
     */
    private val enabled: Boolean
        get() {
            if (!fieldOk || !context.getSetting(TYPING_SMART_CORRECTIONS)) return false
            if (!context.getSetting(ActionBarDisplayedSetting)) return false
            val ok = modelOk ?: ContextModels.typingAvailable(context, locale).also {
                modelOk = it
                Log.i(TAG, "Smart corrections for typing: ${if (it) "on" else "no model for ${locale.language}"}")
            }
            return ok
        }
    private var locale: Locale = Locale.ENGLISH

    /** The running check (one at a time), and the text it checked with where that started. */
    private var running: Job? = null
    private var runningRegion: Pair<Int, String>? = null

    /**
     * A fix found while a word was being typed (or text was selected). It goes in once that's over,
     * if the text it checked is still there ([region], which started at [regionAt] and at
     * [regionStart] of the text the check read).
     */
    private class Waiting(val regionAt: Int, val region: String, val regionStart: Int, val fix: TypingCorrector.Fix)
    private var waiting: Waiting? = null
    private var nextId = 1L

    /**
     * Words to leave alone, picked from the strip or fixes you undid (by the button or by hand),
     * found again by the text around them, so edits elsewhere never lose them. Fixes already
     * made are left alone too (they're found the same way).
     */
    private val kept = ArrayList<TypingMark>()

    /** Fixes undone in this field ("an>and"), which are never made again here. */
    private val refused = HashSet<String>()

    /**
     * Words of finished, checked sentences that have pairs and were left as typed. When one is
     * changed later by hand into one of its pair words, that was a missed fix.
     */
    private val seen = ArrayList<TypingMark>()

    /**
     * Checks that found nothing to fix, by what they read ([TypingPlan.key]). They depend only on
     * the text and the settings, so they're kept across fields (a draft that comes back isn't
     * checked again).
     */
    private val checked = CheckedSentences()

    /** The next look at the text ([request]), when it's due and whether it was asked for right away. */
    private var passJob: Job? = null
    private var passAt = 0L
    private var passNow = false

    /** The quick look right after a word ends ([requestQuick]). */
    private var quickJob: Job? = null

    /** Finding the fixes of a field that came back ([onStartInput]). */
    private var restoreJob: Job? = null

    /**
     * The sentences that were in the field when it opened ([TypingPlan.baseline]), which are left
     * alone until you change them, and whether they're still to be read.
     */
    private val baseline = HashSet<Long>()
    private var baselinePending = false

    /**
     * The field had more text than was read for the [baseline] ([baselinePartial]), read around
     * [baselineAnchor] (which typing moves along with the text). When you then move the cursor far
     * from there, the text around the new place is read as already there too, before any of it is
     * checked. [lastSelStart] is where the cursor was at the last report.
     */
    private var baselinePartial = false
    private var baselineAnchor = -1
    private var lastSelStart = -1

    /**
     * Whether the last full look found nothing but spaces after the cursor on its line, and you
     * haven't moved the cursor since. Only then does the quick look after a key take the text
     * before the cursor for a sentence being typed at its end.
     */
    private var typingAtEnd = false

    /** Checks that failed, by [TypingPlan.key], so a sentence the model can't take is given up on. */
    private val failedChecks = HashMap<Long, Int>()

    /**
     * This field's editor mishandled a fix after the cursor (it selected something else), so fixes
     * after the cursor are off here ([afterOff]), or a fix didn't read back as written, so fixing
     * is off here altogether ([broken]). Both last until the field closes.
     */
    private var afterOff = false
    private var broken = false

    /**
     * The selections our own edit passes through on its way (the range it selects, the cursor
     * after it writes), which the app may report. They're not your doing, so they're ignored
     * ([isOwnEditStep]) until [ownStepsUntil].
     */
    private val ownSteps = ArrayList<Pair<Int, Int>>()
    private var ownStepsUntil = 0L

    /** When you last typed or the text or cursor last changed (an edited sentence waits [QUIET_MS] after it). */
    private var lastInputAt = 0L

    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = helper.lifecycleScope.launch(block = block)

    /** The text field this was set up for, so coming back from dictation can tell it's the same one. */
    private var fieldKey: String? = null

    /**
     * The fixes of the field that just closed, kept for [PARK_MS] in case the same field comes back
     * (the keyboard restarted, you switched apps and came back, or the app reloaded its draft, which
     * Google Messages does as plain text without the underlines).
     */
    private var parked: Parked? = null
    private class Parked(
        val key: String, val at: Long, val fixes: List<TypingFix>, val kept: List<TypingMark>, val refused: Set<String>,
        /** The field's [baseline], so text that was left alone before it closed still is when it comes back. */
        val baseline: Set<Long>,
        val baselinePartial: Boolean,
        val baselineAnchor: Int,
    )

    /** Stops the running check and every look and timer still to come, and drops a fix that was waiting. */
    private fun stopChecks() {
        running?.cancel()
        running = null
        runningRegion = null
        passJob?.cancel()
        passJob = null
        quickJob?.cancel()
        quickJob = null
        restoreJob?.cancel()
        restoreJob = null
        healJob?.cancel()
        waiting = null
    }

    /** Forgets what was kept per field (the words left alone, undone fixes and checked words). */
    private fun forgetField() {
        kept.clear()
        refused.clear()
        seen.clear()
        waiting = null
    }

    private fun currentFieldKey(): String? = helper.getCurrentEditorInfo()?.let { "${it.packageName}/${it.fieldId}/${it.inputType}" }

    fun onStartInput() {
        stopChecks()
        TypingFixes.controller = this
        // what was dictated is looked at once voice input closes
        ContextModels.typingResumed = { helper.lifecycleScope.launch(Dispatchers.Main) { request(OPEN_DELAY_MS) } }
        // back from a dictation in the same field, so the fixes (and their underlines) are kept,
        // and what was dictated is checked like any other change
        val back = TypingFixes.dictationHandoff && currentFieldKey() == fieldKey
        TypingFixes.dictationHandoff = false
        if (back) {
            open = true
            modelOk = null
            // (the text from before was read when the dictation began, see onFinishInput, and what
            // was dictated must never be taken for it)
            baselinePending = false
            request(OPEN_DELAY_MS)
            return
        }
        fieldKey = currentFieldKey()
        val same = parked?.takeIf { it.key == fieldKey && System.currentTimeMillis() - it.at < PARK_MS }
        parked = null
        TypingFixes.selectedWord.value = null
        TypingFixes.stopOffer.value = null
        TypingFixes.fixes.value = emptyList()
        TypingFixes.listOpen.value = false
        TypingFixes.hiddenWhileTyping.value = false
        heals.clear()
        forgetField()
        afterOff = false
        broken = false
        // the text already in a new field isn't checked until you change it
        baseline.clear()
        baselinePending = true
        baselinePartial = false
        baselineAnchor = -1
        lastSelStart = -1
        typingAtEnd = false
        open = true
        modelOk = null
        request(OPEN_DELAY_MS)
        if (same != null) {
            // maybe the same field is back. Its fixes come back (with their underlines put back),
            // and what you undid or picked there still counts, but only if most of them are still in
            // its text, since an app may give different conversations the same field. Then it isn't
            // taken as a new field, so what you typed just before leaving still gets its check.
            restoreJob = launch {
                delay(RESTORE_DELAY_MS)
                restoreJob = null
                val w = around()
                val found = same.fixes.mapNotNull { locate(it.copy(stale = false), w) }
                val taken = found.isNotEmpty() && found.size >= same.fixes.size * RESTORE_SHARE
                diag("Same field back? ${found.size} of ${same.fixes.size} fixes still in the text, ${if (taken) "same field" else "a new one"}")
                if (taken) {
                    TypingFixes.fixes.value = found
                    kept.addAll(same.kept)
                    refused.addAll(same.refused)
                    // what was left alone there before still is (and what you typed since isn't in it)
                    baseline.addAll(same.baseline)
                    baselinePartial = same.baselinePartial
                    baselineAnchor = same.baselineAnchor
                    baselinePending = false
                    updateFocus()
                    heal()
                }
                request(0L)
            }
        }
    }

    /**
     * Reads the text that's in the field now (up to [BASELINE_CHARS] on each side of the cursor)
     * as the field's [baseline], when a new field opens, and again when the cursor is moved far
     * from there in a field with more text than that.
     */
    private fun takeBaseline() {
        if (!baselinePending) return
        val sel = conn.expectedSelectionStart
        if (sel < 0) return
        val before = conn.getTextBeforeCursor(BASELINE_CHARS, 0)?.toString() ?: return
        val selected = if (conn.hasSelection()) conn.getSelectedText(0)?.toString() ?: "" else ""
        val after = conn.getTextAfterCursor(BASELINE_CHARS, 0)?.toString() ?: ""
        baselinePending = false
        val cutStart = sel > before.length
        val cutEnd = after.length >= BASELINE_CHARS
        if (baseline.size > MAX_BASELINE) baseline.clear()
        baseline.addAll(TypingPlan.baseline(before + selected + after, cutStart, cutEnd))
        baselinePartial = cutStart || cutEnd
        baselineAnchor = sel
        diag("Text already in the field: ${baseline.size} sentences left alone until changed")
    }

    fun onFinishInput() {
        // text that was there before a dictation stays left alone after it
        if (TypingFixes.dictationHandoff && open && enabled) takeBaseline()
        stopChecks()
        selectionTip?.cancel()
        endShow()
        if (TypingFixes.dictationHandoff) {
            // dictation is only borrowing the field, so the fixes stay for when typing resumes
            open = false
            return
        }
        // the fixes stay in the text but the list of them goes, kept a while in case the field comes back
        fieldKey?.let { key ->
            if (TypingFixes.fixes.value.isNotEmpty() || refused.isNotEmpty()) {
                parked = Parked(key, System.currentTimeMillis(), TypingFixes.fixes.value, kept.toList(), refused.toSet(), baseline.toSet(), baselinePartial, baselineAnchor)
            }
        }
        TypingFixes.fixes.value = emptyList()
        TypingFixes.selectedWord.value = null
        TypingFixes.stopOffer.value = null
        TypingFixes.listOpen.value = false
        if (modelOk == true) ContextModels.releaseTypingSoon()
        open = false
    }

    private val conn get() = inputLogic.mConnection

    fun afterEvent(event: Event) {
        TypingFixes.lastInput = if (event.isGesture) "swipe" else "keys"
        lastInputAt = SystemClock.uptimeMillis()
        if (!enabled) return
        // when typing goes on, the offer to stop an undone fix goes
        TypingFixes.stopOffer.value = null
        // a word picked from the strip is your choice, so it's left alone
        if (event.eventType == Event.EVENT_TYPE_SUGGESTION_PICKED) keepWordBeforeCursor()
        typedSinceMove = true
        val composing = inputLogic.mWordComposer.isComposingWord
        TypingFixes.hiddenWhileTyping.value = composing
        // a word just ended, so a quick look right after this key (an early check or a waiting
        // fix, from the text before the cursor only), and the full look once typing pauses
        if (!composing) requestQuick()
        request(DEBOUNCE_MS)
    }

    /** Whether you've typed since you last moved the cursor (the chip gives way only to typing). */
    private var typedSinceMove = false

    /** [moved]: you moved the cursor (a tap or drag), rather than typing having moved it. */
    fun onSelectionChanged(newSelStart: Int, newSelEnd: Int, moved: Boolean) {
        lastInputAt = SystemClock.uptimeMillis()
        if (moved) {
            typedSinceMove = false
            typingAtEnd = false
            // far from the text that was read when the field opened, so what's here is read first
            if (baselinePartial && baselineAnchor >= 0 && kotlin.math.abs(newSelStart - baselineAnchor) > BASELINE_CHARS / 2) baselinePending = true
        } else if (baselineAnchor >= 0 && lastSelStart >= 0) {
            // typing moves the text that was read along with the cursor
            baselineAnchor = maxOf(0, baselineAnchor + newSelStart - lastSelStart)
        }
        lastSelStart = newSelStart
        if (TypingFixes.fixes.value.isNotEmpty()) {
            updateFocus(newSelStart)
            scheduleHeal()
        }
        shown?.let { (fix, _) ->
            if (newSelStart == fix.start && newSelEnd == fix.end) {
                TypingFixes.selectedWord.value = null
                return
            }
            shown = null
            showTimeout?.cancel()
            TypingFixes.showing.value = null
        }
        if (!enabled) {
            TypingFixes.selectedWord.value = null
            return
        }
        TypingFixes.selectedWord.value = if (newSelEnd > newSelStart && newSelEnd - newSelStart <= MAX_SELECTED_CHARS) {
            conn.getSelectedText(0)?.let { SELECTED_WORD.matchEntire(it)?.groupValues?.get(2) }
        } else null
        // the Add correction tip shows only once the selection has stayed on one word for a moment (a
        // selection often starts on a word and is then dragged over more), and it goes away again,
        // unseen, when the selection becomes more than one word
        val word = TypingFixes.selectedWord.value
        selectionTip?.cancel()
        if (word == null) FeatureTips.withdraw(FeatureTip.ADD_CORRECTION)
        else selectionTip = launch {
            delay(SELECTION_TIP_MS)
            if (TypingFixes.selectedWord.value == word && conn.hasSelection()) FeatureTips.offer(context, FeatureTip.ADD_CORRECTION)
        }
        // the app cleared the field (sent), so the fixes are kept and the list goes
        if (newSelStart == 0 && (TypingFixes.fixes.value.isNotEmpty() || kept.isNotEmpty() || seen.isNotEmpty()) &&
            conn.getTextBeforeCursor(1, 0).isNullOrEmpty() && conn.getTextAfterCursor(1, 0).isNullOrEmpty()) {
            TypingFixes.fixes.value = emptyList()
            TypingFixes.listOpen.value = false
            forgetField()
        }
        // a word the cursor was moved onto is offered for re-picking, but the chip stays until you type
        TypingFixes.hiddenWhileTyping.value = typedSinceMove && inputLogic.mWordComposer.isComposingWord
        // the text or the cursor changed, maybe without a key (dictation, paste, the app, a move
        // back into the text), so the text is looked at again once the changes settle
        request(DEBOUNCE_MS)
    }

    /**
     * Asks for a look at the text ([pass]) in [delayMs]. A later request replaces an earlier one
     * (so a burst of changes makes one look), except one asked for right away, which sees the
     * newer change too. Nothing runs while the field is idle.
     */
    private fun request(delayMs: Long) {
        if (!open) return
        val at = SystemClock.uptimeMillis() + delayMs
        if (passJob?.isActive == true && passNow && passAt <= at) return
        passJob?.cancel()
        passAt = at
        passNow = delayMs == 0L
        // (the scope runs a coroutine at once until it waits, so the job is known before it starts,
        // and a request made from inside the look isn't overwritten)
        val job = helper.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            if (delayMs > 0) delay(delayMs)
            if (passJob === coroutineContext[Job]) passJob = null
            pass(full = true)
        }
        passJob = job
        job.start()
    }

    /**
     * A quick look right after the key that ended a word, posted so it runs after the key is
     * handled. It reads only the text before the cursor (which the keyboard keeps), so a key
     * press never waits on the app.
     */
    private fun requestQuick() {
        if (!open || quickJob?.isActive == true) return
        quickJob = helper.lifecycleScope.launch(Dispatchers.Main) { pass(full = false) }
    }

    /**
     * The text around the cursor: [text] starts at [base] of the field, [cursor] is where the
     * cursor is in it, [composeStart]..[composeEnd] the word being typed (-1 when none), and
     * [cutStart] / [cutEnd] whether there's more text than was read on either side. Without
     * [afterRead], only the text before the cursor was read.
     */
    private class Window(
        val base: Int,
        val text: String,
        val cursor: Int,
        val composeStart: Int,
        val composeEnd: Int,
        val cutStart: Boolean,
        val cutEnd: Boolean,
        val afterRead: Boolean = true,
    )

    /**
     * Up to [TEXT_CHARS] on each side of the cursor (only before it without [full]), or null with
     * a selection or no cursor.
     */
    private fun window(full: Boolean = true): Window? {
        if (conn.hasSelection()) return null
        val sel = conn.expectedSelectionStart
        if (sel < 0) return null
        val before = conn.getTextBeforeCursor(TEXT_CHARS, 0)?.toString() ?: return null
        val after = if (full) conn.getTextAfterCursor(TEXT_CHARS, 0)?.toString() ?: "" else ""
        var cs = -1
        var ce = -1
        val composer = inputLogic.mWordComposer
        if (composer.isComposingWord) {
            val typed = composer.typedWord
            val n = typed.codePointCount(0, typed.length)
            val inWord = typed.offsetByCodePoints(0, composer.sizeBeforeCursor().coerceIn(0, n))
            cs = before.length - inWord
            ce = cs + typed.length
            if (cs < 0 || ce > before.length + after.length) return null
        }
        return Window(sel - before.length, before + after, before.length, cs, ce, sel > before.length, after.length >= TEXT_CHARS, full)
    }

    /**
     * Looks at the text around the cursor as it is now. A waiting fix goes in first, then fixes
     * you changed back by hand and words you fixed yourself are noticed, and then the first
     * sentence that still needs a check (the one at the cursor first, see [TypingPlan.targets]) is
     * checked. When it's done, this runs again for the next one. A running check whose text has
     * changed is stopped, since its answer would be no use. The quick look (not [full]) right
     * after a key only reads the text before the cursor, takes it as typing at the end, and leaves
     * the rest (fixes undone by hand, missed fixes, stopping a check) to the full one.
     */
    private fun pass(full: Boolean) {
        if (!enabled || broken || ContextModels.typingPaused) return
        // a field that may be the same one coming back is sorted out first
        if (restoreJob?.isActive == true) return
        if (full) takeBaseline() else if (baselinePending) return
        val w = window(full) ?: return
        if (full) typingAtEnd = w.text.substring(w.cursor).takeWhile { it != '\n' }.isBlank()
        if (applyWaiting(w)) {
            // the text changed, so look again from the new text
            request(0L)
            return
        }
        if (full) {
            learnMisses(w)
            findUndos(w)
        }
        running?.let { job ->
            val r = runningRegion
            if (!full || (job.isActive && r != null && TypingFind.findRegion(w.text, r.second, r.first - w.base) != null)) return
            job.cancel()
            running = null
            runningRegion = null
            diag("Typing check dropped, its text changed")
        }
        if (waiting != null) return
        // with text after the cursor (or not known yet), the sentence isn't being typed at its end,
        // and it's left to the full look, which reads both sides and waits for a pause
        if (!full && !typingAtEnd) return
        val config = ContextModels.typingConfig(context)
        val salt = "${config.hashCode()}:${System.identityHashCode(ContextModels.typingPairs(context))}"
        val (fixRanges, keptRanges) = keptRanges(w)
        val skip = TypingPlan.protectedStarts(w.text, w.cursor, w.composeStart, w.composeEnd, keptRanges)
        val quietFor = SystemClock.uptimeMillis() - lastInputAt
        var wake = Long.MAX_VALUE
        for (t in TypingPlan.targets(w.text, w.cursor, w.composeStart, w.composeEnd, w.cutStart, w.cutEnd, baseline)) {
            // (an editor that mishandled a fix after the cursor gets none there)
            if (afterOff && t.to > w.cursor) continue
            if (fixRanges.count { it.first in t.from until t.to } >= MAX_FIXES_PER_SENTENCE) continue
            val key = TypingPlan.key(w.text, t.from, t.to, t.finished, skip, salt)
            if (key in checked) continue
            // a sentence you're editing in the middle of is judged once you stop for a moment
            if (t.editing && quietFor < QUIET_MS) {
                wake = minOf(wake, QUIET_MS - quietFor)
                continue
            }
            if (t.early) {
                val region = w.text.substring(t.from, t.to)
                if (!hasEarlyCandidate(region, config, quiet = quietFor >= QUIET_MS)) {
                    if (quietFor < QUIET_MS && hasEarlyCandidate(region, config, quiet = true)) wake = minOf(wake, QUIET_MS - quietFor)
                    continue
                }
            }
            start(w, t, key, skip, config)
            return
        }
        if (wake != Long.MAX_VALUE && full) request(wake)
    }

    /**
     * Where the fixes ([first]) and every word to leave alone, fixes included ([second]), are in
     * [w]'s text now. A kept word found again is saved with its new place.
     */
    private fun keptRanges(w: Window): Pair<List<IntRange>, List<IntRange>> {
        val fixes = ArrayList<IntRange>()
        for (f in TypingFixes.fixes.value) {
            if (f.stale) continue
            val i = TypingFind.locate(w.text, f.replacement, f.start - w.base, f.before, f.after) ?: continue
            fixes.add(i until i + f.replacement.length)
        }
        val all = ArrayList(fixes)
        for (k in kept.indices) {
            val m = kept[k].located(w.text, w.base, CONTEXT_CHARS) ?: continue
            kept[k] = m
            all.add(m.start - w.base until m.end - w.base)
        }
        return fixes to all
    }

    /** Starts the check of [t] in [w] (one at a time), which looks at the text again when it's done. */
    private fun start(w: Window, t: TypingTarget, key: Long, skip: Set<Int>, config: TypingCorrectionConfig) {
        val text = w.text
        val regionStart = maxOf(0, t.from - TypingPlan.KEY_LEFT_CHARS)
        val region = text.substring(regionStart, t.to)
        val regionAt = w.base + regionStart
        // words of the sentence that no dictionary of the keyboard knows (nor your words) are
        // looked up here, on the keyboard's thread, for missed typos (never a piece of a longer
        // word the corrector can't see whole, like "caf" of "café", which is left alone)
        val unknownStarts = HashSet<Int>()
        if (config.missedTypos) {
            for (m in WORD.findAll(text.substring(t.from, t.to))) {
                val at = t.from + m.range.first
                if (m.value.length >= 3 && at !in skip && !inputLogic.isKnownWord(m.value)) unknownStarts.add(at)
            }
        }
        val side = if (t.to <= w.cursor) "before" else if (t.from >= w.cursor) "after" else "at"
        val what = if (t.early) "early" else if (t.editing) "edited" else "finished"
        val job = helper.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val r = try {
                withContext(Dispatchers.Default) {
                    ContextModels.checkTyping(
                        context, locale, text, t.from, t.to, t.finished,
                        skip = { it in skip },
                        caution = { a, b, c, d -> memory.caution(a, b, c, d) },
                        unknown = { it in unknownStarts },
                    )
                }
            } finally {
                if (running === coroutineContext[Job]) {
                    running = null
                    runningRegion = null
                }
            }
            // a check that didn't really run (voice input, aborted) still needs doing, but one the
            // model failed on twice is given up on, so it isn't tried at every pause for nothing
            // and the sentences after it get their turn
            if (!r.ran) {
                if (!r.failed) return@launch
                val n = (failedChecks[key] ?: 0) + 1
                if (failedChecks.size > MAX_MARKS) failedChecks.clear()
                failedChecks[key] = n
                if (n < MAX_CHECK_FAILURES) return@launch
                failedChecks.remove(key)
                checked.add(key)
                diag("Typing check failed $n times on a sentence, leaving it")
                request(0L)
                return@launch
            }
            checked.add(key)
            val fix = r.fix?.takeIf { allowed(it, text) }
            diag("Typing check ${r.ms} ms, $what sentence $side the cursor: ${fix?.kind ?: if (r.fix != null) "fix not made again" else "no fix"}")
            if (t.finished) rememberWords(text, w.base, t.from, t.to, skip, fix?.start)
            if (fix != null) {
                waiting = Waiting(regionAt, region, regionStart, fix)
                window()?.let { applyWaiting(it) }
            }
            // the next sentence, or this one again after its fix
            request(0L)
        }
        running = job
        runningRegion = regionAt to region
        job.start()
    }

    /**
     * A fix that was undone in this field, one that would leave a sentence the way an undone fix
     * did (in any field, in whatever form), or one that would undo one of ours, is not made.
     * [text] is the text the check read.
     */
    private fun allowed(fix: TypingCorrector.Fix, text: String): Boolean {
        if (refusal(fix.original, fix.replacement) in refused) return false
        if (memory.refusesResult(TypingPlan.resultHash(text, fix.start, fix.end, fix.replacement))) return false
        return TypingFixes.fixes.value.none {
            it.replacement.equals(fix.original, ignoreCase = true) && it.original.equals(fix.replacement, ignoreCase = true)
        }
    }

    private fun refusal(original: String, replacement: String) = "${original.lowercase()}>${replacement.lowercase()}"

    /**
     * Whether some word of an unfinished sentence that may be a slip has just got enough words
     * after it to be checked early (a few more words get it checked again, and the sentence end
     * checks the rest). A doubled word counts too, and once you stop typing for a moment ([quiet])
     * any word with enough words after it, for the other slips of editing.
     */
    private fun hasEarlyCandidate(region: String, config: TypingCorrectionConfig, quiet: Boolean): Boolean {
        // (pieces of words the corrector can't see whole don't count, see TypingPlan.foreignStarts)
        val foreign = TypingPlan.foreignStarts(region)
        val words = WORD.findAll(region).filter { it.range.first !in foreign }.map { it.value }.toList()
        val n = config.earlyWords
        val nTypo = config.earlyTypoWords
        val pairs = ContextModels.typingPairs(context)
        return words.indices.any { i ->
            val after = words.size - 1 - i
            (config.realWords && after in n..n + EARLY_RECHECKS && pairs.candidates(words[i]).isNotEmpty()) ||
                // a word no dictionary knows is a likely slip, so it's checked sooner
                (config.missedTypos && after in nTypo..nTypo + EARLY_RECHECKS && words[i].length >= 3 && !inputLogic.isKnownWord(words[i])) ||
                (config.leftoverWords && i > 0 && after in n..n + EARLY_RECHECKS && words[i].equals(words[i - 1], ignoreCase = true)) ||
                (quiet && config.anyEdits && after >= n)
        }
    }

    /** [replacement] with the auto-correction underline (a SuggestionSpan offering [original]), where the app supports it. */
    private fun underlined(replacement: String, original: String): CharSequence {
        val text = SpannableString(replacement)
        try {
            text.setSpan(
                SuggestionSpan(context, locale, arrayOf(original), SuggestionSpan.FLAG_AUTO_CORRECTION, null),
                0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } catch (e: Exception) {
            // not supported here, so it goes in plain
        }
        return text
    }

    /**
     * Puts in the waiting fix if nothing is being typed or selected, the text it checked is still
     * there (found again if edits elsewhere moved it) and the cursor isn't on or next to it.
     * Returns whether the text changed.
     */
    private fun applyWaiting(w: Window): Boolean {
        val p = waiting ?: return false
        if (w.composeStart >= 0 || inputLogic.mWordComposer.isComposingWord) return false
        val at = TypingFind.findRegion(w.text, p.region, p.regionAt - w.base)
        if (at == null) {
            // (a quick look didn't read past the cursor, where it may be)
            if (!w.afterRead) return false
            waiting = null
            diag("Typing fix dropped, its text changed")
            return false
        }
        val s = at + (p.fix.start - p.regionStart)
        val e = s + p.fix.original.length
        if (s < 0 || e > w.text.length || !w.text.regionMatches(s, p.fix.original, 0, p.fix.original.length)) {
            waiting = null
            return false
        }
        // right after autocorrect changed a word, Backspace puts back what you typed by its old
        // length, so a change before the cursor waits for the next key (which ends that)
        if (!TypingPlan.mayApplyNow(e, w.cursor, inputLogic.mLastComposedWord.canRevertCommit())) return false
        waiting = null
        // the cursor may have come to it meanwhile, or one of its words was picked from the strip
        val skip = TypingPlan.protectedStarts(w.text, w.cursor, -1, -1, keptRanges(w).second)
        if ((s < w.cursor && w.cursor < e) || WORD.findAll(p.fix.original).any { s + it.range.first in skip }) {
            diag("Typing fix dropped, the cursor is on it")
            return false
        }
        if (afterOff && e > w.cursor) {
            diag("Typing fix dropped, fixes after the cursor are off in this field")
            return false
        }
        return apply(w, s, p.fix)
    }

    /**
     * Replaces [fix]'s original at [s] of [w]'s text, before or after the cursor, which stays where
     * it is. The editor must select exactly the original first (some say yes to a selection and
     * ignore it, which would put the fix at the cursor), and the fix must read back afterwards.
     */
    private fun apply(w: Window, s: Int, fix: TypingCorrector.Fix): Boolean {
        val start = w.base + s
        val end = start + fix.original.length
        val beforeCursor = end <= w.base + w.cursor
        val side = if (beforeCursor) "before" else "after"
        val text = underlined(fix.replacement, fix.original)
        expectOwnEdit(start, end, text.length, beforeCursor)
        // (with nothing being typed, before the cursor it moves with the text, and after it stays)
        val result = conn.replaceTextChecked(start, end, fix.original, text)
        when (result) {
            TypingTextEdit.DONE, TypingTextEdit.WRONG_AFTER -> {}
            TypingTextEdit.NOT_SELECTED -> {
                // the editor didn't select what it said it did, so nothing was written
                if (beforeCursor) broken = true else afterOff = true
                diag("Typing fix not made: the app selected something else ($side the cursor), " +
                    if (beforeCursor) "fixes off in this field" else "fixes after the cursor off in this field")
                return false
            }
            else -> {
                diag("Typing fix not taken by the app ($side the cursor, $result)")
                return false
            }
        }
        // Backspace right after this mustn't put back an autocorrected word by its old length
        if (beforeCursor) inputLogic.mLastComposedWord.deactivate()
        val e = s + fix.original.length
        val beforeCtx = w.text.substring(maxOf(0, s - CONTEXT_CHARS), s)
        val afterCtx = w.text.substring(e, minOf(w.text.length, e + CONTEXT_CHARS))
        // a checked word inside the fix wasn't missed, and the text after it moved
        seen.removeAll { it.start < end && start < it.end }
        shiftAfter(start, fix.replacement.length - fix.original.length)
        val made = TypingFix(nextId++, start, fix.original, fix.replacement, beforeCtx, afterCtx, kind = fix.kind)
        TypingFixes.fixes.value = TypingFixes.fixes.value + made
        TypingFixes.focusId.value = made.id
        diag("Fix made in ${Settings.getInstance().current.mInputAttributes.mTargetApplicationPackageName ?: "?"} (${fix.kind}, $side the cursor, last input ${TypingFixes.lastInput})")
        if (result == TypingTextEdit.WRONG_AFTER) {
            // the text there isn't what was written, so this editor can't be trusted with fixes
            broken = true
            diag("Typing fix didn't read back as written ($side the cursor), fixes off in this field")
        }
        checkUnderline(made)
        FeatureTips.offer(context, if (fix.kind == "typo") FeatureTip.MISSED_TYPO_FIX else FeatureTip.TYPING_FIX, fix.original, fix.replacement)
        return true
    }

    /**
     * Our edit of [start]..[end] (to [length] characters) is about to go through, so the selections
     * it passes through on its way are expected and not taken for yours ([isOwnEditStep]). The
     * last selection, which puts the cursor back, is left to the keyboard's own bookkeeping.
     */
    private fun expectOwnEdit(start: Int, end: Int, length: Int, beforeCursor: Boolean) {
        val selStart = conn.expectedSelectionStart
        val selEnd = conn.expectedSelectionEnd
        val delta = if (beforeCursor) length - (end - start) else 0
        val final = (selStart + delta) to (selEnd + delta)
        ownSteps.clear()
        for (step in listOf(start to end, (start + length) to (start + length))) {
            if (step != final && step != (selStart to selEnd)) ownSteps.add(step)
        }
        ownStepsUntil = SystemClock.uptimeMillis() + OWN_EDIT_MS
    }

    /**
     * Whether a selection the app reports ([selStart]..[selEnd]) is one our own edit passed
     * through, which the keyboard then ignores (so it doesn't reset what you're typing or offer
     * Add correction for the range it selected for a moment).
     */
    fun isOwnEditStep(selStart: Int, selEnd: Int): Boolean {
        if (ownSteps.isEmpty()) return false
        if (SystemClock.uptimeMillis() > ownStepsUntil) {
            ownSteps.clear()
            return false
        }
        val i = ownSteps.indexOf(selStart to selEnd)
        if (i < 0) return false
        ownSteps.removeAt(i)
        return true
    }

    /** Leaves [w]'s word [s]..[e] alone from now on, found again by the text around it. */
    private fun keep(text: String, base: Int, s: Int, e: Int) {
        keep(TypingMark(base + s, text.substring(s, e), text.substring(maxOf(0, s - CONTEXT_CHARS), s),
            text.substring(e, minOf(text.length, e + CONTEXT_CHARS))))
    }

    private fun keep(mark: TypingMark) {
        kept.removeAll { it.start == mark.start && it.word == mark.word }
        kept.add(mark)
        if (kept.size > MAX_MARKS) kept.removeAt(0)
    }

    /** The word just before the cursor (after any spaces), which you picked from the strip, is left alone. */
    private fun keepWordBeforeCursor() {
        val w = window(full = false) ?: return
        var i = w.cursor
        while (i > 0 && w.text[i - 1].isWhitespace()) i--
        val end = i
        while (i > 0 && (w.text[i - 1].isLetter() || w.text[i - 1] == '\'')) i--
        if (i < end) keep(w.text, w.base, i, end)
    }

    /**
     * Fixes you changed back to what you typed, by hand, are treated like an undo: that change
     * isn't made again in this field, and it learns caution. Not while you're still on it.
     */
    private fun findUndos(w: Window) {
        for (f in TypingFixes.fixes.value.toList()) {
            val at = undoneByHand(f, w.text, w.base) ?: continue
            val e = at + f.original.length
            if (w.cursor in at..e || (w.composeStart >= 0 && w.composeStart <= e && at <= w.composeEnd)) continue
            diag("Typing fix changed back by hand (${f.kind})")
            forget(f, w.base + at, TypingPlan.resultHash(w.text, at, at + f.original.length, f.replacement))
        }
    }

    /**
     * [fix] was undone and its original is back at [at]: it goes from the list, its words are
     * left alone, and the same change isn't made again in this field. Any kind is remembered in
     * [TypingRevertMemory] too (the corrector asks it about every kind), so the same change in the
     * same words needs much more evidence from then on, also in other fields and after a restart.
     * [result] is the sentence as the fix had it ([TypingPlan.resultHash]), which isn't made again
     * in any form (null when the text couldn't be read).
     */
    private fun forget(fix: TypingFix, at: Int, result: Long?) {
        refused.add(refusal(fix.original, fix.replacement))
        keep(TypingMark(at, fix.original, fix.before, fix.after))
        val (prev, next) = TypingRevertMemory.neighbours(fix.before, fix.after)
        memory.recordRevert(fix.original.lowercase(), fix.replacement.lowercase(), prev, next)
        result?.let { memory.recordRefusedResult(it) }
        TypingFixes.fixes.value = TypingFixes.fixes.value.filter { it.id != fix.id }
        if (TypingFixes.fixes.value.isEmpty()) TypingFixes.listOpen.value = false
        updateFocus()
    }

    /** Remembers the words of the checked sentence [from]..[to] of [text] that have pairs (not the one at [skipAt], nor ones left alone). */
    private fun rememberWords(text: String, base: Int, from: Int, to: Int, skip: Set<Int>, skipAt: Int?) {
        val pairs = ContextModels.typingPairs(context)
        for (m in WORD.findAll(text.substring(from, to))) {
            val at = from + m.range.first
            if (at == skipAt || at in skip || pairs.candidates(m.value).isEmpty()) continue
            if (seen.any { it.start == base + at && it.word == m.value }) continue
            val e = at + m.value.length
            seen.add(TypingMark(base + at, m.value, text.substring(maxOf(0, at - CONTEXT_CHARS), at), text.substring(e, minOf(text.length, e + CONTEXT_CHARS))))
            if (seen.size > MAX_SEEN) seen.removeAt(0)
        }
    }

    /**
     * A remembered word you changed into one of its pair words ("pour" to "pout") was a fix that
     * was missed, so the swap gets more ready (see [TypingRevertMemory.recordMiss]). The word is
     * found by the text around it, so edits elsewhere don't confuse it.
     */
    private fun learnMisses(w: Window) {
        if (seen.isEmpty()) return
        val pairs = ContextModels.typingPairs(context)
        val it = seen.listIterator()
        while (it.hasNext()) {
            val s = it.next()
            val expected = s.start - w.base
            val r = TypingFind.wordBetween(w.text, expected, s.before, s.after)
            // (the cursor is on it, so it may be half typed)
            val busy = { a: Int, b: Int -> w.cursor in a..b || (w.composeStart >= 0 && w.composeStart <= b && a <= w.composeEnd) }
            if (r == null) {
                if (expected in 0..w.text.length && !busy(expected - TypingFind.CHANGED_CONTEXT, expected + s.word.length + TypingFind.CHANGED_CONTEXT)) it.remove()
                continue
            }
            if (busy(r.first, r.last + 1)) continue
            val now = w.text.substring(r.first, r.last + 1)
            if (now.equals(s.word, ignoreCase = true)) {
                if (r.first != expected) it.set(s.copy(start = w.base + r.first))
                continue
            }
            it.remove()
            val old = s.word.lowercase()
            val changed = now.lowercase()
            if (changed !in pairs.candidates(old)) continue
            val (prev, next) = TypingRevertMemory.neighbours(s.before, s.after)
            memory.recordMiss(old, changed, prev, next)
            FeatureTips.offer(context, FeatureTip.TYPING_MISS_LEARNED, old, changed)
        }
    }

    /** After a change of length [delta] at [at] (one of ours), the fixes and words after it moved. */
    private fun shiftAfter(at: Int, delta: Int) {
        if (delta == 0) return
        for (i in kept.indices) if (kept[i].start > at) kept[i] = kept[i].copy(start = kept[i].start + delta)
        for (i in seen.indices) if (seen[i].start > at) seen[i] = seen[i].copy(start = seen[i].start + delta)
        TypingFixes.fixes.value = shiftFixes(TypingFixes.fixes.value, at, delta)
    }

    /** The text on both sides of the cursor (within [LOCATE_CHARS]) and where its first character is. */
    private class Around(val base: Int, val text: String)

    private fun around(): Around? {
        val selStart = conn.expectedSelectionStart
        val selEnd = conn.expectedSelectionEnd
        if (selStart < 0) return null
        val before = conn.getTextBeforeCursor(minOf(selStart, LOCATE_CHARS), 0)?.toString() ?: return null
        val selected = if (selEnd > selStart) conn.getSelectedText(0)?.toString() ?: return null else ""
        val after = conn.getTextAfterCursor(LOCATE_CHARS, 0)?.toString() ?: ""
        return Around(selStart - before.length, before + selected + after)
    }

    /**
     * [fix] where it is now, before or after the cursor. It's the fixed word, found by the text
     * around it: where it was if it's still there, else wherever the surrounding text matches
     * best (you edited earlier text, which moves it, or a word next to it, or the app changed
     * the text). Its surroundings are then refreshed, so later edits next to it can still be
     * followed. Null when it can't be found for sure (the words themselves were changed or deleted,
     * or two places match equally well). A move is saved in the list.
     */
    private fun locate(fix: TypingFix, win: Around? = around()): TypingFix? {
        val w = win ?: return null
        val text = w.text
        val len = fix.replacement.length
        val best = TypingFind.locate(text, fix.replacement, fix.start - w.base, fix.before, fix.after) ?: return null
        val start = w.base + best
        val located = fix.copy(
            start = start, stale = false,
            before = text.substring(maxOf(0, best - CONTEXT_CHARS), best),
            after = text.substring(best + len, minOf(text.length, best + len + CONTEXT_CHARS)),
        )
        if (located != fix) {
            if (start != fix.start) diag("Typing fix found again ${start - fix.start} characters away")
            TypingFixes.fixes.value = TypingFixes.fixes.value.map { if (it.id == fix.id) located else it }
        }
        return located
    }

    /** Finds every fix again in the text as it is now (gone ones are greyed out), then the chip's. */
    private fun relocateAll() {
        if (TypingFixes.fixes.value.isEmpty()) return
        val w = around() ?: return
        for (f in TypingFixes.fixes.value.toList()) {
            val current = TypingFixes.fixes.value.firstOrNull { it.id == f.id } ?: continue
            if (locate(current, w) == null) {
                TypingFixes.fixes.value = TypingFixes.fixes.value.map { if (it.id == f.id) it.copy(stale = true) else it }
            }
        }
        updateFocus()
    }

    /** The chip's fix: the closest before the cursor, else the closest after it. */
    private fun updateFocus(cursor: Int = conn.expectedSelectionStart) {
        val live = TypingFixes.fixes.value.filter { !it.stale }
        val f = live.filter { it.end <= cursor }.maxByOrNull { it.start }
            ?: live.filter { it.start >= cursor }.minByOrNull { it.start }
        TypingFixes.focusId.value = f?.id
    }

    /**
     * Whether the app still has [f]'s underline (its SuggestionSpan), before or after the cursor.
     * Null when the app doesn't say (it doesn't report styling, or the fix is in the selection).
     */
    private fun underlineKept(f: TypingFix): Boolean? {
        val selStart = conn.expectedSelectionStart
        val selEnd = conn.expectedSelectionEnd
        val styled: Spanned
        val offset: Int
        if (f.end <= selStart) {
            styled = conn.getStyledTextBeforeCursorFromEditor(selStart - f.start) as? Spanned ?: return null
            offset = 0
        } else if (f.start >= selEnd) {
            styled = conn.getStyledTextAfterCursorFromEditor(f.end - selEnd) as? Spanned ?: return null
            offset = f.start - selEnd
        } else return null
        if (styled.length < offset + f.replacement.length) return null
        return styled.getSpans(offset, offset + f.replacement.length, SuggestionSpan::class.java).isNotEmpty()
    }

    fun refreshStale() {
        relocateAll()
    }

    /** The fix being shown and the cursor to put back afterwards. */
    private var shown: Pair<TypingFix, Int>? = null
    private var showTimeout: Job? = null

    fun show(fix: TypingFix) {
        endShow()
        if (inputLogic.mWordComposer.isComposingWord) {
            inputLogic.mConnection.finishComposingText()
            inputLogic.mWordComposer.reset(true)
        }
        val fix = locate(fix) ?: run {
            TypingFixes.fixes.value = TypingFixes.fixes.value.map { if (it.id == fix.id) it.copy(stale = true) else it }
            return
        }
        val cursor = conn.expectedSelectionStart
        if (!conn.setSelection(fix.start, fix.end)) {
            conn.setSelection(cursor, cursor)
            return
        }
        shown = fix to cursor
        TypingFixes.showing.value = fix
        showTimeout = launch {
            delay(SHOW_MS)
            endShow()
        }
    }

    /**
     * Called before every key. A shown fix gives the cursor back ([endShow]), and in a field that
     * just opened, the text already there is read as its [baseline] before the first key changes it.
     */
    fun beforeEvent() {
        endShow()
        if (baselinePending && open && enabled) takeBaseline()
    }

    /**
     * Ends [show], putting the cursor back where it was, unless the selection was moved meanwhile
     * (then it's left where you put it). Called before every key, so typing never replaces the
     * shown word.
     */
    fun endShow() {
        val (fix, cursor) = shown ?: return
        shown = null
        showTimeout?.cancel()
        TypingFixes.showing.value = null
        if (conn.expectedSelectionStart == fix.start && conn.expectedSelectionEnd == fix.end) {
            conn.setSelection(cursor, cursor)
        }
    }

    fun revert(fix: TypingFix) {
        endShow()
        if (inputLogic.mWordComposer.isComposingWord) {
            // the composing word would be committed by the edit, so finish it first
            inputLogic.mConnection.finishComposingText()
            inputLogic.mWordComposer.reset(true)
        }
        val fix = locate(fix) ?: run {
            TypingFixes.fixes.value = TypingFixes.fixes.value.map { if (it.id == fix.id) it.copy(stale = true) else it }
            undoFailed("fix not found")
            return
        }
        // with the cursor inside the fixed words, or a selection over them, the cursor goes to
        // their end first, so the undo goes in before it and the cursor ends up after your words
        if (conn.expectedSelectionStart < fix.end && conn.expectedSelectionEnd > fix.start && !conn.setSelection(fix.end, fix.end)) {
            undoFailed("cursor on it")
            return
        }
        // the sentence as the fix has it, which you're rejecting
        val rejected = around()?.let { w ->
            val s = fix.start - w.base
            if (s >= 0 && s + fix.replacement.length <= w.text.length) TypingPlan.resultHash(w.text, s, s + fix.replacement.length, fix.replacement) else null
        }
        val beforeCursor = fix.end <= conn.expectedSelectionStart
        expectOwnEdit(fix.start, fix.end, fix.original.length, beforeCursor)
        val result = conn.replaceTextChecked(fix.start, fix.end, fix.replacement, fix.original)
        if (result != TypingTextEdit.DONE && result != TypingTextEdit.WRONG_AFTER) {
            undoFailed("not taken ($result)")
            return
        }
        if (beforeCursor) inputLogic.mLastComposedWord.deactivate()
        shiftAfter(fix.start, fix.original.length - fix.replacement.length)
        forget(fix, fix.start, rejected)
        // a missing, extra or swapped word, an ending or a join has no pair to stop
        if (!fix.canStop) return
        // the chip offers to stop it everywhere
        TypingFixes.stopOffer.value = fix
        FeatureTips.offer(context, FeatureTip.TYPING_FIX_UNDONE)
        stopOfferTimeout?.cancel()
        stopOfferTimeout = launch {
            delay(STOP_OFFER_MS)
            if (TypingFixes.stopOffer.value?.id == fix.id) TypingFixes.stopOffer.value = null
        }
    }

    /** An undo that couldn't be done is said, rather than nothing happening. */
    private fun undoFailed(why: String) {
        diag("Typing fix undo failed: $why")
        Toast.makeText(context, context.getString(R.string.typing_fix_undo_failed), Toast.LENGTH_SHORT).show()
    }

    /**
     * Logs whether the app kept the fix's underline (its SuggestionSpan), right away and a moment
     * later, so apps that drop it can be found. Only the app's package and yes or no are logged,
     * never text.
     */
    private fun checkUnderline(fix: TypingFix) {
        val app = Settings.getInstance().current.mInputAttributes.mTargetApplicationPackageName ?: "?"
        launch {
            for ((label, wait) in listOf("now" to 50L, "later" to 2_000L)) {
                delay(wait)
                val f = TypingFixes.fixes.value.firstOrNull { it.id == fix.id }?.let { locate(it) } ?: run {
                    diag("Underline check ($label) in $app: fix not found")
                    return@launch
                }
                val kept = underlineKept(f)
                diag("Underline check ($label) in $app: styled=${kept != null}, kept=${kept == true}")
            }
        }
    }

    /**
     * A diagnostic line, kept in a file by the private tuning build (no typed text). It names the app
     * typed in, so it only goes to the system log in debug builds.
     */
    private fun diag(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
        Tuning.diagnosticLog(context, TAG, message)
    }

    private var healJob: Job? = null
    /** How many times each fix's underline was put back (at most [MAX_HEALS]). */
    private val heals = HashMap<Long, Int>()

    private fun scheduleHeal() {
        healJob?.cancel()
        healJob = launch {
            delay(HEAL_DELAY_MS)
            relocateAll()
            heal()
        }
    }

    /**
     * Puts back the underline of fixes whose app dropped it (Google Messages did, once). The word
     * is written again with its underline, so the text and the cursor stay the same. Only for apps
     * that report the styling of their text, and at most [MAX_HEALS] times per fix, so an app that
     * strips it every time can't loop (the eye button still shows where the fix is there).
     */
    private fun heal() {
        if (!enabled || broken || inputLogic.mWordComposer.isComposingWord || conn.hasSelection() || shown != null) return
        val app = Settings.getInstance().current.mInputAttributes.mTargetApplicationPackageName ?: "?"
        for (f in TypingFixes.fixes.value.toList()) {
            if (f.stale) continue
            val beforeCursor = f.end <= conn.expectedSelectionStart
            if (afterOff && !beforeCursor) continue
            val count = heals[f.id] ?: 0
            if (count >= MAX_HEALS) continue
            if (underlineKept(f) != false) continue
            heals[f.id] = count + 1
            diag("Underline dropped in $app after ${TypingFixes.lastInput}, putting it back (${count + 1} of $MAX_HEALS)")
            expectOwnEdit(f.start, f.end, f.replacement.length, beforeCursor)
            // (the same words are written again, and only if the app selects exactly them)
            if (conn.replaceTextChecked(f.start, f.end, f.replacement, underlined(f.replacement, f.original)) == TypingTextEdit.NOT_SELECTED) {
                if (beforeCursor) broken = true else afterOff = true
                diag("Underline not put back: the app selected something else")
                return
            }
        }
    }

    private var stopOfferTimeout: Job? = null
    private var selectionTip: Job? = null

    /**
     * Puts [meant] (in [typed]'s case) in place of the selected word, if [typed] is still what is
     * selected ("Add correction").
     */
    fun replaceSelectedWord(typed: String, meant: String) {
        if (!conn.hasSelection()) return
        val selected = conn.getSelectedText(0)?.toString() ?: return
        val m = SELECTED_WORD.matchEntire(selected) ?: return
        val (_, lead, word, trail) = m.groupValues
        if (!word.equals(typed, ignoreCase = true)) return
        val start = conn.expectedSelectionStart
        val end = conn.expectedSelectionEnd
        conn.beginBatchEdit()
        conn.finishComposingText()
        conn.setSelection(end, end)
        conn.deleteTextBeforeCursor(end - start)
        conn.commitText(lead + TypingCorrector.matchCase(word, meant) + trail, 1)
        conn.endBatchEdit()
        conn.send()
        // your own correction is left alone
        val w = window() ?: return
        val s = start + lead.length - w.base
        val e = s + meant.length
        if (s >= 0 && e <= w.text.length) keep(w.text, w.base, s, e)
    }

    companion object {
        private const val TAG = "TypingCorrections"

        /** Characters on each side of the cursor a check reads. */
        const val TEXT_CHARS = 600

        /** A burst of changes (keys within a word, dictation, paste, cursor moves) is looked at this long after the last one. */
        const val DEBOUNCE_MS = 250L

        /** A sentence you're editing in the middle of waits until you have stopped for this long. */
        const val QUIET_MS = 1_500L

        /** Text already in a field that just opened (or that was dictated) is looked at after this long. */
        const val OPEN_DELAY_MS = 700L

        /** Characters on each side of the cursor read as the text already in a field when it opens. */
        const val BASELINE_CHARS = 4_000

        /** A field is taken as the same one coming back when at least this share of its fixes is still in its text. */
        const val RESTORE_SHARE = 0.5

        /** How long the selections our own edit passes through are expected from the app. */
        const val OWN_EDIT_MS = 300L

        /** A check that fails this many times on the same sentence isn't tried again. */
        const val MAX_CHECK_FAILURES = 2

        /** More sentences than this taken as already there are forgotten before more are read (a very long text gone through end to end). */
        const val MAX_BASELINE = 20_000

        /** At most this many words left alone, and checked words remembered, per field. */
        const val MAX_MARKS = 100
        const val MAX_SEEN = 200

        /** An unfinished sentence is checked again for this many more words after a possible slip. */
        const val EARLY_RECHECKS = 3

        private val WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")

        /** A sentence gets at most this many fixes. */
        const val MAX_FIXES_PER_SENTENCE = 2

        /** A selection must stay on one word this long before the Add correction tip shows. */
        const val SELECTION_TIP_MS = 1500L
        /** How long a fix's word stays selected after "Show where" (any key ends it sooner). */
        const val SHOW_MS = 4_000L
        /** How far back a fix is looked for when the app moved the text, and how many characters around it must match. */
        const val LOCATE_CHARS = 2_000
        /** The underline check waits this long after the text last changed, and puts it back at most this often per fix. */
        const val HEAL_DELAY_MS = 300L
        const val MAX_HEALS = 2
        /** How long a closed field's fixes are kept in case it comes back, and how long its text gets to settle first. */
        const val PARK_MS = 10 * 60_000L
        const val RESTORE_DELAY_MS = 400L
        const val LOCATE_CONTEXT = 10

        /** How long the chip offers to stop a fix that was just undone. */
        const val STOP_OFFER_MS = 8_000L

        /** A selection of one word, maybe with spaces or punctuation around it. */
        val SELECTED_WORD = Regex("^([^A-Za-z]*)([A-Za-z]+(?:'[A-Za-z]+)?)([^A-Za-z]*)$")

        /** Longer selections are not read (they are not one word). */
        const val MAX_SELECTED_CHARS = 40

        /** Characters kept around a fix to recognize it later. */
        const val CONTEXT_CHARS = 20
    }
}
