package org.futo.inputmethod.latin.uix

import org.futo.inputmethod.latin.uix.settings.pages.ActionBarDisplayedSetting
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
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
 * replaced by [replacement]. [before] / [after] are the characters around it when it was made,
 * so a revert can tell whether the text there was edited since ([stale]).
 */
data class TypingFix(
    val id: Long,
    val start: Int,
    val original: String,
    val replacement: String,
    val before: String,
    val after: String,
    val stale: Boolean = false,
) {
    val end: Int get() = start + replacement.length

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
        const val PREFS = "typing_reverts"
    }
}

/**
 * Smart corrections for typing in one text field. After a sentence ends (or a few words after a
 * word that may be a slip), the sentence is re-checked in the background with the context model
 * ([ContextModels.checkTyping]), and a fix is applied behind the cursor, with an auto-correction
 * [SuggestionSpan] (an underline offering the original, where the app supports it), and listed
 * in [TypingFixes] for the strip's chip and review list until the field closes.
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
    private var running: Job? = null
    private var again = false

    /** A fix found while a word was being typed is applied when the word ends. */
    private var waiting: (() -> Unit)? = null
    private var nextId = 1L

    /**
     * Starts of words the user changed by hand (picked from the strip, deleted into) or that
     * were already fixed, so they are never touched (again).
     */
    private val handTouched = HashSet<Int>()

    /** Sentence starts -> fixes they got (at most [MAX_FIXES_PER_SENTENCE]), and checks already made. */
    private val fixedSentences = HashMap<Int, Int>()

    /**
     * Words of finished, checked sentences that have pairs and were left as typed (start -> word).
     * When one is changed later by hand into one of its pair words, that was a missed fix.
     */
    private val seenWords = HashMap<Int, String>()
    private val checked = HashSet<String>()

    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = helper.lifecycleScope.launch(block = block)

    /** The text field this was set up for, so coming back from dictation can tell it's the same one. */
    private var fieldKey: String? = null

    /**
     * The fixes of the field that just closed, kept for [PARK_MS] in case the same field comes back
     * (the keyboard restarted, you switched apps and came back, or the app reloaded its draft, which
     * Google Messages does as plain text without the underlines).
     */
    private var parked: Triple<String, Long, List<TypingFix>>? = null

    private fun currentFieldKey(): String? = helper.getCurrentEditorInfo()?.let { "${it.packageName}/${it.fieldId}/${it.inputType}" }

    fun onStartInput() {
        running?.cancel()
        waiting = null
        TypingFixes.controller = this
        // back from a dictation in the same field, so the fixes (and their underlines) are kept
        val back = TypingFixes.dictationHandoff && currentFieldKey() == fieldKey
        TypingFixes.dictationHandoff = false
        if (back) {
            open = true
            modelOk = null
            return
        }
        fieldKey = currentFieldKey()
        val restore = parked?.takeIf { (key, at, _) -> key == fieldKey && System.currentTimeMillis() - at < PARK_MS }?.third
        parked = null
        TypingFixes.selectedWord.value = null
        TypingFixes.stopOffer.value = null
        TypingFixes.fixes.value = emptyList()
        TypingFixes.listOpen.value = false
        TypingFixes.hiddenWhileTyping.value = false
        handTouched.clear()
        heals.clear()
        fixedSentences.clear()
        seenWords.clear()
        checked.clear()
        open = true
        modelOk = null
        if (!restore.isNullOrEmpty()) {
            // the same field is back, so its fixes come back too, but only the ones still in its
            // text (found by the words around them), and their underlines are put back
            TypingFixes.fixes.value = restore.map { it.copy(stale = false) }
            launch {
                delay(RESTORE_DELAY_MS)
                val w = around()
                val kept = TypingFixes.fixes.value.mapNotNull { locate(it, w) }
                TypingFixes.fixes.value = kept
                updateFocus()
                diag("Same field back: ${kept.size} of ${restore.size} fixes still in the text")
                if (kept.isNotEmpty()) heal()
            }
        }
    }

    fun onFinishInput() {
        running?.cancel()
        selectionTip?.cancel()
        waiting = null
        endShow()
        if (TypingFixes.dictationHandoff) {
            // dictation is only borrowing the field, so the fixes stay for when typing resumes
            open = false
            return
        }
        // the fixes stay in the text but the list of them goes, kept a while in case the field comes back
        fieldKey?.let { key ->
            if (TypingFixes.fixes.value.isNotEmpty()) parked = Triple(key, System.currentTimeMillis(), TypingFixes.fixes.value)
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
        if (!enabled) return
        // when typing goes on, the offer to stop an undone fix goes
        TypingFixes.stopOffer.value = null
        val cursor = conn.expectedSelectionStart
        when {
            event.eventType == Event.EVENT_TYPE_SUGGESTION_PICKED -> wordStartBefore(cursor)?.let { handTouched.add(it) }
            event.mKeyCode == Constants.CODE_DELETE || event.mCodePoint == Constants.CODE_DELETE ->
                wordStartBefore(cursor)?.let { handTouched.add(it) }
        }
        typedSinceMove = true
        TypingFixes.hiddenWhileTyping.value = inputLogic.mWordComposer.isComposingWord
        schedule()
    }

    /** Whether you've typed since you last moved the cursor (the chip gives way only to typing). */
    private var typedSinceMove = false

    /** [moved]: you moved the cursor (a tap or drag), rather than typing having moved it. */
    fun onSelectionChanged(newSelStart: Int, newSelEnd: Int, moved: Boolean) {
        if (moved) typedSinceMove = false
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
        if (newSelStart == 0 && TypingFixes.fixes.value.isNotEmpty() &&
            conn.getTextBeforeCursor(1, 0).isNullOrEmpty() && conn.getTextAfterCursor(1, 0).isNullOrEmpty()) {
            TypingFixes.fixes.value = emptyList()
            TypingFixes.listOpen.value = false
            fixedSentences.clear()
            seenWords.clear()
            handTouched.clear()
            checked.clear()
        }
        // a word the cursor was moved onto is offered for re-picking, but the chip stays until you type
        TypingFixes.hiddenWhileTyping.value = typedSinceMove && inputLogic.mWordComposer.isComposingWord
    }

    private fun wordStartBefore(cursor: Int): Int? {
        val before = conn.getTextBeforeCursor(64, 0)?.toString() ?: return null
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        val end = i
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        return if (i < end) cursor - before.length + i else null
    }

    private val sentenceEndAtCursor = Regex("[.?!][\"')]*\\s*$")

    /**
     * Checks sentences as words end while typing goes on. A running check is never cancelled by
     * typing (it just runs again afterwards), and a fix found in the middle of a word waits for
     * the word to end.
     */
    private fun schedule() {
        if (conn.hasSelection()) return
        // Checks also run while a word is being typed (they look at the text before it), so a fix
        // or the re-check after one never waits for the next space. A fix found meanwhile waits
        // for the word to end ([waiting]), so it never changes text under your fingers.
        val composer = inputLogic.mWordComposer
        val composing = composer.isComposingWord
        if (composing && composer.isCursorFrontOrMiddleOfComposingWord) return
        if (!composing) waiting?.let { waiting = null; it() }
        if (running?.isActive == true) {
            again = true
            return
        }
        val typedLen = if (composing) composer.typedWord.length else 0
        val cursor = conn.expectedSelectionStart - typedLen
        val before = (conn.getTextBeforeCursor(TEXT_CHARS + typedLen, 0)?.toString() ?: return).dropLast(typedLen)
        if (before.isEmpty() || !(before.last().isWhitespace() || before.last() in ".?!,;:")) return
        val base = cursor - before.length
        learnMisses(before, base)
        val sentences = TypingCorrector.sentences(before)
        val last = sentences.lastOrNull() ?: return
        val lastFinished = sentenceEndAtCursor.containsMatchIn(before.substring(last.first))
        // every finished sentence in reach that wasn't checked yet, oldest first, then the
        // unfinished one (early check)
        val targets = sentences.mapIndexedNotNull { i, range ->
            val finished = i < sentences.size - 1 || lastFinished
            if ((fixedSentences[base + range.first] ?: 0) >= MAX_FIXES_PER_SENTENCE) return@mapIndexedNotNull null
            // the first one may be cut off by the window
            if (i == 0 && base > 0) return@mapIndexedNotNull null
            val to = if (finished) range.last + 1 else before.trimEnd().length
            val region = before.substring(range.first, to)
            if (!finished && !hasEarlyCandidate(region)) return@mapIndexedNotNull null
            val key = "${base + range.first}:${region.hashCode()}:$finished"
            if (key in checked) null else Triple(range.first, to, finished) to key
        }
        if (targets.isEmpty()) return
        // words of finished sentences that no dictionary of the keyboard knows (nor your words) are
        // looked up here, on the keyboard's thread, for missed typos
        val unknownStarts = HashSet<Int>()
        if (context.getSetting(TYPING_FIX_MISSED_TYPOS)) {
            for ((target, _) in targets) {
                for (m in WORD.findAll(before.substring(target.first, target.second))) {
                    if (m.value.length >= 3 && !inputLogic.isKnownWord(m.value)) unknownStarts.add(target.first + m.range.first)
                }
            }
        }
        running = helper.lifecycleScope.launch {
            for ((target, key) in targets) {
                val (from, to, finished) = target
                // an unfinished sentence you've typed past since (whole words, not the one you're
                // typing) is checked from the newer text by the next check instead, so old checks
                // don't hold up the one for what you just typed
                val typingNow = if (inputLogic.mWordComposer.isComposingWord) inputLogic.mWordComposer.typedWord.length else 0
                if (!finished && conn.expectedSelectionStart - typingNow - (base + to) > STALE_CHARS) continue
                val fix = withContext(Dispatchers.Default) {
                    ContextModels.checkTyping(
                        context, locale, before, from, to, finished,
                        skip = { base + it in handTouched },
                        caution = { a, b, c, d -> memory.caution(a, b, c, d) },
                        unknown = { it in unknownStarts },
                    )
                }
                checked.add(key)
                if (finished) rememberWords(before, base, from, to, skipAt = fix?.start)
                if (fix != null) {
                    val applyIt = { apply(base, before.substring(0, to), fix, base + from) }
                    if (inputLogic.mWordComposer.isComposingWord || conn.hasSelection()) waiting = applyIt else applyIt()
                    // the text moved, so look again from the new text
                    again = true
                    break
                }
            }
            running = null
            if (again) {
                again = false
                schedule()
            }
        }
    }

    /**
     * Whether some word of an unfinished sentence that may be a slip has just got enough words
     * after it to be checked early (a few more words get it checked again, and the sentence end
     * checks the rest).
     */
    private fun hasEarlyCandidate(region: String): Boolean {
        val words = Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(region).map { it.value }.toList()
        val config = TypingCorrectionConfig()
        val n = config.earlyWords
        val nTypo = config.earlyTypoWords
        val pairs = ContextModels.typingPairs(context)
        val missed = context.getSetting(TYPING_FIX_MISSED_TYPOS)
        return words.indices.any { i ->
            val after = words.size - 1 - i
            (after in n..n + EARLY_RECHECKS && pairs.candidates(words[i]).isNotEmpty()) ||
                // a word no dictionary knows is a likely slip, so it's checked sooner
                (missed && after in nTypo..nTypo + EARLY_RECHECKS && words[i].length >= 3 && !inputLogic.isKnownWord(words[i]))
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

    private fun apply(base: Int, checkedText: String, fix: TypingCorrector.Fix, sentenceStart: Int) {
        if (inputLogic.mWordComposer.isComposingWord || conn.hasSelection()) return
        // the text up to the end of the checked part must be unchanged
        val cursor = conn.expectedSelectionStart
        val now = conn.getTextBeforeCursor(cursor - base, 0)?.toString() ?: return
        if (cursor - now.length != base || !now.startsWith(checkedText.trimEnd())) return
        val start = base + fix.start
        if (!conn.replaceTextBeforeCursor(start, start + fix.original.length, underlined(fix.replacement, fix.original))) return
        fixedSentences[sentenceStart] = (fixedSentences[sentenceStart] ?: 0) + 1
        // the sentence is checked again for another slip ("when your pour" -> "when you pour" -> "pout"),
        // but this word stays as fixed
        handTouched.add(start)
        val after = checkedText.substring(fix.end, minOf(checkedText.length, fix.end + CONTEXT_CHARS))
        val beforeCtx = checkedText.substring(maxOf(0, fix.start - CONTEXT_CHARS), fix.start)
        val made = TypingFix(nextId++, start, fix.original, fix.replacement, beforeCtx, after)
        TypingFixes.fixes.value = TypingFixes.fixes.value + made
        TypingFixes.focusId.value = made.id
        diag("Fix made in ${Settings.getInstance().current.mInputAttributes.mTargetApplicationPackageName ?: "?"} (${if (ContextModels.typingPairs(context).candidates(fix.original).any { it.equals(fix.replacement, ignoreCase = true) }) "real word" else "missed typo"}, last input ${TypingFixes.lastInput})")
        checkUnderline(made)
        val realWord = ContextModels.typingPairs(context).candidates(fix.original).any { it.equals(fix.replacement, ignoreCase = true) }
        FeatureTips.offer(context, if (realWord) FeatureTip.TYPING_FIX else FeatureTip.MISSED_TYPO_FIX, fix.original, fix.replacement)
        // later fixes and hand-touched words after this one moved
        shiftAfter(start, fix.replacement.length - fix.original.length)
    }

    /** Remembers the words of the checked sentence [from]..[to] of [before] that have pairs (not the one at [skipAt]). */
    private fun rememberWords(before: String, base: Int, from: Int, to: Int, skipAt: Int?) {
        val pairs = ContextModels.typingPairs(context)
        for (m in WORD.findAll(before.substring(from, to))) {
            val at = from + m.range.first
            if (at == skipAt || base + at in handTouched || pairs.candidates(m.value).isEmpty()) continue
            seenWords[base + at] = m.value.lowercase()
        }
    }

    /**
     * A remembered word you changed into one of its pair words ("pour" to "pout") was a fix that
     * was missed, so the swap gets more ready (see [TypingRevertMemory.recordMiss]).
     */
    private fun learnMisses(before: String, base: Int) {
        if (seenWords.isEmpty()) return
        val pairs = ContextModels.typingPairs(context)
        for ((abs, old) in seenWords.toList()) {
            val at = abs - base
            if (at < 0 || at >= before.length) continue
            if (at > 0 && (before[at - 1].isLetter() || before[at - 1] == '\'')) { seenWords.remove(abs); continue }
            val m = WORD.find(before, at)?.takeIf { it.range.first == at }
            if (m == null) { seenWords.remove(abs); continue }
            val now = m.value.lowercase()
            if (now == old) continue
            seenWords.remove(abs)
            if (now !in pairs.candidates(old)) continue
            val prev = Regex("([A-Za-z']+)\\W*$").find(before.substring(maxOf(0, at - CONTEXT_CHARS), at))?.groupValues?.get(1)?.lowercase() ?: ""
            val next = Regex("^\\W*([A-Za-z']+)").find(before.substring(m.range.last + 1, minOf(before.length, m.range.last + 1 + CONTEXT_CHARS)))
                ?.groupValues?.get(1)?.lowercase() ?: ""
            memory.recordMiss(old, now, prev, next)
            FeatureTips.offer(context, FeatureTip.TYPING_MISS_LEARNED, old, now)
        }
    }

    private fun shiftAfter(at: Int, delta: Int) {
        if (delta == 0) return
        val touched = handTouched.map { if (it > at) it + delta else it }
        handTouched.clear(); handTouched.addAll(touched)
        val seen = seenWords.entries.associate { (k, v) -> (if (k > at) k + delta else k) to v }
        seenWords.clear(); seenWords.putAll(seen)
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
     * followed. Null when it can't be found for sure (the word itself was changed or deleted, or
     * two places match equally well). A move is saved in the list.
     */
    private fun locate(fix: TypingFix, win: Around? = around()): TypingFix? {
        val w = win ?: return null
        val text = w.text
        val word = fix.replacement
        val len = word.length
        if (len == 0) return null
        fun isWordAt(i: Int) = i >= 0 && i + len <= text.length && text.regionMatches(i, word, 0, len) &&
            (i == 0 || !text[i - 1].isLetterOrDigit()) && (i + len == text.length || !text[i + len].isLetterOrDigit())
        // how many characters right around it still match what was around it
        fun score(i: Int): Int {
            var a = 0
            while (a < fix.before.length && i - 1 - a >= 0 && text[i - 1 - a] == fix.before[fix.before.length - 1 - a]) a++
            var b = 0
            while (b < fix.after.length && i + len + b < text.length && text[i + len + b] == fix.after[b]) b++
            return a + b
        }
        val here = fix.start - w.base
        var best = -1
        var bestScore = -1
        var tie = false
        if (isWordAt(here)) {
            best = here
            bestScore = score(here) + POSITION_BONUS
        }
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) break
            from = i + 1
            if (i == here || !isWordAt(i)) continue
            val sc = score(i)
            if (sc > bestScore) { best = i; bestScore = sc; tie = false } else if (sc == bestScore) tie = true
        }
        if (best < 0 || tie || bestScore < MIN_CONTEXT) return null
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
            return
        }
        if (!conn.replaceText(fix.start, fix.end, fix.original)) return
        val prev = Regex("([A-Za-z']+)\\W*$").find(fix.before)?.groupValues?.get(1)?.lowercase() ?: ""
        val next = Regex("^\\W*([A-Za-z']+)").find(fix.after)?.groupValues?.get(1)?.lowercase() ?: ""
        memory.recordRevert(fix.original.lowercase(), fix.replacement.lowercase(), prev, next)
        handTouched.add(fix.start)
        TypingFixes.fixes.value = TypingFixes.fixes.value.filter { it.id != fix.id }
        shiftAfter(fix.start, fix.original.length - fix.replacement.length)
        if (TypingFixes.fixes.value.isEmpty()) TypingFixes.listOpen.value = false
        updateFocus()
        // the undo makes this fix more careful here, and the chip offers to stop it everywhere
        TypingFixes.stopOffer.value = fix
        FeatureTips.offer(context, FeatureTip.TYPING_FIX_UNDONE)
        stopOfferTimeout?.cancel()
        stopOfferTimeout = launch {
            delay(STOP_OFFER_MS)
            if (TypingFixes.stopOffer.value?.id == fix.id) TypingFixes.stopOffer.value = null
        }
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

    /** A diagnostic line for the system log, also kept in a file by the private tuning build (no typed text). */
    private fun diag(message: String) {
        Log.i(TAG, message)
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
        if (!enabled || inputLogic.mWordComposer.isComposingWord || conn.hasSelection() || shown != null) return
        val app = Settings.getInstance().current.mInputAttributes.mTargetApplicationPackageName ?: "?"
        for (f in TypingFixes.fixes.value.toList()) {
            if (f.stale) continue
            val count = heals[f.id] ?: 0
            if (count >= MAX_HEALS) continue
            if (underlineKept(f) != false) continue
            heals[f.id] = count + 1
            diag("Underline dropped in $app after ${TypingFixes.lastInput}, putting it back (${count + 1} of $MAX_HEALS)")
            conn.replaceText(f.start, f.end, underlined(f.replacement, f.original))
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
        handTouched.add(start + lead.length)
    }

    companion object {
        private const val TAG = "TypingCorrections"

        /** Characters before the cursor a check reads. */
        const val TEXT_CHARS = 600

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
        /** Finding a fix again: the bonus for still being where it was, and the least matching context otherwise. */
        const val POSITION_BONUS = 4
        const val MIN_CONTEXT = 4
        const val MAX_HEALS = 2
        /** An unfinished-sentence check is skipped once you've typed this many characters past it. */
        const val STALE_CHARS = 1
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
