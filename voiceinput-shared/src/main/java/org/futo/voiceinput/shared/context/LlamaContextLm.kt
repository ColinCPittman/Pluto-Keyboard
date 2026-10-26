package org.futo.voiceinput.shared.context

import java.io.File

/** JNI entry points of libfutoctxlm (voiceinput-shared/src/main/cpp/contextlm). */
internal object LlamaNative {
    @Volatile
    private var loaded = false

    /** Loads the library from [path] instead of the app's lib directory (desktop tools). */
    @JvmStatic
    @Synchronized
    fun loadFrom(path: String) {
        if (!loaded) {
            System.load(path)
            loaded = true
        }
    }

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("futoctxlm")
            loaded = true
        }
    }

    external fun nativeLoad(path: String, threads: Int, cpus: IntArray?, repack: Boolean): Long
    external fun nativeInitContext(handle: Long, nCtx: Int, nBatch: Int, nSeqMax: Int): Boolean
    external fun nativeTokenize(handle: Long, utf8: ByteArray, addSpecial: Boolean): IntArray
    external fun nativeTokenPiece(handle: Long, token: Int): ByteArray
    external fun nativeDescribe(handle: Long): String
    external fun nativeEvalBase(handle: Long, tokens: IntArray, keep: Int, needFrom: Int, wantOff: IntArray, wantIds: IntArray): FloatArray?
    external fun nativeEvalVariants(handle: Long, variants: Array<IntArray>, starts: IntArray): Array<FloatArray>?
    external fun nativeSetAbort(handle: Long, abort: Boolean)
    external fun nativeFree(handle: Long)
}

/**
 * [ContextLm] backed by llama.cpp (libfutoctxlm). The GGUF is memory-mapped, and the KV cache
 * is sized for the transcript at hand ([ensureCapacity]) and grows when needed.
 * Not thread-safe, so use from one thread (or under a lock) at a time.
 */
class LlamaContextLm private constructor(private var handle: Long) : ContextLm {
    private var nCtx = 0

    val description: String get() = LlamaNative.nativeDescribe(checkOpen())

    private fun checkOpen(): Long {
        if (handle == 0L) throw IllegalStateException("model closed")
        return handle
    }

    override fun tokenize(text: String, addSpecial: Boolean): IntArray =
        LlamaNative.nativeTokenize(checkOpen(), text.toByteArray(Charsets.UTF_8), addSpecial)

    override fun pieceBytes(token: Int): ByteArray = LlamaNative.nativeTokenPiece(checkOpen(), token)

    override fun ensureCapacity(nTokens: Int) {
        // base + the longest variant tail (at most the base) + one batch of slack
        val needed = roundUp(2 * nTokens + N_BATCH + 64, 256).coerceAtLeast(MIN_CTX)
        if (needed <= nCtx) return
        if (!LlamaNative.nativeInitContext(checkOpen(), needed, N_BATCH, N_SEQ)) {
            nCtx = 0
            throw ContextLmException("could not create a context of $needed tokens")
        }
        nCtx = needed
    }

    override fun evalBase(tokens: IntArray, keep: Int, needFrom: Int, wantOff: IntArray, wantIds: IntArray): ContextLm.BaseEval {
        if (nCtx == 0) ensureCapacity(tokens.size)
        val r = LlamaNative.nativeEvalBase(checkOpen(), tokens, keep, needFrom, wantOff, wantIds)
            ?: throw ContextLmException("evaluation failed")
        return ContextLm.BaseEval(r[0].toInt(), r.copyOfRange(1, r.size))
    }

    override fun evalVariants(variants: List<IntArray>, starts: IntArray): List<FloatArray> =
        LlamaNative.nativeEvalVariants(checkOpen(), variants.toTypedArray(), starts)?.toList()
            ?: throw ContextLmException("variant evaluation failed")

    override fun setAbort(abort: Boolean) {
        if (handle != 0L) LlamaNative.nativeSetAbort(handle, abort)
    }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        const val N_BATCH = 128  // larger batches made generation ~2x slower on the phone (ctxrun)
        const val N_SEQ = 8
        const val MIN_CTX = 512

        private fun roundUp(x: Int, m: Int) = (x + m - 1) / m * m

        /** Desktop tools can use libfutoctxlm from [path] instead of the app's native library directory. */
        fun loadLibraryFrom(path: String) = LlamaNative.loadFrom(path)

        /**
         * Memory-maps [model] (GGUF) and prepares a small context. [cpus] holds optional core ids to
         * pin the [threads] worker threads to. Throws if the model cannot be loaded.
         */
        fun load(model: File, threads: Int, cpus: IntArray? = null, repack: Boolean = true): LlamaContextLm {
            LlamaNative.ensureLoaded()
            val h = LlamaNative.nativeLoad(model.absolutePath, threads, cpus?.takeIf { it.isNotEmpty() }, repack)
            if (h == 0L) throw ContextLmException("could not load ${model.name}")
            val lm = LlamaContextLm(h)
            try {
                lm.ensureCapacity(0)
            } catch (e: Throwable) {
                lm.close()
                throw e
            }
            return lm
        }
    }
}
