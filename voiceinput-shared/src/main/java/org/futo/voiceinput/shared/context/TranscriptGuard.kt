package org.futo.voiceinput.shared.context

/**
 * Deterministic guard for the context stage, ported 1:1 from tools/context-bench/guard.py.
 *
 * Diffs a proposed text against the input at word level and only lets through these changes.
 *  * punctuation changes between words (from a small allowed set, and only the type of an
 *    existing mark may change, plus a final mark at the very end),
 *  * first-letter capitalization changes,
 *  * word substitutions where the two words sound alike (Double Metaphone),
 *  * merges/splits of up to 2+2 words whose joined spelling sounds the same.
 * Everything else (insertions, deletions, rewording) is rejected and the input words are kept.
 *
 * With a [Policy] (what the context stage passes, built from the enabled options) merges and
 * splits are only allowed where the lexicon lists them, names only where the personal
 * dictionary has them, and case changes away from sentence starts only with the
 * capitalization option. The edits of the other options (fillers, self-corrections, numbers,
 * spoken commands) are explicit span edits, each checked by [checkEdit].
 */
object TranscriptGuard {
    private const val ALLOWED_PUNCT = ".,?!;:"
    private const val SENTENCE_END = ".?!"

    data class Block(val op: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    data class Decision(val op: String, val src: String, val dst: String, val accepted: Boolean, val reason: String)

    class Result(val text: String, val decisions: List<Decision>) {
        val accepted get() = decisions.filter { it.accepted }
        val rejected get() = decisions.filter { !it.accepted }
    }

    class Tokens(val words: List<String>, val gaps: List<String>)

    // ------------------------------------------------------------------------------------
    // Tokenizing (text = gap[0] word[0] gap[1] word[1] ... word[n-1] gap[n])

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    fun tokenize(text: String): Tokens {
        val words = ArrayList<String>()
        val gaps = ArrayList<String>()
        var i = 0
        val n = text.length
        var gapStart = 0
        while (i < n) {
            if (isWordChar(text[i])) {
                gaps.add(text.substring(gapStart, i))
                var j = i
                while (j < n) {
                    if (isWordChar(text[j])) {
                        j++
                    } else if (text[j] in "'’-" && j + 1 < n && isWordChar(text[j + 1]) && j > i) {
                        j++
                    } else {
                        break
                    }
                }
                words.add(text.substring(i, j))
                i = j
                gapStart = j
            } else {
                i++
            }
        }
        gaps.add(text.substring(gapStart))
        return Tokens(words, gaps)
    }

    fun normWord(w: String): String = w.lowercase().replace('’', '\'')

    // ------------------------------------------------------------------------------------
    // Alignment (LCS over normalized words -> blocks)

    fun align(a: List<String>, b: List<String>): List<Block> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val blocks = ArrayList<Block>()
        var i = 0
        var j = 0
        var pi = 0
        var pj = 0
        fun flush(iEnd: Int, jEnd: Int) {
            if (pi == iEnd && pj == jEnd) return
            val op = when {
                pi == iEnd -> "insert"
                pj == jEnd -> "delete"
                else -> "replace"
            }
            blocks.add(Block(op, pi, iEnd, pj, jEnd))
        }
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                flush(i, j)
                blocks.add(Block("equal", i, i + 1, j, j + 1))
                i++
                j++
                pi = i
                pj = j
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        flush(n, m)
        return blocks
    }

    // ------------------------------------------------------------------------------------
    // Sound-alike test

    private val NEGATIONS = setOf("not", "no", "never", "cannot", "nor")

    private fun isNegation(w: String) = w.endsWith("n't") || w in NEGATIONS

    /**
     * Swapping two of these changes tense/person/modality, never a mishearing worth fixing
     * ("is" -> "was", "can" -> "could", "he" -> "she").
     */
    val FUNCTION_LOCK = setOf(
        "is", "was", "are", "were", "am", "be", "been", "being", "has", "have", "had", "do", "does",
        "did", "will", "would", "can", "could", "shall", "should", "may", "might", "must",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "a", "an", "the", "this", "that", "these", "those", "as", "at", "in", "on", "of",
    )
    private val SUFFIXES = listOf("s", "es", "'s", "s'", "ed", "d", "ing", "ly", "er", "est")

