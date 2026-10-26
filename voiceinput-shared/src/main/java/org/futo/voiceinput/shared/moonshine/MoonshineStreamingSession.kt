package org.futo.voiceinput.shared.moonshine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.engine.RecognitionEngineConfiguration
import org.futo.voiceinput.shared.engine.RecognitionStats
import org.futo.voiceinput.shared.moonshine.core.EnergySpeechProbability
import org.futo.voiceinput.shared.moonshine.core.MoonshineException
import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingTranscriber
import org.futo.voiceinput.shared.moonshine.core.SileroVad
import org.futo.voiceinput.shared.moonshine.core.SpeechProbabilityModel
import java.io.File

/**
 * One streaming recognition with Moonshine, driven by AudioRecognizer.
 *
 * The recording loop hands every block of samples to [addAudio] (never
 * blocks). A worker coroutine on [Dispatchers.Default] feeds them to the
 * transcriber and runs an update whenever enough new audio has arrived,
 * reporting the transcript so far through [onPartial]. If inference is slower
 * than real time, audio simply queues up and the next update processes a
 * larger batch. [finish] stops the stream, finalizes the open utterance and
 * returns the full transcript.
 */
class MoonshineStreamingSession(
    private val manager: MoonshineModelManager,
    private val configuration: RecognitionEngineConfiguration.MoonshineStreaming,
    private val onPartial: suspend (String) -> Unit
) {
    /** [segments] are the decoded utterances (between pauses) that [text] was joined from. */
    data class Result(val text: String, val segments: List<String>, val stats: RecognitionStats)

    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private var lease: MoonshineModelManager.Lease? = null
    private var vad: SpeechProbabilityModel? = null
    private var transcriber: MoonshineStreamingTranscriber? = null
    private var worker: Job? = null
    private val lock = Object()
    @Volatile private var cancelled = false
    private var finishing = false
    @Volatile private var workerError: Throwable? = null

    /** Loads the model (or reuses the cached one) and starts the worker. Throws on load failure. */
    suspend fun start(scope: CoroutineScope) = withContext(Dispatchers.Default) {
        val lease = manager.obtain(configuration.modelDir, configuration.runtime)
        this@MoonshineStreamingSession.lease = lease
        if (cancelled) {
            release()
            return@withContext
        }
        val vadFile = File(configuration.modelDir, SileroVad.FILE_NAME)
        val vad = if (vadFile.isFile) {
            try {
                SileroVad(vadFile)
            } catch (e: Exception) {
                Log.w(TAG, "Silero VAD failed to load, using energy VAD", e)
                EnergySpeechProbability()
            }
        } else {
            EnergySpeechProbability()
        }
        this@MoonshineStreamingSession.vad = vad

        val transcriber = MoonshineStreamingTranscriber(lease.model, vad, configuration.streaming)
        transcriber.start()
        this@MoonshineStreamingSession.transcriber = transcriber

        val job = scope.launch(Dispatchers.Default) {
            var lastPartial = ""
            try {
                for (block in audio) {
                    transcriber.addAudio(block)
                    // Take everything that queued up while the last update ran.
                    while (true) {
                        val more = audio.tryReceive().getOrNull() ?: break
                        transcriber.addAudio(more)
                    }
                    yield()
                    if (cancelled) break
                    val transcript = transcriber.update() ?: continue
                    val text = transcript.text
                    if (text.isNotBlank() && text != lastPartial) {
                        lastPartial = text
                        onPartial(text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Reported by finish(), and never let inference errors crash the keyboard.
                Log.e(TAG, "Streaming update failed", e)
                workerError = e
                audio.close()
            }
        }
        synchronized(lock) {
            worker = job
            if (cancelled) {
                // cancel() ran while we were loading, so it couldn't see the worker.
                job.invokeOnCompletion { release() }
            }
        }
    }

    /** Called from the recording loop with 16 kHz mono samples. */
    fun addAudio(samples: FloatArray) {
        if (!cancelled) audio.trySend(samples)
    }

    /**
     * Ends the stream and returns the final transcript, or null if the session
     * was cancelled or never started.
     */
    suspend fun finish(): Result? = withContext(Dispatchers.Default) {
        val stopTime = System.nanoTime()
        audio.close()
        worker?.join()
        synchronized(lock) {
            if (cancelled) return@withContext null
            finishing = true
        }
        workerError?.let { error ->
            release()
            throw MoonshineException("Recognition failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
        val transcriber = transcriber ?: return@withContext null.also { release() }
        val lease = lease ?: return@withContext null.also { release() }

        try {
            val transcript = transcriber.stop()
            val finalizeSeconds = (System.nanoTime() - stopTime) / 1e9
            val s = transcriber.stats
            val model = lease.model
            Result(
                text = transcript.text,
                segments = transcript.lines.map { it.text.trim() }.filter { it.isNotEmpty() },
                stats = RecognitionStats(
                    engineName = "Moonshine",
                    modelName = configuration.modelName,
                    audioSeconds = s.audioSeconds,
                    processingSeconds = s.computeSeconds,
                    finalizeSeconds = finalizeSeconds,
                    modelLoadMs = if (lease.freshlyLoaded) model.loadTimeMs else null,
                    acceleratorRequested = model.requestedAccelerator,
                    acceleratorUsed = model.accelerator,
                    fallbackReason = model.fallbackReason,
                    threads = model.threads
                )
            )
        } finally {
            release()
        }
    }

    /** Stops without producing a result. Safe to call from any thread, any time. */
    fun cancel() {
        val w: Job?
        synchronized(lock) {
            cancelled = true
            // A running finish() releases everything itself when done.
            if (finishing) return
            w = worker
        }
        audio.close()
        // Never close the model under a running inference, so wait for the worker.
        // Without a worker, start() is still loading and releases on its own.
        w?.invokeOnCompletion { release() }
    }

    private fun release() = synchronized(lock) {
        transcriber?.close()
        transcriber = null
        vad?.close()
        vad = null
        lease?.close()
        lease = null
    }

    companion object {
        private const val TAG = "MoonshineSession"
    }
}
