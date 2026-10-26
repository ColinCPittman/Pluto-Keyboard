package org.futo.voiceinput.shared.context

/**
 * Rule-based candidate generation for the options that delete or restructure text (fillers,
 * self-corrections, spoken commands and numbers). Rules only PROPOSE edits, and the context
 * stage's LM judges each group of alternatives against the unchanged text, and
 * [TranscriptGuard.checkEdit] re-checks every accepted edit with the same rules.
 */
object SpokenEdits {
    enum class Kind { FILLER, REPEAT, SELF_CORRECTION, COMMAND, SCRATCH, DELETE_WORD, QUOTE, EMOJI, NUMBER }

    /**
     * Replace text[start, end) with [replacement]. [parallel] marks a self-correction whose repair
     * mirrors what it replaces (it needs less of the LM's approval).
     */
    data class SpanEdit(
        val start: Int, val end: Int, val replacement: String, val kind: Kind, val label: String = "",
        val parallel: Boolean = false,
    )

    /**
     * Alternatives for one spot, and at most one is applied (the LM's best, if it beats the original).
     * [certain] is a single alternative the rules are sure of (a command phrase said between two
     * pauses), applied without asking the LM, but the guard still checks it.
     */
    class Group(val kind: Kind, val alternatives: List<SpanEdit>, val label: String, val certain: Boolean = false) {
        val start get() = alternatives.minOf { it.start }
        val end get() = alternatives.maxOf { it.end }
    }

    /** A tokenized text with the character span of every word. */
    class Words(val text: String) {
        val tok = TranscriptGuard.tokenize(text)
        val words get() = tok.words
        val gaps get() = tok.gaps
        val norm = tok.words.map { TranscriptGuard.normWord(it) }
        val starts = IntArray(tok.words.size)
        val ends = IntArray(tok.words.size)

        init {
            var pos = tok.gaps[0].length
            for ((k, w) in tok.words.withIndex()) {
                starts[k] = pos
                ends[k] = pos + w.length
                pos += w.length + tok.gaps[k + 1].length
            }
        }

        val size get() = words.size

        /** Punctuation (non-space) of the gap before word k (the end of the text when k == size). */
        fun punctBefore(k: Int) = TranscriptGuard.punct(gaps[k])

        /** Word k starts a sentence (text start, or after . ? ! or a line break). */
        fun sentenceStart(k: Int) = k == 0 || punctBefore(k).any { it in ".?!" } || '\n' in gaps[k]

        /** The gap before word k ends a sentence/line (text end when k == size). */
        fun sentenceEndBefore(k: Int) = k == size || sentenceStart(k)
    }

    // ------------------------------------------------------------------------------------
    // Fillers and repeats

    /** Words that count as fillers for other rules (repeats, self-corrections), meaning the hesitation sounds. */
    val FILLERS: Set<String> = FillerWords.SOUNDS.toSet() + setOf("ummm", "uhhh", "hmmm")

    /** The built-in filler list ([FillerWords.DEFAULTS]) as the rules use it. */
    val DEFAULT_FILLERS: List<FillerWords.Filler> = FillerWords.fillers(FillerWords.DEFAULTS)

    /** Emphatic or grammatical doubles that are never "accidental repeats". */
    val PROTECTED_REPEATS = setOf(
        "that", "had", "is", "very", "really", "so", "no", "bye", "yeah", "yes", "knock", "night", "chop", "ha",
        "go", "now", "there", "well", "blah", "tsk", "ho", "hey", "okay", "ok", "please", "much", "many", "far",
        "long", "more", "too", "never", "ever", "over", "again", "round", "on", "out", "up", "down", "away", "big",
        "little", "wait", "hip", "boo", "bla", "la", "na", "oh", "ah", "aye", "tut", "do", "chug", "yum", "woo",
        "hush", "ding", "bling", "beep", "honk", "hmm", "um", "uh", "fifty", "twenty", "one", "two", "three",
        "ten", "hundred", "thousand", "million", "bang", "boom", "ping", "pong", "tick", "tock",
    )

    /** Second halves of two-word interjections that start with a filler sound ("uh huh", "uh oh"). */
    private val INTERJECTION_FIRST = setOf("uh", "um", "uhm", "uhh", "umm")
    private val INTERJECTION_SECOND = setOf("huh", "oh", "hmm", "hm")

    /** Asides said between pauses that start with a word the sentence may just have used ("Do you, you know, ..."). */
    private val ASIDES = listOf(listOf("you", "know"), listOf("you", "see"))

    /** Word k is inside quotation marks (quoted speech keeps its fillers, as in she said "um, what?"). */
    private fun insideQuotes(words: Words, k: Int): Boolean {
        val before = words.text.substring(0, words.starts[k])
        return before.count { it == '"' } % 2 == 1 || before.count { it == '“' } > before.count { it == '”' }
    }

    /** Marks the voice model writes at a pause inside a sentence. */
    private const val PAUSE_MARKS = ",;—–…"

    /** Length (in words) of the filler starting at word k, or 0. */
    private fun fillerAt(words: Words, k: Int, fillers: List<FillerWords.Filler>): Int {
        if (insideQuotes(words, k)) return 0
        for (f in fillers) {
            val n = f.words.size
            if (k + n > words.size || words.norm.subList(k, k + n) != f.words) continue
            // a filler phrase is said in one go ("one of a kind, of course" is not "kind of")
            if ((1 until n).any { words.punctBefore(k + it).isNotEmpty() || words.sentenceStart(k + it) }) continue
            // "ER" (the emergency room) / "UM" ... are words when in all caps, not fillers
            if ((k until k + n).any { val w = words.words[it]; w.length > 1 && w.all { c -> c.isUpperCase() } }) continue
            if (f.atEnd) {
                // "so yeah" closes a sentence after a pause ("we went home, so yeah."), or is one on
                // its own, and not a question ("so yeah?")
                if (!words.sentenceEndBefore(k + n) || '?' in words.punctBefore(k + n)) continue
                if (!words.sentenceStart(k) && words.punctBefore(k).none { it in PAUSE_MARKS }) continue
            } else {
                // interjections ("Ah, I see.") and the user's own fillers only inside a sentence
                if (!f.anywhere && (words.sentenceStart(k) || words.sentenceEndBefore(k + n))) continue
                // words that are fillers only between pauses ("it was, like, huge" but not "I like it")
                if (f.betweenPauses && (words.punctBefore(k).none { it in PAUSE_MARKS } || words.punctBefore(k + n).none { it in PAUSE_MARKS })) continue
                // before a number it means "about" ("it was, like, twenty bucks")
                if (f.betweenPauses && isNumberish(words.words[k + n])) continue
            }
            // part of a two-word interjection said without a pause ("uh huh", "uh oh", "um hmm")
            if (n == 1 && words.norm[k] in INTERJECTION_FIRST && k + 1 < words.size && words.gaps[k + 1] == " " &&
                words.norm[k + 1] in INTERJECTION_SECOND
            ) continue
            return n
        }
        return 0
    }

