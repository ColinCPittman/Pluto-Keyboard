package org.futo.voiceinput.shared.transcribe

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.engine.RecognitionEngineConfiguration
import org.futo.voiceinput.shared.engine.RecognitionStats
import org.futo.voiceinput.shared.engine.StreamingRecognitionSession
import org.futo.voiceinput.shared.engine.StreamingRecognitionSession.Result

/**
 * One recognition with a transcribe.cpp model, driven by AudioRecognizer like
 * moonshine.MoonshineStreamingSession. A streaming model (Nemotron) gets the audio as it arrives
 * and reports its live text through [onPartial]. Other models (Parakeet TDT) keep the audio and
 * transcribe it all in [finish].
 *
 * Unlike Moonshine, the model holds the decoding state itself, so a session uses it exclusively
 * ([TranscribeModel.inUse]) from [start] until it is released.
 */
class TranscribeSession(
    private val manager: TranscribeModelManager,
    private val configuration: RecognitionEngineConfiguration.Transcribe,
    private val onPartial: suspend (String) -> Unit
) : StreamingRecognitionSession {
    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val lock = Object()
    // guarded by lock
    private var lease: TranscribeModelManager.Lease? = null
    private var holdsModel = false
    private var worker: Job? = null
    private var finishing = false
    @Volatile private var cancelled = false
    @Volatile private var workerError: Throwable? = null

    // written by the worker, read by finish() after joining it
    // the whole recording, for models that don't stream
    private val kept = ArrayList<FloatArray>()
    private var audioSamples = 0L
    private var computeNanos = 0L
    private var feeds = 0
    private var slowestFeedNanos = 0L

    override suspend fun start(scope: CoroutineScope) = withContext(Dispatchers.Default) {
        val lease = manager.obtain(configuration.modelFile, configuration.threads)
        synchronized(lock) { this@TranscribeSession.lease = lease }
        val model = lease.model
        try {
            if (!cancelled) {
                // a cancelled dictation may still be stopping on the model (it was aborted, so briefly)
                withTimeoutOrNull(BUSY_TIMEOUT_MS) { model.inUse.lock() }
                    ?: throw TranscribeException("The voice model is still busy")
                synchronized(lock) { holdsModel = true }
            }
            if (cancelled) {
                release()
                return@withContext
            }
            if (model.streams) model.streamBegin(model.languageHint(configuration.language))
        } catch (e: Throwable) {
            // AudioRecognizer only cancels after this, which can't see a worker to release with
            release()
            throw e
        }
        val streams = model.streams

        val job = scope.launch(Dispatchers.Default) {
            var lastPartial = ""
            try {
                for (block in audio) {
                    if (!streams) {
                        kept.add(block)
                        audioSamples += block.size
                        continue
                    }
                    // take everything that queued up while the last feed ran
                    var batch = block
                    while (true) {
                        val more = audio.tryReceive().getOrNull() ?: break
                        batch += more
                    }
                    audioSamples += batch.size
                    yield()
                    if (cancelled) break
                    val start = System.nanoTime()
                    val changed = model.streamFeed(batch)
                    val took = System.nanoTime() - start
                    computeNanos += took
                    feeds++
                    if (took > slowestFeedNanos) slowestFeedNanos = took
                    if (!changed) continue
                    val text = model.streamDisplayText()
                    if (text.isNotBlank() && text != lastPartial) {
                        lastPartial = text
                        onPartial(text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // reported by finish(), and never let inference errors crash the keyboard
                if (!cancelled) Log.e(TAG, "Streaming update failed", e)
                workerError = e
                audio.close()
            }
        }
        // the scope ending (the keyboard closing) means no finish() is coming
        job.invokeOnCompletion { if (it is CancellationException) cancel() }
        synchronized(lock) {
            worker = job
            if (cancelled) {
                // cancel() ran while we were loading, so it couldn't see the worker
                job.invokeOnCompletion { release() }
            }
        }
    }

    override fun addAudio(samples: FloatArray) {
        if (!cancelled) audio.trySend(samples)
    }

    override suspend fun finish(): Result? = withContext(Dispatchers.Default) {
        val stopTime = System.nanoTime()
        audio.close()
        worker?.join()
        // time spent on audio that queued up while the model was behind
        val caughtUpTime = System.nanoTime()
        val lease = synchronized(lock) {
            if (cancelled) return@withContext null
            finishing = true
            this@TranscribeSession.lease
        }
        try {
            workerError?.let { throw TranscribeException("Recognition failed: ${it.message ?: it.javaClass.simpleName}") }
            val model = lease?.model ?: return@withContext null
            val start = System.nanoTime()
            if (model.streams) {
                model.streamFinalize()
            } else {
                val all = FloatArray(audioSamples.toInt())
                var pos = 0
                for (b in kept) {
                    b.copyInto(all, pos)
                    pos += b.size
                }
                kept.clear()
                if (all.isNotEmpty()) model.run(all, model.languageHint(configuration.language))
            }
            val finalNanos = System.nanoTime() - start
            computeNanos += finalNanos
            if (cancelled) return@withContext null
            val result = if (audioSamples > 0) model.result() else TranscribeResult("", emptyList(), LongArray(0))
            val text = result.text.trim()
            val audioSeconds = audioSamples / 16000.0
            val stopToTextNanos = System.nanoTime() - stopTime
            Log.i(TAG, "%s: %.1f s audio, catching up after stop %d ms, final pass %d ms, stop to text %d ms, RTF %.2f, %d feeds (slowest %d ms)".format(
                configuration.modelName, audioSeconds,
                (caughtUpTime - stopTime) / 1_000_000, finalNanos / 1_000_000, stopToTextNanos / 1_000_000,
                if (audioSeconds > 0) computeNanos / 1e9 / audioSeconds else 0.0, feeds, slowestFeedNanos / 1_000_000
            ))
            Result(
                text = text,
                segments = PauseSegments.split(text, result.words, result.wordTimes),
                stats = RecognitionStats(
                    engineName = "transcribe.cpp",
                    modelName = configuration.modelName,
                    audioSeconds = audioSeconds,
                    processingSeconds = computeNanos / 1e9,
                    finalizeSeconds = stopToTextNanos / 1e9,
                    modelLoadMs = if (lease.freshlyLoaded) model.loadTimeMs else null,
                    acceleratorRequested = null,
                    acceleratorUsed = null,
                    fallbackReason = null,
                    threads = model.threads.takeIf { it > 0 }
                )
            )
        } catch (e: TranscribeException) {
            // cancel() aborted the final pass
            if (cancelled) return@withContext null
            throw e
        } finally {
            release()
        }
    }

    override fun cancel() {
        val w: Job?
        synchronized(lock) {
            cancelled = true
            // stops the running feed or final pass early (only while the model is ours: another
            // session may still be stopping on it, and while we hold the lease it stays open)
            if (holdsModel) lease?.model?.abort()
            // a running finish() releases everything itself when done
            if (finishing) return
            w = worker
        }
        audio.close()
        // never close the model under a running inference, so wait for the worker, and without a
        // worker, start() is still loading and releases on its own
        w?.invokeOnCompletion { release() }
    }

    private fun release() {
        synchronized(lock) {
            kept.clear()
            val lease = this.lease ?: return
            this.lease = null
            if (holdsModel) {
                holdsModel = false
                val model = lease.model
                model.abort(false)
                // a stream that didn't finish is dropped, so the next dictation starts clean
                if (model.streams) model.streamReset()
                model.inUse.unlock()
            }
            lease.close()
        }
    }

    companion object {
        private const val TAG = "TranscribeSession"
        /** How long [start] waits for a cancelled dictation to let go of the model. */
        private const val BUSY_TIMEOUT_MS = 10_000L
    }
}