    /** b is a plus an inflectional suffix ("bagel" -> "bagels"). */
    fun isInflection(a: String, b: String): Boolean =
        SUFFIXES.any { b == a + it } || (b.endsWith("ies") && a.endsWith("y") && b.dropLast(3) == a.dropLast(1))

    /**
     * Forms of irregular verbs, one verb per line. Swapping two forms of one verb ("took" ->
     * "take", "get" -> "got") changes the tense, never a mishearing worth fixing.
     */
    private val VERB_FORMS: Map<String, String> = """
        get got gotten|take took taken|come came|run ran|sit sat|win won|begin began begun|sing sang sung
        ring rang rung|drink drank drunk|swim swam swum|give gave given|see saw seen|know knew known
        grow grew grown|throw threw thrown|blow blew blown|fly flew flown|draw drew drawn|find found
        make made|say said|tell told|sell sold|hold held|feel felt|keep kept|leave left|meet met|feed fed
        speak spoke spoken|break broke broken|wake woke woken|write wrote written|ride rode ridden
        drive drove driven|rise rose risen|choose chose chosen|freeze froze frozen|steal stole stolen
        wear wore worn|tear tore torn|swear swore sworn|shake shook shaken|stand stood|bite bit bitten
        hide hid hidden|slide slid|fight fought|buy bought|think thought|bring brought|catch caught
        teach taught|dig dug|stick stuck|strike struck|hang hung|spin spun|shoot shot|lose lost|mean meant
        send sent|spend spent|build built|bend bent|lend lent|sleep slept|sweep swept|weep wept|creep crept
        deal dealt|dream dreamt|kneel knelt|have had|do did done|go went gone|eat ate eaten|fall fell fallen
        forget forgot forgotten|hear heard|lay laid|pay paid|become became|forgive forgave|beat beaten
    """.trimIndent().split('\n', '|').flatMap { line ->
        val forms = line.trim().split(' ').filter { it.isNotEmpty() }
        forms.map { it to forms[0] }
    }.toMap()

    /** [w] is a form of an irregular verb ("paid", "took", "went"). */
    fun isVerbForm(w: String): Boolean = normWord(w) in VERB_FORMS

    /** [a] and [b] are two forms of one irregular verb ("took", "take"). */
    fun isTenseChange(a: String, b: String): Boolean {
        val la = VERB_FORMS[normWord(a)] ?: return false
        return la == VERB_FORMS[normWord(b)] && normWord(a) != normWord(b)
    }

    /** Double Metaphone codes of [w] as the guard compares them (for [PersonalNames]). */
    fun soundCodes(w: String): List<String> = codes(w)

    private fun codes(w: String): List<String> {
        val (p, s) = DoubleMetaphone.encode(w.replace("'", ""))
        // treat a leading aspirate like a leading vowel ("hour" HR ~ "our" AR)
        return listOf(p, s).filter { it.isNotEmpty() }.map { if (it.startsWith("H")) "A" + it.substring(1) else it }
    }

    fun soundsAlike(a0: String, b0: String): Pair<Boolean, String> {
        val a = normWord(a0)
        val b = normWord(b0)
        if (a == b) return true to "same"
        if ((a + b).any { it.isDigit() }) return false to "digits"
        if (isNegation(a) != isNegation(b)) return false to "negation"
        if (a in FUNCTION_LOCK && b in FUNCTION_LOCK) return false to "function-word swap"
        if (isInflection(a, b) || isInflection(b, a)) return false to "inflection"
        if (isTenseChange(a, b)) return false to "tense change"
        val ca = codes(a)
        val cb = codes(b)
        if (ca.isEmpty() || cb.isEmpty()) return false to "no-code"
        for (x in ca) {
            for (y in cb) {
                if (x == y) {
                    // one-letter codes are too weak when one side is a 1-letter word ("a" vs "I")
                    if (x.length == 1 && minOf(a.length, b.length) <= 1) return false to "too-short"
                    return true to "metaphone $x"
                }
            }
        }
        return false to "codes ${ca.joinToString("/")} vs ${cb.joinToString("/")}"
    }