    /** Deleting words [i, j) gives alternatives for the punctuation left behind. */
    private fun deletion(
        words: Words, i: Int, j: Int, kind: Kind, label: String, isWord: ((String) -> Boolean)? = null,
    ): List<SpanEdit> {
        val t = words.text
        val out = ArrayList<SpanEdit>()
        if (j < words.size) {
            // keep the gap before word i, drop the deleted words and the gap after them
            out.add(SpanEdit(words.starts[i], words.starts[j], "", kind, label))
            // or join the neighbours with a plain space ("I was, uh, thinking" -> "I was thinking")
            if (i > 0 && !words.sentenceEndBefore(i) && words.punctBefore(i).isNotEmpty() && '\n' !in words.gaps[i]) {
                out.add(SpanEdit(words.ends[i - 1], words.starts[j], " ", kind, label))
            }
            // the voice model ended a sentence at the pause after the filler ("The meeting is, um...
            // At 2.", "I was. Um. Thinking we could go"), or join across it with the next word lowercase
            if (i > 0 && words.sentenceStart(j) && '\n' !in words.gaps[j] && '\n' !in words.gaps[i]) {
                val w = words.words[j]
                val next = if (w[0].isUpperCase() && !TranscriptGuard.isPronounI(w) && isWord?.invoke(w) == true) {
                    w.substring(0, 1).lowercase() + w.substring(1)
                } else w
                if (next != w) out.add(SpanEdit(words.ends[i - 1], words.ends[j], " $next", kind, label))
                else if (!w[0].isLowerCase()) out.add(SpanEdit(words.ends[i - 1], words.starts[j], " ", kind, label))
            }
        } else if (i > 0) {
            // at the end, keep the final punctuation ("I think so, um." -> "I think so.")
            out.add(SpanEdit(words.ends[i - 1], words.ends[j - 1], "", kind, label))
        }
        return out.filter { e -> e.start < e.end && t.substring(e.start, e.end) != e.replacement }
    }

