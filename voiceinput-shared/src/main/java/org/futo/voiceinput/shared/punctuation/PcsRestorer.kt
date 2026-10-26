package org.futo.voiceinput.shared.punctuation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.Locale

/**
 * Engine "pcs" is 1-800-BAD-CODE/punctuation_fullstop_truecase_english (punct_cap_seg_en.onnx).
 *
 * Mirrors the `punctuators` Python package (PunctCapSegModelONNX):
 *  - the model input is the *lowercased* text, SentencePiece-unigram encoded, wrapped in BOS(1)/EOS(2),
 *  - at most 256 positions (254 pieces) per window, windows overlap by 16 pieces, and in the overlap each
 *    window contributes only its inner part (the first/last 8 pieces of an inner window are dropped),
 *  - outputs (already arg-maxed inside the graph) are pre_preds (inverted marks, unused), post_preds
 *    [<NULL>, <ACRONYM>, ., `,`, ?], cap_preds [T,16] (per character of the piece, index counted *with* the
 *    leading U+2581), seg_preds (sentence boundary after this piece).
 *
 * Differences from the Python package, by design. The output is rebuilt from the ORIGINAL words (see
 * [TextProcessing]), ACRONYM is ignored, and sentence ends are forced to be . or ?.
 */
internal class PcsRestorer(
    onnxFile: File,
    spmFile: File,
    sessionOptions: OrtSession.SessionOptions?,
    private val options: RestoreOptions,
) : PunctuationRestorer {
    private val env = OrtEnvironment.getEnvironment()
    private val ownedOptions: OrtSession.SessionOptions? =
        if (sessionOptions == null) OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
        } else null
    private val session: OrtSession = env.createSession(onnxFile.absolutePath, sessionOptions ?: ownedOptions!!)
    private val sp = SentencePieceUnigram.load(spmFile)

    private companion object {
        const val MAX_LEN = 256
        const val OVERLAP = 16
        val POST_LABELS = arrayOf("", "<ACRONYM>", ".", ",", "?")
    }

    override fun restoreSegments(segments: List<String>): String {
        val words = TextProcessing.splitSegments(segments)
        if (words.isEmpty()) return segments.joinToString(" ").trim()

        // Tokenize word by word on the lowercased text
        val ids = ArrayList<Int>()
        val wordFirst = IntArray(words.size)
        val wordLast = IntArray(words.size)
        for ((i, w) in words.withIndex()) {
            val t = sp.encodeWord(w.modelText.lowercase(Locale.ROOT))
            wordFirst[i] = ids.size
            for (id in t) ids.add(id)
            wordLast[i] = ids.size - 1
        }
        val n = ids.size
        val post = IntArray(n)
        val seg = BooleanArray(n)
        val cap = arrayOfNulls<BooleanArray>(n)
        predict(ids.toIntArray(), post, seg, cap)

        val cased = Array(words.size) { "" }
        val marks = Array(words.size) { "" }
        val boundary = BooleanArray(words.size)
        for ((i, w) in words.withIndex()) {
            val last = wordLast[i]
            val label = POST_LABELS[post[last]]
            val mark = when (label) {
                ".", ",", "?" -> label
                else -> "" // <NULL>, <ACRONYM> (ignored)
            }
            marks[i] = mark
            // a "." without a segmentation flag is left as the model wrote it (abbreviation-like, e.g. "Corp.")
            boundary[i] = seg[last] || mark == "?"
            cased[i] = applyCase(words, i, wordFirst[i], wordLast[i], ids, cap)
        }
        return TextProcessing.assemble(words, cased, marks, boundary, options)
    }

    private fun applyCase(
        words: List<AsrWord>, i: Int, first: Int, last: Int, ids: List<Int>, cap: Array<BooleanArray?>,
    ): String {
        val w = words[i]
        if (w.mixedCase) return w.core
        val lc = w.modelText.lowercase(Locale.ROOT)
        // Collect per-character capitalization flags from the pieces
        val flags = ArrayList<Boolean>()
        for (k in first..last) {
            val piece = sp.piece(ids[k])
            val c = cap[k]
            var idx = 0
            var p = 0
            while (p < piece.length) {
                val cp = piece.codePointAt(p)
                if (!(idx == 0 && cp == 0x2581)) flags.add(c != null && idx < c.size && c[idx])
                idx++
                p += Character.charCount(cp)
            }
        }
        val cps = lc.codePoints().toArray()
        var result: String
        if (flags.size == cps.size) {
            val sb = StringBuilder()
            for (j in cps.indices) sb.appendCodePoint(if (flags[j]) Character.toUpperCase(cps[j]) else cps[j])
            result = sb.toString()
        } else {
            // Unknown characters / length-changing lowercase, so only trust the first-letter flag
            result = if (flags.isNotEmpty() && flags[0]) TextProcessing.capitalizeFirst(lc, false) else lc
        }
        // the per-subword flags can produce "eTA" or "mCDonald", so only whole-word casing is allowed
        if (!TextProcessing.wholeWordCase(result)) result = w.core
        // Mid-sentence (no ASR sentence terminator before it, not a segment start) the ASR's casing is
        // trusted in both directions because it heard the audio, while the casing model, trained on news text, can
        // turn common words into names ("toaster oven" -> "Toaster oven"). At segment starts and after the
        // ASR's sentence terminators its capitals are unreliable ("I got | Just start"), so the model decides.
        if (options.keepAsrCapitals && i > 0 && words[i - 1].asrTerminal == null && !w.segmentStart) {
            result = when {
                w.startsUpper -> TextProcessing.capitalizeFirst(result, false)
                TextProcessing.PRONOUN_I.matches(lc) -> result
                // the model's ALL CAPS word (an acronym) stays whole, because lowering only its first letter gives "eTA"
                result.count { it.isLetter() } >= 2 && result.none { it.isLowerCase() } -> result
                else -> TextProcessing.lowercaseFirst(result)
            }
        }
        return w.wrap(result)
    }

    /** Runs the (windowed) model over all pieces. */
    private fun predict(ids: IntArray, post: IntArray, seg: BooleanArray, cap: Array<BooleanArray?>) {
        val maxLen = MAX_LEN - 2
        // windows exactly as in punctuators' TextInferenceDataset
        val starts = ArrayList<Int>()
        val stops = ArrayList<Int>()
        var start = 0
        var inputIdx = 0
        while (start < ids.size) {
            val adjStart = start - (if (inputIdx == 0) 0 else OVERLAP)
            val stop = adjStart + maxLen
            starts.add(adjStart)
            stops.add(minOf(stop, ids.size))
            start = stop
            inputIdx++
        }
        val count = starts.size
        for (s in 0 until count) {
            val a = starts[s]
            val b = stops[s]
            val len = b - a
            val input = LongArray(len + 2)
            input[0] = sp.bosId.toLong()
            for (k in 0 until len) input[k + 1] = ids[a + k].toLong()
            input[len + 1] = sp.eosId.toLong()
            OnnxTensor.createTensor(env, arrayOf(input)).use { tensor ->
                session.run(mapOf("input_ids" to tensor)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val postP = res.get("post_preds").get().value as Array<LongArray>
                    @Suppress("UNCHECKED_CAST")
                    val capP = res.get("cap_preds").get().value as Array<Array<BooleanArray>>
                    @Suppress("UNCHECKED_CAST")
                    val segP = res.get("seg_preds").get().value as Array<BooleanArray>
                    // drop overlap on both inner sides, as the Python collector does
                    var from = 0
                    var to = len
                    if (s > 0) from += OVERLAP / 2
                    if (s < count - 1) to -= OVERLAP / 2
                    for (k in from until to) {
                        val g = a + k
                        post[g] = postP[0][k + 1].toInt()
                        seg[g] = segP[0][k + 1]
                        cap[g] = capP[0][k + 1]
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
        ownedOptions?.close()
    }
}
