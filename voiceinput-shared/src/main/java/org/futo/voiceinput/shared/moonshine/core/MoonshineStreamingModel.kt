package org.futo.voiceinput.shared.moonshine.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Per-utterance inference state (port of `MoonshineStreamingState`).
 *
 * Frontend buffers are small and copied in and out of every run. The decoder
 * self-attention cache and the cross-attention K/V are kept as the ONNX
 * Runtime output tensors of the previous run and passed straight back in as
 * inputs, which avoids copying them on every decoding step.
 */
class MoonshineStreamingState internal constructor(private val config: MoonshineStreamingConfig) : AutoCloseable {
    // Frontend state
    internal val sampleBuffer = FloatArray(79)
    internal var sampleLen = 0L
    internal val conv1Buffer = FloatArray(config.dModelFrontend * 4)
    internal val conv2Buffer = FloatArray(config.c1 * 4)
    internal var frameCount = 0L

    // Feature accumulator [T, encoder_dim]
    internal val features = FloatList(config.encoderDim * 256)
    internal var featureCount = 0

    internal var encoderFramesEmitted = 0
    internal var adapterPosOffset = 0L

    // Adapter output (decoder memory) [T, decoder_dim]
    internal val memory = FloatList(config.decoderDim * 256)
    internal var memoryLen = 0

    // Decoder self-attention KV cache, owned by the Result of the last decoder run
    internal var selfResult: OrtSession.Result? = null
    internal var kSelf: OnnxTensor? = null
    internal var vSelf: OnnxTensor? = null
    internal var cacheSeqLen = 0

    // Cross-attention K/V computed from memory, owned by the cross_kv Result
    internal var crossResult: OrtSession.Result? = null
    internal var kCross: OnnxTensor? = null
    internal var vCross: OnnxTensor? = null
    internal var crossLen = 0
    internal val crossKvValid: Boolean get() = crossResult != null

    val memoryFrames: Int get() = memoryLen

    internal fun resetDecoderCache() {
        selfResult?.close()
        selfResult = null
        kSelf = null
        vSelf = null
        cacheSeqLen = 0
    }

    internal fun invalidateCrossKv() {
        crossResult?.close()
        crossResult = null
        kCross = null
        vCross = null
        crossLen = 0
    }

    fun reset() {
        sampleBuffer.fill(0f)
        sampleLen = 0
        conv1Buffer.fill(0f)
        conv2Buffer.fill(0f)
        frameCount = 0
        features.clear()
        featureCount = 0
        encoderFramesEmitted = 0
        adapterPosOffset = 0
        memory.clear()
        memoryLen = 0
        resetDecoderCache()
        invalidateCrossKv()
    }

    override fun close() {
        resetDecoderCache()
        invalidateCrossKv()
        features.release()
        memory.release()
    }
}

/** Cumulative time spent in each graph (for benchmarking). */
class MoonshineTimings {
    var frontendNanos = 0L
    var encoderNanos = 0L
    var adapterNanos = 0L
    var crossKvNanos = 0L
    var decoderNanos = 0L
    var decoderRuns = 0
    var decodedPositions = 0

    fun reset() {
        frontendNanos = 0; encoderNanos = 0; adapterNanos = 0; crossKvNanos = 0; decoderNanos = 0
        decoderRuns = 0; decodedPositions = 0
    }

    override fun toString(): String = "frontend %.0f ms, encoder %.0f ms, adapter %.0f ms, cross_kv %.0f ms, decoder %.0f ms (%d runs, %d positions)".format(
        frontendNanos / 1e6, encoderNanos / 1e6, adapterNanos / 1e6, crossKvNanos / 1e6, decoderNanos / 1e6, decoderRuns, decodedPositions
    )
}

/**
 * The five ONNX graphs of a Moonshine streaming model plus tokenizer (port of
 * `MoonshineStreamingModel`).
 *
 * Not thread-safe by itself, but [MoonshineStreamingTranscriber] serializes access.
 */
