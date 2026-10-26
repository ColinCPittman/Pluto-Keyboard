package org.futo.voiceinput.shared.moonshine.core

import java.io.File

/** File layout of a Moonshine streaming model directory (no ONNX Runtime dependency). */
object MoonshineModelFiles {
    const val FRONTEND_MODEL = "frontend.model.ort"
    const val FRONTEND_WEIGHTS = "frontend.weights.ort"
    const val FRONTEND_SINGLE = "frontend.ort"
    const val ENCODER = "encoder.ort"
    const val ADAPTER = "adapter.ort"
    const val CROSS_KV = "cross_kv.ort"
    const val DECODER_KV = "decoder_kv.ort"
    const val CONFIG = "streaming_config.json"
    const val TOKENIZER = "tokenizer.bin"

    /** Files that must exist in a model directory (the frontend is a split pair or a single file). */
    val REQUIRED_FILES = listOf(ENCODER, ADAPTER, CROSS_KV, DECODER_KV, CONFIG, TOKENIZER)

    /** Returns a list of problems with [dir], empty if it looks loadable. */
    fun validateDirectory(dir: File): List<String> {
        val problems = mutableListOf<String>()
        REQUIRED_FILES.forEach { if (!File(dir, it).isFile) problems.add("missing $it") }
        val split = File(dir, FRONTEND_MODEL).isFile && File(dir, FRONTEND_WEIGHTS).isFile
        if (!split && !File(dir, FRONTEND_SINGLE).isFile) {
            problems.add("missing $FRONTEND_MODEL + $FRONTEND_WEIGHTS (or $FRONTEND_SINGLE)")
        }
        return problems
    }
}