    fun joinedSoundsAlike(aWords: List<String>, bWords: List<String>): Pair<Boolean, String> {
        val a = aWords.joinToString("") { normWord(it) }
        val b = bWords.joinToString("") { normWord(it) }
        if (a.count { it == '\'' } != b.count { it == '\'' }) return false to "contraction change"
        if (a.replace("'", "") == b.replace("'", "")) return true to "respaced"
        val (ok, why) = soundsAlike(a, b)
        if (ok && why.startsWith("metaphone")) return true to "joined $why"
        return false to "joined $why"
    }

    // ------------------------------------------------------------------------------------
    // Gaps (punctuation between words)

    fun punct(g: String): String = g.filter { !it.isWhitespace() }

    /** Returns (gap, changed). */
    fun chooseGap(inGap: String, outGap: String, isLast: Boolean): Pair<String, Boolean> {
        val pin = punct(inGap)
        var pout = punct(outGap)
        if ('?' in pout || '!' in pout) pout = pout.filter { it !in ".,;:" }  // "right?." -> "right?"
        if (pin == pout) return inGap to false
        if (!pin.all { it in ALLOWED_PUNCT } || !pout.all { it in ALLOWED_PUNCT }) return inGap to false
        if (pout.length > 2) return inGap to false
        // Only change the TYPE of an existing mark (the end of the text may gain a final mark).
        if (pout.isEmpty() || (pin.isEmpty() && !isLast)) return inGap to false
        // never drop a question/exclamation mark the ASR produced
        for (c in "?!") {
            if (c in pin && c !in pout) return inGap to false
        }
        // keep line breaks (spoken "new line" commands) and otherwise use one space
        val ws = when {
            isLast -> ""
            '\n' in inGap -> inGap.filter { it.isWhitespace() }
            inGap.any { it.isWhitespace() } || inGap.isEmpty() -> " "
            else -> ""
        }
        return (pout + ws) to true
    }

    private fun caseOk(inW: String, outW: String) = inW.drop(1) == outW.drop(1)

    // ------------------------------------------------------------------------------------

    private class Entry(val wIn: String, val wOut: String, val gIn: String, val gOut: String, val useOut: Boolean)

    /**
     * What the word-level guard allows besides sound-alike substitutions and punctuation types.
     * [LEGACY] (no policy) is the original guard.py behaviour.
     */
    class Policy(
        /** Merges/splits ("in to" -> "into") are allowed only if this lists them (null means the legacy joined-spelling test). */
        val boundary: ((src: List<String>, dst: List<String>) -> Boolean)? = null,
        /** Personal-dictionary names that sound like the replaced words may be substituted. */
        val names: PersonalNames? = null,
        /** Case-only changes of words that don't start a sentence ("Toaster" -> "toaster", "jordan" -> "Jordan"). */
        val caseChanges: Boolean = false,
        /** If false, case changes away from sentence starts are not checked (legacy behaviour). */
        val strictCase: Boolean = false,
    ) {
        companion object {
            val LEGACY = Policy()
        }
    }

    /** The case of "I", "I'm", "I'll", ... is never changed. */
    fun isPronounI(w: String): Boolean {
        val n = normWord(w)
        return n == "i" || n.startsWith("i'")
    }

    fun guard(inp: String, out0: String): Result = guard(inp, out0, Policy.LEGACY)

