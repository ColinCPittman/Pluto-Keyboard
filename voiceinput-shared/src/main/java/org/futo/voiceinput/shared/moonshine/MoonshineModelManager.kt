package org.futo.voiceinput.shared.moonshine

import android.util.Log
import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingModel
import org.futo.voiceinput.shared.moonshine.core.OrtRuntimeOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps one loaded Moonshine model alive between voice input sessions (like
 * [org.futo.voiceinput.shared.whisper.ModelManager] does for whisper), so only
 * the first use pays the load time.
 *
 * Models are handed out as leases, so [cleanUp] (memory pressure, keyboard
 * closing) never closes a model that is still being used by a recognition.
 * It is closed when the last lease is released instead.
 *
 * The cached model is also released after [IDLE_RELEASE_MS] without use, so the
 * keyboard doesn't hold a few hundred MB between dictations on phones with little RAM.
 */
class MoonshineModelManager {
    private data class Key(val dir: String, val generation: Long, val runtime: OrtRuntimeOptions)

    internal class Entry(internal val key: Any, val model: MoonshineStreamingModel) {
        var leases = 0
        var closeWhenUnused = false
    }

    inner class Lease internal constructor(
        private val entry: Entry,
        /** True if this call loaded the model (false if it was cached). */
        val freshlyLoaded: Boolean
    ) : AutoCloseable {
        val model: MoonshineStreamingModel get() = entry.model
        private var released = false

        override fun close() {
            synchronized(this@MoonshineModelManager) {
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

    private fun keyFor(dir: File, runtime: OrtRuntimeOptions): Key {
        // Re-importing a package replaces its files, so the manifest's mtime changes.
        val generation = File(dir, "futo-model.json").lastModified()
        return Key(dir.absolutePath, generation, runtime.copy(threads = runtime.effectiveThreads))
    }

    /** Returns a lease on the model in [dir], loading it if needed (slow, so call off the main thread). */
    @Synchronized
    fun obtain(dir: File, runtime: OrtRuntimeOptions): Lease {
        idleRelease?.cancel(false)
        idleRelease = null
        val key = keyFor(dir, runtime)
        val existing = current
        if (existing != null && existing.key == key && !existing.closeWhenUnused) {
            existing.leases++
            return Lease(existing, freshlyLoaded = false)
        }

        // A different model or setting was requested, so drop the old one.
        existing?.let { retire(it) }
        current = null

        val model = MoonshineStreamingModel.load(dir, runtime) { Log.w(TAG, it) }
        Log.i(TAG, "Loaded ${dir.name} in ${model.loadTimeMs} ms on ${model.accelerator} x${model.threads}" +
                (model.fallbackReason?.let { " (fallback: $it)" } ?: ""))
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
        private const val TAG = "MoonshineModelManager"
        private const val IDLE_RELEASE_MS = 2 * 60 * 1000L
        private val idleTimer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "MoonshineIdleRelease").apply { isDaemon = true }
        }
    }
}
