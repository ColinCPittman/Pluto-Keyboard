package org.futo.voiceinput.shared.punctuation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.Locale

/**
 * Engine "horizon" is Horizon-Labs/punctuation-restoration-small (ModernBERT token classifier, multilingual).
 *
 * Mirrors the model card's `punctuate.py`:
 *  - input ids = [<bos>(2)] + tokens + [<eos>(1)] (the tokenizer's cls/sep), attention_mask all ones,
 *  - text is processed in windows of 400 tokens, and after a non-final window the last 50 tokens are re-read in
 *    the next window (only the first `chunk - overlap` labels of each window are used),
 *  - labels `0 . , ? - :` describe the mark that FOLLOWS the word, and the label of a word is the arg-max at its last
 *    token. The model has no casing head, so casing is rule based (see [applyCase]).
 *
 * Label mapping: `.`->".", `,`->",", `?`->"?", `:`->":", `-` (a free-standing dash in the training data)
 * is mapped to "," (a dash token would add a "word"), `0`->nothing.
 */
internal class HorizonRestorer(
    onnxFile: File,
    tokenizerFile: File,
    sessionOptions: OrtSession.SessionOptions?,
    private val options: RestoreOptions,
) : PunctuationRestorer {
    private val env = OrtEnvironment.getEnvironment()
    private val ownedOptions: OrtSession.SessionOptions? =
        if (sessionOptions == null) OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
        } else null
    private val session: OrtSession = env.createSession(onnxFile.absolutePath, sessionOptions ?: ownedOptions!!)
    private val tokenizer = HfBpeTokenizer.load(tokenizerFile)
    private val needsAttentionMask = session.inputNames.contains("attention_mask")

    private companion object {
        const val CHUNK = 400
        const val OVERLAP = 50
        val LABEL_MARKS = arrayOf("", ".", ",", "?", ",", ":")

    }

    override fun restoreSegments(segments: List<String>): String {
        val words = TextProcessing.splitSegments(segments)
        if (words.isEmpty()) return segments.joinToString(" ").trim()

        val ids = ArrayList<Int>()
        val wordLast = IntArray(words.size)
        for ((i, w) in words.withIndex()) {
            val fed = if (options.horizonLowercaseInput) w.modelText.lowercase(Locale.ROOT) else w.modelText
            for (id in tokenizer.encodeWord(fed)) ids.add(id)
            wordLast[i] = ids.size - 1
        }
        val labels = predict(ids.toIntArray())

        val cased = Array(words.size) { "" }
        val marks = Array(words.size) { "" }
        val boundary = BooleanArray(words.size)
        for ((i, w) in words.withIndex()) {
            val mark = LABEL_MARKS[labels[wordLast[i]]]
            marks[i] = mark
            boundary[i] = mark == "." || mark == "?"
            cased[i] = applyCase(words, i)
        }
        return TextProcessing.assemble(words, cased, marks, boundary, options)
    }

    /**
     * Rule based casing (the model only predicts punctuation):
     *  - mixed-case words (iPhone, NASA, McDonald) are untouched,
     *  - standalone "i", "i'm", "i've", "i'll", "i'd" become "I...",
     *  - sentence starts are capitalized by [TextProcessing.assemble] using the NEW sentence boundaries,
     *  - a word the ASR capitalized only because it began one of its bogus sentences (it follows an ASR
     *    sentence terminator) or a new utterance (an ASR segment start, "I got | Just start") is lowercased when
     *    the tokenizer vocabulary says it is a common word, meaning its lowercase form is more frequent than the
     *    capitalized one (lower BPE id means more frequent, so "The", "Just", "Project", "Groceries" -> lowercase,
     *    "Friday", "Sarah", "Google", "Seattle" -> keep). If the word really starts a sentence in the restored
     *    text, assemble() capitalizes it again,
     *  - every other word keeps the ASR's casing.
     */
    private fun applyCase(words: List<AsrWord>, i: Int): String {
        val w = words[i]
        if (w.mixedCase) return w.core
        val lower = w.modelText.lowercase(Locale.ROOT)
        if (TextProcessing.PRONOUN_I.matches(lower)) return w.wrap("I" + w.body.substring(1))
        if (options.horizonLowercaseBogusCapitals && i > 0 && w.startsUpper &&
            (words[i - 1].asrTerminal != null || w.segmentStart) && isCommonWord(w.modelText)) {
            return w.wrap(TextProcessing.lowercaseFirst(w.body))
        }
        return w.core
    }

    private fun isCommonWord(word: String): Boolean {
        // leading letters only ("Let's" -> "Let")
        val start = TextProcessing.firstLetterIndex(word)
        if (start < 0) return false
        var end = start
        while (end < word.length && Character.isLetter(word[end])) end++
        val head = word.substring(start, end)
        val lower = head.lowercase(Locale.ROOT)
        val low = tokenizer.vocabId("▁" + lower) ?: return false
        val up = tokenizer.vocabId("▁" + head) ?: return true
        return low < up
    }

    /** arg-max label id per token, with the windowing of punctuate.py */
    private fun predict(ids: IntArray): IntArray {
        val out = IntArray(ids.size)
        var start = 0
        while (start < ids.size) {
            val end = minOf(ids.size, start + CHUNK)
            val len = end - start
            val input = LongArray(len + 2)
            input[0] = tokenizer.bosId.toLong()
            for (k in 0 until len) input[k + 1] = ids[start + k].toLong()
            input[len + 1] = tokenizer.eosId.toLong()
            val mask = LongArray(len + 2) { 1L }
            val keepTo = if (end == ids.size) end else end - OVERLAP
            OnnxTensor.createTensor(env, arrayOf(input)).use { idT ->
                val feeds = HashMap<String, OnnxTensor>()
                feeds["input_ids"] = idT
                var maskT: OnnxTensor? = null
                if (needsAttentionMask) {
                    maskT = OnnxTensor.createTensor(env, arrayOf(mask))
                    feeds["attention_mask"] = maskT
                }
                try {
                    session.run(feeds).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (res[0].value as Array<Array<FloatArray>>)[0]
                        for (k in start until keepTo) {
                            val row = logits[k - start + 1]
                            var best = 0
                            for (c in 1 until row.size) if (row[c] > row[best]) best = c
                            out[k] = best
                        }
                    }
                } finally {
                    maskT?.close()
                }
            }
            if (end == ids.size) break
            start = keepTo
        }
        return out
    }

    override fun close() {
        session.close()
        ownedOptions?.close()
    }
}
