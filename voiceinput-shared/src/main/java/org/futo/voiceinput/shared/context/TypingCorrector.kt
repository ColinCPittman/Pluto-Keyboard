package org.futo.voiceinput.shared.context

/** Thresholds of Smart corrections for typing (tuned on tools/context-bench/testset_typing.jsonl). */
data class TypingCorrectionConfig(
    /** Prompt the typed text is scored after. */
    val prompt: String = "A text message:\n",
    /** A swap must raise the log-prob of the whole text by this much ... */
    val margin: Double = 6.5,
    /** ... plus this for the first word of a sentence (less context to go on). */
    val openingMargin: Double = 3.0,
    /** The words after the swapped one must not get less likely than this (log-prob change). */
    val tailMin: Double = -1.5,
    /** Sentences with fewer words are not checked (too little to go on, like "Than what?"). */
    val minWords: Int = 3,
    /** Before its sentence ends, a word is checked once this many words follow it. */
    val earlyWords: Int = 3,
    /**
     * A candidate is scored only if its first token is at most this much less likely than the
     * typed word's where it starts (one pass for all), and 0 scores them all.
     */
    val probeDrop: Double = 6.0,
    /** "then" right after a comparative ("taller then her") needs this much less than [margin]. */
    val comparativeRelief: Double = 3.5,
    /** A pair the user added themselves needs this much less than [margin], because they know it's their slip ... */
    val ownRelief: Double = 4.0,
    /** ... but always at least this much (the swapped text must still be clearly more likely). */
    val ownMinNeed: Double = 1.5,
    /** Characters of text before the checked sentence that are scored with it. */
    val leftChars: Int = 300,
    /** Fix typos that are real words (the typing pairs). */
    val realWords: Boolean = true,
    /** Attempt to fix missed typos, meaning words no dictionary knows ("ststy" -> "start"). */
    val missedTypos: Boolean = false,
    /** Log-prob a missed typo's candidate loses per unit of [KeyboardTypos.cost] (how unlikely the slip is). */
    val typoUnit: Double = 2.0,
    /** Candidates of a missed typo at most this far ([KeyboardTypos.cost]) from what was typed. */
    val maxTypoCost: Double = 4.0,
    /** At most this many candidates per missed typo are scored (the cheapest slips). */
    val maxTypoCandidates: Int = 10,
    /** The best candidate's score (LM gain minus slip cost) must reach this ... */
    val missedNeed: Double = 2.0,
    /** ... and beat the runner-up by this much (otherwise it is a guess). */
    val missedMargin: Double = 3.0,
    /** A missed typo shorter than 5 letters needs this much more per missing letter. */
    val shortWordNeed: Double = 2.0,
    /** A sentence with more missed-typo candidates than this gets none of them fixed. */
    val maxMissedPerSentence: Int = 2,
) {
    /** This config with [margin] moved by the "Sensitivity" setting (see [SmartCorrectionSensitivity]). */
    fun withSensitivity(steps: Int): TypingCorrectionConfig {
        val d = steps.coerceIn(SmartCorrectionSensitivity.MIN, SmartCorrectionSensitivity.MAX) * SmartCorrectionSensitivity.STEP
        return copy(margin = margin - d, missedNeed = missedNeed - d, missedMargin = maxOf(0.5, missedMargin - d / 2))
    }
}

/**
 * Smart corrections for typing re-check a finished sentence for real-word typos
 * ("some new salt an pepper" -> "and") with the context LM, which reads the whole sentence,
 * including the words after the typo that make it obvious.
 *
 * Candidates are one-key-off swaps from [TypingConfusions], and each is scored as the whole text
 * (the sentence after [TypingCorrectionConfig.leftChars] of what came before). At most one
 * swap per sentence is returned (the one that raises the log-prob most, if it beats the margin).
 */
