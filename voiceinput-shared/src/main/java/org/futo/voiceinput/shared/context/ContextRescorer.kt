package org.futo.voiceinput.shared.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Thresholds of the rescoring design (defaults = tools/context-bench/rescore.py's). */
data class ContextRescoreConfig(
    /** Prompt the transcript is scored after. */
    val prompt: String = "Transcript of a casual voice message:\n",
    /** A word is a candidate for replacement if its tokens' log-prob sum is below this. */
    val surprise: Double = -4.0,
    /** At most this many (most surprising) words get candidates scored. */
    val maxWords: Int = 8,
    /**
     * A sound-alike word's gain minus how much more common the replacement is anyway (its prior
     * log-prob in neutral contexts) must beat this for an exact homophone ("meat" -> "meet") ...
     */
    val homophoneMargin: Double = 3.1,
    /** ... and this for a near one ("pool" -> "poll", "noise" -> "news"), since the voice model heard a different sound. */
    val nearMargin: Double = 7.0,
    /** Added to those for a word that opens its sentence (or follows only "The", "A", ...). */
    val openingMargin: Double = 6.5,
    /** Candidates per word. */
    val k: Int = 6,
    /** Drop candidates whose first token is this much less likely than the original's. */
    val probeMargin: Double = 3.0,
    /** Words after the end of a word's sentence included when scoring its candidates. */
    val tailWords: Int = 6,
    /** A candidate must beat the original by this log-prob margin. */
    val wordMargin: Double = 2.0,
    /** log P("?") - log P(".") at a sentence end needed for a question mark. */
    val qMargin: Double = 0.0,
    /** log P("?") - log P(",") after a tag word ("right, and") needed for a question mark. */
    val tagMargin: Double = 0.0,
    /** Same as [qMargin] for a fragment following a question ("? And bowls and spoons."). */
    val followMargin: Double = -3.0,
    /** Same as [qMargin] for a sentence that starts with a command ("Ask her what she wants."). */
    val imperativeMargin: Double = 3.0,
    val tagWords: Set<String> = setOf("right", "okay", "ok", "yeah", "correct", "huh", "no", "yes", "really"),
    // options (Smart corrections settings page) and log-prob margins of each kind of edit
    /** At most this many word-boundary / name / capitalization spots get candidates scored. */
    val maxExtraSlots: Int = 6,
    /** Total log-prob gain needed for a word-boundary change ("in to" -> "into"). */
    val boundaryMargin: Double = 3.0,
    /** A word is split ("away" -> "a way") only if its log-prob is below this (splits are rarely right). */
    val splitSurprise: Double = -9.0,
    /** Total log-prob gain needed for a personal-dictionary name. */
    val nameMargin: Double = 3.0,
    /** Gain needed for a personal-dictionary name replacing a capitalized name-like word ("Reese" -> "Rhys"). */
    val nameSwapMargin: Double = 0.0,
    /** Total log-prob gain needed for a case-only change ("Toaster" -> "toaster", "jordan" -> "Jordan"). */
    val caseMargin: Double = 2.0,
    /** Total log-prob gain needed to remove a filler. */
    val fillerMargin: Double = 0.0,
    /** Total log-prob gain needed to remove an accidental repeat. */
    val repeatMargin: Double = 3.0,
    /** Change of the mean log-prob per token of the sentence needed for a self-correction (a veto, and the rules decide most). */
    val selfCorrectionMargin: Double = -2.0,
    /** Same for a repair that mirrors what it replaces ("at five. I mean, at six", "Sam, sorry, Alex"). */
    val selfCorrectionParallelMargin: Double = -3.5,
    /** Total log-prob gain needed for "Scratch that" (the pause rules decide most). */
    val scratchMargin: Double = 0.0,
    /** Total log-prob gain needed for spoken punctuation marks. */
    val commandMargin: Double = 0.0,
    /** Total log-prob gain needed for a spoken "new line" / "new paragraph" (a veto, and the pause rules decide). */
    val lineBreakMargin: Double = -8.0,
    /** Total log-prob gain needed to write numbers as digits. */
    val numberMargin: Double = 0.0,
    /** A single number word without a unit as digits ("forty" -> "40") needs this total gain. */
    val numberSingleWordMargin: Double = 5.0,
    /** Sentence-break veto. This is the log-prob gain needed to join a break the punctuation stage added mid-utterance (Infinity turns it off). */
    val breakMargin: Double = 3.0,
    /** At most this many added breaks are scored per transcript. */
    val maxBreaks: Int = 6,
    // runtime (not part of rescore.py)
    val threads: Int = 2,
    /** Let ggml repack the weights for its fastest CPU kernels (faster, but weights then use anonymous memory). */
    val repack: Boolean = true,
    /** Longer transcripts are corrected in chunks of whole sentences of about this many characters. */
    val maxChars: Int = 1500,
) {
    companion object {
        /** Parses a package's config JSON. Unknown keys are ignored and missing ones keep their defaults. */
        fun parse(json: String): ContextRescoreConfig {
            val o: JsonObject = Json.parseToJsonElement(json).jsonObject
            val d = ContextRescoreConfig()
            fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
            fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull
            return ContextRescoreConfig(
                prompt = (o["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: d.prompt,
                surprise = num("surprise") ?: d.surprise,
                maxWords = int("max_words") ?: d.maxWords,
                k = int("k") ?: d.k,
                probeMargin = num("probe_margin") ?: d.probeMargin,
                tailWords = int("tail_words") ?: d.tailWords,
                wordMargin = num("word_margin") ?: d.wordMargin,
                qMargin = num("q_margin") ?: d.qMargin,
                tagMargin = num("tag_margin") ?: d.tagMargin,
                followMargin = num("follow_margin") ?: d.followMargin,
                imperativeMargin = num("imperative_margin") ?: d.imperativeMargin,
                tagWords = (o["tag_words"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }?.toSet()
                    ?: d.tagWords,
                maxExtraSlots = int("max_extra_slots") ?: d.maxExtraSlots,
                boundaryMargin = num("boundary_margin") ?: d.boundaryMargin,
                splitSurprise = num("split_surprise") ?: d.splitSurprise,
                nameMargin = num("name_margin") ?: d.nameMargin,
                nameSwapMargin = num("name_swap_margin") ?: d.nameSwapMargin,
                caseMargin = num("case_margin") ?: d.caseMargin,
                fillerMargin = num("filler_margin") ?: d.fillerMargin,
                repeatMargin = num("repeat_margin") ?: d.repeatMargin,
                selfCorrectionMargin = num("self_correction_margin") ?: d.selfCorrectionMargin,
                selfCorrectionParallelMargin = num("self_correction_parallel_margin") ?: d.selfCorrectionParallelMargin,
                scratchMargin = num("scratch_margin") ?: d.scratchMargin,
                commandMargin = num("command_margin") ?: d.commandMargin,
                lineBreakMargin = num("line_break_margin") ?: d.lineBreakMargin,
                numberMargin = num("number_margin") ?: d.numberMargin,
                numberSingleWordMargin = num("number_single_word_margin") ?: d.numberSingleWordMargin,
                breakMargin = num("break_margin") ?: d.breakMargin,
                maxBreaks = int("max_breaks") ?: d.maxBreaks,
                threads = int("threads") ?: d.threads,
                repack = (o["repack"] as? JsonPrimitive)?.booleanOrNull ?: d.repack,
                maxChars = int("max_chars") ?: d.maxChars,
            )
        }
    }
}

/**
 * The context stage, where a small LM judges the transcript (port of tools/context-bench/rescore.py,
 * extended with the options of [SmartCorrectionOptions]).
 *
 * No generation. Code proposes candidate edits, the LM scores them, and an edit is accepted
 * only if it beats the unchanged text by a margin. The transcript is scored once (per-token
 * log-probs). Words the LM finds surprising get sound-alike candidates from the [lexicon]
 * (plus word-boundary, personal-name and capitalization candidates), and each candidate is scored
 * by re-decoding only the tokens after the change. Question marks come from log P("?") vs
 * log P(".") at sentence ends (and vs "," after tag words like "right"). Fillers, self-corrections,
 * spoken commands and numbers are rule-proposed span edits judged the same way, before the word
 * pass. Everything then goes through [TranscriptGuard], which only lets the enabled kinds of
 * edits through.
 */
class ContextRescorer(
    lm: ContextLm,
    private val lexicon: SoundAlikeLexicon,
    baseConfig: ContextRescoreConfig = ContextRescoreConfig(),
    val options: SmartCorrectionOptions = SmartCorrectionOptions(),
    personal: PersonalDictionary = PersonalDictionary.EMPTY,
) {
    /** [baseConfig] with the margins moved by the options' sensitivity settings. */
    val config: ContextRescoreConfig = options.sensitivity.adjust(baseConfig)
    private val scorer = LmScorer(lm, config.prompt)
    private val names: PersonalNames by lazy {
        if (options.names) PersonalNames.from(personal, lexicon) else PersonalNames.EMPTY
    }

    class Edit(val start: Int, val end: Int, val replacement: String, val kind: String, val delta: Double)

    class Result(
        val input: String,
        /** The text with all accepted edits, before the guard. */
        val candidate: String,
        /** The final text (the guard's output). */
        val text: String,
        val decisions: List<TranscriptGuard.Decision>,
        /** (original, replacement, kind, delta) of each edit, like rescore.py's "edits". */
        val edits: List<Edit>,
        val editSources: List<String>,
        val nCandidateWords: Int,
        /** Milliseconds of each LM call. */
        val timings: List<Double>,
        val surprisal: List<Pair<String, Double>>,
        /** Every scored candidate of the option stages (for tuning tools). */
        val trace: List<String> = emptyList(),
    )

    private val sentenceEnd = Regex("[.?!](\\s|$)|\\n")
    private val tailRegex = Regex("([^\\s]+[ \\t]+){0,${config.tailWords}}")
    private val questionLower = Regex("[?] ([a-z])")
    private val tokenRegexForLeft = Regex("[A-Z][a-z]+")
    private val tagWord = Regex("[A-Za-z']+")
    private val follower = Regex("(And|Or|Plus) ")
    /** The first word of a sentence, unless a comma follows it ("Wait, so you paid?" is not a command). */
    private val imperativeStart = Regex("^\\s*([A-Za-z']+)(?![A-Za-z',])")

    private fun matchCase(src: String, dst: String): String =
        if (src.isNotEmpty() && src[0].isUpperCase()) dst.substring(0, 1).uppercase() + dst.substring(1) else dst

    /** Lexicon phrases are lowercase, so this restores "I". */
    private fun fixI(phrase: String): String = phrase.split(' ').joinToString(" ") { w ->
        if (TranscriptGuard.isPronounI(w)) "I" + w.substring(1) else w
    }

    private fun apply(t0: String, eds: List<Edit>): String {
        var t = t0
        for (e in eds.sortedBy { -it.start }) t = t.substring(0, e.start) + e.replacement + t.substring(e.end)
        return questionLower.replace(t) { m -> "? " + m.groupValues[1].uppercase() }
    }

    /** End of the scoring window for a change ending at [b], which is the end of its sentence plus a few words. */
    private fun windowEnd(text: String, b: Int): Int {
        val m = sentenceEnd.find(text.substring(b))
        var end = b + (m?.range?.last?.plus(1) ?: (text.length - b))
        val tail = tailRegex.matchAt(text.substring(end), 0)
        end += tail?.value?.length ?: 0
        return minOf(end, text.length)
    }

    // ------------------------------------------------------------------------------------
    // Sentence-break veto

    /**
     * Joins sentence breaks (". Word") the punctuation stage put inside one of the ASR's
     * utterances ("in a manner. That is easier ..." -> "in a manner that is easier ..."), when the
     * LM prefers the text without the break by [ContextRescoreConfig.breakMargin]. Breaks that are
     * in the ASR's own text (a pause, or the voice model's marks) are never touched.
     */
    private var breakDeferred = false

    private fun breakVeto(
        text: String, focus: Int, segments: List<String>, decisions: MutableList<TranscriptGuard.Decision>,
        edits: MutableList<Edit>, sources: MutableList<String>, trace: MutableList<String>, checkpoint: () -> Unit,
    ): String {
        breakDeferred = false
        if (!options.any || config.breakMargin.isInfinite() || segments.isEmpty() || !lexicon.hasCaseData) return text
        val tok = TranscriptGuard.tokenize(text)
        val words = tok.words
        val gaps = tok.gaps
        val spans = ArrayList<IntArray>()
        var pos = gaps[0].length
        for ((k, w) in words.withIndex()) {
            spans.add(intArrayOf(pos, pos + w.length))
            pos += w.length + gaps[k + 1].length
        }
        val added = SpokenEdits.addedBreaks(text, segments)
        if (added.isEmpty()) return text
        class Cand(val k: Int, val start: Int, val end: Int, val replacements: List<String>, val sentence: Int) {
            var replacement = replacements[0]
        }
        val cands = ArrayList<Cand>()
        var sentence = 0
        for (k in 1 until words.size) {
            if (TranscriptGuard.punct(gaps[k]).any { it in ".?!" } || '\n' in gaps[k]) sentence++
            if (k !in added || spans[k][0] < focus || gaps[k] != ". ") continue
            val w = words[k]
            val nw = TranscriptGuard.normWord(w)
            if (nw in INTERJECTIONS) continue
            // "Who wants to go first?" asks a question of its own
            if (nw in RELATIVE_STARTS) {
                var e = k + 1
                while (e < words.size && TranscriptGuard.punct(gaps[e]).none { it in ".?!" } && '\n' !in gaps[e]) e++
                if ('?' in gaps[e]) continue
            }
            // a plain capitalized common word loses its capital, but a name, "I" or an acronym keeps it
            val plainCap = w.length >= 2 && w[0].isUpperCase() && w.drop(1).all { !it.isLetter() || it.isLowerCase() }
            val lower = plainCap && (nw in RELATIVE_STARTS || lexicon.isLowercaseWord(w)) && !TranscriptGuard.isPronounI(w) && !(options.names && names.contains(w))
            val joined = if (lower) w.replaceFirstChar { it.lowercase() } else w
            // a relative clause the break cut off may also be a comma's ("to Marcus, who locked ...")
            val reps = if (nw in RELATIVE_STARTS) listOf(" $joined", ", $joined") else listOf(" $joined")
            cands.add(Cand(k, spans[k - 1][1], spans[k][1], reps, sentence))
        }
        if (cands.isEmpty()) return text
        val chosen = cands.take(config.maxBreaks)
        ensureBase(text, checkpoint)
        val variants = ArrayList<String>()
        val first = ArrayList<Int>()
        for (c in chosen) {
            val end = maxOf(windowEnd(text, c.end), c.end)
            first.add(variants.size)
            variants.add(text.substring(0, end))
            for (r in c.replacements) variants.add(text.substring(0, c.start) + r + text.substring(c.end, end))
        }
        checkpoint()
        val rr = scorer.score(variants)
        val ok = ArrayList<Pair<Cand, Double>>()
        for ((i, c) in chosen.withIndex()) {
            val f = first[i]
            val best = c.replacements.indices.maxBy { rr[f + 1 + it] }
            c.replacement = c.replacements[best]
            val delta = rr[f + 1 + best] - rr[f]
            trace.add("break (%d%s): %.2f".format(c.k, if (best > 0) " comma" else "", delta))
            if (delta <= config.breakMargin) continue
            val d = TranscriptGuard.checkBreakJoin(text, c.start, c.end, c.replacement)
            decisions.add(d)
            if (d.accepted) ok.add(c to delta)
        }
        // each join was judged with its neighbouring breaks in place, so of two joins around one sentence the stronger wins
        val applied = ArrayList<Pair<Cand, Double>>()
        for (p in ok.sortedByDescending { it.second }) if (applied.none { kotlin.math.abs(it.first.sentence - p.first.sentence) <= 1 }) applied.add(p)
        breakDeferred = ok.size > applied.size
        var t = text
        for ((c, delta) in applied.sortedByDescending { it.first.start }) {
            edits.add(Edit(c.start, c.end, c.replacement, "break", delta))
            sources.add(text.substring(c.start, c.end))
            t = t.substring(0, c.start) + c.replacement + t.substring(c.end)
        }
        return t
    }

    // ------------------------------------------------------------------------------------
    // Option stages (rule-proposed span edits judged by the LM)

    private var baseText: String? = null

    private fun ensureBase(t: String, checkpoint: () -> Unit) {
        if (baseText == t) return
        checkpoint()
        scorer.detail(t, ALTS)
        baseText = t
    }

    /** The log-prob gain an edit needs (see [ContextRescoreConfig]). */
    private fun margin(t: String, e: SpokenEdits.SpanEdit) = when (e.kind) {
        SpokenEdits.Kind.FILLER -> config.fillerMargin
        SpokenEdits.Kind.REPEAT -> config.repeatMargin
        SpokenEdits.Kind.SELF_CORRECTION -> if (e.parallel) config.selfCorrectionParallelMargin else config.selfCorrectionMargin
        SpokenEdits.Kind.SCRATCH, SpokenEdits.Kind.DELETE_WORD -> config.scratchMargin
        SpokenEdits.Kind.QUOTE -> config.commandMargin - options.sensitivity.quoteShift()
        SpokenEdits.Kind.EMOJI -> config.commandMargin - options.sensitivity.emojiShift()
        SpokenEdits.Kind.COMMAND -> if (e.replacement.contains('\n')) config.lineBreakMargin else config.commandMargin
        SpokenEdits.Kind.NUMBER ->
            // a lone number word without a unit ("forty winks", "the eleventh hour") is often
            // better spelled out, so it needs more evidence
            if (TranscriptGuard.tokenize(t.substring(e.start, e.end)).words.size == 1 && e.replacement.all { it.isLetterOrDigit() || it == ',' }) {
                config.numberSingleWordMargin
            } else config.numberMargin
    }

    /**
     * Self-corrections are accepted on the length-normalized score (mean log-prob per token of the
     * edited sentence, since the total always favours deleting words). All alternatives of a group are
     * compared by their total.
     */
    private fun normalized(kind: SpokenEdits.Kind) = kind == SpokenEdits.Kind.SELF_CORRECTION

    /** Score of an edit the rules are certain of (sorts first when edits overlap). */
    private val CERTAIN_SCORE = 1e6

    private class Judged(val edit: SpokenEdits.SpanEdit, val total: Double, val accept: Double)

    /** Scores every group's alternatives against the unchanged text and returns the accepted, non-overlapping edits. */
    private fun judge(
        t: String, groups: List<SpokenEdits.Group>, trace: MutableList<String>, checkpoint: () -> Unit,
    ): List<SpokenEdits.SpanEdit> {
        if (groups.isEmpty()) return emptyList()
        ensureBase(t, checkpoint)
        val variants = ArrayList<String>()
        val index = ArrayList<IntArray>()  // per group, as [orig index, first alt index]
        for (g in groups) {
            val wEnd = maxOf(windowEnd(t, g.end), g.end)
            index.add(intArrayOf(variants.size, variants.size + 1))
            variants.add(t.substring(0, wEnd))
            for (a in g.alternatives) variants.add(t.substring(0, a.start) + a.replacement + t.substring(a.end, wEnd))
        }
        // token index where each group's sentence starts (for the length-normalized score)
        val sentTok = groups.map { g ->
            var s0 = g.start
            while (s0 > 0 && !(t[s0 - 1] == '\n' || (s0 >= 2 && t[s0 - 2] in ".?!" && t[s0 - 1].isWhitespace()))) s0--
            scorer.tokenCount(t.substring(0, s0).trimEnd())
        }
        checkpoint()
        val sc = scorer.scoreDetailed(variants)
        val accepted = ArrayList<Judged>()
        for ((gi, g) in groups.withIndex()) {
            val o = sc[index[gi][0]]
            var best: Judged? = null
            for ((ai, a) in g.alternatives.withIndex()) {
                val v = sc[index[gi][1] + ai]
                var l = 0
                while (l < o.tokens.size && l < v.tokens.size && o.tokens[l] == v.tokens[l]) l++
                var so = 0.0
                for (i in l until o.lps.size) so += o.lps[i]
                var sv = 0.0
                for (i in l until v.lps.size) sv += v.lps[i]
                val total = sv - so
                val no = o.lps.size - l
                val nv = v.lps.size - l
                val mean = if (no > 0 && nv > 0) sv / nv - so / no else total
                // length-normalized over the whole sentence, as the mean log-prob per token from the
                // start of the edited sentence to the end of the window
                val ps = minOf(sentTok[gi], l)
                var po = 0.0
                for (i in ps until o.lps.size) po += o.lps[i]
                var pv = 0.0
                for (i in ps until v.lps.size) pv += v.lps[i]
                val smean = if (o.lps.size > ps && v.lps.size > ps) pv / (v.lps.size - ps) - po / (o.lps.size - ps) else total
                trace.add(
                    "%s '%s' -> '%s': total %.2f mean %.3f smean %.3f".format(
                        g.kind.name.lowercase(), t.substring(a.start, a.end).replace("\n", "\\n"),
                        a.replacement.replace("\n", "\\n"), total, mean, smean
                    )
                )
                if (best == null || total > best.total) best = Judged(a, total, if (normalized(g.kind)) smean else total)
            }
            if (g.certain && best != null) {
                // the rules are sure of the edit, and the LM only picks among its alternatives
                trace.add("%s '%s': certain".format(g.kind.name.lowercase(), t.substring(g.start, g.end).replace("\n", "\\n")))
                accepted.add(Judged(best.edit, CERTAIN_SCORE, CERTAIN_SCORE))
            } else if (best != null && best.accept > margin(t, best.edit)) accepted.add(best)
        }
        // overlapping edits, so the better one wins
        val out = ArrayList<SpokenEdits.SpanEdit>()
        for (j in accepted.sortedByDescending { it.total }) {
            if (out.none { it.start < j.edit.end && j.edit.start < it.end }) out.add(j.edit)
        }
        return out
    }

    /** Applies span edits, and a word that now starts a sentence gets a capital letter. */
    private fun applySpans(t0: String, eds: List<SpokenEdits.SpanEdit>): String {
        var t = t0
        for (e in eds.sortedByDescending { it.start }) {
            t = t.substring(0, e.start) + e.replacement + t.substring(e.end)
            var p = e.start + e.replacement.length
            while (p < t.length && !t[p].isLetterOrDigit()) p++
            if (p < t.length && t[p].isLowerCase()) {
                val before = t.substring(0, p).trimEnd { it == ' ' || it == '\t' }
                val starts = before.isEmpty() || before.last() in ".?!\n"
                if (starts) t = t.substring(0, p) + t[p].uppercaseChar() + t.substring(p + 1)
            }
        }
        return t
    }

    private fun optionStages(
        text: String, focus: Int, segments: List<String>, decisions: MutableList<TranscriptGuard.Decision>,
        edits: MutableList<Edit>, sources: MutableList<String>, trace: MutableList<String>, checkpoint: () -> Unit,
    ): String {
        var t = text
        val stages = listOfNotNull(
            // commands first, because quotes they insert keep the fillers inside them ("she said "um, what?"")
            SpokenEdits.Kind.COMMAND.takeIf { options.commands },
            SpokenEdits.Kind.EMOJI.takeIf { options.emoji },
            SpokenEdits.Kind.QUOTE.takeIf { options.quotes },
            SpokenEdits.Kind.FILLER.takeIf { options.fillers },
            SpokenEdits.Kind.SELF_CORRECTION.takeIf { options.selfCorrections },
            SpokenEdits.Kind.NUMBER.takeIf { options.numbers },
        )
        // commands run again on their own output because two commands in a row ("Dear Sarah comma new
        // paragraph") overlap, so the second one is applied in the next pass
        var commandChanged = true
        val isWord: ((String) -> Boolean)? = if (lexicon.hasCaseData) { x -> lexicon.isLowercaseWord(x) } else null
        for (stage in stages.flatMap { if (it == SpokenEdits.Kind.COMMAND) listOf(it, it, it) else listOf(it) }) {
            if (stage == SpokenEdits.Kind.COMMAND) {
                if (!commandChanged) continue
                commandChanged = false
            }
            val w = SpokenEdits.Words(t)
            if (w.size == 0) break
            val segStarts = if (stage == SpokenEdits.Kind.COMMAND || stage == SpokenEdits.Kind.SELF_CORRECTION) {
                SpokenEdits.segmentStarts(t, segments)
            } else emptySet()
            val groups = when (stage) {
                SpokenEdits.Kind.FILLER -> SpokenEdits.fillerGroups(w, options.fillerList, isWord)
                SpokenEdits.Kind.COMMAND -> SpokenEdits.commandGroups(w, segStarts, lexicon, names, options.commandList)
                SpokenEdits.Kind.SELF_CORRECTION -> SpokenEdits.selfCorrectionGroups(w, segStarts, options.correctionMarkers, isWord)
                SpokenEdits.Kind.NUMBER -> SpokenEdits.numberGroups(w)
                SpokenEdits.Kind.QUOTE -> SpokenEdits.quoteGroups(w, options.quoteTriggers, isWord)
                SpokenEdits.Kind.EMOJI -> SpokenEdits.emojiGroups(w, options.emojiNames)
                else -> emptyList()
            }.filter { it.start >= focus }
            if (groups.isEmpty()) continue
            val accepted = judge(t, groups, trace, checkpoint)
            val ok = accepted.filter { e ->
                val d = TranscriptGuard.checkEdit(t, e, options.commandList, options.correctionMarkers, options.fillerList, options.emojiNames)
                decisions.add(d)
                d.accepted
            }
            for (e in ok) {
                edits.add(Edit(e.start, e.end, e.replacement, e.kind.name.lowercase(), 0.0))
                sources.add(t.substring(e.start, e.end))
            }
            if (ok.isNotEmpty()) {
                t = applySpans(t, ok)
                if (stage == SpokenEdits.Kind.COMMAND) commandChanged = true
            }
        }
        return t
    }

    // ------------------------------------------------------------------------------------
    // Word pass

    /** A spot with candidates beyond the sound-alike words (words [a, b), replacements, kind). */
    private class Slot(val a: Int, val b: Int, val cands: List<String>, val kind: String, val always: Boolean = false)

    /** Names for [nameSplitOk] (lowercase), set while a text is corrected. */
    private var splitNames: Set<String> = emptySet()

    private fun metaCode(w: String) = DoubleMetaphone.encode(w).first

    /** "Marcus" -> ["Mark is", "Mark's"] when "Mark" is a name in [known] (or the personal dictionary) that sounds like its start. */
    private fun nameSplitSlot(w: String, known: Collection<String>): List<String>? {
        if (!w.all { it.isLetter() }) return null
        val lw = w.lowercase()
        if (lw in known.map { it.lowercase() }) return null
        val pool = known + names.allNames()
        for (n in pool) {
            if (n.length < 3 || n.lowercase() == lw || lw[0] != n.lowercase()[0] || kotlin.math.abs(lw.length - n.length) > 3) continue
            if (metaCode(lw) == metaCode(n + "s") || metaCode(lw) == metaCode(n + "is")) return listOf("$n is", "$n's")
        }
        return null
    }

    private fun nameSplitOk(src: List<String>, dst: List<String>): Boolean {
        if (src.size != 1) return false
        val w = src[0]
        val d = dst.map { TranscriptGuard.normWord(it) }
        val name = when {
            d.size == 2 && d[1] == "is" -> d[0]
            d.size == 1 && d[0].endsWith("'s") -> d[0].removeSuffix("'s")
            else -> return false
        }
        return name in splitNames && metaCode(w.lowercase()).let { it == metaCode(name + "s") || it == metaCode(name + "is") }
    }

    private fun extraSlots(words: List<String>, gaps: List<String>, sentenceStart: (Int) -> Boolean, segStarts: Set<Int>): List<Slot> {
        val out = ArrayList<Slot>()
        fun plainGap(k: Int) = gaps[k] == " "
        if (options.soundAlike && lexicon.hasBoundaries) {
            for (k in words.indices) {
                // splits ("into" -> "in to") and merges ("every day" -> "everyday")
                val split = lexicon.boundaryCandidates(words[k]).map { fixI(matchCase(words[k], it)) }
                if (split.isNotEmpty()) out.add(Slot(k, k + 1, split, "split"))
                // (a sentence's first "A" is the article, so "A cent short." is not "Ascent short.")
                if (k + 1 < words.size && plainGap(k + 1) && !(sentenceStart(k) && TranscriptGuard.normWord(words[k]) == "a")) {
                    val merge = lexicon.boundaryCandidates(words[k] + " " + words[k + 1]).map { fixI(matchCase(words[k], it)) }
                    if (merge.isNotEmpty()) out.add(Slot(k, k + 2, merge, "boundary"))
                }
            }
        }
        if (options.soundAlike) {
            // a name heard as a longer word that swallowed the next one ("Mark is" -> "Marcus"), only for a
            // name already used before it in the text (the field's earlier text, the dictation) or in the
            // personal dictionary, and the LM decides
            val known = LinkedHashSet<String>()
            for (k in words.indices) {
                val w = words[k]
                if (w.length >= 4) {
                    val ws = nameSplitSlot(w, known)
                    if (ws != null) out.add(Slot(k, k + 1, ws, "namesplit"))
                }
                if (w.length >= 3 && w[0].isUpperCase() && w.drop(1).all { !it.isLetter() || it.isLowerCase() } &&
                    TranscriptGuard.normWord(w) !in TranscriptGuard.FUNCTION_LOCK && TranscriptGuard.normWord(w) !in CLAUSE_OPENERS) known.add(w)
            }
            splitNames = (known + names.allNames()).map { it.lowercase() }.toSet()
        }
        if (options.names) {
            if (!names.isEmpty) {
                for (k in words.indices) {
                    for (n in 1..3) {
                        if (k + n > words.size || (1 until n).any { !plainGap(k + it) }) break
                        // an exclamation is not part of a misheard name ("Oh darn" is not "Hadorn")
                        if (TranscriptGuard.normWord(words[k + n - 1]) in INTERJECTIONS) break
                        val c = names.soundAlike(words.subList(k, k + n))
                        if (c.isEmpty()) continue
                        // one capitalized word that is not a common word ("Reese") is the ASR's
                        // spelling of a name, and the personal dictionary's spelling is likely meant
                        val w = words[k]
                        val swap = n == 1 && w[0].isUpperCase() && w.drop(1).all { !it.isLetter() || it.isLowerCase() } &&
                            !lexicon.isLowercaseWord(w) && TranscriptGuard.normWord(w) !in TranscriptGuard.FUNCTION_LOCK &&
                            !TranscriptGuard.isPronounI(w)
                        out.add(Slot(k, k + n, c, if (swap) "name-swap" else "name", always = swap))
                    }
                }
            }
            for (k in words.indices) {
                val w = words[k]
                if (sentenceStart(k) || TranscriptGuard.isPronounI(w) || w.any { it.isDigit() } || w.length < 2) continue
                if (w.all { !it.isLetter() || it.isLowerCase() }) {
                    // a name / proper noun written lowercase ("jordan" -> "Jordan")
                    val cased = names.name(w) ?: lexicon.capitalized(w)
                    // (only the first letter, so "wifi" stays and doesn't become "WiFi")
                    if (cased != null && cased != w && cased.drop(1) == w.drop(1)) out.add(Slot(k, k + 1, listOf(cased), "case"))
                } else if (k in segStarts && TranscriptGuard.normWord(w) !in CLAUSE_OPENERS && w[0].isUpperCase() && w.drop(1).all { !it.isLetter() || it.isLowerCase() } && !names.contains(w) &&
                    TranscriptGuard.normWord(w) !in TranscriptGuard.FUNCTION_LOCK && lexicon.isLowercaseWord(w)) {
                    // (never "The", "A", "In", ... because capitalized function words mid-sentence are titles)
                    // a common word capitalized mid-sentence where the ASR started a new utterance
                    // ("the | Toaster oven"), but elsewhere the capital is meant ("the song Yesterday")
                    out.add(Slot(k, k + 1, listOf(w.lowercase()), "case", always = true))
                }
            }
        }
        return out
    }

    private fun policy() = TranscriptGuard.Policy(
        boundary = { src, dst ->
            options.soundAlike && (lexicon.boundaryCandidates(src.joinToString(" ")).contains(dst.joinToString(" ") { TranscriptGuard.normWord(it) }) ||
                nameSplitOk(src, dst))
        },
        names = if (options.names && !names.isEmpty) names else null,
        caseChanges = options.names,
        strictCase = true,
    )

    /**
     * Corrects [text]. Only words/punctuation at or after char offset [focus] may change.
     * [segments] are the ASR's utterances (pauses between them), if known. [checkpoint] runs
     * between LM calls (e.g. to throw on cancellation).
     */
    fun correct(text: String, focus: Int = 0, segments: List<String> = emptyList(), checkpoint: () -> Unit = {}): Result {
        val timingStart = scorer.timings.size
        val decisions0 = ArrayList<TranscriptGuard.Decision>()
        val edits0 = ArrayList<Edit>()
        val sources0 = ArrayList<String>()
        val trace = ArrayList<String>()
        var joined = breakVeto(text, focus, segments, decisions0, edits0, sources0, trace, checkpoint)
        // joins next to each other were left for a second look at the joined text
        if (breakDeferred) joined = breakVeto(joined, focus, segments, decisions0, edits0, sources0, trace, checkpoint)
        val t = optionStages(joined, focus, segments, decisions0, edits0, sources0, trace, checkpoint)
        val r = wordPass(t, focus, segments, checkpoint, trace)
        return Result(
            input = text,
            candidate = r.candidate,
            text = r.text,
            decisions = decisions0 + r.decisions,
            edits = edits0 + r.edits,
            editSources = sources0 + r.editSources,
            nCandidateWords = r.nCandidateWords,
            timings = scorer.timings.subList(timingStart, scorer.timings.size).toList(),
            surprisal = r.surprisal,
            trace = trace,
        )
    }

    private fun wordPass(text: String, focus: Int, segments: List<String>, checkpoint: () -> Unit, trace: MutableList<String>): Result {
        val wordsOn = options.soundAlike
        if (!wordsOn && !options.names && !options.questions) {
            return Result(text, text, text, emptyList(), emptyList(), emptyList(), 0, emptyList(), emptyList())
        }
        val tok = TranscriptGuard.tokenize(text)
        val words = tok.words
        val gaps = tok.gaps
        val spans = ArrayList<IntArray>()
        run {
            var pos = gaps[0].length
            for ((k, w) in words.withIndex()) {
                spans.add(intArrayOf(pos, pos + w.length))
                pos += w.length + gaps[k + 1].length
            }
        }
        fun sentenceStart(k: Int) = k == 0 || TranscriptGuard.punct(gaps[k]).any { it in ".?!" } || '\n' in gaps[k]
        val allCands = LinkedHashMap<Int, List<String>>()
        val leftNames: Set<String> = if (focus > 0) tokenRegexForLeft.findAll(text.substring(0, focus)).map { it.value }.toSet() else emptySet()
        if (wordsOn) {
            for (k in words.indices) {
                // interjections and fillers are never "misheard words" ("Uh" -> "Ah", "Uh-oh" -> "Yo")
                if (TranscriptGuard.normWord(words[k]) in INTERJECTIONS) continue
                // a sentence opener ("See, I tried", "So I tried", "Well, ...") is fine either way,
                // because the LM's preference between them is not a sign of a misheard word
                if (TranscriptGuard.normWord(words[k]) in SENTENCE_OPENERS &&
                    (sentenceStart(k) || TranscriptGuard.punct(gaps[k]).contains(','))) continue
                // an acronym ("ETA", "RSVP") is never a misheard word
                if (words[k].count { it.isLetter() } >= 2 && words[k].none { it.isLowerCase() }) continue
                // spoken numbers are heard reliably, and a swap changes a time or an amount ("at nine" -> "at noon")
                if (NumberWords.isSpokenNumber(words[k])) continue
                // the user's own words (personal dictionary) are kept as they are
                if (options.names && names.contains(words[k])) continue
                // a question word makes the sentence a question even when the ASR ended it with "."
                // ("Who's driving." -> "He's driving.")
                if (TranscriptGuard.normWord(words[k]) in QUESTION_WORDS) continue
                // a name ("I think Reese wants ...", "Owen and I ...") that is capitalized and not a common
                // lowercase word is left to the names option
                val w = words[k]
                if (lexicon.hasCaseData && w[0].isUpperCase() && !TranscriptGuard.isPronounI(w) &&
                    w.drop(1).all { !it.isLetter() || it.isLowerCase() } && !lexicon.isLowercaseWord(w)) continue
                // ... and neither is one that replaces a word with an opener ("Sew the button" -> "So the button")
                val opensSentence = sentenceStart(k) || TranscriptGuard.punct(gaps[k]).contains(',')
                val c = lexicon.candidates(words[k])
                    .filter { it !in INTERJECTIONS && !TranscriptGuard.isTenseChange(words[k], it) }
                    // nor is a word swapped for a number ("To new beginnings" -> "Two")
                    .filter { !NumberWords.isSpokenNumber(it) }
                    .filter { !opensSentence || it !in SENTENCE_OPENERS }
                    // a word before a pause or the end is not replaced by one that cannot end a
                    // clause ("We're going too." -> "to.")
                    .filter { k + 1 < gaps.size && TranscriptGuard.punct(gaps[k + 1]).isEmpty() && k + 1 < words.size || it !in NO_CLAUSE_END }
                    // only sound-alikes the voice model could have confused it with (see [Homophones])
                    .filter { !Homophones.isLoaded || Homophones.same(words[k], it) || Homophones.confusable(words[k], it) }
                    .map { matchCase(words[k], it) }
                    // a name already written in the text before the dictation keeps its capital
                    .map { c -> if (focus > 0 && c[0].isLowerCase() && leftNames.contains(c.replaceFirstChar { x -> x.uppercase() })) c.replaceFirstChar { x -> x.uppercase() } else c }
                if (c.isNotEmpty() && spans[k][0] >= focus) allCands[k] = c
            }
        }
        val segStarts = if (options.names) SpokenEdits.segmentStarts(text, segments) else emptySet()
        val slots = extraSlots(words, gaps, ::sentenceStart, segStarts).filter { spans[it.a][0] >= focus }
        val probeKeys = allCands.keys.sorted()
        checkpoint()
        val v = scorer.detail(
            text, ALTS,
            probeKeys.map { spans[it][0] to allCands.getValue(it) } + slots.map { spans[it.a][0] to it.cands }
        )
        baseText = text
        var pieces = v.pieces.size

        // word surprisal = sum of log-probs of tokens starting inside the word
        val surpr = DoubleArray(words.size)
        for (i in 0 until pieces) {
            val c = v.charOffset(i)
            for (k in spans.indices) {
                if (spans[k][0] <= c && c < spans[k][1]) {
                    surpr[k] += v.lps[i].toDouble()
                    break
                }
            }
        }

        val edits = ArrayList<Edit>()
        val sources = ArrayList<String>()
        // first-token probe that drops candidates the LM already finds much less likely than the
        // original word at that position (left context only)
        val kept = LinkedHashMap<Int, List<String>>()
        for ((idx, k) in probeKeys.withIndex()) {
            val pr = v.probes.getOrNull(idx) ?: continue
            if (pr.isEmpty()) continue
            val orig = pr[pr.size - 1].toDouble()
            val cands = allCands.getValue(k)
            val ok = cands.indices.filter { it < pr.size - 1 && pr[it].toDouble() >= orig - config.probeMargin }.map { cands[it] }
            if (ok.isNotEmpty()) kept[k] = ok.take(config.k)
        }
        val candWords = kept.keys.filter { surpr[it] < config.surprise }.sortedBy { surpr[it] }.take(config.maxWords)
        for (k in candWords) {
            val a = spans[k][0]
            val b = spans[k][1]
            val end = windowEnd(text, b)
            val cands = kept.getValue(k)
            val variants = listOf(text.substring(0, end)) + cands.map { text.substring(0, a) + it + text.substring(b, end) }
            checkpoint()
            val rr = scorer.score(variants)
            val lp0 = rr[0]
            // the best candidate for its kind, since a near sound-alike needs more than an exact homophone
            // ("I new it" -> "knew", not "know")
            fun need(i: Int) = if (Homophones.isLoaded && !Homophones.same(words[k], cands[i - 1])) config.nearMargin - config.homophoneMargin else 0.0
            var best = 1
            for (i in 2 until variants.size) if (rr[i] - need(i) > rr[best] - need(best)) best = i
            val delta = rr[best] - lp0
            if (delta > config.wordMargin) {
                edits.add(Edit(a, b, cands[best - 1], "word", delta))
                sources.add(text.substring(a, b))
            }
        }
        // context must win, meaning the gain, less how much more common the replacement is anyway (the LM's
        // preference for a frequent word is no sign that the voice model misheard), must beat a
        // margin, and a near sound-alike ("pool" -> "poll") was heard differently and needs far more
        if (edits.isNotEmpty()) {
            val pv = ArrayList<String>()
            for ((i, e) in edits.withIndex()) for (f in PRIOR_FRAMES) {
                pv.add(f + sources[i].lowercase())
                pv.add(f + e.replacement.lowercase())
            }
            checkpoint()
            val pr = scorer.score(pv)
            val keep = ArrayList<Int>()
            for ((i, e) in edits.withIndex()) {
                var d = 0.0
                for (j in PRIOR_FRAMES.indices) d += pr[2 * (i * PRIOR_FRAMES.size + j) + 1] - pr[2 * (i * PRIOR_FRAMES.size + j)]
                d /= PRIOR_FRAMES.size
                val adj = e.delta - maxOf(0.0, d)
                val exact = Homophones.same(sources[i], e.replacement)
                trace.add("prior '%s' -> '%s': delta %.2f prior %.2f adjusted %.2f %s".format(
                    sources[i], e.replacement, e.delta, d, adj,
                    if (exact) "homophone" else if (Homophones.confusable(sources[i], e.replacement)) "near" else "distant"))
                // a sound-alike that differs in a clearly different sound ("fan" -> "phone") the voice
                // model did not mishear, so it is never swapped
                val margin = when {
                    exact -> config.homophoneMargin
                    Homophones.confusable(sources[i], e.replacement) -> config.nearMargin
                    else -> Double.POSITIVE_INFINITY
                }
                // with nothing before it in its sentence ("Pare the apples.", "The doe ran.", "A cent
                // short."), the LM only knows which word usually opens a sentence, so more is needed
                val k = spans.indexOfFirst { it[0] == e.start }
                val opening = k >= 0 && (sentenceStart(k) || (k >= 1 && sentenceStart(k - 1) && TranscriptGuard.normWord(words[k - 1]) in OPENING_DETERMINERS))
                if (adj > margin + (if (opening) config.openingMargin else 0.0)) keep.add(i)
            }
            if (keep.size < edits.size) {
                val ke = keep.map { edits[it] }
                val ks = keep.map { sources[it] }
                edits.clear(); edits.addAll(ke)
                sources.clear(); sources.addAll(ks)
            }
        }
        // fixes of neighbouring words were each judged with the other word unchanged ("over
        // their" -> "ever there"), so the weaker one must also win next to the stronger one
        if (edits.size > 1) {
            val kept = ArrayList<Edit>()
            for (e in edits.sortedByDescending { it.delta }) {
                val next = kept.any { x ->
                    val lo = minOf(x.end, e.end)
                    val hi = maxOf(x.start, e.start)
                    lo <= hi && text.substring(lo, hi).none { it.isLetterOrDigit() }
                }
                if (next) {
                    checkpoint()
                    val rr = scorer.score(listOf(apply(text, kept), apply(text, kept + e)))
                    trace.add("word '%s' -> '%s' next to another fix: %.2f".format(text.substring(e.start, e.end), e.replacement, rr[1] - rr[0]))
                    if (rr[1] - rr[0] <= config.wordMargin) continue
                }
                kept.add(e)
            }
            if (kept.size < edits.size) {
                val keptSources = edits.indices.filter { edits[it] in kept }.map { sources[it] }
                edits.retainAll(kept.toSet())
                sources.clear()
                sources.addAll(keptSources)
            }
        }

        // word boundaries, names and capitalization use the same probe + surprise filters, all scored in one batch
        if (slots.isNotEmpty()) {
            class Scoring(val slot: Slot, val cands: List<String>, val sur: Double)
            val scorings = ArrayList<Scoring>()
            for ((si, s) in slots.withIndex()) {
                val pr = v.probes.getOrNull(probeKeys.size + si) ?: continue
                if (pr.isEmpty()) continue
                val orig = pr[pr.size - 1].toDouble()
                // a name spelling the personal dictionary has is scored even if its first token is unlikely
                val ok = if (s.kind == "name-swap") s.cands else
                    s.cands.indices.filter { it < pr.size - 1 && pr[it].toDouble() >= orig - config.probeMargin }.map { s.cands[it] }
                if (ok.isEmpty()) continue
                val sur = (s.a until s.b).minOf { surpr[it] }
                // splitting a common compound ("everything") is rarely the fix, so only very surprising words
                val threshold = if (s.kind == "split") config.splitSurprise else config.surprise
                if (sur < threshold || s.always) scorings.add(Scoring(s, ok.take(config.k), if (s.always) Double.NEGATIVE_INFINITY else sur))
            }
            val chosen = scorings.sortedBy { it.sur }.take(config.maxExtraSlots)
            if (chosen.isNotEmpty()) {
                val variants = ArrayList<String>()
                val first = ArrayList<Int>()
                for (c in chosen) {
                    val a = spans[c.slot.a][0]
                    val b = spans[c.slot.b - 1][1]
                    val end = windowEnd(text, b)
                    first.add(variants.size)
                    variants.add(text.substring(0, end))
                    for (cand in c.cands) variants.add(text.substring(0, a) + cand + text.substring(b, end))
                }
                checkpoint()
                val rr = scorer.score(variants)
                for ((ci, c) in chosen.withIndex()) {
                    val o = first[ci]
                    var best = 1
                    for (i in 2..c.cands.size) if (rr[o + i] > rr[o + best]) best = i
                    val delta = rr[o + best] - rr[o]
                    val a = spans[c.slot.a][0]
                    val b = spans[c.slot.b - 1][1]
                    for ((i, cand) in c.cands.withIndex()) {
                        trace.add("%s '%s' -> '%s': %.2f".format(c.slot.kind, text.substring(a, b), cand, rr[o + 1 + i] - rr[o]))
                    }
                    val margin = when (c.slot.kind) {
                        "boundary", "split", "namesplit" -> config.boundaryMargin
                        "name" -> config.nameMargin
                        "name-swap" -> config.nameSwapMargin
                        else -> config.caseMargin
                    }
                    if (delta > margin) {
                        val e = Edit(a, b, c.cands[best - 1], if (c.slot.kind == "split" || c.slot.kind == "namesplit") "boundary" else c.slot.kind, delta)
                        // overlapping or right next to a sound-alike word fix ("lives a lone"), the
                        // larger gain wins (each was judged with the other unchanged)
                        val clash = edits.filter { x ->
                            val lo = minOf(x.end, e.end)
                            val hi = maxOf(x.start, e.start)
                            (x.start < e.end && e.start < x.end) || (lo <= hi && text.substring(lo, hi).none { it.isLetterOrDigit() })
                        }
                        if (clash.all { it.delta < e.delta }) {
                            for (x in clash) {
                                val i = edits.indexOf(x)
                                edits.removeAt(i)
                                sources.removeAt(i)
                            }
                            edits.add(e)
                            sources.add(text.substring(a, b))
                        }
                    }
                }
            }
        }

        val qEdits = ArrayList<Edit>()
        var text2 = text
        if (options.questions) {
            // tag questions ("..., right, and" -> "right? And") compare P("?") vs P(",") after tag words
            for (i in 0 until pieces) {
                if (v.strippedPiece(i) != ",") continue
                val c = v.charOffset(i)
                if (c <= focus || c >= text.length || text[c] != ',') continue
                val prev = tagWord.findAll(text.substring(0, c)).lastOrNull()?.value
                if (prev != null && prev.lowercase() in config.tagWords) {
                    val delta = v.alts[i][0].toDouble() - v.alts[i][2].toDouble()
                    if (delta > config.tagMargin) {
                        edits.add(Edit(c, c + 1, "?", "tag", delta))
                        sources.add(",")
                    }
                }
            }

            // the transcript ends with the comma the ASR wrote where you stopped ("... for some
            // reason,"), which is a sentence end. Not after a greeting or sign-off ("Hi Sarah,", "Thanks again,")
            val lastMark = text.indexOfLast { !it.isWhitespace() }
            if (lastMark > focus && text[lastMark] in ",;" && edits.none { it.start == lastMark }) {
                val sStart = maxOf(text.lastIndexOfAny(charArrayOf('.', '?', '!', '\n'), lastMark - 1) + 1, 0)
                val last = TranscriptGuard.tokenize(text.substring(sStart, lastMark)).words.map { TranscriptGuard.normWord(it) }
                if (last.size >= 5 && last[0] !in LETTER_OPENERS) {
                    edits.add(Edit(lastMark, lastMark + 1, ".", "end", 0.0))
                    sources.add(text[lastMark].toString())
                }
            }

            // sentence-final questions compare P("?") vs P(".") at each sentence end. If words or tags
            // changed, re-score the edited text first so the decision sees the corrected sentence.
            text2 = apply(text, edits)
            var d = v
            if (text2 != text) {
                checkpoint()
                d = scorer.detail(text2, ALTS)
                baseText = text2
                pieces = d.pieces.size
            }
            for (i in 0 until pieces) {
                if (d.strippedPiece(i) != ".") continue
                val c = d.charOffset(i)
                if (c <= focus || c >= text2.length || text2[c] != '.') continue
                val delta = d.alts[i][0].toDouble() - d.alts[i][1].toDouble()
                // follow-on fragment after a question ("right? And bowls and spoons and things.")
                val sentStart = maxOf(text2.lastIndexOf(". ", c - 2), text2.lastIndexOf("? ", c - 2), text2.lastIndexOf("! ", c - 2))
                val follow = sentStart >= 0 && text2[sentStart] == '?' && follower.matchAt(text2, sentStart + 2) != null
                // a sentence that starts with a command verb ("Ask her what she wants.", "Let me
                // know whether ...") is rarely a question
                val s0 = maxOf(if (sentStart >= 0) sentStart + 2 else 0, text2.lastIndexOf('\n', c - 1) + 1)
                val first = imperativeStart.find(text2.substring(s0, c))
                // ... or with "I wonder", "I was wondering", "I'm not sure", ... (an indirect question)
                val imperative = (first != null && first.groupValues[1].lowercase() in IMPERATIVE_VERBS) ||
                    INDIRECT_QUESTION.containsMatchIn(text2.substring(s0, c)) || !questionShaped(text2.substring(s0, c))
                val needed = when {
                    follow -> config.followMargin
                    imperative -> config.imperativeMargin
                    else -> config.qMargin
                }
                if (delta > needed) {
                    qEdits.add(Edit(c, c + 1, "?", "q", delta))
                }
            }
        } else {
            text2 = apply(text, edits)
        }
        val out = apply(text2, qEdits)
        val g = TranscriptGuard.guard(text, out, policy())
        return Result(
            input = text,
            candidate = out,
            text = g.text,
            decisions = g.decisions,
            edits = edits + qEdits,
            editSources = sources + qEdits.map { "." },
            nCandidateWords = candWords.size,
            timings = emptyList(),
            surprisal = words.indices.filter { surpr[it] < config.surprise }.map { words[it] to surpr[it] },
        )
    }

    /**
     * Whole-transcript entry point used by the app. It corrects [text] in chunks of whole sentences
     * of at most [ContextRescoreConfig.maxChars] characters (one chunk for normal dictation).
     */
    fun correctAll(text: String, segments: List<String> = emptyList(), checkpoint: () -> Unit = {}, left: String = ""): String {
        if (text.isBlank()) return text
        val chunks = splitChunks(text, config.maxChars)
        return chunks.withIndex().joinToString("") { (ci, chunk) ->
            val lead = chunk.takeWhile { it.isWhitespace() }
            val body = chunk.substring(lead.length)
            val trail = body.takeLastWhile { it.isWhitespace() }
            val core = body.substring(0, body.length - trail.length)
            if (core.isEmpty()) chunk else lead + correctAfter(core, if (ci == 0) left else "", if (chunks.size == 1) segments else emptyList(), checkpoint).text + trail
        }
    }

    /**
     * Like [correct] for a dictation that follows [left], the text already in the field. The
     * LM reads the end of [left] (at most [LEFT_CONTEXT_CHARS] characters, from a word start)
     * as context, so a name used there makes the same name likely here, and [left] itself is never
     * edited. Without usable context this is plain [correct].
     */
    fun correctAfter(text: String, left: String, segments: List<String> = emptyList(), checkpoint: () -> Unit = {}): Result {
        val ctx = leftContext(left)
        if (ctx.isEmpty()) return correct(text, 0, segments, checkpoint)
        val prefix = "$ctx "
        val r = correct(prefix + text, prefix.length, segments, checkpoint)
        if (!r.text.startsWith(prefix)) return correct(text, 0, segments, checkpoint)
        return Result(
            input = text,
            candidate = r.candidate.removePrefix(prefix),
            text = r.text.substring(prefix.length),
            decisions = r.decisions, edits = r.edits, editSources = r.editSources,
            nCandidateWords = r.nCandidateWords, timings = r.timings, surprisal = r.surprisal, trace = r.trace,
        )
    }

    /** The end of [left] as context (at most [LEFT_CONTEXT_CHARS] characters, starting at a word). */
    private fun leftContext(left: String): String {
        val l = left.trimEnd()
        if (l.isEmpty()) return ""
        var tail = l.takeLast(LEFT_CONTEXT_CHARS)
        if (l.length > tail.length && !l[l.length - tail.length - 1].isWhitespace()) {
            val cut = tail.indexOf(' ')
            tail = if (cut < 0) "" else tail.substring(cut + 1)
        }
        return tail.trimStart()
    }

    /** Old signature (no segments). */
    fun correctAll(text: String, checkpoint: () -> Unit): String = correctAll(text, emptyList(), checkpoint)

    companion object {
        /** How much of the text before a dictation the context stage reads. */
        const val LEFT_CONTEXT_CHARS = 120
        val ALTS = listOf("?", ".", ",")

        /** First words of greetings and sign-offs that end with a comma ("Dear Sam,", "Best regards,"). */
        val LETTER_OPENERS = setOf(
            "hi", "hey", "hello", "dear", "thanks", "thank", "best", "kind", "warm", "regards", "sincerely", "cheers",
            "love", "yours", "good", "morning", "afternoon", "evening", "greetings", "hiya", "yo", "talk", "see",
        )

        /**
         * Words that start a clause. When capitalized where the voice model started an utterance, they
         * more likely start a sentence it didn't end ("dehydrated | We're heading home") than
         * continue a phrase ("the | Toaster oven"), so they are not lowercased.
         */
        val CLAUSE_OPENERS = setOf(
            "we", "they", "you", "he", "she", "it", "there", "that", "this", "these", "those", "we're", "they're",
            "you're", "he's", "she's", "it's", "that's", "there's", "here's", "what's", "let's", "we'll", "they'll",
            "you'll", "he'll", "she'll", "it'll", "that'll", "we've", "they've", "you've", "we'd", "they'd", "you'd",
            "he'd", "she'd", "it'd",
        )

        /** Determiners that leave the next word without context at a sentence start ("The doe ran."). */
        val OPENING_DETERMINERS = setOf("the", "a", "an", "this", "that", "my", "your", "our", "his", "her", "their", "some")

        /** Words that never end a clause (articles, prepositions, possessives, conjunctions). */
        val NO_CLAUSE_END = setOf(
            "to", "the", "a", "an", "of", "for", "at", "in", "on", "with", "from", "by", "into", "onto", "my", "your",
            "their", "our", "his", "its", "and", "or", "but", "nor", "than", "if", "because",
        )

        /** Neutral contexts for a word's prior log-prob (how common it is, whatever the transcript). */
        val PRIOR_FRAMES = listOf("I said ", "The word is ", "Word: ", "He wrote the word ")

        /** Never replaced by a sound-alike word. */
        val QUESTION_WORDS = setOf(
            "who", "who's", "whose", "whom", "what", "what's", "where", "where's", "when", "when's", "why", "how", "how's", "which",
        )

        /** Verbs that start a command ("Let me know ...", "Ask her ..."), see [ContextRescoreConfig.imperativeMargin]. */
        val IMPERATIVE_VERBS = setOf(
            "let", "tell", "ask", "get", "bring", "take", "send", "call", "text", "give", "put", "make", "pick",
            "grab", "remind", "check", "show", "buy", "go", "come", "try", "keep", "stop", "please", "remember",
            "turn", "meet", "wait", "email", "find", "look", "leave", "add", "don't", "guess", "imagine",
        )

        /** Words that may open a question before its first word ("So, what ...", "Wait, so you ..."). */
        private val QUESTION_LEAD = setOf(
            "so", "and", "but", "or", "well", "oh", "hey", "okay", "ok", "wait", "um", "uh", "now", "then", "also",
            "plus", "anyway", "like", "yeah", "yes", "no", "hmm", "see", "look", "listen", "sorry", "honestly", "seriously",
            "really", "still", "dude", "guys", "babe", "mom", "dad",
        )
        private val AUX_START = setOf(
            "are", "is", "am", "was", "were", "do", "does", "did", "can", "could", "will", "would", "should", "shall",
            "may", "might", "must", "have", "has", "had", "isn't", "aren't", "wasn't", "weren't", "doesn't", "didn't",
            "can't", "couldn't", "won't", "wouldn't", "shouldn't", "haven't", "hasn't", "hadn't", "ain't", "don't",
        )
        /** A subject. A sentence that starts with one may be a question said as a statement ("So you already paid?"). */
        private val SUBJECT_START = setOf(
            "you", "we", "they", "he", "she", "it", "i", "that", "this", "there", "these", "those", "you're", "we're",
            "they're", "he's", "she's", "it's", "that's", "there's", "i'm", "you'll", "we'll", "they'll", "he'll", "she'll",
            "it'll", "i'll", "you've", "we've", "they've", "i've", "you'd", "we'd", "they'd", "i'd", "everyone", "everybody",
            "someone", "somebody", "anyone", "anybody", "nobody", "the", "my", "your", "our", "his", "her", "their",
            "both", "all", "any", "some", "one", "everything", "something", "anything", "nothing",
        )
        private val TAG_END = Regex("(?:,\\s*(?:right|okay|ok|yeah|correct|huh|no|yes|really|eh)|n['’]t (?:you|we|they|he|she|it|i|there))\\W*$", RegexOption.IGNORE_CASE)
        private val EXCLAMATIVE = Regex(
            "^(?:what an?\\b|how \\w+ of (?:you|him|her|them|us|me)\\b|such an?\\b|" +
                // "How cool." (but "How come?", "How much?" ask)
                "how (?!come\\b|so\\b|much\\b|many\\b|often\\b|long\\b|far\\b|old\\b|soon\\b|about\\b|big\\b|tall\\b|late\\b|early\\b)\\w+\\W*$)",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Whether [sentence] can be a question. It starts (after "so", "wait", ...) with a verb like
         * "are" / "can", a question word, or a subject ("you already paid"), or ends with a tag
         * ("..., right"). Not a command ("Serve with rice or bread"), a fragment ("Kinda tired,
         * might skip the gym") or an exclamation ("How nice of you", "What a mess").
         */
        fun questionShaped(sentence: String): Boolean {
            if (TAG_END.containsMatchIn(sentence.trimEnd('.', ' '))) return true
            val ws = TranscriptGuard.tokenize(sentence).words.map { TranscriptGuard.normWord(it) }
            var i = 0
            while (i < ws.size - 1 && ws[i] in QUESTION_LEAD) i++
            if (i >= ws.size) return true
            val rest = ws.drop(i).joinToString(" ")
            if (EXCLAMATIVE.containsMatchIn(rest)) return false
            val w = ws[i]
            val second = ws.getOrNull(i + 1)
            if (w in QUESTION_WORDS) return true
            // "Have a great day", "Do your homework" are commands, unless a subject follows ("Do you ...")
            if (w == "have" || w == "do" || w == "don't") return second != null && second in PRONOUN_SUBJECTS
            if (w in AUX_START) return true
            if (w !in SUBJECT_START) return false
            // a pronoun subject is followed by its verb ("you already paid"), but a noun phrase needs a
            // verb in the sentence ("The kids are asleep?"), not just a phrase ("The fur coat.", "All set.")
            if (w in PRONOUN_SUBJECTS || '\'' in w) return true
            return ws.drop(i + 1).any { x -> x in AUX_START || x in COPULAS || '\'' in x || x.endsWith("ed") || TranscriptGuard.isVerbForm(x) }
        }

        private val PRONOUN_SUBJECTS = setOf("you", "we", "they", "he", "she", "it", "i", "y'all", "there", "that", "this")
        private val COPULAS = setOf("is", "are", "was", "were", "am", "be", "been", "will", "won't", "can", "could", "should", "would")

        /** Statements about a question ("I wonder if ...", "I was wondering whether ..."), see [ContextRescoreConfig.imperativeMargin]. */
        val INDIRECT_QUESTION = Regex(
            "^\\s*(?:i|we)(?:['’]m|['’]re| am| are| was| were)?\\s+(?:just\\s+|really\\s+|still\\s+)?" +
                "(?:wonder|wondered|wondering|don['’]t know|do not know|didn['’]t know|not sure|can['’]t remember|" +
                "cannot remember|forgot|have no idea|had no idea|asked|curious)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Never replaced by, or replacing, a sound-alike word. */
        /** Words that open a sentence on their own ("See, ...", "So ...", "Well, ..."). */
        val SENTENCE_OPENERS = setOf(
            "so", "see", "well", "now", "okay", "ok", "yeah", "yes", "no", "but", "and", "or", "look", "listen",
            "right", "then", "also", "plus", "anyway", "like", "sure", "fine", "still", "though", "say",
        )

        val INTERJECTIONS = SpokenEdits.FILLERS + setOf(
            "oh", "ah", "aw", "eh", "huh", "uh-oh", "uh-huh", "mm", "mhm", "yo", "ugh", "ooh", "wow", "hey", "ha", "haha",
            "darn", "dang", "gosh", "geez", "jeez", "yikes", "oops", "whoops", "whoa", "yay", "phew",
        )

        /** Words that may open a relative clause set off by a comma ("Marcus, who ..."). */
        val RELATIVE_STARTS = setOf("who", "whom", "whose", "which")

        /** Splits after sentence ends so that chunks stay below [maxChars] where possible. */
        fun splitChunks(text: String, maxChars: Int): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val out = ArrayList<String>()
            var start = 0
            var lastBreak = -1
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if ((c == '.' || c == '?' || c == '!') && (i + 1 == text.length || text[i + 1].isWhitespace())) lastBreak = i + 1
                if (i - start >= maxChars && lastBreak > start) {
                    out.add(text.substring(start, lastBreak))
                    start = lastBreak
                }
                i++
            }
            if (start < text.length) out.add(text.substring(start))
            return out
        }
    }
}
