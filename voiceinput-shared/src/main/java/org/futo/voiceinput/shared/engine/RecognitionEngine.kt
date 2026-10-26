package org.futo.voiceinput.shared.engine

import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingTranscriber
import org.futo.voiceinput.shared.moonshine.core.OrtAccelerator
import org.futo.voiceinput.shared.moonshine.core.OrtRuntimeOptions
import java.io.File

/** Which speech recognition engine an [org.futo.voiceinput.shared.AudioRecognizer] drives. */
sealed class RecognitionEngineConfiguration {
    /**
     * The original whisper.cpp engine. Audio is recorded (up to 30 s, or more
     * with long-form input) and transcribed once recording stops.
     */
    object Whisper : RecognitionEngineConfiguration()

    /**
     * Moonshine streaming (English only) on ONNX Runtime. Audio is transcribed
     * while recording, with live partial results and no length limit.
     */
    data class MoonshineStreaming(
        /** Directory of an installed `moonshine-streaming` model package. */
        val modelDir: File,
        val modelName: String,
        val runtime: OrtRuntimeOptions,
        /** Live text, refresh interval and the pause that ends a phrase (Voice Input -> Advanced options). */
        val streaming: MoonshineStreamingTranscriber.Options = MoonshineStreamingTranscriber.Options()
    ) : RecognitionEngineConfiguration()
}

/** Timing information for one recognition, used for the benchmark readout. */
data class RecognitionStats(
    val engineName: String,
    val modelName: String,
    val audioSeconds: Double,
    /** Total time spent computing (streaming updates + finalization). */
    val processingSeconds: Double,
    /** Time from the end of recording until the final transcript was ready. */
    val finalizeSeconds: Double,
    val modelLoadMs: Long?,
    val acceleratorRequested: OrtAccelerator?,
    val acceleratorUsed: OrtAccelerator?,
    val fallbackReason: String?,
    val threads: Int?
) {
    val realTimeFactor: Double get() = if (audioSeconds > 0) processingSeconds / audioSeconds else 0.0

    /** One-line human readable summary (in English, for diagnostics only). */
    fun summary(): String = buildString {
        append("%s: %.1f s audio, %.2f s processing (RTF %.2f), final %.2f s".format(
            modelName, audioSeconds, processingSeconds, realTimeFactor, finalizeSeconds))
        if (acceleratorUsed != null) {
            append(", ").append(acceleratorUsed.name)
            if (threads != null) append(" ×").append(threads)
            if (acceleratorRequested != null && acceleratorRequested != acceleratorUsed) {
                append(" (").append(acceleratorRequested.name).append(" failed, fell back to CPU)")
            }
        }
        if (modelLoadMs != null) append(", model loaded in ").append(modelLoadMs).append(" ms")
    }
}
