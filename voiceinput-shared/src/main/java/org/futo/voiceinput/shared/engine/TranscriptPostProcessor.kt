package org.futo.voiceinput.shared.engine

/**
 * Post-processing step applied to the complete transcript of a voice input
 * session before it is inserted into the text field (e.g. punctuation and
 * casing restoration).
 *
 * Contract:
 * - Called exactly once per finished recognition, by [org.futo.voiceinput.shared.AudioRecognizer],
 *   after the engine has been finalized, with the full raw transcript
 *   (already trimmed, and may be empty, in which case it is not called) and the
 *   segments it was joined from, meaning the utterances between pauses that a streaming
 *   engine decoded separately (one segment for engines that decode everything at
 *   once). A segment's first word is capitalized by the ASR because it started a
 *   new utterance, not necessarily a new sentence.
 * - Runs on [kotlinx.coroutines.Dispatchers.Default] while the voice input UI shows its
 *   "Processing…" state, and it may suspend or block for a while.
 * - Should be cooperative with cancellation (the user can close the voice
 *   input window while it runs).
 * - If it throws, the error is logged and the raw transcript is used.
 * - The returned text goes through the usual output sanitizer and is committed.
 *
 * Supplied through [org.futo.voiceinput.shared.RecognizerViewSettings.postProcessor],
 * and defaults to [Identity].
 */
fun interface TranscriptPostProcessor {
    suspend fun process(rawTranscript: String, segments: List<String>): String

    companion object {
        val Identity = TranscriptPostProcessor { text, _ -> text }
    }
}
