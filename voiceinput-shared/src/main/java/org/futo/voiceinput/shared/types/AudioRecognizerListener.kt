package org.futo.voiceinput.shared.types

import org.futo.voiceinput.shared.engine.RecognitionStats
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

enum class MagnitudeState {
    NOT_TALKED_YET, MIC_MAY_BE_BLOCKED, TALKING
}

interface AudioRecognizerListener {
    fun cancelled()
    fun finished(result: String)
    fun languageDetected(language: Language)
    fun partialResult(result: String)
    fun decodingStatus(status: InferenceState)
    fun modelLoadingFailed()

    /** Model failed to load or run, with a reason to show to the user. */
    fun modelLoadingFailed(message: String) = modelLoadingFailed()

    /** Timing information after a recognition (currently Moonshine only). */
    fun recognitionStats(stats: RecognitionStats) {}

    fun loading()
    fun needPermission(onResult: (Boolean) -> Unit)

    fun recordingStarted(device: MicrophoneDeviceState)
    fun updateMagnitude(magnitude: Float, state: MagnitudeState)

    fun processing()
}