class TypingCorrector(
    lm: ContextLm,
    private val confusions: TypingConfusions = TypingConfusions.builtIn,
    val config: TypingCorrectionConfig = TypingCorrectionConfig(),
    /** Its lowercase words are the candidates for missed typos ([TypingCorrectionConfig.missedTypos]). */
    private val lexicon: SoundAlikeLexicon? = null,
    /**
     * Extra margin for a swap the user reverted before, or less for one they made themselves
     * where it was missed, keyed by (original, replacement, previous word, next word), all lowercase ("" at
     * an edge), with the log-prob as the value.
     */
    private val caution: (String, String, String, String) -> Double = { _, _, _, _ -> 0.0 },
) {
    private val scorer = LmScorer(lm, config.prompt)

    /** A swap of [original] at [start]..[end] of the checked text for [replacement]. */
    class Fix(
        val start: Int,
        val end: Int,
        val original: String,
        val replacement: String,
        /** Log-prob gain of the whole text. */
        val gain: Double,
        /** Of that, the gain of the words after the swapped one. */
        val tailGain: Double,
    )

    class Result(val fix: Fix?, val trace: List<String>, val timings: List<Double>)

    private val word = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")

    /**
     * Checks the words of [text] in [from]..[to] (the finished sentence, and what's before it is
     * context). [skip] tells which word starts must not change (corrected by hand, reverted).
     * While the sentence is not [finished], only words with [TypingCorrectionConfig.earlyWords]
     * words after them are candidates. [unknown] tells which word starts are words no dictionary
     * knows (missed typos, and the keyboard checks its own dictionaries and your words).
     */
    fun check(
        text: String,
        from: Int = 0,
        to: Int = text.length,
        finished: Boolean = true,
        skip: (Int) -> Boolean = { false },
        checkpoint: () -> Unit = {},
        unknown: (Int) -> Boolean = { false },
    ): Result {
        val trace = ArrayList<String>()
        scorer.timings.clear()
        // the scored text is up to leftChars before the sentence, cut at a word start
        var cut = maxOf(0, from - config.leftChars)
        if (cut > 0) {
            while (cut < from && !text[cut - 1].isWhitespace()) cut++
        }
        val scored = text.substring(cut, to)

        /** [typoCost] is, for a missed typo, how unlikely the slip is ([KeyboardTypos.cost]), and null for a pair. */
        class Cand(val m: MatchResult, val replacement: String, val variant: String, val typoCost: Double? = null)
        val cands = ArrayList<Cand>()
        val words = word.findAll(text, 0).filter { it.range.last < to }.toList()
        if (words.count { it.range.first >= from } < config.minWords) return Result(null, trace, emptyList())
        // the words that may be missed typos (a sentence with many of them is probably another
        // language, code or jargon, so it is left alone, which also keeps the check quick)
        val typos = if (config.missedTypos && finished && lexicon != null) {
            words.filter { m ->
                m.range.first >= from && !skip(m.range.first) && missedTypoShape(text, m) && !confusions.isNever(m.value) &&
                    unknown(m.range.first) && !lexicon.knows(m.value)
            }.takeIf { it.size <= config.maxMissedPerSentence }?.toSet() ?: emptySet()
        } else emptySet()
        for ((wi, m) in words.withIndex()) {
            if (m.range.first < from || skip(m.range.first)) continue
            if (!finished && words.size - 1 - wi < config.earlyWords) continue
            val s = m.range.first - cut
            if (config.realWords) for (c in confusions.candidates(m.value)) {
                val rep = matchCase(m.value, c)
                cands.add(Cand(m, rep, scored.substring(0, s) + rep + scored.substring(s + m.value.length)))
            }
            // a word no dictionary knows, so the real words it may be a slip of (finished sentences only)
            if (m in typos) {
                for ((c, cost) in KeyboardTypos.candidates(m.value, lexicon!!.lowercaseWords, config.maxTypoCost, config.maxTypoCandidates)) {
                    val rep = matchCase(m.value, c)
                    cands.add(Cand(m, rep, scored.substring(0, s) + rep + scored.substring(s + m.value.length), cost))
                }
            }
        }
        if (cands.isEmpty()) return Result(null, trace, emptyList())

        checkpoint()
        val groups = cands.groupBy { it.m }.entries.toList()
        val probes = if (config.probeDrop > 0) groups.map { (m, cs) -> (m.range.first - cut) to cs.map { it.replacement } } else emptyList()
        val detail = scorer.detail(scored, emptyList(), probes)
        if (probes.isNotEmpty()) {
            val kept = groups.flatMapIndexed { gi, (_, cs) ->
                val pr = detail.probes.getOrNull(gi)
                if (pr == null || pr.size != cs.size + 1) cs
                else cs.filterIndexed { k, c -> c.typoCost != null || pr[k] >= pr[cs.size] - config.probeDrop || confusions.isOwn(c.m.value, c.replacement) }
            }
            if (kept.size < cands.size) trace.add("probe kept ${kept.size}/${cands.size}")
            cands.retainAll(kept.toSet())
            if (cands.isEmpty()) return Result(null, trace, scorer.timings.toList())
        }
        checkpoint()
        val res = scorer.scoreDetailed(listOf(scored) + cands.map { it.variant })
        val base = res[0]
        var best: Fix? = null
        var bestOver = 0.0
        // missed typos are checked per word, taking the best candidate (LM gain minus slip cost) against the runner-up
        class Scored(val c: Cand, val gain: Double, val tail: Double, val score: Double)
        val typoScores = HashMap<MatchResult, MutableList<Scored>>()
        for ((i, c) in cands.withIndex()) {
            val v = res[i + 1]
            val gain = v.total - base.total
            val cost = c.typoCost
            if (cost != null) {
                val score = gain - cost * config.typoUnit
                typoScores.getOrPut(c.m) { ArrayList() }.add(Scored(c, gain, 0.0, score))
                continue
            }
            // the words after the swap are the tokens both texts end with
            var n = 0
            while (n < v.tokens.size && n < base.tokens.size &&
                v.tokens[v.tokens.size - 1 - n] == base.tokens[base.tokens.size - 1 - n]) n++
            var tail = 0.0
            for (k in 1..n) tail += v.lps[v.lps.size - k] - base.lps[base.lps.size - k]
            val idx = words.indexOf(c.m)
            val prev = words.getOrNull(idx - 1)?.takeIf { gapAt(text, it, c.m).none { ch -> ch in ".?!\n" } }
            val next = words.getOrNull(idx + 1)
            val opening = opensSentence(text, c.m.range.first)
            val comparative = c.m.value.equals("then", true) && c.replacement.equals("than", true) &&
                prev != null && isComparative(prev.value) &&
                next != null && gapAt(text, c.m, next).none { ch -> !ch.isWhitespace() }
            var base = config.margin + (if (opening) config.openingMargin else 0.0) -
                (if (comparative) config.comparativeRelief else 0.0)
            if (confusions.isOwn(c.m.value, c.replacement)) base = maxOf(base - config.ownRelief, config.ownMinNeed)
            // what was learned may make it more careful, or more ready down to the floor of your own pairs
            val need = maxOf(
                base + caution(c.m.value.lowercase(), c.replacement.lowercase(), prev?.value?.lowercase() ?: "", next?.value?.lowercase() ?: ""),
                config.ownMinNeed,
            )
            val ok = gain >= need && tail >= config.tailMin
            trace.add("${c.m.value}->${c.replacement} gain=%.2f tail=%.2f need=%.2f%s".format(gain, tail, need, if (ok) " OK" else ""))
            if (ok && gain - need > bestOver) {
                bestOver = gain - need
                best = Fix(c.m.range.first, c.m.range.last + 1, c.m.value, c.replacement, gain, tail)
            }
        }
        for ((m, list) in typoScores) {
            val sorted = list.sortedByDescending { it.score }
            val top = sorted[0]
            val runnerUp = sorted.getOrNull(1)?.score ?: Double.NEGATIVE_INFINITY
            val idx = words.indexOf(m)
            val prev = words.getOrNull(idx - 1)?.takeIf { gapAt(text, it, m).none { ch -> ch in ".?!\n" } }
            val next = words.getOrNull(idx + 1)
            val learned = caution(m.value.lowercase(), top.c.replacement.lowercase(), prev?.value?.lowercase() ?: "", next?.value?.lowercase() ?: "")
            // short words have many neighbours, and are often abbreviations ("brb", "ttyl")
            val need = config.missedNeed + learned + maxOf(0, SHORT_WORD - m.value.length) * config.shortWordNeed
            val margin = config.missedMargin + maxOf(0.0, learned)
            val over = minOf(top.score - need, top.score - runnerUp - margin)
            trace.add(sorted.take(3).joinToString(" ") { "${m.value}->${it.c.replacement} gain=%.2f score=%.2f".format(it.gain, it.score) } +
                " need=%.2f margin=%.2f%s".format(need, margin, if (over >= 0) " OK" else ""))
            if (over >= 0 && (best == null || over > bestOver)) {
                bestOver = over
                best = Fix(m.range.first, m.range.last + 1, m.value, top.c.replacement, top.gain, 0.0)
            }
        }
        return Result(best, trace, scorer.timings.toList())
    }

    /**
     * A word that may be a missed typo has letters only, at least 3, all lowercase, or capitalized
     * only where a sentence starts (a capital elsewhere is a name, and all caps is an acronym).
     */
    private fun missedTypoShape(text: String, m: MatchResult): Boolean {
        val w = m.value
        if (w.length < 3 || !w.all { it.isLetter() }) return false
        if (w.drop(1).any { it.isUpperCase() }) return false
        return !w[0].isUpperCase() || opensSentence(text, m.range.first)
    }

    private fun isComparative(w: String): Boolean {
        val l = w.lowercase()
        return l in COMPARATIVES || (l.length >= 5 && l.endsWith("er"))
    }

    private fun gapAt(text: String, a: MatchResult, b: MatchResult) = text.substring(a.range.last + 1, b.range.first)

    /** Whether the word at [at] opens its sentence. */
    private fun opensSentence(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '"' || text[i] == '(')) i--
        return i < 0 || text[i] in ".?!\n"
    }

    companion object {
        /** Missed typos shorter than this need more ([TypingCorrectionConfig.shortWordNeed]). */
        private const val SHORT_WORD = 5

        private val COMPARATIVES = setOf("more", "less", "better", "worse", "rather", "other", "further", "else")

        fun matchCase(src: String, dst: String): String = when {
            src.length > 1 && src.all { !it.isLetter() || it.isUpperCase() } -> dst.uppercase()
            src.first().isUpperCase() -> dst.replaceFirstChar { it.uppercaseChar() }
            else -> dst
        }

        private val sentenceEnd = Regex("[.?!]+[\"')]*(?=\\s|$)|\\n")

        /** Start and end of each sentence of [text] (end after its final mark, and the last may have none). */
        fun sentences(text: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var s = 0
            for (m in sentenceEnd.findAll(text)) {
                val e = m.range.last + 1
                if (text.substring(s, e).any { it.isLetter() }) out.add(s until e)
                s = e
            }
            if (s < text.length && text.substring(s).any { it.isLetter() }) out.add(s until text.length)
            return out
        }
    }
}
