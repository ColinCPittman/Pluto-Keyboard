package org.futo.voiceinput.shared.transcribe

import android.util.Log
import org.futo.voiceinput.shared.engine.FastCores
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps one loaded transcribe.cpp model alive between voice input sessions, handed out as
 * leases, and releases it after [IDLE_RELEASE_MS] without use (see moonshine.MoonshineModelManager,
 * which this mirrors). The GGUF is memory-mapped, so its weights are file-backed pages.
 */
class TranscribeModelManager {
    private data class Key(val path: String, val stamp: Long, val threads: Int)

    internal class Entry(internal val key: Any, val model: TranscribeModel) {
        var leases = 0
        var closeWhenUnused = false
    }

    inner class Lease internal constructor(
        private val entry: Entry,
        /** True if this call loaded the model (false if it was cached). */
        val freshlyLoaded: Boolean
    ) : AutoCloseable {
        val model: TranscribeModel get() = entry.model
        private var released = false

        override fun close() {
            synchronized(this@TranscribeModelManager) {
                if (released) return
                released = true
                entry.leases--
                if (entry.leases == 0) {
                    if (entry.closeWhenUnused) entry.model.close() else scheduleIdleRelease()
                }
            }
        }
    }

    private var current: Entry? = null
    private var idleRelease: ScheduledFuture<*>? = null

    @Synchronized
    private fun scheduleIdleRelease() {
        idleRelease?.cancel(false)
        idleRelease = idleTimer.schedule({
            synchronized(this) {
                val entry = current
                if (entry != null && entry.leases == 0) {
                    Log.i(TAG, "Releasing idle model")
                    cleanUp()
                }
            }
        }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
    }

    /** Returns a lease on the model in [file], loading it if needed (slow, so call off the main thread). */
    @Synchronized
    fun obtain(file: File, threads: Int): Lease {
        idleRelease?.cancel(false)
        idleRelease = null
        // re-importing a package replaces the file, so its mtime changes
        val key = Key(file.absolutePath, file.lastModified(), threads)
        val existing = current
        if (existing != null && existing.key == key && !existing.closeWhenUnused) {
            existing.leases++
            return Lease(existing, freshlyLoaded = false)
        }
        existing?.let { retire(it) }
        current = null

        // automatic: one thread on each fast core (a thread on a slow core holds the others back)
        val cpus = if (threads <= 0) FastCores.fastTier() else null
        val model = TranscribeModel.load(file, cpus?.size ?: threads, cpus)
        Log.i(TAG, "Loaded ${model.description} in ${model.loadTimeMs} ms" +
            " (streams=${model.streams}, languages=${model.languages.size}, threads=${model.threads}" +
            (cpus?.let { ", cpus ${it.joinToString(",")}" } ?: "") + ")")
        val entry = Entry(key, model)
        entry.leases = 1
        current = entry
        return Lease(entry, freshlyLoaded = true)
    }

    private fun retire(entry: Entry) {
        entry.closeWhenUnused = true
        if (entry.leases == 0) entry.model.close()
    }

    /** Releases the cached model (immediately, or once the running recognition ends). */
    @Synchronized
    fun cleanUp() {
        idleRelease?.cancel(false)
        idleRelease = null
        current?.let { retire(it) }
        current = null
    }

    companion object {
        private const val TAG = "TranscribeModelManager"
        private const val IDLE_RELEASE_MS = 2 * 60 * 1000L
        private val idleTimer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TranscribeIdleRelease").apply { isDaemon = true }
        }
    }
}
