package org.futo.voiceinput.shared.punctuation

import java.util.Locale

/** One whitespace-delimited word of the ASR transcript, with the ASR's own sentence punctuation removed from its edges. */
internal class AsrWord(
    /** Word without edge sentence punctuation (original casing). Never empty. */
    val core: String,
    /** The ASR wrote an abbreviation dot ("Dr.", "U.S.", "etc.") that must be kept. */
    val keepDot: Boolean,
    /** Strongest sentence terminator ('.', '?' or '!') the ASR had after this word, or null. */
    val asrTerminal: Char?,
    /**
     * First word of an ASR segment (an utterance after a pause) other than the first one. The ASR capitalizes
     * such a word because a new utterance started there, not because it knows a sentence starts there.
     */
    val segmentStart: Boolean = false,
) {
    /**
     * "Mixed case" means having an uppercase letter anywhere but position 0 (iPhone, NASA, McDonald), or being a dotted
     * abbreviation (p.m., e.g., U.S). Such words keep the ASR's casing.
     */
    val mixedCase: Boolean = core.codePoints().skip(1).anyMatch { Character.isUpperCase(it) } ||
        TextProcessing.DOTTED_INNER.containsMatchIn(core)
    val startsUpper: Boolean = TextProcessing.firstLetterIndex(core).let { it >= 0 && Character.isUpperCase(core.codePointAt(it)) }

    /** Opening quotes / brackets in front of the word (kept, but not shown to the models). */
    val prefix: String
    /** The word proper. */
    val body: String
    /** Closing quotes / brackets after the word (kept, but not shown to the models). */
    val suffix: String
    /** What the models see (original casing, curly apostrophes straightened). */
    val modelText: String

    init {
        var a = 0
        while (a < core.length - 1 && TextProcessing.OPENERS.indexOf(core[a]) >= 0) a++
        var b = core.length
        while (b > a + 1 && TextProcessing.WRAP_CLOSERS.indexOf(core[b - 1]) >= 0) b--
        prefix = core.substring(0, a)
        body = core.substring(a, b)
        suffix = core.substring(b)
        modelText = body.replace('’', '\'').replace('‘', '\'')
    }

    fun wrap(cased: String): String = prefix + cased + suffix
}

internal object TextProcessing {
    private const val SENTENCE_PUNCT = ".,?!;:…"
    const val OPENERS = "\"“‘([{«"
    const val WRAP_CLOSERS = "\"”’)]}»"
    private const val CLOSERS = "\"'”’)]}"

    /** Abbreviations whose trailing dot is not a sentence boundary signal and must be kept as the ASR wrote it. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "jr", "sr", "vs", "etc", "inc", "ltd", "mt", "st", "approx", "dept", "est",
    )
    /** "i", "i'm", "i've", "i'll", "i'd" (lowercase), which are always capitalized. */
    val PRONOUN_I = Regex("^i(?:['’](?:m|ve|ll|d))?$")
    val DOTTED_INNER = Regex("\\p{L}\\.\\p{L}")
    private val DOTTED_ABBREV = Regex("^[A-Za-z](\\.[A-Za-z])+$") // U.S, e.g, a.m, p.m (final dot stripped)

    /** Words of the ASR [segments] (utterances, in order), with [AsrWord.segmentStart] set on segment starts. */
    fun splitSegments(segments: List<String>): List<AsrWord> {
        val out = ArrayList<AsrWord>()
        for (segment in segments) {
            val words = splitWords(segment)
            if (words.isEmpty()) continue
            if (out.isNotEmpty()) {
                val w = words[0]
                out.add(AsrWord(w.core, w.keepDot, w.asrTerminal, segmentStart = true))
                out.addAll(words.subList(1, words.size))
            } else {
                out.addAll(words)
            }
        }
        return out
    }

    fun splitWords(text: String): List<AsrWord> {
        val out = ArrayList<AsrWord>()
        for (raw in text.split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            var t = raw
            // trailing run of sentence punctuation (possibly followed by closing quotes / brackets)
            var end = t.length
            while (end > 0 && CLOSERS.indexOf(t[end - 1]) >= 0) end--
            var j = end
            while (j > 0 && SENTENCE_PUNCT.indexOf(t[j - 1]) >= 0) j--
            val removedTrailing = t.substring(j, end)
            if (j < end) t = t.substring(0, j) + t.substring(end)
            // leading run (".hello"), but not a decimal like ".5"
            var s = 0
            while (s < t.length && SENTENCE_PUNCT.indexOf(t[s]) >= 0 &&
                !(t[s] == '.' && s + 1 < t.length && t[s + 1].isDigit())) s++
            val removedLeading = t.substring(0, s)
            t = t.substring(s)
            if (t.isEmpty() || t.all { it in CLOSERS }) {
                // Token made only of punctuation (e.g. a lone "." or a lone quote), so not a word.
                // Attach quotes to the previous word and pass the terminator on to it.
                val prev = out.lastOrNull()
                val term = terminal(removedTrailing + removedLeading)
                if (prev != null) {
                    out[out.size - 1] = AsrWord(prev.core + t, prev.keepDot, prev.asrTerminal ?: term, prev.segmentStart)
                }
                continue
            }
            val bare = t.trimEnd { it in CLOSERS }
            val keepDot = removedTrailing.startsWith(".") && (
                bare.lowercase(Locale.ROOT) in ABBREVIATIONS || DOTTED_ABBREV.matches(bare))
            out.add(AsrWord(t, keepDot, terminal(removedTrailing)))
        }
        return out
    }

