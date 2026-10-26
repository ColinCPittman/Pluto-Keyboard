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

    internal var controller: TypingCorrections? = null

    fun revert(fix: TypingFix) {
        controller?.revert(fix)
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

    fun onStartInput() {
        running?.cancel()
        waiting = null
        TypingFixes.controller = this
        TypingFixes.selectedWord.value = null
        TypingFixes.stopOffer.value = null
        TypingFixes.fixes.value = emptyList()
        TypingFixes.listOpen.value = false
        TypingFixes.hiddenWhileTyping.value = false
        handTouched.clear()
        fixedSentences.clear()
        seenWords.clear()
        checked.clear()
        open = true
        modelOk = null
    }

    fun onFinishInput() {
        running?.cancel()
        selectionTip?.cancel()
        waiting = null
        // the fixes stay but the list of them goes
        TypingFixes.fixes.value = emptyList()
        TypingFixes.selectedWord.value = null
        TypingFixes.stopOffer.value = null
        TypingFixes.listOpen.value = false
        if (modelOk == true) ContextModels.releaseTypingSoon()
        open = false
    }

    private val conn get() = inputLogic.mConnection

    fun afterEvent(event: Event) {
        if (!enabled) return
        // when typing goes on, the offer to stop an undone fix goes
        TypingFixes.stopOffer.value = null
        val cursor = conn.expectedSelectionStart
        when {
            event.eventType == Event.EVENT_TYPE_SUGGESTION_PICKED -> wordStartBefore(cursor)?.let { handTouched.add(it) }
            event.mKeyCode == Constants.CODE_DELETE || event.mCodePoint == Constants.CODE_DELETE ->
                wordStartBefore(cursor)?.let { handTouched.add(it) }
        }
        TypingFixes.hiddenWhileTyping.value = inputLogic.mWordComposer.isComposingWord
        schedule()
    }

    fun onSelectionChanged(newSelStart: Int, newSelEnd: Int) {
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
        TypingFixes.hiddenWhileTyping.value = inputLogic.mWordComposer.isComposingWord
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
        if (inputLogic.mWordComposer.isComposingWord || conn.hasSelection()) return
        waiting?.let { waiting = null; it() }
        if (running?.isActive == true) {
            again = true
            return
        }
        val cursor = conn.expectedSelectionStart
        val before = conn.getTextBeforeCursor(TEXT_CHARS, 0)?.toString() ?: return
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
                if (!target.third) continue
                for (m in WORD.findAll(before.substring(target.first, target.second))) {
                    if (m.value.length >= 3 && !inputLogic.isKnownWord(m.value)) unknownStarts.add(target.first + m.range.first)
                }
            }
        }
        running = helper.lifecycleScope.launch {
            for ((target, key) in targets) {
                val (from, to, finished) = target
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
        val n = TypingCorrectionConfig().earlyWords
        val pairs = ContextModels.typingPairs(context)
        return words.indices.any { i ->
            val after = words.size - 1 - i
            after in n..n + EARLY_RECHECKS && pairs.candidates(words[i]).isNotEmpty()
        }
    }

    private fun apply(base: Int, checkedText: String, fix: TypingCorrector.Fix, sentenceStart: Int) {
        if (inputLogic.mWordComposer.isComposingWord || conn.hasSelection()) return
        // the text up to the end of the checked part must be unchanged
        val cursor = conn.expectedSelectionStart
        val now = conn.getTextBeforeCursor(cursor - base, 0)?.toString() ?: return
        if (cursor - now.length != base || !now.startsWith(checkedText.trimEnd())) return
        val start = base + fix.start
        val text = SpannableString(fix.replacement)
        try {
            text.setSpan(
                SuggestionSpan(context, locale, arrayOf(fix.original), SuggestionSpan.FLAG_AUTO_CORRECTION, null),
                0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } catch (e: Exception) {
            // not supported here, so commit it plain
        }
        if (!conn.replaceTextBeforeCursor(start, start + fix.original.length, text)) return
        fixedSentences[sentenceStart] = (fixedSentences[sentenceStart] ?: 0) + 1
        // the sentence is checked again for another slip ("when your pour" -> "when you pour" -> "pout"),
        // but this word stays as fixed
        handTouched.add(start)
        val after = checkedText.substring(fix.end, minOf(checkedText.length, fix.end + CONTEXT_CHARS))
        val beforeCtx = checkedText.substring(maxOf(0, fix.start - CONTEXT_CHARS), fix.start)
        TypingFixes.fixes.value = TypingFixes.fixes.value + TypingFix(nextId++, start, fix.original, fix.replacement, beforeCtx, after)
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

    /** Whether the text around [fix] is still what it was right after the fix. */
    private fun intact(fix: TypingFix): Boolean {
        val cursor = conn.expectedSelectionStart
        if (conn.hasSelection() || fix.end > cursor) return false
        val from = fix.start - fix.before.length
        val now = conn.getTextBeforeCursor(cursor - from, 0)?.toString() ?: return false
        if (cursor - now.length != from) return false
        return fix.matches(now)
    }

    fun refreshStale() {
        if (inputLogic.mWordComposer.isComposingWord) return
        TypingFixes.fixes.value = TypingFixes.fixes.value.map { it.copy(stale = !intact(it)) }
    }

    fun revert(fix: TypingFix) {
        if (inputLogic.mWordComposer.isComposingWord) {
            // the composing word would be committed by the edit, so finish it first
            inputLogic.mConnection.finishComposingText()
            inputLogic.mWordComposer.reset(true)
        }
        if (!intact(fix)) {
            TypingFixes.fixes.value = TypingFixes.fixes.value.map { if (it.id == fix.id) it.copy(stale = true) else it }
            return
        }
        if (!conn.replaceTextBeforeCursor(fix.start, fix.end, fix.original)) return
        val prev = Regex("([A-Za-z']+)\\W*$").find(fix.before)?.groupValues?.get(1)?.lowercase() ?: ""
        val next = Regex("^\\W*([A-Za-z']+)").find(fix.after)?.groupValues?.get(1)?.lowercase() ?: ""
        memory.recordRevert(fix.original.lowercase(), fix.replacement.lowercase(), prev, next)
        handTouched.add(fix.start)
        TypingFixes.fixes.value = TypingFixes.fixes.value.filter { it.id != fix.id }
        shiftAfter(fix.start, fix.original.length - fix.replacement.length)
        if (TypingFixes.fixes.value.isEmpty()) TypingFixes.listOpen.value = false
        // the undo makes this fix more careful here, and the chip offers to stop it everywhere
        TypingFixes.stopOffer.value = fix
        FeatureTips.offer(context, FeatureTip.TYPING_FIX_UNDONE)
        stopOfferTimeout?.cancel()
        stopOfferTimeout = launch {
            delay(STOP_OFFER_MS)
            if (TypingFixes.stopOffer.value?.id == fix.id) TypingFixes.stopOffer.value = null
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
