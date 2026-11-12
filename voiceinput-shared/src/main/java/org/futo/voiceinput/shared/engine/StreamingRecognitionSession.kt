package org.futo.voiceinput.shared.engine

import kotlinx.coroutines.CoroutineScope

/**
 * One recognition by an engine that takes audio while recording (Moonshine, transcribe.cpp),
 * driven by AudioRecognizer. Engines that only run at the end take audio the same way and do
 * their work in [finish].
 */
interface StreamingRecognitionSession {
    /** [segments] are the decoded utterances (between pauses) that [text] was joined from. */
    data class Result(val text: String, val segments: List<String>, val stats: RecognitionStats)

    /** Loads the model (or reuses the cached one) and starts the worker. Throws on load failure. */
    suspend fun start(scope: CoroutineScope)

    /** Called from the recording loop with 16 kHz mono samples. Never blocks. */
    fun addAudio(samples: FloatArray)

    /** Ends the stream and returns the final transcript, or null if cancelled or never started. */
    suspend fun finish(): Result?

    /** Stops without producing a result. Safe to call from any thread, any time. */
    fun cancel()
}
