package org.futo.inputmethod.latin.uix

import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.futo.voiceinput.shared.engine.TranscriptPostProcessor
import org.futo.voiceinput.shared.modelpackage.InstalledModelPackage
import org.futo.voiceinput.shared.modelpackage.ModelPackageKinds
import org.futo.voiceinput.shared.modelpackage.ModelPackages
import org.futo.voiceinput.shared.moonshine.core.CpuTopology
import org.futo.voiceinput.shared.punctuation.PunctuationRestorer
import org.futo.voiceinput.shared.punctuation.PunctuationRestorers
import java.io.File
import java.util.Locale

/**
 * Second pass for voice input that restores punctuation, capitalization and sentence boundaries of
 * the finished transcript with an imported `punctuation` model package (see PunctuationRestorers).
 *
 * The model is loaded lazily (and preloaded when voice input opens, see [preload]), kept while
 * voice input is being used, and released after [IDLE_RELEASE_MS] without use, since a loaded
 * model costs 100+ MB of memory.
 */
object PunctuationModels {
    private const val TAG = "PunctuationModels"
    private const val IDLE_RELEASE_MS = 3 * 60 * 1000L

    private class Loaded(
        val id: String,
        val dir: File,
        val stamp: Long,
        val restorer: PunctuationRestorer,
        val sessionOptions: OrtSession.SessionOptions
    ) {
        fun close() {
            restorer.close()
            sessionOptions.close()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var loaded: Loaded? = null
    private var releaseJob: Job? = null

    fun installed(context: Context): List<InstalledModelPackage> =
        ModelPackages.list(context, ModelPackageKinds.PUNCTUATION)

    /** The selected punctuation package, falling back to the first installed one. */
    fun selected(context: Context): InstalledModelPackage? {
        val installed = installed(context)
        val id = context.getSetting(PUNCTUATION_MODEL_ID)
        return installed.firstOrNull { it.id == id } ?: installed.firstOrNull()
    }

    /** Whether [pkg] supports [locale]. Packages declare "multi" (or nothing) when they are multilingual. */
    fun supportsLocale(pkg: InstalledModelPackage, locale: Locale): Boolean {
        val language = pkg.manifest.language
        return language.isNullOrEmpty() || language == "multi" || language == locale.language
    }

    /** The package to use for a transcript in [locale], or null if punctuation restoration doesn't apply. */
    private fun packageFor(context: Context, locale: Locale): InstalledModelPackage? {
        if (!context.getSetting(PUNCTUATION_RESTORATION_ENABLED)) return null
        val pkg = selected(context) ?: return null
        if (!supportsLocale(pkg, locale)) return null
        if (PunctuationRestorers.validate(pkg.manifest.engine, pkg.dir).isNotEmpty()) return null
        return pkg
    }

    /** Punctuation stage for transcripts in [locale], or null if punctuation restoration is off or unavailable. */
    fun processorFor(context: Context, locale: Locale): TranscriptPostProcessor? {
        val pkg = packageFor(context, locale) ?: return null
        return TranscriptPostProcessor { raw, segments -> restore(context, pkg, raw, segments) }
    }

    /** Starts loading the model for [locale] in the background so it is ready when the transcript is. */
    fun preload(context: Context, locale: Locale) {
        val pkg = packageFor(context, locale) ?: return
        scope.launch {
            try {
                mutex.withLock {
                    obtainLocked(pkg)
                    scheduleReleaseLocked()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload punctuation model ${pkg.id}", e)
            }
        }
    }

    /** Releases the loaded model, e.g. after its package was deleted. */
    fun release() {
        scope.launch {
            mutex.withLock {
                releaseJob?.cancel()
                releaseJob = null
                loaded?.close()
                loaded = null
            }
        }
    }

    private suspend fun restore(
        context: Context, pkg: InstalledModelPackage, text: String, segments: List<String>,
        queued: Long = System.nanoTime(),
    ): String = mutex.withLock {
        try {
            val start = System.nanoTime()
            DictationRecorder.note(context, "punctuation_wait_ms", (start - queued) / 1_000_000)
            // Segments let the model decide casing where the ASR started a new utterance after a pause
            val restorer = obtainLocked(pkg)
            DictationRecorder.note(context, "punctuation_load_ms", (System.nanoTime() - start) / 1_000_000)
            val result = if (segments.size > 1) restorer.restoreSegments(segments) else restorer.restore(text)
            Log.i(TAG, "Restored punctuation of ${text.length} chars in ${(System.nanoTime() - start) / 1_000_000} ms")
            result
        } finally {
            scheduleReleaseLocked()
        }
    }

    private fun obtainLocked(pkg: InstalledModelPackage): PunctuationRestorer {
        releaseJob?.cancel()
        releaseJob = null

        val stamp = pkg.dir.lastModified()
        loaded?.let {
            if (it.id == pkg.id && it.dir == pkg.dir && it.stamp == stamp) return it.restorer
            it.close()
            loaded = null
        }

        val engine = pkg.manifest.engine ?: PunctuationRestorers.ENGINE_PCS
        val sessionOptions = OrtSession.SessionOptions()
        try {
            sessionOptions.setIntraOpNumThreads(CpuTopology.autoThreadCount)
            sessionOptions.setInterOpNumThreads(1)
            sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0")
            val start = System.nanoTime()
            val restorer = PunctuationRestorers.load(engine, pkg.dir, sessionOptions)
            Log.i(TAG, "Loaded punctuation model ${pkg.id} ($engine) in ${(System.nanoTime() - start) / 1_000_000} ms")
            loaded = Loaded(pkg.id, pkg.dir, stamp, restorer, sessionOptions)
            return restorer
        } catch (e: Throwable) {
            sessionOptions.close()
            throw e
        }
    }

    private fun scheduleReleaseLocked() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                loaded?.close()
                loaded = null
                releaseJob = null
            }
        }
    }
}