    fun guard(inp: String, out0: String, policy: Policy): Result {
        val out = out0.trim { it.isWhitespace() }
        val ti = tokenize(inp)
        val to = tokenize(out)
        val iw = ti.words
        val ig = ti.gaps
        val ow = to.words
        val og = to.gaps
        val decisions = ArrayList<Decision>()
        if (ow.isEmpty()) {
            decisions.add(Decision("empty", inp, out, false, "empty output"))
            return Result(inp, decisions)
        }
        val blocks = align(iw.map { normWord(it) }, ow.map { normWord(it) })

        val words = ArrayList<Entry>()
        val gapsAfter = ArrayList<String>()
        val nIn = iw.size

        fun takeGap(iEnd: Int, jEnd: Int): String {
            val isLast = iEnd == nIn
            val o = if (jEnd < og.size) og[jEnd] else ""
            val (g, changed) = chooseGap(ig[iEnd], o, isLast)
            if (changed) {
                decisions.add(Decision("punct", punct(ig[iEnd]).ifEmpty { "_" }, punct(g).ifEmpty { "_" }, true, "punct"))
            } else if (punct(ig[iEnd]) != punct(o)) {
                decisions.add(Decision("punct", punct(ig[iEnd]).ifEmpty { "_" }, punct(o).ifEmpty { "_" }, false, "punct not allowed"))
            }
            return g
        }

        fun keepInput(i1: Int, i2: Int) {
            for (i in i1 until i2) {
                words.add(Entry(iw[i], iw[i], ig[i], ig[i], false))
                gapsAfter.add(ig[i + 1])
            }
        }

        for (b in blocks) {
            when (b.op) {
                "equal" -> {
                    words.add(Entry(iw[b.i1], ow[b.j1], ig[b.i1], og[b.j1], true))
                    gapsAfter.add(takeGap(b.i2, b.j2))
                }
                "replace" -> {
                    val src = iw.subList(b.i1, b.i2)
                    val dst = ow.subList(b.j1, b.j2)
                    val ok: Boolean
                    val why: String
                    val nameOk = policy.names != null && dst.size == 1 && src.size in 1..3 &&
                        policy.names.isSoundAlikeName(src, dst[0])
                    // a personal name only replaces words that sound like it and look like a
                    // misheard name ([PersonalNames.soundAlike]), and a compound ("x-ray") is never misheard
                    val nameDst = policy.names != null && dst.size == 1 && policy.names.name(dst[0]) == dst[0] &&
                        src.none { normWord(it) == normWord(dst[0]) }
                    if (nameOk) {
                        ok = true; why = "personal name"
                    } else if (nameDst) {
                        ok = false; why = "not a misheard name"
                    } else if (src.any { '-' in it } && src.size == 1 && dst.size == 1) {
                        ok = false; why = "compound word"
                    } else if (src.size == 1 && dst.size == 1) {
                        val r = soundsAlike(src[0], dst[0]); ok = r.first; why = r.second
                    } else if (src.size <= 2 && dst.size <= 2 && policy.boundary == null) {
                        val r = joinedSoundsAlike(src, dst); ok = r.first; why = r.second
                    } else if (src.size <= 2 && dst.size <= 2) {
                        // adjacent substitutions ("over their" -> "over there" + ...) or a listed boundary change
                        val pairs = if (src.size == dst.size) src.zip(dst).map { (x, y) -> soundsAlike(x, y) } else emptyList()
                        if (pairs.isNotEmpty() && pairs.all { it.first }) {
                            ok = true; why = pairs.joinToString("; ") { it.second }
                        } else if (policy.boundary?.invoke(src, dst) == true) {
                            ok = true; why = "word boundary"
                        } else {
                            ok = false; why = "boundary not listed"
                        }
                    } else if (src.size == dst.size) {
                        // several adjacent substitutions are accepted only if every pair sounds alike
                        val pairs = src.zip(dst).map { (x, y) -> soundsAlike(x, y) }
                        ok = pairs.all { it.first }
                        why = pairs.joinToString("; ") { it.second }
                    } else {
                        ok = false; why = "rewording"
                    }
                    decisions.add(Decision("subst", src.joinToString(" "), dst.joinToString(" "), ok, why))
                    if (ok) {
                        for ((k, w) in dst.withIndex()) {
                            words.add(Entry(w, w, if (k == 0) ig[b.i1] else " ", og[b.j1 + k], true))
                            if (k < dst.size - 1) gapsAfter.add(" ")
                        }
                        gapsAfter.add(takeGap(b.i2, b.j2))
                    } else {
                        keepInput(b.i1, b.i2)
                    }
                }
                "delete" -> {
                    decisions.add(Decision("delete", iw.subList(b.i1, b.i2).joinToString(" "), "", false, "deletion"))
                    keepInput(b.i1, b.i2)
                }
                "insert" -> {
                    decisions.add(Decision("insert", "", ow.subList(b.j1, b.j2).joinToString(" "), false, "insertion"))
                    // the gap after the previous word was taken from the output, which preceded
                    // the inserted words, so re-take it against the gap after the insert
                    if (words.isNotEmpty()) {
                        gapsAfter[gapsAfter.size - 1] =
                            chooseGap(ig[b.i1], if (b.j2 < og.size) og[b.j2] else "", b.i1 == nIn).first
                    }
                }
            }
        }

        fun sentenceEnd(g: String) = punct(g).any { it in SENTENCE_END }
        fun plain(w: String) = w.drop(1) == w.drop(1).lowercase()  // only the first letter may carry case
        fun firstIsLower(w: String) = w.isNotEmpty() && w[0].isLowerCase()
        fun firstIsUpper(w: String) = w.isNotEmpty() && w[0].isUpperCase()

        // Casing pass. The output's first-letter case is used only where the output's sentence
        // structure around the word matches the final one, and words that now start a sentence get
        // capitalized.
        val sb = StringBuilder(ig[0])
        for ((k, e) in words.withIndex()) {
            val gFinal = if (k > 0) gapsAfter[k - 1] else ig[0]
            val startF = k == 0 || sentenceEnd(gFinal)
            val startI = k == 0 || sentenceEnd(e.gIn)
            val startO = k == 0 || sentenceEnd(e.gOut)
            var w = e.wIn
            // strict policy. A case change of a word that neither starts nor started a sentence
            // is a capitalization edit, allowed only with that option
            val structural = startF || startI || startO
            if (policy.strictCase && e.useOut && e.wOut != e.wIn && !structural) {
                if (policy.caseChanges && e.wOut.equals(e.wIn, ignoreCase = true) && !isPronounI(e.wIn)) {
                    w = e.wOut
                    decisions.add(Decision("case", e.wIn, e.wOut, true, "capitalization"))
                } else {
                    decisions.add(Decision("case", e.wIn, e.wOut, false, "case change not allowed"))
                }
            } else if (e.useOut && e.wOut != e.wIn && caseOk(e.wIn, e.wOut) && !(policy.strictCase && isPronounI(e.wIn))) {
                if (startO == startF) {
                    w = e.wOut
                } else {
                    decisions.add(Decision("case", e.wIn, e.wOut, false, "case follows rejected punct"))
                }
            } else if (e.useOut && e.wOut != e.wIn) {
                decisions.add(Decision("case", e.wIn, e.wOut, false, "inner case change"))
            }
            if (startF && firstIsLower(w) && firstIsUpper(e.wIn)) w = e.wIn  // never lowercase a sentence start
            if (startF && !startI && firstIsLower(w) && plain(w)) w = w.substring(0, 1).uppercase() + w.substring(1)
            sb.append(w).append(gapsAfter[k])
        }
        var text = sb.toString()
        // If the proposal went off the rails, don't trust any of its edits.
        val nBad = decisions.filter { !it.accepted && it.op in setOf("subst", "insert", "delete") }
            .sumOf { splitCount(it.src) + splitCount(it.dst) }
        if (nBad > maxOf(6.0, 0.4 * nIn)) {
            decisions.add(Decision("bail", "", "", false, "$nBad words in rejected edits"))
            text = inp
        }
        return Result(text, decisions)
    }

