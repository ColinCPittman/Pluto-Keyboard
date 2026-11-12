package org.futo.voiceinput.shared.transcribe

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException

class TranscribeException(message: String) : IOException(message)

/**
 * A GGUF speech model loaded by transcribe.cpp (Nemotron, Parakeet, ...), with the one session
 * voice input uses. Not thread-safe: one recognition at a time (see [inUse]), except [abort].
 */
class TranscribeModel private constructor(
    @Volatile private var handle: Long,
    val loadTimeMs: Long,
    val threads: Int,
) : AutoCloseable {
    /** "arch variant", e.g. "nemotron nemotron-speech-streaming-en-0.6b". */
    val description: String = TranscribeNative.nativeDescription(handle)
    private val features = TranscribeNative.nativeFeatures(handle)
    val streams: Boolean get() = features and 1 != 0
    val detectsLanguage: Boolean get() = features and 4 != 0
    /** Language codes the model accepts as a hint (empty if it doesn't say). */
    val languages: List<String> = TranscribeNative.nativeLanguages(handle).toList()

    /**
     * Held by the recognition using the model. The library has one session per model, and a
     * cancelled dictation can still be stopping on it when the next one starts.
     */
    internal val inUse = Mutex()

    private fun h(): Long = handle.also { if (it == 0L) throw TranscribeException("Model is closed") }

    private fun check(status: Int, what: String) {
        if (status != 0) throw TranscribeException("$what failed (transcribe status $status)")
    }

    /**
     * The language hint for [language] ("en"), or null when the model doesn't list it (the library
     * rejects hints outside the list, and a model without a list has one language).
     */
    fun languageHint(language: String): String? = language.takeIf { it in languages }

    /** Whether the model can transcribe [language]. */
    fun supportsLanguage(language: String): Boolean = languages.isEmpty() || language in languages

    fun streamBegin(language: String?) = check(TranscribeNative.nativeStreamBegin(h(), language), "Starting the stream")

    /** Returns whether the live text changed. */
    fun streamFeed(samples: FloatArray): Boolean {
        val r = TranscribeNative.nativeStreamFeed(h(), samples, samples.size)
        if (r < 0) throw TranscribeException("Streaming failed (transcribe status ${-r})")
        return r == 1
    }

    fun streamDisplayText(): String = TranscribeNative.nativeStreamDisplayText(h())

    fun streamFinalize() = check(TranscribeNative.nativeStreamFinalize(h()), "Finishing the stream")

    fun streamReset() = TranscribeNative.nativeStreamReset(h())

    fun run(samples: FloatArray, language: String?) {
        val status = TranscribeNative.nativeRun(h(), samples, samples.size, language)
        if (status == STATUS_OUTPUT_TRUNCATED || status == STATUS_OUTPUT_REPETITION) {
            // the decode stopped early (length budget, or a loop), but what it got is in the
            // result, which beats dropping the whole dictation
            Log.w(TAG, "Transcript is incomplete (transcribe status $status)")
            return
        }
        check(status, "Transcription")
    }

    /** The last result, with its words and their times (all 0 if the model has none). */
    fun result(): TranscribeResult {
        val handle = h()
        return TranscribeResult(
            TranscribeNative.nativeFullText(handle),
            TranscribeNative.nativeWords(handle).toList(),
            TranscribeNative.nativeWordTimes(handle),
        )
    }

    /** Makes the running feed/finalize/run return early. Safe from any thread. */
    fun abort(abort: Boolean = true) {
        val handle = handle
        if (handle != 0L) TranscribeNative.nativeAbort(handle, abort)
    }

    override fun close() {
        val handle = handle
        this.handle = 0L
        if (handle != 0L) TranscribeNative.nativeClose(handle)
    }

    companion object {
        private const val TAG = "TranscribeModel"
        // transcribe_status values of a run that still has a (partial) result
        private const val STATUS_OUTPUT_TRUNCATED = 18
        private const val STATUS_OUTPUT_REPETITION = 19

        /**
         * Loads [file] (slow, so call off the main thread). [threads] 0 lets the library choose;
         * [cpus] are the cores to run on (null for any).
         */
        fun load(file: File, threads: Int, cpus: IntArray? = null): TranscribeModel {
            val start = System.nanoTime()
            val handle = TranscribeNative.nativeOpen(file.absolutePath, threads, cpus)
            if (handle == 0L) throw TranscribeException("Could not load ${file.name}: ${TranscribeNative.nativeLastError()}")
            return try {
                TranscribeModel(handle, (System.nanoTime() - start) / 1_000_000, threads)
            } catch (e: Throwable) {
                TranscribeNative.nativeClose(handle)
                throw e
            }
        }
    }
}

/** A finished transcript: its text, words and their [t0, t1, ...] times in ms. */
class TranscribeResult(val text: String, val words: List<String>, val wordTimes: LongArray)
