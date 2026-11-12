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
    /** ... and a missed typo (a word no dictionary knows, so plainly a slip) once this many do. */
    val earlyTypoWords: Int = 2,
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
    /** A missed typo fixed only by dropping its first letter ("uhaul" -> "haul") needs this much more (often a brand or a name). */
    val firstLetterNeed: Double = 6.0,
    /** Add a small word that is missing ("a cup coffee" -> "a cup of coffee"). */
    val missingWords: Boolean = false,
    /** Remove a doubled or leftover word ("to to", "its it's", "at the a store"). */
    val leftoverWords: Boolean = false,
    /** Fix a wrong ending, or words that stopped agreeing ("a long lists", "you friends"). */
    val wordEndings: Boolean = false,
    /** Swap two neighbouring words in the wrong order ("a rough of sense" -> "a rough sense of"). */
    val wordOrder: Boolean = false,
    /** Join, split or add the apostrophe ("Every thing", "upto", "thats"). */
    val joinSplit: Boolean = false,
    /** An added word must raise the log-prob by this much (an addition makes the text longer, so it scores low). */
    val addMargin: Double = 6.0,
    /**
     * A removed word must raise the log-prob of the words after it by this much (its total gain is
     * mostly the removed word's own log-prob, as the text got shorter) ...
     */
    val removeMargin: Double = 8.5,
    /** ... a word typed twice in two forms or a few words apart ("photos photo", "should probably should") this much ... */
    val nearRepeatMargin: Double = 6.0,
    /** ... and an exact repeat ("to to") must raise the log-prob of the whole text this much (the repeat itself is the slip). */
    val repeatMargin: Double = 6.0,
    /** A changed ending needs this much. */
    val endingMargin: Double = 6.5,
    /** Two swapped words need this much. */
    val orderMargin: Double = 6.5,
    /** A join, split or apostrophe needs this much. */
    val joinMargin: Double = 5.5,
    /**
     * At most this many of the edits around surprising words are scored per check (by kind), and
     * exact repeats and up to 2 joins always are. Each scored edit costs about as much as the pass itself.
     */
    val editBudget: Int = 8,
    /** A changed ending or a swap whose first word is this much less likely than the typed one's is not scored. */
    val editProbeDrop: Double = 0.0,
    /** An added word must be at least this much more likely than the typed one where it goes (most are). */
    val addMinProbe: Double = 3.0,
    /**
     * Edits are only tried around a surprising word (a slip shows in the words right after it): one
     * whose log-prob is at most this ...
     */
    val spikeLogProb: Double = -6.5,
    /** ... or this much below the sentence's middle word (off by default, as it found no more slips) ... */
    val spikeBelowMedian: Double = 99.0,
    /** ... on that word and this many words before it. */
    val spikeWindow: Int = 2,
    /**
     * A removal (of a word that is not an exact or near repeat next to it) is not made when, next to the removed word, another
     * change (an added word, an ending, a swap or a pair) raises the log-prob this much too: then a
     * word may as well be missing.
     */
    val ambiguityGain: Double = 3.0,
    /** An edit is first scored up to this many words after it ... */
    val editWindowWords: Int = 2,
    /** ... and then to the end of the text if it came within this much of its margin there ... */
    val editWindowSlack: Double = 2.0,
    /** ... at most this many per check (the most promising). */
    val editFullScores: Int = 2,
    /** Trace every word's log-prob and every edit not scored (tools). */
    val traceEdits: Boolean = false,
) {
    /** This config with [margin] moved by the "Sensitivity" setting (see [SmartCorrectionSensitivity]). */
    fun withSensitivity(steps: Int): TypingCorrectionConfig {
        val d = steps.coerceIn(SmartCorrectionSensitivity.MIN, SmartCorrectionSensitivity.MAX) * SmartCorrectionSensitivity.STEP
        return copy(
            margin = margin - d, missedNeed = missedNeed - d, missedMargin = maxOf(0.5, missedMargin - d / 2),
            addMargin = maxOf(1.0, addMargin - d), removeMargin = maxOf(1.0, removeMargin - d), repeatMargin = maxOf(1.0, repeatMargin - d),
            nearRepeatMargin = maxOf(1.0, nearRepeatMargin - d),
            endingMargin = maxOf(1.0, endingMargin - d), orderMargin = maxOf(1.0, orderMargin - d), joinMargin = maxOf(1.0, joinMargin - d),
        )
    }

    /** Whether any of the edit kinds is on (missing, leftover, endings, order, join). */
    val anyEdits: Boolean get() = missingWords || leftoverWords || wordEndings || wordOrder || joinSplit

    /** The margin an edit of [kind] needs (before the opening margin and what was learned). */
    fun editMargin(kind: String, repeat: Boolean, nearRepeat: Boolean = false): Double = when (kind) {
        "add" -> addMargin
        "remove" -> if (repeat) repeatMargin else if (nearRepeat) nearRepeatMargin else removeMargin
        "ending" -> endingMargin
        "order" -> orderMargin
        else -> joinMargin
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
 *
 * When switched on, edits for slips made while editing are candidates too ([TypingEdits]): a missing
 * small word, a doubled or leftover word, a wrong ending, two words in the wrong order, and a join,
 * split or apostrophe. There are hundreds per sentence, so the one pass first tells how likely each
 * edit's first word is next to the typed one, and only [TypingCorrectionConfig.editBudget] of
 * the most likely are scored in full.
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
    private val edits = TypingEdits(config, lexicon)

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
        /** "pair", "typo" (a missed typo), "add", "remove", "ending", "order" or "join" (see [TypingEdit]). */
        val kind: String = "pair",
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
        val typos = if (config.missedTypos && lexicon != null) {
            words.filter { m ->
                m.range.first >= from && !skip(m.range.first) && missedTypoShape(text, m) && !confusions.isNever(m.value) &&
                    unknown(m.range.first) && !lexicon.knows(m.value)
            }.takeIf { it.size <= config.maxMissedPerSentence }?.toSet() ?: emptySet()
        } else emptySet()
        for ((wi, m) in words.withIndex()) {
            if (m.range.first < from || skip(m.range.first)) continue
            // before the sentence ends, a word needs a few words after it to be judged
            val wordsAfter = words.size - 1 - wi
            val pairReady = finished || wordsAfter >= config.earlyWords
            val typoReady = finished || wordsAfter >= config.earlyTypoWords
            if (!pairReady && !typoReady) continue
            val s = m.range.first - cut
            if (config.realWords && pairReady) for (c in confusions.candidates(m.value)) {
                val rep = matchCase(m.value, c)
                cands.add(Cand(m, rep, scored.substring(0, s) + rep + scored.substring(s + m.value.length)))
            }
            // a word no dictionary knows, so the real words it may be a slip of
            if (typoReady && m in typos) {
                for ((c, cost) in KeyboardTypos.candidates(m.value, lexicon!!.lowercaseWords, config.maxTypoCost, config.maxTypoCandidates)) {
                    val rep = matchCase(m.value, c)
                    cands.add(Cand(m, rep, scored.substring(0, s) + rep + scored.substring(s + m.value.length), cost))
                }
            }
        }
        // slips made while editing (a missing, leftover or swapped word, a wrong ending, a join), whose
        // edits need the same few words after them as a pair before the sentence ends
        val first = words.indexOfFirst { it.range.first >= from }
        val editCands = if (config.anyEdits && first >= 0) edits.candidates(
            text, words, first,
            usable = { wi -> !skip(words[wi].range.first) && (finished || words.size - 1 - wi >= config.earlyWords) },
            opens = { opensSentence(text, it) },
            unknown = unknown,
        ) else emptyList()
        if (cands.isEmpty() && editCands.isEmpty()) return Result(null, trace, emptyList())

        checkpoint()
        val groups = cands.groupBy { it.m }.entries.toList()
        val probes = if (config.probeDrop > 0) groups.map { (m, cs) -> (m.range.first - cut) to cs.map { it.replacement } } else emptyList()
        // an edit's first different word is probed where it starts, which ranks the additions, endings,
        // swaps and joins (a removal is not probed: the word after a leftover is unlikely where the
        // leftover stands, though the leftover is the slip)
        val editGroups = editCands.filter { it.kind != "remove" && it.probeWord != null }.groupBy { it.probeAt }.mapValues { (_, es) -> es.map { it.probeWord!! }.distinct() }
        val editAt = editGroups.keys.toList()
        val detail = scorer.detail(scored, emptyList(), probes + editAt.map { (it - cut) to editGroups.getValue(it) })
        if (probes.isNotEmpty()) {
            val kept = groups.flatMapIndexed { gi, (_, cs) ->
                val pr = detail.probes.getOrNull(gi)
                if (pr == null || pr.size != cs.size + 1) cs
                else cs.filterIndexed { k, c -> c.typoCost != null || pr[k] >= pr[cs.size] - config.probeDrop || confusions.isOwn(c.m.value, c.replacement) }
            }
            if (kept.size < cands.size) trace.add("probe kept ${kept.size}/${cands.size}")
            cands.retainAll(kept.toSet())
        }
        val probeOf = HashMap<TypingEdit, Double>()
        for ((k, at) in editAt.withIndex()) {
            val pr = detail.probes.getOrNull(probes.size + k) ?: continue
            val ws = editGroups.getValue(at)
            if (pr.size != ws.size + 1) continue
            for (e in editCands) if (e.kind != "remove" && e.probeAt == at && e.probeWord != null) probeOf[e] = (pr[ws.indexOf(e.probeWord)] - pr[ws.size]).toDouble()
        }
        val chosen = if (editCands.isEmpty()) emptyList() else chooseEdits(text, words, first, cut, detail, editCands, probeOf, trace)
        if (cands.isEmpty() && chosen.isEmpty()) return Result(null, trace, scorer.timings.toList())
        checkpoint()
        // an edit is first scored only up to a few words after it (a slip shows right after it, and a
        // shorter text costs less), and only the most promising are then scored to the end
        val winEnds = chosen.map { e -> minOf(words.getOrNull(e.lastWord + config.editWindowWords)?.let { it.range.last + 1 } ?: to, to) }
        val res = scorer.scoreDetailed(listOf(scored) + cands.map { it.variant } +
            chosen.mapIndexed { k, e -> scored.substring(0, e.start - cut) + e.replacement + scored.substring(e.end - cut, winEnds[k] - cut) })
        val base = res[0]
        var best: Fix? = null
        var bestOver = 0.0
        // the words after the change are the tokens both texts end with (of the base, its first [upTo] tokens)
        fun tailOf(v: LmScorer.Scored, upTo: Int = base.tokens.size): Double {
            var n = 0
            while (n < v.tokens.size && n < upTo &&
                v.tokens[v.tokens.size - 1 - n] == base.tokens[upTo - 1 - n]) n++
            var tail = 0.0
            for (k in 1..n) tail += v.lps[v.lps.size - k] - base.lps[upTo - k]
            return tail
        }
        // the changes other than removals (word span and gain), for the ambiguity rule of removals
        val others = ArrayList<Triple<Int, Int, Double>>()
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
            val tail = tailOf(v)
            val idx = words.indexOf(c.m)
            others.add(Triple(idx, idx, gain))
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
            // short words have many neighbours, and are often abbreviations ("brb", "ttyl"), and a word
            // that is a real one after its first letter is often a brand or a name ("uhaul")
            val firstLetter = top.c.replacement.lowercase() == m.value.lowercase().drop(1)
            val need = config.missedNeed + learned + maxOf(0, SHORT_WORD - m.value.length) * config.shortWordNeed +
                (if (firstLetter) config.firstLetterNeed else 0.0)
            val margin = config.missedMargin + maxOf(0.0, learned)
            val over = minOf(top.score - need, top.score - runnerUp - margin)
            trace.add(sorted.take(3).joinToString(" ") { "${m.value}->${it.c.replacement} gain=%.2f score=%.2f".format(it.gain, it.score) } +
                " need=%.2f margin=%.2f%s".format(need, margin, if (over >= 0) " OK" else ""))
            if (over >= 0 && (best == null || over > bestOver)) {
                bestOver = over
                best = Fix(m.range.first, m.range.last + 1, m.value, top.c.replacement, top.gain, 0.0, "typo")
            }
        }
        // the edits, each against its kind's margin; a removal is judged by what it does to the words
        // after the removed one (its total gain is mostly the removed word's own log-prob, as the text
        // got shorter), except an exact repeat, and a word that is not a repeat is not removed where
        // a word may as well be missing
        fun needOf(e: TypingEdit): Double {
            val prev = words.getOrNull(e.firstWord - 1)?.takeIf { gapAt(text, it, words[e.firstWord]).none { ch -> ch in ".?!\n" } }
            val next = words.getOrNull(e.lastWord + 1)
            val opening = opensSentence(text, e.start)
            return maxOf(
                config.editMargin(e.kind, e.repeat, e.nearRepeat || e.windowRepeat) + (if (opening) config.openingMargin else 0.0) +
                    caution(e.original.lowercase(), e.replacement.lowercase(), prev?.value?.lowercase() ?: "", next?.value?.lowercase() ?: ""),
                config.ownMinNeed,
            )
        }
        fun valueOf(e: TypingEdit, gain: Double, tail: Double) = if (e.kind == "remove" && !e.repeat) tail else gain
        val needs = chosen.map { needOf(it) }
        val gains = DoubleArray(chosen.size)
        val tails = DoubleArray(chosen.size)
        val complete = BooleanArray(chosen.size)
        for ((k, e) in chosen.withIndex()) {
            val v = res[1 + cands.size + k]
            // the base up to the same place (its tokens that start before the window ends)
            val upTo = if (winEnds[k] >= to) base.tokens.size else (0 until base.tokens.size).count { detail.charOffset(it) < winEnds[k] - cut }
            var baseTotal = 0.0
            for (i in 0 until upTo) baseTotal += base.lps[i]
            gains[k] = v.total - baseTotal
            tails[k] = tailOf(v, upTo)
            complete[k] = winEnds[k] >= to
        }
        val promising = chosen.indices.filter { !complete[it] && valueOf(chosen[it], gains[it], tails[it]) >= needs[it] - config.editWindowSlack }
            .sortedByDescending { valueOf(chosen[it], gains[it], tails[it]) - needs[it] }.take(config.editFullScores)
        if (promising.isNotEmpty()) {
            checkpoint()
            val full = scorer.scoreDetailed(listOf(scored) + promising.map { k ->
                val e = chosen[k]
                scored.substring(0, e.start - cut) + e.replacement + scored.substring(e.end - cut)
            })
            for ((j, k) in promising.withIndex()) {
                gains[k] = full[1 + j].total - full[0].total
                tails[k] = tailOf(full[1 + j])
                complete[k] = true
            }
        }
        for ((k, e) in chosen.withIndex()) if (e.kind != "remove") others.add(Triple(e.firstWord, e.lastWord, gains[k]))
        for ((k, e) in chosen.withIndex()) {
            val gain = gains[k]
            val tail = tails[k]
            val need = needs[k]
            val removal = e.kind == "remove"
            val plainRemoval = removal && !e.repeat && !e.nearRepeat
            val value = valueOf(e, gain, tail)
            val rival = if (!plainRemoval) null else others.filter { (a, b, g) -> g >= config.ambiguityGain && b >= e.removed - 1 && a <= e.removed + 1 }.maxOfOrNull { it.third }
            // one scored only up to its window is never changed (it was not promising enough there)
            val ok = complete[k] && value >= need && (removal || tail >= config.tailMin) && rival == null
            trace.add("${e.kind} ${e.original}->${e.replacement} gain=%.2f tail=%.2f need=%.2f probe=%s%s%s%s%s".format(
                gain, tail, need, if (e.repeat) "repeat" else (probeOf[e]?.let { "%.2f".format(it) } ?: "-") + (if (e.nearRepeat) " near" else if (e.windowRepeat) " window" else ""),
                if (rival != null) " ambiguous=%.2f".format(rival) else "",
                if (complete[k]) "" else " partial",
                if (config.traceEdits) " at=${e.firstWord - first},${e.lastWord - first},${if (e.removed >= 0) e.removed - first else -1}" else "",
                if (ok) " OK" else ""))
            if (ok && value - need > bestOver) {
                bestOver = value - need
                best = Fix(e.start, e.end, e.original, e.replacement, gain, tail, e.kind)
            }
        }
        // a swap moves a word one place, and the same misplaced word can also go the other way ("out
        // running of time" is "running out of time", not "out of running time"); the swaps sharing a
        // word with the one about to be made were maybe not scored, so they are now, and the best wins
        val swap = best?.takeIf { it.kind == "order" }?.let { b -> chosen.firstOrNull { it.kind == "order" && it.start == b.start && it.end == b.end } }
        if (swap != null) {
            val scoredSet = chosen.toSet()
            val rivals = editCands.filter { it.kind == "order" && it !in scoredSet && it.lastWord >= swap.firstWord && it.firstWord <= swap.lastWord }
            if (rivals.isNotEmpty()) {
                checkpoint()
                val full = scorer.scoreDetailed(listOf(scored) + rivals.map { e -> scored.substring(0, e.start - cut) + e.replacement + scored.substring(e.end - cut) })
                for ((j, e) in rivals.withIndex()) {
                    val gain = full[1 + j].total - full[0].total
                    val tail = tailOf(full[1 + j])
                    val need = needOf(e)
                    val ok = gain >= need && tail >= config.tailMin
                    trace.add("order ${e.original}->${e.replacement} gain=%.2f tail=%.2f need=%.2f probe=rival%s".format(gain, tail, need, if (ok) " OK" else ""))
                    if (ok && gain - need > bestOver) {
                        bestOver = gain - need
                        best = Fix(e.start, e.end, e.original, e.replacement, gain, tail, e.kind)
                    }
                }
            }
        }
        return Result(best, trace, scorer.timings.toList())
    }

    /**
     * The edits worth scoring: those on a surprising word or the [TypingCorrectionConfig.spikeWindow]
     * words before it (a clean sentence has few or none), exact repeats, and then the most likely by
     * kind within [TypingCorrectionConfig.editBudget] (removals by how surprising their word is, the
     * others by how likely their first word is where it goes).
     */
    private fun chooseEdits(
        text: String,
        words: List<MatchResult>,
        first: Int,
        cut: Int,
        detail: LmScorer.Detail,
        editCands: List<TypingEdit>,
        probeOf: Map<TypingEdit, Double>,
        trace: MutableList<String>,
    ): List<TypingEdit> {
        // each word's log-prob is the sum of its tokens' (the punctuation after it is not counted)
        val wlp = DoubleArray(words.size) { Double.NaN }
        val starts = IntArray(words.size) { words[it].range.first }
        for (t in detail.lps.indices) {
            val c = detail.charOffset(t) + cut
            var wi = java.util.Arrays.binarySearch(starts, c)
            if (wi < 0) wi = -wi - 2
            if (wi < 0 || words[wi].range.first < cut || c > words[wi].range.last) continue
            wlp[wi] = (if (wlp[wi].isNaN()) 0.0 else wlp[wi]) + detail.lps[t]
        }
        // the first word of a sentence is often surprising for other reasons, so it is never a spike
        val inner = (first until words.size).filter { !wlp[it].isNaN() && !opensSentence(text, words[it].range.first) }
        val median = inner.map { wlp[it] }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val spikes = inner.filter { wlp[it] <= config.spikeLogProb || wlp[it] <= median - config.spikeBelowMedian }
        if (config.traceEdits) trace.add("words " + (first until words.size).joinToString(" ") { "${words[it].value}:%.1f".format(wlp[it]) })
        if (spikes.isNotEmpty()) trace.add("spikes " + spikes.joinToString(" ") { "${words[it].value}:%.1f".format(wlp[it]) })
        fun spikeScore(e: TypingEdit): Double? =
            spikes.filter { e.firstWord in it - config.spikeWindow..it }.maxOfOrNull { -wlp[it] }
        val gated = editCands.filter { it.repeat || spikeScore(it) != null }
        // each kind gets its share of the budget (what a kind does not use is not given to the others,
        // which keeps clean sentences cheap); exact repeats and a few joins are always scored
        val always = gated.filter { it.repeat } + gated.filter { it.kind == "join" && !it.repeat }
            .sortedByDescending { probeOf[it] ?: 0.0 }.take(MAX_JOINS)
        // removals by how surprising their word is, the others by their probe
        val ranked = gated.filter { !it.repeat && it.kind != "join" }
            .filter { it.kind != "add" || (probeOf[it] ?: Double.NEGATIVE_INFINITY) >= config.addMinProbe }
            .filter { (it.kind != "ending" && it.kind != "order") || (probeOf[it] ?: 0.0) >= -config.editProbeDrop }
            .sortedByDescending { if (it.kind == "remove") spikeScore(it) ?: 0.0 else probeOf[it] ?: 0.0 }
        val picked = LinkedHashSet<TypingEdit>()
        for ((kind, share) in BUDGET_SHARES) picked.addAll(ranked.filter { it.kind == kind }.take(Math.round(config.editBudget * share).toInt()))
        val chosen = always + picked.take(config.editBudget)
        trace.add("edits scored ${chosen.size}/${gated.size} of ${editCands.size}")
        if (config.traceEdits) {
            val set = chosen.toSet()
            val rankedSet = ranked.toSet()
            for (e in editCands) if (e !in set) {
                val why = if (e !in gated) "gated" else if (e.kind != "remove" && e.kind != "join" && e !in rankedSet) "probe" else "budget"
                trace.add("skip $why ${e.kind} ${e.original}->${e.replacement} at=${e.firstWord - first}")
            }
        }
        return chosen
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

        /** Share of [TypingCorrectionConfig.editBudget] each kind of edit gets first. */
        private val BUDGET_SHARES = listOf("add" to 0.25, "remove" to 0.25, "ending" to 0.25, "order" to 0.25)

        /** At most this many joins, splits and apostrophes are scored (outside the budget). */
        private const val MAX_JOINS = 2

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