    /**
     * Checks a sentence-break join ([start, end) of [text] = ". Word" -> " word"). The period goes,
     * the next word keeps its letters and may only lose its first capital (never "I"). The period
     * may also become a comma (", who").
     */
    fun checkBreakJoin(text: String, start: Int, end: Int, replacement: String): Decision {
        val src = text.substring(start, end)
        fun no(why: String) = Decision("breakjoin", src, replacement, false, why)
        if (!Regex("^\\. [^\\s.?!,;:]+$").matches(src)) return no("not a single sentence break")
        val lead = if (replacement.startsWith(", ")) 2 else 1
        if (!replacement.startsWith(" ") && lead == 1 || replacement.length != src.length - 2 + lead) return no("not a plain join")
        val w = src.substring(2)
        val r = replacement.substring(lead)
        if (w == r) return Decision("breakjoin", src, replacement, true, "join, capital kept")
        if (isPronounI(w) || !isLowercased(w, r)) return no("word changed")
        return Decision("breakjoin", src, replacement, true, "join, capital dropped")
    }

    // ------------------------------------------------------------------------------------
    // Span edits of the deleting / restructuring options

    /**
     * Checks one edit of the fillers / self-corrections / commands / numbers options against
     * [text] (the text it applies to). Each kind may only do its own thing, meaning deletions of exactly
     * the words its rule names (leaving only punctuation and spaces behind), digits only as the
     * number parser's written form of the replaced words, line breaks and marks only in place
     * of the spoken command (plus the next word's first-letter case).
     */
    fun checkEdit(
        text: String, e: SpokenEdits.SpanEdit,
        commands: List<VoiceCommand> = VoiceCommands.DEFAULTS,
        markers: List<CorrectionMarker> = SpokenEdits.DEFAULT_MARKERS,
        fillers: List<FillerWords.Filler> = SpokenEdits.DEFAULT_FILLERS,
        emojiNames: Map<List<String>, String> = emptyMap(),
    ): Decision {
        val src = text.substring(e.start, e.end)
        val dst = e.replacement
        val sw = tokenize(src).words
        val dw = tokenize(dst).words
        val sn = sw.map { normWord(it) }
        val op = e.kind.name.lowercase()
        fun no(why: String) = Decision(op, src, dst, false, why)
        fun yes(why: String) = Decision(op, src, dst, true, why)
        // an edit starts and ends at word boundaries
        if (e.start > 0 && e.start < text.length && text[e.start - 1].isLetterOrDigit() && text[e.start].isLetterOrDigit()) return no("splits a word")
        if (e.end in 1 until text.length && text[e.end - 1].isLetterOrDigit() && text[e.end].isLetterOrDigit()) return no("splits a word")
        // a filler or self-correction deletion may also lowercase the word after it, which the voice
        // model capitalized after a pause ("I was. Um. Thinking" -> "I was thinking"). The
        // deletion is checked on its own
        if ((e.kind == SpokenEdits.Kind.FILLER || e.kind == SpokenEdits.Kind.SELF_CORRECTION) && dw.size == 1 && sw.size >= 2) {
            val last = sw.last()
            val at = src.lastIndexOf(last)
            if (at >= 0 && src.substring(at) == last && dst.endsWith(dw[0]) && isLowercased(last, dw[0]) && !isPronounI(last)) {
                val inner = e.copy(end = e.start + at, replacement = dst.substring(0, dst.length - dw[0].length))
                val d = checkEdit(text, inner, commands, markers, fillers)
                return Decision(op, src, dst, d.accepted, d.reason + (if (d.accepted) ", next word lowercased" else ""))
            }
        }
        val onlyPunct = dst.all { it.isWhitespace() || it in ALLOWED_PUNCT }
        fun sentenceEndInside(s: String) = s.any { it in SENTENCE_END || it == '\n' }
        when (e.kind) {
            SpokenEdits.Kind.FILLER -> {
                if (dw.isNotEmpty() || !onlyPunct) return no("filler edit adds words")
                if (sw.isEmpty() || !isFillerSequence(sn, fillers)) return no("not a filler")
                if (sw.any { it.length > 1 && it.all { c -> c.isUpperCase() } }) return no("all caps word")
                return yes("filler")
            }
            SpokenEdits.Kind.REPEAT -> {
                if (dst.isNotEmpty() || sw.size !in 1..2) return no("not a repeat deletion")
                val after = tokenize(text.substring(e.end)).words.take(sw.size).map { normWord(it) }
                if (after != sn) return no("not repeated")
                if (sw.size == 1 && sn[0] in SpokenEdits.PROTECTED_REPEATS) return no("intended double")
                if (sentenceEndInside(src)) return no("crosses a sentence end")
                return yes("repeat")
            }
            SpokenEdits.Kind.SELF_CORRECTION -> {
                if (dst.isNotEmpty()) return no("self-correction edit adds text")
                val mk = markers.firstOrNull { m -> sn.size > m.words.size && sn.takeLast(m.words.size) == m.words }
                    ?: return no("no repair marker")
                val marker = mk.words
                if (sn.size - marker.size !in 1..4) return no("too much removed")
                // no sentence end inside, except right before a marker that may start a sentence
                // ("in the toaster oven? I mean, air fryer?")
                val stk = tokenize(src)
                val markerAt = sw.size - marker.size
                val rest = text.substring(e.end)
                // (or a number corrected by a number, as in "We need three. Actually, four chairs.")
                fun number(w: String) = NumberWords.isSpokenNumber(w) || (w.isNotEmpty() && w.all { it.isDigit() })
                val numbers = markerAt > 0 && number(sw[markerAt - 1]) && tokenize(rest).words.firstOrNull()?.let { number(it) } == true
                for (g in 1 until sw.size) {
                    if (!sentenceEndInside(stk.gaps[g])) continue
                    if (g != markerAt || !(mk.atSentenceStart || numbers)) return no("crosses a sentence end")
                }
                // the repair follows (the voice model may have started a sentence at the pause before it)
                if (rest.isEmpty() || !rest[0].isLetterOrDigit()) return no("no repair after the marker")
                return yes("self-correction")
            }
            SpokenEdits.Kind.SCRATCH -> {
                if (dst.isNotEmpty()) return no("scratch edit adds text")
                val cmdLen = SpokenEdits.scratchCommands(commands).firstNotNullOfOrNull { c ->
                    (1 until sn.size).firstOrNull { st -> SpokenEdits.phraseLength(sn, st, c) == sn.size - st }?.let { sn.size - it }
                } ?: return no("no scratch command")
                // exactly one sentence (or utterance) before the command, which starts a sentence/utterance
                val tk = tokenize(src)
                val nScratched = sw.size - cmdLen
                val innerEnds = (1 until nScratched).count { sentenceEndInside(tk.gaps[it]) }
                if (innerEnds > 0) return no("more than one sentence")
                val textStart = e.start == 0 || text.substring(0, e.start).trimEnd().let { it.isEmpty() || it.last() in SENTENCE_END || it.endsWith("\n") } ||
                    src.isNotEmpty() && !src[0].isLetterOrDigit()
                if (!textStart) return no("does not start at a sentence")
                return yes("scratch that")
            }
            SpokenEdits.Kind.NUMBER -> {
                val forms = NumberWords.forms(src)
                return if (dst in forms) yes("number") else no("not a written form of these words")
            }
            SpokenEdits.Kind.COMMAND -> {
                // (the command may be written with hyphens, as in "close-quote")
                var cmdLen = 0
                val cmd = SpokenEdits.punctuationCommands(commands).firstOrNull { c ->
                    cmdLen = SpokenEdits.phraseLength(sn, 0, c.words)
                    cmdLen > 0
                } ?: return no("no command")
                val extra = sw.drop(cmdLen)
                if (extra.size > 1 || dw.size != extra.size) return no("command edit changes words")
                if (extra.size == 1 && (!dw[0].equals(extra[0], ignoreCase = true) || dw[0].drop(1) != extra[0].drop(1))) return no("next word changed")
                if (!src.startsWith(src.takeWhile { !it.isLetterOrDigit() }) || src.takeWhile { !it.isLetterOrDigit() }.any { it.isLetterOrDigit() }) return no("bad span")
                val marks = (if (dw.isEmpty()) dst else dst.substring(0, dst.length - dw[0].length))
                return if (cmd.symbol.isBlank()) {
                    val breaks = marks.filter { it == '\n' }
                    val punct = marks.filter { !it.isWhitespace() }
                    if (breaks == cmd.symbol && punct.length <= 1 && punct.all { it in ".?!:," }) yes("line break") else no("bad line break")
                } else {
                    // a mark before a word may keep the sentence end in front of it (". (Next")
                    val core = marks.trim().let {
                        if (cmd.spacing == CommandSpacing.BEFORE_WORD || cmd.symbol in SpokenEdits.CLOSERS) {
                            it.trimStart('.', '?', '!').trim()
                        } else it
                    }
                    if (core == cmd.symbol) yes("spoken punctuation") else no("bad punctuation")
                }
            }
            SpokenEdits.Kind.EMOJI -> {
                // a known name and "emoji" become exactly that name's emoji
                val kw = if (sn.isEmpty()) 0 else EmojiNames.keywordLength(sn, sn.lastIndex)
                if (kw == 0 || sn.size <= kw) return no("no emoji name")
                val emoji = emojiNames[sn.dropLast(kw).flatMap { VoiceCommands.phraseWords(it) }] ?: return no("unknown emoji name")
                if (emoji.isEmpty()) return no("name gives no emoji")
                // a question mark the emoji followed goes before it
                return if (dst == emoji || dst == "? $emoji" && src.first().isWhitespace()) yes("emoji") else no("not that name's emoji")
            }
            SpokenEdits.Kind.QUOTE -> {
                // the same words (only the first one's case may change), two quotation marks
                // added, and otherwise only , . ? ! changed
                val sNorm = sn
                val dNorm = dw.map { normWord(it) }
                if (sNorm != dNorm) return no("quote edit changes words")
                if (dst.count { it == '"' } - src.count { it == '"' } != 2) return no("not one pair of quotes")
                val extra = dst.filter { !it.isLetterOrDigit() && !it.isWhitespace() && it != '"' && it != '\'' && it != '’' }
                if (extra.any { it !in ",.?!" }) return no("quote edit adds other marks")
                // (a word after a sentence end the voice model wrote at a pause may be lowercased,
                // as in "um. What" -> "Um, what")
                val sGaps = tokenize(src).gaps
                if (dw.size != sw.size || (1 until dw.size).any { k ->
                        dw[k] != sw[k] && !(sentenceEndInside(sGaps[k]) && isLowercased(sw[k], dw[k]))
                    }
                ) return no("quote edit changes case")
                return yes("quoted speech")
            }
            SpokenEdits.Kind.DELETE_WORD -> {
                if (dst.isNotEmpty()) return no("delete-word edit adds text")
                SpokenEdits.deleteWordCommands(commands).firstOrNull { c -> sn.size >= 2 && SpokenEdits.phraseLength(sn, 1, c) == sn.size - 1 }
                    ?: return no("not one word and a delete-word command")
                return yes("delete last word")
            }
        }
    }

    /** [to] is [from] with its first letter lowercased ("Thinking" -> "thinking"). */
    private fun isLowercased(from: String, to: String): Boolean =
        from.isNotEmpty() && from[0].isUpperCase() && to.isNotEmpty() && to[0] == from[0].lowercaseChar() && to.drop(1) == from.drop(1)

    /** Whether [words] is made of filler phrases from [fillers] only ("um uh", "you know"). */
    private fun isFillerSequence(words: List<String>, fillers: List<FillerWords.Filler>): Boolean {
        var k = 0
        while (k < words.size) {
            val f = fillers.firstOrNull { k + it.words.size <= words.size && words.subList(k, k + it.words.size) == it.words }
                ?: return false
            k += f.words.size
        }
        return true
    }

    /** Python len(s.split()). */
    private fun splitCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }
}