class MoonshineStreamingModel private constructor(
    val modelDir: File,
    val config: MoonshineStreamingConfig,
    val tokenizer: BinTokenizer,
    private val env: OrtEnvironment,
    private val factory: OrtSessionFactory,
    private val frontend: OrtSession,
    private val frontendWeights: List<Pair<String, OnnxTensor>>,
    private val encoder: OrtSession,
    private val adapter: OrtSession,
    private val crossKv: OrtSession,
    private val decoderKv: OrtSession,
    /** The accelerator that is actually in use (after any fallback). */
    val accelerator: OrtAccelerator,
    val threads: Int
) : AutoCloseable {
    /** Set when the requested accelerator failed and CPU was used instead. */
    var fallbackReason: String? = null
        private set

    /** The accelerator originally requested. */
    var requestedAccelerator: OrtAccelerator = accelerator
        private set

    var loadTimeMs: Long = 0
        private set

    val timings = MoonshineTimings()

    private val emptyKvShape = longArrayOf(config.depth.toLong(), 1, config.nheads.toLong(), 0, config.headDim.toLong())
    private val emptyKSelf = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyKvShape)
    private val emptyVSelf = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyKvShape)

    @Volatile
    private var closed = false

    fun createState(): MoonshineStreamingState = MoonshineStreamingState(config)

    private fun floatTensor(data: FloatArray, offset: Int, length: Int, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data, offset, length).slice(), shape)

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun OrtSession.Result.tensor(name: String): OnnxTensor =
        (get(name).orElse(null) as? OnnxTensor) ?: throw MoonshineException("Model output $name missing")

    private fun checkOpen() {
        if (closed) throw MoonshineException("Model was closed")
    }

    /**
     * Runs the frontend on [length] samples starting at [offset]. Callers must
     * feed multiples of 80 samples (the published English graphs drop a
     * remainder, as in transcriber.cpp). Returns the number of new feature frames.
     */
    fun processAudioChunk(state: MoonshineStreamingState, audio: FloatArray, offset: Int, length: Int): Int {
        checkOpen()
        if (length == 0) return 0

        val inputs = LinkedHashMap<String, OnnxTensor>()
        val owned = ArrayList<OnnxTensor>(6)
        try {
            fun own(name: String, t: OnnxTensor) {
                inputs[name] = t
                owned.add(t)
            }
            own("audio_chunk", floatTensor(audio, offset, length, longArrayOf(1, length.toLong())))
            own("sample_buffer", floatTensor(state.sampleBuffer, longArrayOf(1, 79)))
            own("sample_len", longTensor(longArrayOf(state.sampleLen), longArrayOf(1)))
            own("conv1_buffer", floatTensor(state.conv1Buffer, longArrayOf(1, config.dModelFrontend.toLong(), 4)))
            own("conv2_buffer", floatTensor(state.conv2Buffer, longArrayOf(1, config.c1.toLong(), 4)))
            own("frame_count", longTensor(longArrayOf(state.frameCount), longArrayOf(1)))
            for ((name, tensor) in frontendWeights) inputs[name] = tensor

            val t0 = System.nanoTime()
            frontend.run(inputs, FRONTEND_OUTPUTS).use { result ->
                timings.frontendNanos += System.nanoTime() - t0
                val features = result.tensor("features")
                val shape = features.info.shape
                val numFeatures = shape[1].toInt()
                if (numFeatures > 0) {
                    state.features.append(features.floatBuffer)
                    state.featureCount += numFeatures
                }
                result.tensor("sample_buffer_out").floatBuffer.get(state.sampleBuffer)
                state.sampleLen = result.tensor("sample_len_out").longBuffer.get(0)
                result.tensor("conv1_buffer_out").floatBuffer.get(state.conv1Buffer)
                result.tensor("conv2_buffer_out").floatBuffer.get(state.conv2Buffer)
                state.frameCount = result.tensor("frame_count_out").longBuffer.get(0)
                return numFeatures
            }
        } finally {
            owned.forEach { it.close() }
        }
    }

    /**
     * Runs the sliding-window encoder over the accumulated features and the
     * adapter over the newly stable frames, appending them to the decoder
     * memory. Unless [isFinal], the last `total_lookahead` frames are held back
     * until more audio arrives. Returns the number of new memory frames.
     */
    fun encode(state: MoonshineStreamingState, isFinal: Boolean): Int {
        checkOpen()
        val totalFeatures = state.featureCount
        if (totalFeatures == 0) return 0

        val stableCount = if (isFinal) totalFeatures else max(0, totalFeatures - config.totalLookahead)
        val newFrames = stableCount - state.encoderFramesEmitted
        if (newFrames <= 0) return 0

        // Encoder uses a sliding window with fixed per-layer left context.
        val leftContextFrames = 16 * config.depth
        val windowStart = max(0, state.encoderFramesEmitted - leftContextFrames)
        val windowSize = totalFeatures - windowStart
        val dim = config.encoderDim

        val newEncoded: FloatArray
        floatTensor(
            state.features.data, windowStart * dim, windowSize * dim,
            longArrayOf(1, windowSize.toLong(), dim.toLong())
        ).use { featuresTensor ->
            val t0 = System.nanoTime()
            encoder.run(mapOf("features" to featuresTensor), ENCODER_OUTPUTS).use { result ->
                timings.encoderNanos += System.nanoTime() - t0
                val encoded = result.tensor("encoded")
                val totalEncoded = encoded.info.shape[1].toInt()
                val startIdx = state.encoderFramesEmitted - windowStart
                if (startIdx < 0 || startIdx + newFrames > totalEncoded) {
                    throw MoonshineException(
                        "Encoder window misaligned: start=$startIdx new=$newFrames total=$totalEncoded"
                    )
                }
                val buffer = encoded.floatBuffer
                buffer.position(startIdx * dim)
                newEncoded = FloatArray(newFrames * dim)
                buffer.get(newEncoded)
            }
        }

        floatTensor(newEncoded, longArrayOf(1, newFrames.toLong(), dim.toLong())).use { encodedTensor ->
            longTensor(longArrayOf(state.adapterPosOffset), longArrayOf(1)).use { posTensor ->
                val t0 = System.nanoTime()
                adapter.run(
                    mapOf("encoded" to encodedTensor, "pos_offset" to posTensor),
                    ADAPTER_OUTPUTS
                ).use { result ->
                    timings.adapterNanos += System.nanoTime() - t0
                    state.memory.append(result.tensor("memory").floatBuffer)
                    state.memoryLen += newFrames
                }
            }
        }

        // Memory changed, so the cross-attention K/V must be recomputed.
        state.invalidateCrossKv()
        state.encoderFramesEmitted = stableCount
        state.adapterPosOffset += newFrames
        return newFrames
    }

    private fun computeCrossKv(state: MoonshineStreamingState) {
        if (state.memoryLen == 0) throw MoonshineException("No memory to compute cross K/V from")
        state.invalidateCrossKv()
        floatTensor(
            state.memory.data, 0, state.memoryLen * config.decoderDim,
            longArrayOf(1, state.memoryLen.toLong(), config.decoderDim.toLong())
        ).use { memoryTensor ->
            val t0 = System.nanoTime()
            val result = crossKv.run(mapOf("memory" to memoryTensor), CROSS_KV_OUTPUTS)
            timings.crossKvNanos += System.nanoTime() - t0
            try {
                val k = result.tensor("k_cross")
                val shape = k.info.shape
                if (shape.size != 5) throw MoonshineException("Expected 5D cross KV tensor")
                state.kCross = k
                state.vCross = result.tensor("v_cross")
                state.crossLen = shape[3].toInt()
                state.crossResult = result
            } catch (e: Throwable) {
                result.close()
                state.invalidateCrossKv()
                throw e
            }
        }
    }

    /**
     * Runs the decoder on [tokens] (appended after the cached ones) and returns
     * the logits for every input position, `[tokens.size * vocab_size]`.
     */
    private fun runDecoder(state: MoonshineStreamingState, tokens: LongArray): FloatArray {
        if (!state.crossKvValid) computeCrossKv(state)

        val kSelf = state.kSelf ?: emptyKSelf
        val vSelf = state.vSelf ?: emptyVSelf
        longTensor(tokens, longArrayOf(1, tokens.size.toLong())).use { tokenTensor ->
            val t0 = System.nanoTime()
            val result = decoderKv.run(
                mapOf(
                    "token" to tokenTensor,
                    "k_self" to kSelf,
                    "v_self" to vSelf,
                    "out_k_cross" to state.kCross!!,
                    "out_v_cross" to state.vCross!!
                ),
                DECODER_OUTPUTS
            )
            timings.decoderNanos += System.nanoTime() - t0
            timings.decoderRuns++
            timings.decodedPositions += tokens.size
            try {
                val logitsBuffer = result.tensor("logits").floatBuffer
                val logits = FloatArray(tokens.size * config.vocabSize)
                logitsBuffer.get(logits)

                val newK = result.tensor("out_k_self")
                val newV = result.tensor("out_v_self")
                val newLen = newK.info.shape[3].toInt()

                // The previous cache tensors were inputs of this run, so release them now.
                state.selfResult?.close()
                state.selfResult = result
                state.kSelf = newK
                state.vSelf = newV
                state.cacheSeqLen = newLen
                return logits
            } catch (e: Throwable) {
                result.close()
                throw e
            }
        }
    }

    private fun argmax(logits: FloatArray, row: Int): Int {
        val base = row * config.vocabSize
        var best = 0
        var bestScore = logits[base]
        for (i in 1 until config.vocabSize) {
            val v = logits[base + i]
            if (v > bestScore) {
                bestScore = v
                best = i
            }
        }
        return best
    }

    /** Clears the self-attention cache, and cross K/V stays valid until memory changes. */
    fun decoderReset(state: MoonshineStreamingState) = state.resetDecoderCache()

    /**
     * Single-token decode step that returns the argmax of the next-token logits.
     * Returns EOS when there is no memory yet.
     */
    fun decodeStep(state: MoonshineStreamingState, token: Int): Int {
        checkOpen()
        if (state.memoryLen == 0) return config.eosId
        val logits = runDecoder(state, longArrayOf(token.toLong()))
        return argmax(logits, 0)
    }

    /**
     * Greedy decode with optional speculative verification of [draft] (port of
     * `decode_full`). The draft (the previous hypothesis for this utterance,
     * without BOS/EOS) is checked in one batched decoder pass, and tokens are
     * accepted up to the first mismatch and decoding continues from there.
     * Returns the tokens without BOS/EOS. The caller must reset the decoder
     * cache first.
     */
    fun decodeFull(state: MoonshineStreamingState, draft: IntArray?): IntArray {
        checkOpen()
        if (state.memoryLen == 0) return IntArray(0)

        val result = ArrayList<Int>()
        val durationSec = state.memoryLen * 0.020f
        val maxTokens = min(ceil(durationSec * 6.5).toInt(), config.maxSeqLen)

        if (!state.crossKvValid) computeCrossKv(state)

        fun continueAr(startToken: Int) {
            var current = startToken
            while (current != config.eosId && result.size < maxTokens) {
                result.add(current)
                val logits = runDecoder(state, longArrayOf(current.toLong()))
                current = argmax(logits, 0)
            }
        }

        if (draft != null && draft.isNotEmpty()) {
            val withBos = LongArray(draft.size + 1)
            withBos[0] = config.bosId.toLong()
            for (i in draft.indices) withBos[i + 1] = draft[i].toLong()

            val logits = runDecoder(state, withBos)
            val predictions = IntArray(withBos.size) { argmax(logits, it) }

            var divergePoint = 0
            for (i in draft.indices) {
                if (predictions[i] == draft[i]) divergePoint = i + 1 else break
            }
            for (i in 0 until divergePoint) result.add(draft[i])

            if (divergePoint == draft.size) {
                continueAr(predictions[draft.size])
            } else {
                // Diverged, so re-run with only the accepted tokens so the cache matches.
                state.resetDecoderCache()
                val accepted = LongArray(divergePoint + 1)
                accepted[0] = config.bosId.toLong()
                for (i in 0 until divergePoint) accepted[i + 1] = draft[i].toLong()
                val logits2 = runDecoder(state, accepted)
                continueAr(argmax(logits2, divergePoint))
            }
        } else {
            val logits = runDecoder(state, longArrayOf(config.bosId.toLong()))
            continueAr(argmax(logits, 0))
        }

        return result.toIntArray()
    }

    fun tokensToText(tokens: IntArray): String = tokenizer.tokensToText(tokens)

    override fun close() {
        if (closed) return
        closed = true
        emptyKSelf.close()
        emptyVSelf.close()
        frontendWeights.forEach { it.second.close() }
        factory.close()
    }

    companion object {
        private val FRONTEND_OUTPUTS = setOf(
            "features", "sample_buffer_out", "sample_len_out",
            "conv1_buffer_out", "conv2_buffer_out", "frame_count_out"
        )
        private val ENCODER_OUTPUTS = setOf("encoded")
        private val ADAPTER_OUTPUTS = setOf("memory")
        private val CROSS_KV_OUTPUTS = setOf("k_cross", "v_cross")
        private val DECODER_OUTPUTS = setOf("logits", "out_k_self", "out_v_self")

        /** Returns a list of problems with [dir], empty if it looks loadable. */
        fun validateDirectory(dir: File): List<String> = MoonshineModelFiles.validateDirectory(dir)

        /**
         * Loads the model in [dir]. If the requested accelerator can't create
         * the sessions or fails a warm-up run, the model is reloaded on CPU and
         * [fallbackReason] says why.
         */
        fun load(dir: File, options: OrtRuntimeOptions, log: (String) -> Unit = {}): MoonshineStreamingModel {
            val start = System.nanoTime()
            val model = try {
                loadWith(dir, options.accelerator, options.effectiveThreads).also { it.warmUp() }
            } catch (e: Throwable) {
                if (options.accelerator == OrtAccelerator.CPU) {
                    throw if (e is MoonshineException) e else MoonshineException("Failed to load Moonshine model: ${e.message}", e)
                }
                val reason = "${options.accelerator} failed: ${e.message ?: e.javaClass.simpleName}"
                log("$reason; falling back to CPU")
                try {
                    loadWith(dir, OrtAccelerator.CPU, options.effectiveThreads).also {
                        it.warmUp()
                        it.fallbackReason = reason
                        it.requestedAccelerator = options.accelerator
                    }
                } catch (e2: Throwable) {
                    throw if (e2 is MoonshineException) e2 else MoonshineException("Failed to load Moonshine model: ${e2.message}", e2)
                }
            }
            model.loadTimeMs = (System.nanoTime() - start) / 1_000_000
            return model
        }

        private fun loadWith(dir: File, accelerator: OrtAccelerator, threads: Int): MoonshineStreamingModel {
            val problems = validateDirectory(dir)
            if (problems.isNotEmpty()) throw MoonshineException("Invalid Moonshine model: " + problems.joinToString())

            val config = MoonshineStreamingConfig.parse(File(dir, MoonshineModelFiles.CONFIG).readText())
            val tokenizer = BinTokenizer.load(File(dir, MoonshineModelFiles.TOKENIZER))
            val env = OrtEnvironment.getEnvironment()
            val factory = OrtSessionFactory(env, accelerator, threads)
            val weights = ArrayList<Pair<String, OnnxTensor>>()
            try {
                val splitFrontend = File(dir, MoonshineModelFiles.FRONTEND_MODEL).isFile && File(dir, MoonshineModelFiles.FRONTEND_WEIGHTS).isFile
                val frontend = if (splitFrontend) {
                    // The split frontend keeps int8 weights in a separate graph whose
                    // outputs are the dequantized float weights, so run it once and feed
                    // the results as extra inputs on every frontend run.
                    val weightsSession = factory.open(File(dir, MoonshineModelFiles.FRONTEND_WEIGHTS), cpuOnly = true)
                    weightsSession.run(emptyMap<String, OnnxTensor>()).use { result ->
                        for (entry in result) {
                            val tensor = entry.value as? OnnxTensor
                                ?: throw MoonshineException("Frontend weight ${entry.key} is not a tensor")
                            val data = tensor.floatBuffer
                            weights.add(entry.key to OnnxTensor.createTensor(env, data, tensor.info.shape))
                        }
                    }
                    factory.release(weightsSession)
                    factory.open(File(dir, MoonshineModelFiles.FRONTEND_MODEL))
                } else {
                    factory.open(File(dir, MoonshineModelFiles.FRONTEND_SINGLE))
                }

                return MoonshineStreamingModel(
                    modelDir = dir,
                    config = config,
                    tokenizer = tokenizer,
                    env = env,
                    factory = factory,
                    frontend = frontend,
                    frontendWeights = weights,
                    encoder = factory.open(File(dir, MoonshineModelFiles.ENCODER)),
                    adapter = factory.open(File(dir, MoonshineModelFiles.ADAPTER)),
                    crossKv = factory.open(File(dir, MoonshineModelFiles.CROSS_KV)),
                    decoderKv = factory.open(File(dir, MoonshineModelFiles.DECODER_KV)),
                    accelerator = accelerator,
                    threads = threads
                )
            } catch (e: Throwable) {
                weights.forEach { it.second.close() }
                factory.close()
                throw e
            }
        }
    }

    /** Runs every graph once on silence so a broken accelerator fails at load time. */
    private fun warmUp() {
        createState().use { state ->
            val silence = FloatArray(WARMUP_CHUNK)
            processAudioChunk(state, silence, 0, silence.size)
            processAudioChunk(state, silence, 0, silence.size)
            encode(state, isFinal = true)
            if (state.memoryLen > 0) {
                decoderReset(state)
                decodeStep(state, config.bosId)
            }
        }
    }
}

private const val WARMUP_CHUNK = 1280
