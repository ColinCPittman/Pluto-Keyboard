package org.futo.voiceinput.shared.moonshine.core

// Moonshine streaming speech recognition, ported to Kotlin + ONNX Runtime Java
// from the moonshine C++ core (https://github.com/moonshine-ai/moonshine,
// core/moonshine-streaming-model.cpp, core/transcriber.cpp,
// core/voice-activity-detector.cpp, core/silero-vad.cpp,
// core/bin-tokenizer/bin-tokenizer.cpp), Copyright (c) Moonshine AI, MIT License.
// See THIRD_PARTY_NOTICES.md.
//
// Everything in this package is plain JVM code (no Android APIs) so it can be
// compiled and verified on desktop by tools/moonshine-harness.

/** Streaming model configuration, parsed from `streaming_config.json`. */
data class MoonshineStreamingConfig(
    val encoderDim: Int,
    val decoderDim: Int,
    val depth: Int,
    val nheads: Int,
    val headDim: Int,
    val vocabSize: Int,
    val bosId: Int,
    val eosId: Int,
    val frameLen: Int,
    val totalLookahead: Int,
    val dModelFrontend: Int,
    val c1: Int,
    val c2: Int,
    val maxSeqLen: Int
) {
    companion object {
        /**
         * Same lenient key lookup as the C++ `parse_config_json`, where missing keys
         * read as 0, `max_seq_len` defaults to 448.
         */
        fun parse(json: String): MoonshineStreamingConfig {
            fun int(key: String): Int {
                val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+)").find(json)
                return m?.groupValues?.get(1)?.toInt() ?: 0
            }

            val config = MoonshineStreamingConfig(
                encoderDim = int("encoder_dim"),
                decoderDim = int("decoder_dim"),
                depth = int("depth"),
                nheads = int("nheads"),
                headDim = int("head_dim"),
                vocabSize = int("vocab_size"),
                bosId = int("bos_id"),
                eosId = int("eos_id"),
                frameLen = int("frame_len"),
                totalLookahead = int("total_lookahead"),
                dModelFrontend = int("d_model_frontend"),
                c1 = int("c1"),
                c2 = int("c2"),
                maxSeqLen = int("max_seq_len").let { if (it > 0) it else 448 }
            )

            if (config.depth <= 0 || config.decoderDim <= 0 || config.vocabSize <= 0) {
                throw MoonshineException("Invalid streaming_config.json")
            }
            return config
        }
    }
}

class MoonshineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Minimal growable float array (avoids boxing). */
internal class FloatList(initialCapacity: Int = 1024) {
    var data = FloatArray(initialCapacity)
        private set
    var size = 0
        private set

    fun append(src: FloatArray, offset: Int = 0, length: Int = src.size - offset) {
        ensureCapacity(size + length)
        System.arraycopy(src, offset, data, size, length)
        size += length
    }

    fun append(src: java.nio.FloatBuffer) {
        val n = src.remaining()
        ensureCapacity(size + n)
        src.get(data, size, n)
        size += n
    }

    private fun ensureCapacity(n: Int) {
        if (n > data.size) {
            var c = data.size.coerceAtLeast(16)
            while (c < n) c *= 2
            data = data.copyOf(c)
        }
    }

    fun clear() {
        size = 0
    }

    /** Drops the backing storage (used when a segment's audio is no longer needed). */
    fun release() {
        data = FloatArray(0)
        size = 0
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
