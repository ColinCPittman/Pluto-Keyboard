package org.futo.voiceinput.shared.punctuation

import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Restores punctuation, capitalization and sentence boundaries of a finished speech transcript.
 * The sequence of words is never changed (nothing is added, dropped, reordered or respelled), and
 * only sentence punctuation (. , ? ! ; :) at word edges and letter case may change.
 */
interface PunctuationRestorer : AutoCloseable {
    /** Returns text with punctuation/casing/sentence boundaries restored. Words are never changed. Blocking, so call off the main thread. */
    fun restore(text: String): String = restoreSegments(listOf(text))

    /**
     * Like [restore], for a transcript the ASR decoded as separate [segments] (utterances between pauses, in
     * order). The ASR's capital on a segment's first word only marks a new utterance, so the restorer decides
     * the casing there itself, and capitals inside a segment are the ASR's own choice and are kept.
     */
    fun restoreSegments(segments: List<String>): String
}

/** Tuning knobs. The defaults are what the app should use, and `pure` is for comparing with the reference implementations. */
data class RestoreOptions(
    /**
     * If the ASR had put a "?" or "!" after a word, end the sentence there with the ASR's mark, whatever the
     * model predicted, because the ASR heard the intonation and the text-only punctuation model can't.
     */
    val preserveAsrTerminators: Boolean = true,
    /**
     * A capitalized word inside an ASR segment that did NOT follow an ASR sentence terminator was capitalized on
     * purpose by the ASR (a proper noun). Keep the capital even if the casing model predicts lowercase. Segment
     * starts are excluded, since there the capital only marks a new utterance.
     */
    val keepAsrCapitals: Boolean = true,
    /** Horizon engine only. Feed the model lowercased words (robust against the ASR's bogus capital letters). */
    val horizonLowercaseInput: Boolean = true,
    /**
     * Horizon engine only (it has no casing model). Lowercase common words the ASR capitalized only because it
     * started a bogus sentence there. English oriented; disable for languages that capitalize common nouns (German).
     */
    val horizonLowercaseBogusCapitals: Boolean = true,
) {
    companion object {
        val PURE = RestoreOptions(preserveAsrTerminators = false, keepAsrCapitals = false)
    }
}

object PunctuationRestorers {
    const val ENGINE_PCS = "pcs"
    const val ENGINE_HORIZON = "horizon"

    /**
     * The engine is "pcs" or "horizon" (from the model package manifest). modelDir contains the package files.
     *
     * Required files:
     *  - pcs:     `punct_cap_seg_en.onnx` (any single `*.onnx`) + `spe_32k_lc_en.model` (any single `*.model`)
     *  - horizon: `model_quantized.onnx` (any single `*.onnx`, also looked up in `onnx/`) + `tokenizer.json`
     *
     * [sessionOptions] is owned by the caller (it is not closed by the restorer), and when null a default
     * set of options is created and released with the restorer. The restorer must be [close]d.
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        engine: String,
        modelDir: File,
        sessionOptions: OrtSession.SessionOptions? = null,
        options: RestoreOptions = RestoreOptions(),
    ): PunctuationRestorer {
        require(modelDir.isDirectory) { "Punctuation model directory does not exist: $modelDir" }
        return when (engine) {
            ENGINE_PCS -> PcsRestorer(
                onnxFile = findFile(modelDir, "punct_cap_seg_en.onnx", ".onnx"),
                spmFile = findFile(modelDir, "spe_32k_lc_en.model", ".model"),
                sessionOptions = sessionOptions,
                options = options,
            )
            ENGINE_HORIZON -> HorizonRestorer(
                onnxFile = findFile(modelDir, "model_quantized.onnx", ".onnx"),
                tokenizerFile = findFile(modelDir, "tokenizer.json", ".json"),
                sessionOptions = sessionOptions,
                options = options,
            )
            else -> throw IllegalArgumentException("Unknown punctuation engine '$engine'")
        }
    }

    /** Returns a list of problems with the package in [modelDir] for [engine], or empty if it can be loaded. */
    @JvmStatic
    fun validate(engine: String?, modelDir: File): List<String> {
        val required = when (engine) {
            ENGINE_PCS -> listOf("punct_cap_seg_en.onnx" to ".onnx", "spe_32k_lc_en.model" to ".model")
            ENGINE_HORIZON -> listOf("model_quantized.onnx" to ".onnx", "tokenizer.json" to ".json")
            else -> return listOf("unknown punctuation engine '$engine' (expected '$ENGINE_PCS' or '$ENGINE_HORIZON')")
        }
        return required.mapNotNull { (preferred, suffix) ->
            if (findFileOrNull(modelDir, preferred, suffix) == null) "missing $preferred" else null
        }
    }

    private fun findFileOrNull(dir: File, preferred: String, suffix: String): File? {
        for (d in listOf(dir, File(dir, "onnx"))) {
            val p = File(d, preferred)
            if (p.isFile) return p
        }
        for (d in listOf(dir, File(dir, "onnx"))) {
            val c = d.listFiles { f -> f.isFile && f.name.endsWith(suffix) }
            if (c != null && c.size == 1) return c[0]
        }
        return null
    }

    private fun findFile(dir: File, preferred: String, suffix: String): File =
        findFileOrNull(dir, preferred, suffix)
            ?: throw IllegalArgumentException("Punctuation model package is missing '$preferred' in $dir")
}