    private fun terminal(removed: String): Char? = when {
        removed.contains('?') -> '?'
        removed.contains('!') -> '!'
        removed.contains('.') || removed.contains('…') -> '.'
        else -> null
    }

    /** Joins everything. [marks] hold "", ",", ".", "?", "!" or ":" per word, and [boundary] marks sentence ends. */
    fun assemble(
        words: List<AsrWord>,
        cased: Array<String>,
        marks: Array<String>,
        boundary: BooleanArray,
        options: RestoreOptions,
    ): String {
        val sb = StringBuilder()
        var sentenceStart = true
        for (i in words.indices) {
            val w = words[i]
            var text = cased[i]
            if (sentenceStart) text = capitalizeFirst(text, w.mixedCase)
            var mark = marks[i]
            var isBoundary = boundary[i]
            val last = i == words.size - 1
            if (isBoundary && mark != "." && mark != "?" && mark != "!") mark = "."
            if (w.keepDot) {
                if (mark != "?" && mark != "!") mark = "."
            }
            if (last && mark != "." && mark != "?" && mark != "!") { mark = "."; isBoundary = true }
            // The ASR heard the audio, so its "?" / "!" reflect intonation, which a text-only punctuation model
            // can't know ("...at the clubhouse, right? And bowls..."). Keep them as sentence ends.
            // ... except a "?" on a lone connective ("with his condition, | And? | Gets a little impatient"), because
            // that is a pause, not a question, so the model decides
            val pauseQuestion = w.asrTerminal == '?' &&
                w.core.trimEnd { it in CLOSERS || it == '?' }.lowercase(Locale.ROOT) in PAUSE_WORDS
            if (options.preserveAsrTerminators && !pauseQuestion && (w.asrTerminal == '?' || w.asrTerminal == '!')) {
                mark = w.asrTerminal.toString()
                isBoundary = true
            }
            if (i > 0) sb.append(' ')
            sb.append(text).append(mark)
            sentenceStart = isBoundary
        }
        return sb.toString()
    }

    fun firstLetterIndex(s: String): Int {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isLetter(cp)) return i
            if (Character.isDigit(cp)) return -1
            i += Character.charCount(cp)
        }
        return -1
    }

    /** Uppercases the first letter unless the word is "mixed case" and starts lowercase (iPhone). */
    private val PAUSE_WORDS = setOf("and", "but", "so", "or", "then", "because", "although", "though", "while", "yet")
    private val KNOWN_MIXED_FORMS = setOf(
        "iPhone", "iPad", "iPod", "iMac", "iOS", "macOS", "iPadOS", "YouTube", "eBay", "PayPal", "GitHub",
        "LinkedIn", "TikTok", "WhatsApp", "JavaScript", "PowerPoint", "McDonald", "McDonald's",
    )

    /** [w] is all lower case, Capitalized, ALL CAPS or a known mixed-case form ("iPhone"), but not subword casing ("eTA"). */
    fun wholeWordCase(w: String): Boolean {
        val letters = w.filter { it.isLetter() }
        if (letters.length < 2) return true
        if (letters.all { it.isLowerCase() } || letters.all { it.isUpperCase() }) return true
        if (letters[0].isUpperCase() && letters.drop(1).all { it.isLowerCase() }) return true
        return w in KNOWN_MIXED_FORMS
    }

    fun capitalizeFirst(s: String, mixedCase: Boolean): String {
        val i = firstLetterIndex(s)
        if (i < 0) return s
        val cp = s.codePointAt(i)
        if (Character.isUpperCase(cp)) return s
        if (mixedCase) return s
        val n = Character.charCount(cp)
        return s.substring(0, i) + String(Character.toChars(Character.toTitleCase(cp))) + s.substring(i + n)
    }

    fun lowercaseFirst(s: String): String {
        val i = firstLetterIndex(s)
        if (i < 0) return s
        val cp = s.codePointAt(i)
        val n = Character.charCount(cp)
        return s.substring(0, i) + String(Character.toChars(Character.toLowerCase(cp))) + s.substring(i + n)
    }
}