    /**
     * [isWord] tells whether a word is a common lowercase word (the lexicon's case data). A repeated
     * word that is not ("mahi mahi", "Bora Bora", "choo choo") is a name or a reduplication,
     * not a stutter. Null means no such data (every word counts).
     */
    fun fillerGroups(
        words: Words, fillers: List<FillerWords.Filler> = DEFAULT_FILLERS, isWord: ((String) -> Boolean)? = null,
    ): List<Group> {
        val out = ArrayList<Group>()
        var k = 0
        while (k < words.size) {
            val first = fillerAt(words, k, fillers)
            if (first > 0) {
                // a run of fillers ("um, uh")
                var e = k + first
                while (e < words.size && !words.sentenceStart(e)) {
                    val n = fillerAt(words, e, fillers)
                    if (n == 0) break
                    e += n
                }
                if (!(k == 0 && e == words.size)) {
                    val alts = deletion(words, k, e, Kind.FILLER, words.words.subList(k, e).joinToString(" "), isWord)
                    // hesitation sounds ("um", "hmm") are never meant, so they are removed without the LM's
                    // vote (it only picks the punctuation left behind)
                    val certain = (k until e).all { i -> fillers.any { f -> f.anywhere && f.words == listOf(words.norm[i]) } }
                    if (alts.isNotEmpty()) out.add(Group(Kind.FILLER, alts, "filler", certain))
                }
                k = e
                continue
            }
            k++
        }
        // accidental repeats of one or two words ("I I think", "the the", "I think I think")
        for (n in 1..2) {
            for (i in 0..words.size - 2 * n) {
                val a = words.norm.subList(i, i + n)
                val b = words.norm.subList(i + n, i + 2 * n)
                if (a != b) continue
                if (n == 1 && a[0] in PROTECTED_REPEATS) continue
                if (a.any { it.any { c -> c.isDigit() } } || a.all { it in FILLERS }) continue
                // the repeat must be within one sentence, separated by spaces/commas/dashes only
                val inner = (i + 1..i + 2 * n - 1)
                if (inner.any { words.sentenceStart(it) || !words.punctBefore(it).all { c -> c in ",-—–…" } }) continue
                // no pause inside a copy ("I know, I know, I" is not "know, I" twice)
                if ((1 until n).any { words.punctBefore(i + it).isNotEmpty() || words.punctBefore(i + n + it).isNotEmpty() }) continue
                // said twice on purpose when there is a pause between the copies and after the second one
                // ("I know, I know.", "Got it, got it, thanks", "Sorry, sorry, I didn't see you")
                if (words.punctBefore(i + n).isNotEmpty() && (i + 2 * n == words.size || words.punctBefore(i + 2 * n).isNotEmpty())) continue
                // the second copy opens an aside between pauses ("Do you, you know, want to come?")
                if (n == 1 && words.punctBefore(i + 1).isNotEmpty() && ASIDES.any { p ->
                        i + 1 + p.size < words.size && words.norm.subList(i + 1, i + 1 + p.size) == p &&
                            (2 until 1 + p.size).none { words.punctBefore(i + it).isNotEmpty() } &&
                            words.punctBefore(i + 1 + p.size).any { it in PAUSE_MARKS }
                    }) continue
                // a name or a reduplicated word ("Bora Bora", "mahi mahi"), not a stutter
                if (n == 1 && !TranscriptGuard.isPronounI(words.words[i]) &&
                    (isMidName(words, i) || isMidName(words, i + 1) || (isWord != null && !isWord(words.words[i])))
                ) continue
                val alts = deletion(words, i, i + n, Kind.REPEAT, a.joinToString(" "))
                    .filter { it.replacement == "" }  // keep the gap before the first copy
                if (alts.isNotEmpty()) out.add(Group(Kind.REPEAT, alts, "repeat"))
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------
    // Self-corrections

    /** The built-in self-correction phrases ([CorrectionPhrases.DEFAULTS]) as markers. */
    val DEFAULT_MARKERS: List<CorrectionMarker> = CorrectionPhrases.markers(CorrectionPhrases.DEFAULTS)

    /** A marker followed by one of these starts a new clause ("I mean it", "actually, I think ..."), so no repair. */
    val CLAUSE_STARTERS = setOf(
        "i", "you", "he", "she", "it", "we", "they", "i'm", "you're", "he's", "she's", "it's", "we're", "they're",
        "i'll", "you'll", "we'll", "they'll", "i've", "you've", "we've", "they've", "i'd", "you'd", "we'd",
        "that's", "there's", "this", "that", "these", "those", "what", "how", "why", "who", "when", "where", "if",
        "like", "honestly", "seriously", "really", "come", "look", "yes", "yeah", "no", "sure", "ok", "okay", "well",
        "just", "to", "by", "is", "was", "are", "were", "do", "does", "did", "can", "could", "will", "would",
        "should", "have", "has", "had", "let", "let's", "please", "so", "but", "and", "because", "though",
        "anyway", "about", "for", "not", "never", "than", "me", "him", "her", "us", "them", "business", "every",
        "wait",
    )

    private const val DELIMS = ",;-—–…:("

    /** Prepositions and determiners that start a phrase ("on Monday", "the red one"). */
    /** Words that can come before a self-correction cue at the start of a dictation ("No, I mean", "Oh, sorry"). */
    private val OPENING_LEAD = setOf(
        "no", "nope", "oh", "ah", "um", "uh", "er", "well", "so", "and", "but", "or", "okay", "ok", "yeah", "yes",
        "sorry", "wait", "actually", "oops", "hmm", "hey", "nah", "ugh", "whoops", "shoot",
    )

    private val PHRASE_STARTS = setOf(
        "on", "at", "in", "to", "for", "with", "from", "by", "of", "about", "into", "onto", "after", "before",
        "until", "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that", "these", "those", "some",
    )

    /** Word k is a capitalized word inside a sentence (a name, like "Sam" or "Alex"). */
    private fun isMidName(words: Words, k: Int): Boolean {
        val w = words.words[k]
        return !words.sentenceStart(k) && w[0].isUpperCase() && !TranscriptGuard.isPronounI(w) &&
            w.drop(1).all { !it.isLetter() || it.isLowerCase() }
    }

    /** Word k (the first marker word) follows a pause (punctuation before it, or a segment start). */
    private fun delimited(words: Words, k: Int, segmentStarts: Set<Int>) =
        k in segmentStarts || words.punctBefore(k).any { it in DELIMS }

    /** A number, spoken or in digits ("five", "5"). */
    private fun isNumberish(w: String) = NumberWords.isSpokenNumber(w) || (w.isNotEmpty() && w.all { it.isDigit() })

    /** [isWord] tells whether a word is a common lowercase word (a repair the voice model capitalized after a pause is lowercased). */
    fun selfCorrectionGroups(
        words: Words, segmentStarts: Set<Int>, markers: List<CorrectionMarker> = DEFAULT_MARKERS,
        isWord: ((String) -> Boolean)? = null,
    ): List<Group> {
        val out = ArrayList<Group>()
        var m = 1
        while (m < words.size) {
            val marker = markers.firstOrNull { mk ->
                val ws = mk.words
                m + ws.size <= words.size && words.norm.subList(m, m + ws.size) == ws &&
                    (1 until ws.size).none { words.sentenceStart(m + it) }
            }
            if (marker == null) {
                m++
                continue
            }
            val mw = marker.words
            val needsDelim = marker.needsPause
            val next = m + mw.size
            // a cue that opens the dictation ("No, I mean the other one.", "Oh, wait, ...") corrects an
            // earlier message, not anything here (only lead-in words before it), so it stays as said
            if ((0 until m).all { words.norm[it] in OPENING_LEAD || words.norm[it] in FILLERS }) {
                m = next
                continue
            }
            val atSentenceStart = words.sentenceStart(m)
            // the voice model ends a sentence at every pause ("on Friday, no wait. Saturday.", "We
            // need three. Actually, four chairs."). A repair that starts a sentence follows a phrase made
            // for corrections, or mirrors a number ("three" -> "four")
            val repairStartsSentence = next < words.size && words.sentenceStart(next) && '\n' !in words.gaps[next]
            val numbers = m > 0 && next < words.size && isNumberish(words.words[m - 1]) && isNumberish(words.words[next])
            if (marker.numbersOnly && !numbers) {
                m++
                continue
            }
            val sentenceOk = (!atSentenceStart || marker.atSentenceStart || (numbers && delimited(words, next, segmentStarts))) &&
                (!repairStartsSentence || ((mw.size > 1 || numbers) && (atSentenceStart || delimited(words, m, segmentStarts))))
            val ok = next < words.size && sentenceOk && (next >= words.size || '\n' !in words.gaps[next]) &&
                (!needsDelim || atSentenceStart || delimited(words, m, segmentStarts)) && words.norm[next] !in CLAUSE_STARTERS && !words.norm[next].endsWith("n't") &&
                words.norm[next] !in FILLERS
            if (ok) {
                // the repair is the words after the marker up to the next punctuation (at most 4)
                var rEnd = next + 1
                while (rEnd < words.size && rEnd - next < 4 && words.punctBefore(rEnd).isEmpty() && '\n' !in words.gaps[rEnd]) rEnd++
                val repairLen = rEnd - next
                // across a sentence end ("... toaster oven? I mean, air fryer?") only a short
                // repair that ends its sentence ("... the movie. I mean, the ending was weird" is a new thought)
                if (atSentenceStart || repairStartsSentence) {
                    var sEnd = next + 1
                    while (sEnd < words.size && !words.sentenceStart(sEnd)) sEnd++
                    if (sEnd - next > 3) {
                        m = next
                        continue
                    }
                }
                // the reparandum is 1..4 words before the marker, within its clause
                val lengths = ArrayList<Int>()
                for (n in 1..4) {
                    val r = m - n
                    if (r < 0) break
                    if ((r + 1..m - 1).any { words.punctBefore(it).isNotEmpty() || words.sentenceStart(it) }) break
                    lengths.add(n)
                    if (words.sentenceStart(r)) break
                }
                // a repair that restarts with the reparandum's first word ("the red one, sorry, the
                // blue one", "at the mall, scratch that, at the library") fixes the length, and otherwise
                // the reparandum is at most as long as the repair and the LM picks
                val restart = lengths.firstOrNull { words.norm[m - it] == words.norm[next] }
                // a reparandum that would take the sentence's first word too ("Text Sam, sorry, Alex")
                // only if nothing shorter is possible
                val notStart = lengths.filter { !words.sentenceStart(m - it) }.ifEmpty { lengths.take(1) }
                // the repair replaces a phrase of the same kind, so a reparandum that starts with
                // "on", "the", ... needs a repair that does too ("on Monday, I mean, Tuesday"
                // replaces "Monday", not "on Monday")
                val parallelStart = notStart.filter { n ->
                    words.norm[m - n] !in PHRASE_STARTS || words.norm[next] in PHRASE_STARTS
                }.ifEmpty { notStart.take(1) }
                val chosen = if (restart != null) listOf(restart) else parallelStart.filter { it <= repairLen }
                // a one-word marker ("sorry", "actually", "rather") said without a pause after it
                // is an ordinary word ("a long, rather boring meeting")
                val pauseAfter = delimited(words, next, segmentStarts)
                val repairIsPhrase = words.norm[next] in PHRASE_STARTS
                // a repair that starts with a number ("one cup") replaces a number ("two cups of sugar,
                // sorry, one cup" is not "two cups of one cup")
                val repairIsNumber = isNumberish(words.words[next])
                val alts = chosen.mapNotNull { n ->
                    // the repair mirrors the reparandum, so it restarts with the same word ("at five.
                    // I mean, at six"), or both are numbers or names ("Sam, sorry, Alex")
                    val a = words.words[m - n]
                    val b = words.words[next]
                    val parallel = n == restart ||
                        (isNumberish(a) && isNumberish(b)) ||
                        (n == 1 && isMidName(words, m - n) && isMidName(words, next))
                    val ok = (parallel || (
                        (!repairIsNumber || isNumberish(a)) &&
                        (mw.size > 1 || pauseAfter) &&
                            // a repair that starts a phrase ("the store is closed", "my car broke
                            // down") replaces a phrase of the same kind ("in the box, sorry, on the
                            // shelf"), or a name ("Sarah, I mean, my sister")
                            (!repairIsPhrase || words.norm[m - n] in PHRASE_STARTS || (n == 1 && isMidName(words, m - n))) &&
                            // all of the sentence before the marker ("Thanks, sorry, gotta run") only
                            // with a phrase made for corrections ("Monday, I mean, Tuesday works")
                            (mw.size > 1 || !words.sentenceStart(m - n))
                        ))
                    // a repair the voice model capitalized after its pause goes on lowercase ("Blue bag." -> "blue bag.")
                    val lower = repairStartsSentence && b[0].isUpperCase() && !TranscriptGuard.isPronounI(b) && isWord?.invoke(b) == true
                    if (!ok) null
                    else if (lower) SpanEdit(words.starts[m - n], words.ends[next], b.substring(0, 1).lowercase() + b.substring(1), Kind.SELF_CORRECTION, mw.joinToString(" "), parallel)
                    else SpanEdit(words.starts[m - n], words.starts[next], "", Kind.SELF_CORRECTION, mw.joinToString(" "), parallel)
                }
                if (alts.isNotEmpty()) out.add(Group(Kind.SELF_CORRECTION, alts, mw.joinToString(" ")))
            }
            m = next
        }
        return out
    }

    // ------------------------------------------------------------------------------------
    // Spoken commands

    /** An inserting command (punctuation, symbol or line break) with its words and what it does. */
    class Command(
        val words: List<String>,
        val action: VoiceCommandAction,
        val symbol: String = action.symbol,
        val endsSentence: Boolean = action.endsSentence,
    ) {
        val spacing get() = action.spacing

        /** A closing quote with a mark said right after it ("close quote, question mark") giving `?"`. */
        val isQuoteCombo get() = symbol != action.symbol

        /**
         * Unmistakable commands that need no pause around them, like quotes and brackets ("open quote"),
         * and words people rarely say otherwise ("ampersand", "asterisk").
         */
        val isDistinctive get() = (words.size > 1 && action in DISTINCTIVE_ACTIONS) ||
            (words.size == 1 && words[0] in DISTINCTIVE_WORDS)
    }

    private val DISTINCTIVE_ACTIONS = setOf(
        VoiceCommandAction.OPEN_QUOTE, VoiceCommandAction.CLOSE_QUOTE,
        VoiceCommandAction.OPEN_PARENTHESIS, VoiceCommandAction.CLOSE_PARENTHESIS,
        VoiceCommandAction.OPEN_BRACKET, VoiceCommandAction.CLOSE_BRACKET,
    )

    /** One-word commands people rarely say as ordinary words. */
    private val DISTINCTIVE_WORDS = setOf("ampersand", "asterisk", "underscore", "semicolon")

    /** Marks that go inside a closing quote said just before them. */
    private val MARKS_INSIDE_QUOTES = setOf(
        VoiceCommandAction.PERIOD, VoiceCommandAction.QUESTION_MARK, VoiceCommandAction.EXCLAMATION_MARK, VoiceCommandAction.COMMA,
    )

    /** The inserting commands of [commands] (switched on), longest phrase first. */
    fun punctuationCommands(commands: List<VoiceCommand>): List<Command> = commands
        .filter {
            it.enabled && it.words.isNotEmpty() &&
                it.action.spacing != CommandSpacing.DELETE_SENTENCE && it.action.spacing != CommandSpacing.DELETE_WORD
        }
        .map { Command(it.words, it.action) }
        .let { base ->
            // "close quote question mark" -> `?"` (the mark goes inside the quote)
            val combos = base.filter { it.symbol in CLOSERS }.flatMap { q ->
                base.filter { it.action in MARKS_INSIDE_QUOTES }.map { m ->
                    Command(q.words + m.words, q.action, m.symbol + q.symbol, m.action.endsSentence)
                }
            }
            base + combos
        }
        .sortedByDescending { it.words.size }

    /** The "delete the last sentence" phrases of [commands] ("scratch that"), longest first. */
    fun scratchCommands(commands: List<VoiceCommand>): List<List<String>> = commands
        .filter { it.enabled && it.action.spacing == CommandSpacing.DELETE_SENTENCE && it.words.isNotEmpty() }
        .map { it.words }
        .sortedByDescending { it.size }

    /** Closing quotes keep the sentence end said before them inside ("doing?""). */
    val CLOSERS = setOf("\"")

    /** The "delete the last word" phrases of [commands], longest first. */
    fun deleteWordCommands(commands: List<VoiceCommand>): List<List<String>> = commands
        .filter { it.enabled && it.action.spacing == CommandSpacing.DELETE_WORD && it.words.isNotEmpty() }
        .map { it.words }
        .sortedByDescending { it.size }

    /** Symbols that attach to the next word as a handle, tag or amount ("@sam", "#travel", "$5"). */
    private val TAGGING_ACTIONS = setOf(VoiceCommandAction.AT_SIGN, VoiceCommandAction.HASHTAG, VoiceCommandAction.DOLLAR_SIGN)

    /** Words that are never a handle, tag or amount, because after them the symbol is being talked about. */
    private val NOT_A_TAG = setOf(
        "or", "and", "is", "are", "was", "means", "mean", "of", "the", "a", "an", "to", "for", "in", "on", "at", "with",
        "vs", "versus", "key", "button", "symbol", "sign", "looks", "goes", "isn't", "but",
    )

    /** Before a punctuation word, these make it a noun ("the period", "a comma", "long period"). */
    val NOUN_CONTEXT = setOf(
        "a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "our", "their", "its", "of",
        "per", "each", "every", "any", "some", "no", "one", "another", "whole", "entire", "first", "last", "next",
        "same", "long", "short", "final", "extra", "big", "small", "little", "giant",
        "huge", "single", "double", "oxford", "serial", "missing", "misplaced", "put", "use", "add", "type", "need",
        "needs", "without", "with", "and", "or", "than", "what", "which",
    )

    /** Before "period" only, these make it a noun ("a trial period", "second period"), but "time comma" is a command. */
    val PERIOD_NOUN_CONTEXT = setOf(
        "trial", "time", "waiting", "grace", "free", "probation", "probationary", "cooling", "rest", "school", "class",
        "second", "third", "fourth", "honeymoon", "transition", "billing", "reporting", "adjustment", "menstrual",
        "heavy",
    )

    /**
     * Before a command of two or more words ("exclamation point", "new line") only these make it a
     * noun ("an exclamation point", "the new line"), and the rest of [NOUN_CONTEXT] is about one-word
     * marks ("a free period", "the oxford comma").
     */
    val PHRASE_NOUN_CONTEXT = setOf(
        "a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "our", "their", "its", "of",
        "each", "every", "any", "some", "no", "one", "another", "put", "use", "add", "type", "need", "needs",
        "without", "with", "and", "or", "than", "what", "which", "missing", "extra", "double", "single",
        "big", "huge", "little", "small", "giant", "tiny", "large", "long", "short", "whole", "entire", "same",
        "first", "last", "real", "total", "complete", "final",
    )

    /** After a command word these make it a noun phrase ("period of time", "new line of products"). */
    val NOUN_FOLLOW = setOf("of", "separated", "splice", "splices", "key", "button", "sign", "symbol", "pieces", "piece", "drama", "for", "in")

    /** Word k (start) or word end (exclusive) is a pause boundary (segment start, sentence boundary or text edge). */
    private fun boundaryAt(words: Words, k: Int, segmentStarts: Set<Int>) =
        k == 0 || k == words.size || k in segmentStarts || words.sentenceStart(k)

    /** Word e starts an utterance or was capitalized by the voice model ("Exclamation point When are you"). */
    private fun nextStartsSentence(words: Words, e: Int, segmentStarts: Set<Int>) =
        e < words.size && (e in segmentStarts || (words.words[e][0].isUpperCase() && !TranscriptGuard.isPronounI(words.words[e])))

    /** Marks after which a sentence goes on (the next word is lowercase even after the ASR's period). */
    private val CONTINUING_MARKS = setOf(VoiceCommandAction.COMMA, VoiceCommandAction.SEMICOLON, VoiceCommandAction.COLON)

    /** The words from [e] to the next sentence end do not end with . ? ! (a fragment, like "for some reason,"). */
    private fun clauseContinues(words: Words, e: Int): Boolean {
        var c = e + 1
        while (c < words.size && !words.sentenceStart(c)) c++
        return TranscriptGuard.punct(words.gaps[c]).none { it in ".?!" }
    }

    private fun capitalizeFirst(w: String) = w.substring(0, 1).uppercase() + w.substring(1)

    /**
     * Number of words of [words] from [k] that say [phrase], or 0. A word may hold several words
     * of the phrase joined by hyphens ("open-quote", "close-quote", "new-line"), but it must hold
     * whole phrase words only.
     */
    fun phraseAt(words: Words, k: Int, phrase: List<String>): Int = phraseLength(words.norm, k, phrase)

    /** [phraseAt] on normalized words. */
    fun phraseLength(norm: List<String>, k: Int, phrase: List<String>): Int {
        var i = 0
        var t = k
        while (i < phrase.size) {
            if (t >= norm.size) return 0
            val parts = if ('-' in norm[t]) norm[t].split('-').filter { it.isNotEmpty() } else listOf(norm[t])
            if (i + parts.size > phrase.size || phrase.subList(i, i + parts.size) != parts) return 0
            i += parts.size
            t++
        }
        return t - k
    }

    fun commandGroups(
        words: Words, segmentStarts: Set<Int>, lexicon: SoundAlikeLexicon?, names: PersonalNames,
        commands: List<VoiceCommand> = VoiceCommands.DEFAULTS,
    ): List<Group> {
        val out = ArrayList<Group>()
        val t = words.text
        val punctuation = punctuationCommands(commands)
        val scratches = scratchCommands(commands)
        val deleteWords = deleteWordCommands(commands)
        var k = 0
        while (k < words.size) {
            val scratchLen = scratches.firstNotNullOfOrNull { phraseAt(words, k, it).takeIf { n -> n > 0 } }
            if (scratchLen != null) {
                scratchGroup(words, k, k + scratchLen, segmentStarts)?.let { out.add(it) }
                k += scratchLen
                continue
            }
            val deleteLen = deleteWords.firstNotNullOfOrNull { phraseAt(words, k, it).takeIf { n -> n > 0 } }
            if (deleteLen != null) {
                deleteWordGroup(words, k, k + deleteLen, segmentStarts)?.let { out.add(it) }
                k += deleteLen
                continue
            }
            var cmdLen = 0
            val cmd = punctuation.firstOrNull { c ->
                val n = phraseAt(words, k, c.words)
                cmdLen = n
                n > 0 && (1 until n).none {
                    // the ASR writes a comma where you pause inside a command ("open, quote",
                    // "close quote, question mark"), so only a sentence end splits a command
                    val p = words.punctBefore(k + it)
                    words.sentenceStart(k + it) || (p.isNotEmpty() && p.any { ch -> ch !in ",;-—–" })
                }
            }
            if (cmd == null) {
                k++
                continue
            }
            val e = k + cmdLen
            val before = boundaryAt(words, k, segmentStarts)
            val after = boundaryAt(words, e, segmentStarts)
            val prev = if (k > 0) words.norm[k - 1] else null
            val nextW = if (e < words.size) words.norm[e] else null
            val spacing = cmd.spacing
            // evidence is a pause on one side and no noun context ("a free period", "my colon"). Noun
            // context only counts within the same sentence, so after the previous sentence's own
            // punctuation ("... for free. Exclamation point.") it doesn't
            val nounWords = if (cmd.words.size > 1) PHRASE_NOUN_CONTEXT else NOUN_CONTEXT
            val nounBefore = prev != null && !words.sentenceStart(k) &&
                (prev in nounWords || (cmd.action == VoiceCommandAction.PERIOD && cmd.words.size == 1 && prev in PERIOD_NOUN_CONTEXT))
            // in "close quote for some reason" words like "for" only make a one-word mark a noun ("period for")
            val nounAfter = nextW != null && nextW in NOUN_FOLLOW && !cmd.isDistinctive
            var ok = (before || after || cmd.isDistinctive) && !nounBefore && !nounAfter
            ok = ok && when (spacing) {
                // a mark after a word. A one-word mark ("period", "comma") needs a pause after it,
                // longer ones ("exclamation point") also work with a pause before
                CommandSpacing.AFTER_WORD ->
                    k > 0 && (after || cmd.isDistinctive || (cmd.words.size > 1 && before && nextStartsSentence(words, e, segmentStarts)))
                // a mark before a word ("open parenthesis", "dollar sign") needs that word
                // ("At sign or at symbol?" doesn't count, since a handle, a tag or an amount is not "or", "means", ...)
                CommandSpacing.BEFORE_WORD -> e < words.size &&
                    (cmd.action !in TAGGING_ACTIONS || words.norm[e] !in NOT_A_TAG)
                // joining ("hyphen", "slash") needs a word on both sides, and a spaced symbol a word
                // before it ("rock & roll", while "Asterisk means footnote." talks about it)
                CommandSpacing.JOIN -> k > 0 && e < words.size
                CommandSpacing.SPACED -> k > 0
                CommandSpacing.LINE -> !(k == 0 && e == words.size)
                else -> false
            }
            // ", period." is emphasis ("You're not going, period."), not dictation
            if (cmd.symbol == "." && words.punctBefore(k).isNotEmpty()) ok = false
            if (!ok) {
                k = e
                continue
            }
            // replacement range is [end of the previous word (or the command), end of the next word (or the text end))
            val a = if (k > 0) words.ends[k - 1] else words.starts[k]
            val b = if (e < words.size) words.starts[e] else t.length
            val prevPunct = if (k > 0) TranscriptGuard.punct(words.gaps[k]).filter { it in ".?!,;:" } else ""
            var nextWord = if (e < words.size) words.words[e] else null
            // a word the ASR capitalized only because it started an utterance, inside a sentence
            fun lowercased(w: String): String =
                if (w.length > 1 && w[0].isUpperCase() && !TranscriptGuard.isPronounI(w) && !names.isCapitalizedName(w) &&
                    w.drop(1).none { it.isUpperCase() } && lexicon?.isLowercaseWord(w) == true
                ) w.substring(0, 1).lowercase() + w.substring(1) else w
            val gapAfter = if (nextWord != null && '\n' in words.gaps[e]) words.gaps[e].filter { it.isWhitespace() } else " "
            val rep = StringBuilder()
            when (spacing) {
                CommandSpacing.LINE -> {
                    // keep the previous sentence's own punctuation, then the line break, and the next line starts capitalized
                    rep.append(prevPunct.filter { it in ".?!:," })
                    rep.append(cmd.symbol)
                    if (nextWord != null && nextWord[0].isLowerCase()) nextWord = capitalizeFirst(nextWord)
                }
                CommandSpacing.AFTER_WORD -> {
                    if (cmd.action.spaceBefore) rep.append(' ')
                    // the ASR ended a sentence after the command only because you paused, so after a
                    // comma, or before a clause that does not end a sentence ("... close quote.
                    // For some reason," -> `" for some reason`), the next word goes on lowercase
                    val continues = nextWord != null && !cmd.endsSentence && words.sentenceStart(e) && '\n' !in words.gaps[e] &&
                        !prevPunct.any { it in ".?!" } && (cmd.action in CONTINUING_MARKS || clauseContinues(words, e))
                    if (continues) nextWord = lowercased(nextWord!!)
                    // a closing mark keeps the sentence's own end in front of it ("doing?"")
                    // (only when the sentence ends there, since ""Hello" to me" continues)
                    if (cmd.symbol in CLOSERS && !cmd.isQuoteCombo && (nextWord == null || nextWord!![0].isUpperCase())) {
                        rep.append(prevPunct.filter { it in ".?!" })
                        // or the end the ASR wrote after the command ("on my way close quote. Then")
                        if (nextWord != null && !rep.any { it in ".?!" }) {
                            rep.append(TranscriptGuard.punct(words.gaps[e]).filter { it in ".?!" }.take(1))
                        }
                    }
                    // a closing quote at the very end takes the final mark inside ("doing."")
                    if (nextWord == null && cmd.symbol in CLOSERS && !rep.any { it in ".?!" }) {
                        rep.append(TranscriptGuard.punct(words.gaps[e]).filter { it in ".?!" }.take(1))
                    }
                    rep.append(cmd.symbol)
                    if (nextWord != null) {
                        rep.append(gapAfter)
                        val nw = nextWord!!
                        nextWord = if (cmd.endsSentence) {
                            if (nw[0].isLowerCase()) capitalizeFirst(nw) else nw
                        } else if (continues) {
                            nw
                        } else if (words.sentenceStart(e) && !prevPunct.any { it in ".?!" }) {
                            nw
                        } else lowercased(nw)
                    }
                }
                CommandSpacing.BEFORE_WORD -> {
                    // the ASR's sentence end before it is kept when the next word starts a new
                    // sentence ("Call me. (Maybe"), not when it only marked the pause ("Follow #travel")
                    if (nextWord != null && nextWord[0].isUpperCase()) {
                        // before a quote only a question or exclamation mark, because a period there marks the pause
                        rep.append(prevPunct.filter { it in (if (cmd.action == VoiceCommandAction.OPEN_QUOTE) "?!" else ".?!") })
                    }
                    if (k > 0) rep.append(' ')
                    rep.append(cmd.symbol)
                    // quoted speech starts with a capital letter
                    if (cmd.action == VoiceCommandAction.OPEN_QUOTE && nextWord != null && nextWord[0].isLowerCase()) {
                        nextWord = capitalizeFirst(nextWord)
                    }
                }
                CommandSpacing.JOIN -> {
                    rep.append(cmd.symbol)
                    if (nextWord != null) nextWord = lowercased(nextWord)
                }
                CommandSpacing.SPACED -> {
                    if (k > 0) rep.append(' ')
                    rep.append(cmd.symbol)
                    if (nextWord != null) {
                        rep.append(gapAfter)
                        nextWord = lowercased(nextWord)
                    }
                }
                else -> {}
            }
            val end: Int
            if (nextWord != null) {
                rep.append(nextWord)
                end = words.ends[e]
            } else {
                end = b
            }
            // a phrase of two or more words said on its own, between pauses, is meant as a command
            val certain = cmd.words.size > 1 && before && after
            out.add(Group(Kind.COMMAND, listOf(SpanEdit(a, end, rep.toString(), Kind.COMMAND, cmd.words.joinToString(" "))), cmd.words.joinToString(" "), certain))
            k = e
        }
        return out
    }

    /**
     * "... the red. Delete last word. Blue car." -> remove the word before the command and the
     * command. The command must follow a pause.
     */
    private fun deleteWordGroup(words: Words, k: Int, e: Int, segmentStarts: Set<Int>): Group? {
        if (k == 0) return null
        if (!(words.sentenceStart(k) || k in segmentStarts || words.punctBefore(k).isNotEmpty())) return null
        val afterOk = e == words.size || words.sentenceStart(e) || e in segmentStarts || words.punctBefore(e).isNotEmpty()
        if (!afterOk) return null
        val w = k - 1
        val start = if (e == words.size && w > 0) words.ends[w - 1] else words.starts[w]
        val end = if (e < words.size) words.starts[e] else words.text.length
        return Group(Kind.DELETE_WORD, listOf(SpanEdit(start, end, "", Kind.DELETE_WORD, words.norm.subList(k, e).joinToString(" "))), "delete word", true)
    }

    private val CONTENT_STOP = TranscriptGuard.FUNCTION_LOCK + setOf("and", "or", "but", "so", "to", "for", "with", "my", "your", "i'll", "i'm", "it's")

    /** "... at five. Scratch that. I'll be there at six." -> remove the previous sentence (or utterance) and the command. */
    private fun scratchGroup(words: Words, k: Int, e: Int, segmentStarts: Set<Int>): Group? {
        if (k == 0) return null
        // the command stands on its own, with a pause/sentence boundary before it, and after it a
        // sentence end, a comma, a pause or the text end
        if (!(words.sentenceStart(k) || k in segmentStarts)) return null
        val afterOk = e == words.size || words.sentenceStart(e) || e in segmentStarts || words.punctBefore(e).contains(',')
        if (!afterOk) return null
        // what is scratched goes back to the start of the previous sentence or utterance, whichever is closer
        var s = k - 1
        while (s > 0 && !words.sentenceStart(s) && s !in segmentStarts) s--
        if (s >= k || (s == 0 && e == words.size)) return null
        // "delete that" is also an ordinary request ("I sent you a photo. Delete that."), so only
        // when the user then says the sentence again (a content word in common)
        // ("delete last sentence" is explicit enough on its own)
        if (words.norm.subList(k, e) == listOf("delete", "that")) {
            val gone = words.norm.subList(s, k).filter { it !in CONTENT_STOP }.toSet()
            var f = e
            val again = ArrayList<String>()
            while (f < words.size && (f == e || !words.sentenceStart(f))) again.add(words.norm[f++])
            if (gone.intersect(again.toSet()).isEmpty()) return null
        }
        val end = if (e < words.size) words.starts[e] else words.text.length
        val start = words.starts[s]
        // at the text end nothing follows, so drop the trailing gap too
        val a = if (e == words.size && s > 0) {
            // keep the previous sentence's final punctuation, drop the space after it
            val ws = words.gaps[s].indexOfFirst { it.isWhitespace() }
            words.ends[s - 1] + (if (ws < 0) words.gaps[s].length else ws)
        } else start
        return Group(Kind.SCRATCH, listOf(SpanEdit(a, end, "", Kind.SCRATCH, words.norm.subList(k, e).joinToString(" "))), "scratch")
    }

    // ------------------------------------------------------------------------------------
    // Quoted speech

    private val PERSON_SUBJECTS = setOf("i", "he", "she", "they", "we", "you", "mom", "dad")

    /** People who can be quoted ("my boss was like ...", "the kids said ..."). */
    private val PERSON_NOUNS = setOf(
        "boss", "friend", "friends", "mom", "dad", "mum", "mother", "father", "parents", "brother", "sister", "wife",
        "husband", "kid", "kids", "son", "daughter", "teacher", "guy", "guys", "girl", "girls", "man", "woman", "lady",
        "coworker", "manager", "roommate", "neighbor", "neighbour", "grandma", "grandpa", "aunt", "uncle", "cousin",
        "girlfriend", "boyfriend", "partner", "doctor", "nurse", "everyone", "everybody", "someone", "somebody",
        "baby", "babe", "honey", "client", "customer", "waiter", "waitress", "driver", "officer", "cop",
    )
    private val CONTRACTED_BE = setOf("i'm", "he's", "she's", "they're", "we're", "you're")

    /** The built-in trigger phrases ([QuoteTriggers.DEFAULTS]) as words. */
    val DEFAULT_QUOTE_TRIGGERS: List<List<String>> = QuoteTriggers.triggers(QuoteTriggers.DEFAULTS)

    /** Interjections that only start quoted speech, never a report ("she said, wow, ..."). */
    private val QUOTE_STARTERS = setOf("oh", "wow", "whoa", "ugh", "omg")

    /**
     * Triggers after which whatever follows is what was said ("I was like ...", "she goes ...").
     * After the others ("said", "went", "told him") a filler is only a hesitation ("I went, um,
     * to the doctor", "she said, um, that she'd be late").
     */
    private val QUOTATIVE_TRIGGERS = setOf(
        listOf("was", "like"), listOf("were", "like"), listOf("is", "like"), listOf("are", "like"), listOf("am", "like"),
        listOf("like"), listOf("goes"), listOf("was", "all"), listOf("were", "all"), listOf("is", "all"),
        listOf("goes", "like"),
    )

    /**
     * Words that start what someone did or what something was, not what they said ("he yelled at
     * me!", "she said that?", "they were all there!"), so not quoted unless it is a question.
     */
    private val NOT_SPEECH_STARTS = setOf(
        "at", "to", "for", "about", "with", "from", "into", "onto", "on", "in", "of", "by", "over", "back", "off",
        "up", "down", "out", "around", "through", "that", "it", "this", "these", "those", "something", "nothing",
        "anything", "everything", "there", "here",
    )

    /**
     * Words that start quoted speech only before a pause ("she said, no, I can't"), because they are often
     * just what was said ("said no to the offer", "said sorry to the neighbors", "said hi to everyone").
     */
    private val QUOTE_STARTERS_BEFORE_PAUSE = setOf(
        "hey", "dude", "okay", "ok", "yeah", "yep", "nope", "no", "yes", "wait", "seriously", "really", "hi",
        "hello", "sorry", "thanks", "please", "stop", "bro", "man", "well", "look", "listen",
    )
    private val WH_WORDS = setOf("what", "why", "how", "who", "where", "when", "which")
    private val AUX_VERBS = setOf(
        "are", "is", "am", "was", "were", "do", "does", "did", "can", "could", "will", "would", "should", "have", "has",
    )
    private val PRONOUNS = setOf("you", "we", "they", "i", "he", "she", "it")

    /**
     * "and I was like um what are you doing" -> `and I was like "Um, what are you doing?"`. After a
     * person and "was like" / "said" / "asked" / "goes", the rest of the sentence is put in quotes
     * when it can only be quoted speech, meaning it starts with a filler or interjection ("um", "hey",
     * "wow"), is a question ("what are you doing"), or the voice model ended it with ? or !.
     */
    /** Words of an interjection said on its own before quoted speech ("um.", "oh my god!", "oh no."). */
    private val LEAD_WORDS = FILLERS + setOf("oh", "wow", "whoa", "ugh", "omg", "my", "god", "gosh", "goodness", "no")

    /** [isWord] tells whether a word is a common lowercase word (lowercased after a pause that becomes a comma inside the quote). */
    fun quoteGroups(
        words: Words, triggers: List<List<String>> = DEFAULT_QUOTE_TRIGGERS, isWord: ((String) -> Boolean)? = null,
    ): List<Group> {
        val out = ArrayList<Group>()
        val t = words.text
        val person: (Int) -> Boolean = { j ->
            j >= 0 && (words.norm[j] in PERSON_SUBJECTS || words.norm[j] in PERSON_NOUNS ||
                (words.words[j][0].isUpperCase() && !words.sentenceStart(j) && !TranscriptGuard.isPronounI(words.words[j])))
        }
        var start = 1
        while (start < words.size - 1) {
            val trig = triggers.firstOrNull { tw ->
                start + tw.size < words.size && words.norm.subList(start, start + tw.size) == tw &&
                    (1 until tw.size).none { words.sentenceStart(start + it) }
            }
            // a phrase counts after a person ("she said", "I was like"), and "like" alone after "I'm", "she's", ...
            val trigOk = trig != null && (if (trig == listOf("like")) words.norm[start - 1] in CONTRACTED_BE else person(start - 1))
            if (!trigOk) {
                start++
                continue
            }
            val i = start + trig!!.size - 1
            start = i + 1
            val q = i + 1
            // the quoted words go up to the end of the sentence, 1 to 20 words
            var c = q + 1
            while (c < words.size && !words.sentenceStart(c)) c++
            // the voice model ends a sentence at every pause, so an interjection said on its own ("I was
            // like, um. What are you doing?", "she goes, oh no. Not again.") opens the next sentence
            val quotative = trig in QUOTATIVE_TRIGGERS
            var joinAt = -1
            if (c < words.size && c - q <= 3 && '\n' !in words.gaps[c]) {
                val lead = words.norm.subList(q, c)
                val opener = lead.any { it in SpokenEdits.FILLERS || it in QUOTE_STARTERS }
                val ok = lead.all { it in LEAD_WORDS || (quotative && it in QUOTE_STARTERS_BEFORE_PAUSE) } && (quotative || opener)
                if (ok) {
                    joinAt = c
                    c++
                    while (c < words.size && !words.sentenceStart(c)) c++
                }
            }
            if (c - q > 20) continue
            // already quoted (a spoken "open quote")
            val endPos = if (c < words.size) words.starts[c] else t.length
            if (t.substring(words.ends[i], endPos).any { it == '"' || it == '“' }) continue
            val finalGap = if (c < words.size) words.gaps[c] else words.gaps[words.size]
            val asrEnd = TranscriptGuard.punct(finalGap).firstOrNull { it in ".?!" }
            // skip a leading filler to look at the speech itself
            val first = words.norm[q]
            val fillerStart = first in FillerWords.SOUNDS
            val body = if (fillerStart && q + 1 < c) q + 1 else q
            val bodyFirst = words.norm[body]
            // (a filler right after "was like" / "goes" opens quoted speech, but after "said", "went", ... it is a hesitation)
            // "what are ...", "why did ...", or "can you ...", "are we ..."
            val isQuestion = c - body >= 2 && (
                (words.norm[body] in WH_WORDS && words.norm[body + 1] in AUX_VERBS) ||
                    (words.norm[body] in AUX_VERBS && words.norm[body + 1] in PRONOUNS))
            val pauseAfterFirst = body + 1 < c && (TranscriptGuard.punct(words.gaps[body + 1]).any { it in ",;:—–-" } || body + 1 == joinAt)
            val opensSpeech = (fillerStart && quotative && q + 1 < c) || bodyFirst in QUOTE_STARTERS
            // after "she was all", "he answered", ... a ? or ! alone doesn't make it speech
            val speechOnly = trig in QuoteTriggers.SPEECH_ONLY
            val marked = (asrEnd == '?' || asrEnd == '!') && !speechOnly && bodyFirst !in NOT_SPEECH_STARTS
            val quoted = opensSpeech || isQuestion || marked ||
                (bodyFirst in QUOTE_STARTERS_BEFORE_PAUSE && pauseAfterFirst)
            if (!quoted) continue
            val mark = when {
                isQuestion -> '?'
                asrEnd != null -> asrEnd
                else -> '.'
            }
            // the replacement runs from the end of "like"/"said" to the end of the sentence's own mark
            val markLen = finalGap.indexOfFirst { it.isWhitespace() }.let { if (it < 0) finalGap.length else it }
            val end = (if (c < words.size) words.ends[c - 1] else words.ends[words.size - 1]) + markLen
            val sb = StringBuilder(" \"")
            for (k in q until c) {
                var w = words.words[k]
                if (k == q) w = capitalizeFirst(w)
                if (k > q) {
                    var gap = words.gaps[k]
                    // a comma after a leading filler ("Um, what are you doing?")
                    if (k == q + 1 && first in FillerWords.SOUNDS && TranscriptGuard.punct(gap).isEmpty()) gap = ", "
                    // the pause after the interjection ("um. What") is a comma inside the quote
                    // ("hey! Can you" keeps its mark)
                    if (k == joinAt && TranscriptGuard.punct(gap).none { it in "?!" }) {
                        gap = ", "
                        if (isWord != null && !TranscriptGuard.isPronounI(w) && w[0].isUpperCase() && isWord(w)) {
                            w = w.substring(0, 1).lowercase() + w.substring(1)
                        }
                    }
                    sb.append(gap)
                }
                sb.append(w)
            }
            sb.append(mark).append('"')
            // a quote that opens with a filler or interjection ("I was like, um, ...") can only be
            // quoted speech, applied without the LM's vote (quotation marks look unlikely to it).
            // So can a question or "no," / "hey," said after a pause ("she goes, what are you
            // doing?", "I said, no, I can't")
            val pauseAfterTrigger = TranscriptGuard.punct(words.gaps[q]).any { it in ",:—–-" }
            val certain = (opensSpeech && body + 1 < c) ||
                (pauseAfterTrigger && (isQuestion || (bodyFirst in QUOTE_STARTERS_BEFORE_PAUSE && pauseAfterFirst)))
            out.add(Group(Kind.QUOTE, listOf(SpanEdit(words.ends[i], end, sb.toString(), Kind.QUOTE, "quote")), "quote", certain))
        }
        return out
    }

    // ------------------------------------------------------------------------------------
    // Emoji by name

    /** Before the name, these make it talk about an emoji instead of inserting one ("the laughing emoji is ..."). */
    private val EMOJI_NOUN_BEFORE = setOf(
        "a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "our", "their", "its",
        "which", "what", "any", "some", "every", "each", "no", "favorite", "favourite", "same", "new", "other",
    )

    /** After "emoji", these make it a noun ("emoji is", "emoji for", "emoji keyboard"). */
    private val EMOJI_NOUN_AFTER = setOf(
        "is", "was", "are", "were", "means", "meant", "for", "of", "looks", "has", "had", "keyboard", "picker",
        "reaction", "reactions", "button", "and", "or", "too", "instead", "that", "which", "you", "i",
    )

    /** Names that are also verbs, and the words before them that make them one ("I love emoji"). */
    private val EMOJI_VERB_NAMES = setOf("love", "hug", "kiss")
    private val EMOJI_VERB_SUBJECTS = setOf(
        "i", "we", "they", "he", "she", "people", "kids", "everyone", "everybody", "who", "i'd", "we'd",
        "you'd", "they'd", "i'll", "we'll", "you'll", "do", "does", "did", "don't", "doesn't", "didn't", "can", "can't",
        "could", "would", "will", "won't", "should", "to", "just", "really", "also", "still", "totally", "never",
        "always", "not", "so",
    )
    /** "you" is the subject at the start or after these ("do you love emoji"), else the object ("miss you love emoji"). */
    private val EMOJI_YOU_SUBJECT_AFTER = setOf("do", "did", "don't", "didn't", "can", "can't", "could", "would", "will", "should", "if", "and", "because", "that")

    /** Whether the word at [i] is the subject of a verb that follows it. */
    private fun isSubject(words: Words, i: Int): Boolean = when (words.norm[i]) {
        in EMOJI_VERB_SUBJECTS -> true
        "you" -> words.sentenceStart(i) || i == 0 || words.norm[i - 1] in EMOJI_YOU_SUBJECT_AFTER
        else -> false
    }

    /**
     * "That's hilarious laughing emoji" -> "That's hilarious 😂". The name said right before
     * "emoji" (the longest one in [names], 1 to [EmojiNames.MAX_WORDS] words) becomes the emoji.
     * Not when the words talk about an emoji ("the laughing emoji is my favorite", "send a heart emoji").
     */
    fun emojiGroups(words: Words, names: Map<List<String>, String>): List<Group> {
        val out = ArrayList<Group>()
        if (names.isEmpty()) return out
        for (k in 1 until words.size) {
            val kw = EmojiNames.keywordLength(words.norm, k)
            if (kw == 0) continue
            val nameEnd = k - kw + 1  // the first word of "emoji" ("at Mojie" is two)
            if (kw == 2 && words.sentenceStart(k)) continue
            // the name is the longest known name ending right before "emoji", within one sentence
            var start = -1
            var emoji: String? = null
            val emojiAlone = words.sentenceEndBefore(k + 1) || ',' in words.punctBefore(k + 1)
            for (n in minOf(EmojiNames.MAX_WORDS, nameEnd) downTo 1) {
                val s = nameEnd - n
                // the punctuation model may also split the name ("Crying? Laughing emoji?"), so a
                // sentence break inside it is allowed, and the longest name still wins
                // the punctuation model may make "emoji" a sentence of its own ("Mind blown. Emoji.")
                if (words.sentenceStart(nameEnd) && !emojiAlone) continue
                // names are stored without hyphens, so "mind-blown" matches "mind blown"
                val e = names[words.norm.subList(s, nameEnd).flatMap { VoiceCommands.phraseWords(it) }] ?: continue
                // a name that gives no emoji (it fits several, or is switched off) gives none, not a shorter name's
                if (e.isEmpty()) break
                start = s
                emoji = e
                break
            }
            if (emoji == null) continue
            if (nameEnd - start == 1 && words.norm[start] in EMOJI_VERB_NAMES && start > 0 && words.gaps[start].isBlank() &&
                isSubject(words, start - 1)) continue
            val prev = if (start > 0 && !words.sentenceStart(start)) words.norm[start - 1] else null
            val next = if (k + 1 < words.size && !words.sentenceStart(k + 1)) words.norm[k + 1] else null
            if (prev != null && prev in EMOJI_NOUN_BEFORE) continue
            if (next != null && next in EMOJI_NOUN_AFTER) continue
            var a = words.starts[start]
            var b = words.ends[k]
            var replacement: String = emoji
            // an emoji that ends the text or is a sentence of its own takes no full stop or question
            // mark ("Now? Laughing emoji?" -> "Now? 😂") because they are the voice model's, not said. A question
            // the emoji ends keeps its question mark before it ("Coming tonight? 👍").
            if (words.sentenceEndBefore(k + 1)) {
                val marks = words.gaps[k + 1].takeWhile { it == '.' || it == '?' }
                val question = '?' in marks && !words.sentenceStart(start) && words.gaps[start].isNotEmpty() && words.gaps[start].isBlank()
                if (question || k + 1 == words.size || words.sentenceStart(start)) b += marks.length
                if (question) {
                    a = words.ends[start - 1]
                    replacement = "? $emoji"
                }
            }
            out.add(Group(Kind.EMOJI, listOf(SpanEdit(a, b, replacement, Kind.EMOJI, "emoji")), "emoji", certain = true))
        }
        return out
    }

    // ------------------------------------------------------------------------------------
    // Numbers

    fun numberGroups(words: Words): List<Group> {
        val out = ArrayList<Group>()
        for (c in NumberWords.candidates(words.words, words.gaps) { words.sentenceStart(it) }) {
            val a = words.starts[c.start]
            var b = words.ends[c.end - 1]
            // "p.m." mid-sentence keeps its final dot because it is part of the abbreviation
            if (words.norm[c.end - 1] == "m" && c.end < words.size && words.gaps[c.end].startsWith(".") &&
                words.words[c.end][0].isLowerCase()) b += 1
            val alts = c.forms.map { SpanEdit(a, b, it, Kind.NUMBER, words.text.substring(a, b)) }
            // an amount said with its unit ("six hundred dollars", "fifty percent") is always
            // written with digits, even where the LM prefers words (at a sentence start)
            out.add(Group(Kind.NUMBER, alts, "number", certain = c.forms.all { NumberWords.isAmount(it) }))
        }
        // digits the voice model wrote, the unit as a word ("800 dollars" -> "$800")
        for ((a, b, form) in NumberWords.digitAmounts(words.text)) {
            out.add(Group(Kind.NUMBER, listOf(SpanEdit(a, b, form, Kind.NUMBER, words.text.substring(a, b))), "number", certain = true))
        }
        return out
    }

    // ------------------------------------------------------------------------------------
    // ASR segments

    /**
     * Word indices of [text] where an ASR segment (utterance between pauses) starts, except the
     * first word. The words of [segments] are aligned with the text's (the punctuation stage may
     * have changed case and punctuation, never the words themselves).
     */
    fun segmentStarts(text: String, segments: List<String>): Set<Int> {
        if (segments.size < 2) return emptySet()
        val tw = TranscriptGuard.tokenize(text).words.map { TranscriptGuard.normWord(it) }
        val sw = ArrayList<String>()
        val segFirst = ArrayList<Int>()
        for (s in segments) {
            val ws = TranscriptGuard.tokenize(s).words.map { TranscriptGuard.normWord(it) }
            if (ws.isEmpty()) continue
            segFirst.add(sw.size)
            sw.addAll(ws)
        }
        val map = IntArray(sw.size) { -1 }
        for (bl in TranscriptGuard.align(sw, tw)) {
            if (bl.op == "equal") map[bl.i1] = bl.j1
        }
        val out = HashSet<Int>()
        for (g in segFirst.drop(1)) {
            // the first aligned word at or after the segment start
            var i = g
            while (i < sw.size && map[i] < 0) i++
            if (i < sw.size && (i == g || i - g <= 1)) out.add(map[i])
        }
        out.remove(0)
        return out
    }

    /**
     * Indices k of the text's words where the ASR itself put no break before word k, meaning words k-1
     * and k are in the same segment with no sentence mark between them. A sentence break the text
     * has there was made by the punctuation stage. Words that cannot be aligned are left out.
     */
    fun addedBreaks(text: String, segments: List<String>): Set<Int> {
        val tw = TranscriptGuard.tokenize(text).words.map { TranscriptGuard.normWord(it) }
        val sw = ArrayList<String>()
        val segOf = ArrayList<Int>()
        val markAfter = ArrayList<Boolean>()
        for ((si, s) in segments.withIndex()) {
            val t = TranscriptGuard.tokenize(s)
            for ((k, w) in t.words.withIndex()) {
                sw.add(TranscriptGuard.normWord(w))
                segOf.add(si)
                markAfter.add(k + 1 < t.words.size && (TranscriptGuard.punct(t.gaps[k + 1]).any { it in ".?!" } || '\n' in t.gaps[k + 1]))
            }
        }
        val toOrig = IntArray(tw.size) { -1 }
        for (bl in TranscriptGuard.align(sw, tw)) {
            if (bl.op == "equal") for (n in 0 until bl.i2 - bl.i1) toOrig[bl.j1 + n] = bl.i1 + n
        }
        val out = HashSet<Int>()
        for (k in 1 until tw.size) {
            val a = toOrig[k - 1]
            val b = toOrig[k]
            if (a >= 0 && b == a + 1 && segOf[a] == segOf[b] && !markAfter[a]) out.add(k)
        }
        return out
    }
}
