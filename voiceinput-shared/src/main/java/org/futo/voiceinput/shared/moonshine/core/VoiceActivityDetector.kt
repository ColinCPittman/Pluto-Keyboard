package org.futo.voiceinput.shared.moonshine.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Returns a speech probability for one hop of 512 samples at 16 kHz. */
interface SpeechProbabilityModel : AutoCloseable {
    fun reset()
    fun predict(chunk: FloatArray): Float
}

/** Silero VAD on ONNX Runtime (port of core/silero-vad.cpp). */
class SileroVad(file: File) : SpeechProbabilityModel {
    private val env = OrtEnvironment.getEnvironment()
    private val factory = OrtSessionFactory(env, OrtAccelerator.CPU, 1)
    private val session: OrtSession = try {
        factory.open(file, cpuOnly = true, threadsOverride = 1)
    } catch (e: Throwable) {
        factory.close()
        throw e
    }

    private val context = FloatArray(CONTEXT_SAMPLES)
    private val state = FloatArray(2 * 1 * 128)
    private val input = FloatArray(CONTEXT_SAMPLES + WINDOW_SAMPLES)
    private val srTensor = OnnxTensor.createTensor(env, 16000L)

    override fun reset() {
        context.fill(0f)
        state.fill(0f)
    }

    override fun predict(chunk: FloatArray): Float {
        System.arraycopy(context, 0, input, 0, CONTEXT_SAMPLES)
        System.arraycopy(chunk, 0, input, CONTEXT_SAMPLES, WINDOW_SAMPLES)

        val prob: Float
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { inputTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateTensor ->
                session.run(
                    mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor),
                    OUTPUTS
                ).use { result ->
                    prob = (result.get("output").get() as OnnxTensor).floatBuffer.get(0)
                    (result.get("stateN").get() as OnnxTensor).floatBuffer.get(state)
                }
            }
        }
        System.arraycopy(input, input.size - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES)
        return prob
    }

    override fun close() {
        srTensor.close()
        factory.close()
    }

    companion object {
        const val FILE_NAME = "silero_vad.ort"
        const val WINDOW_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private val OUTPUTS = setOf("output", "stateN")
    }
}

/**
 * Fallback when a model package has no Silero VAD. It is a simple adaptive energy
 * detector. Much cruder than Silero, but enough to split long dictation at
 * pauses.
 */
class EnergySpeechProbability : SpeechProbabilityModel {
    private var noiseFloor = 0.003f

    override fun reset() {
        noiseFloor = 0.003f
    }

    override fun predict(chunk: FloatArray): Float {
        var sum = 0.0
        for (s in chunk) sum += (s * s).toDouble()
        val rms = sqrt(sum / chunk.size).toFloat()
        // Track the noise floor (fall quickly, rise slowly).
        noiseFloor = if (rms < noiseFloor) rms.coerceAtLeast(0.0005f) else noiseFloor * 0.995f + rms * 0.005f
        val ratio = rms / noiseFloor
        return ((ratio - 2.0f) / 4.0f).coerceIn(0.0f, 1.0f)
    }

    override fun close() {}
}

/** One stretch of speech found by [VoiceActivityDetector]. */
class VoiceActivitySegment internal constructor(val index: Int) {
    internal val audio = FloatList(16000)
    var startTime = 0f
        internal set
    var endTime = 0f
        internal set
    var isComplete = false
        internal set
    var justUpdated = false
        internal set

    /**
     * True when this segment directly continues the previous one, which was
     * cut because it reached the length limit (no pause in between).
     */
    var continuesPrevious = false
        internal set

    val audioLength: Int get() = audio.size
    val audioData: FloatArray get() = audio.data
}

/**
 * Splits a 16 kHz stream into speech segments (port of
 * core/voice-activity-detector.cpp), where per-hop speech probabilities are averaged
 * over a sliding window and thresholded, and a segment starts with some
 * look-behind audio.
 *
 * Long segments must be cut. The reference fades the probability towards zero
 * from 2/3 of [maxSegmentSamples], but its fade factor starts at 0, so every
 * segment is cut on the first hop past 2/3 of the limit, wherever that falls
 * in a word, and the next segment re-reads [lookBehindSamples] of audio the
 * previous one already transcribed. In continuous speech that shows up as
 * repeated or invented words at the cut.
 *
 * With [splitAtPause], a segment past 2/3 of the limit is instead cut at the
 * first short energy dip that is deep enough relative to the segment's mean
 * level (usually a gap between words, sometimes a stop consonant), and the
 * required depth relaxes towards the hard limit, where the segment is cut
 * regardless. The next segment is marked [VoiceActivitySegment.continuesPrevious]
 * and starts [splitOverlapSamples] before the cut, so the word at the cut is
 * heard whole at least once, and the text join (MoonshineText.joinLines) then
 * merges the words both segments decoded from the overlap. Segments shorter
 * than 2/3 of the limit behave exactly like the reference.
 */
class VoiceActivityDetector(
    private val model: SpeechProbabilityModel,
    private val threshold: Float = 0.5f,
    windowDurationSec: Float = 0.5f,
    private val hopSize: Int = 512,
    private val lookBehindSamples: Int = 8192,
    private val maxSegmentSamples: Int = 15 * 16000,
    private val splitAtPause: Boolean = true,
    private val splitOverlapSamples: Int = 12000
) {
    private val windowSize = kotlin.math.ceil(windowDurationSec * 16000f / hopSize).toInt()
    private val probabilityWindow = FloatArray(windowSize)
    private var probabilityWindowIndex = 0
    // Recent audio (the look-behind for new segments, and the overlap a
    // continuation segment re-reads, which may be longer).
    private val history = FloatArray(maxOf(lookBehindSamples, splitOverlapSamples + hopSize))
    private val remainder = FloatList(hopSize * 2)
    private val hop = FloatArray(hopSize)
    private var currentSegment: VoiceActivitySegment? = null
    private var previousIsVoice = false
    private var samplesProcessed = 0L

    // Split-at-pause state is the mean power of the open segment, and where the
    // last forced split happened (absolute sample index, -1 if none).
    private var segmentPowerSum = 0.0
    private var segmentPowerHops = 0
    private var lastSplitSample = -1L

    val segments = ArrayList<VoiceActivitySegment>()

    var isActive = false
        private set

    fun start() {
        isActive = true
        samplesProcessed = 0
        segments.clear()
        currentSegment = null
        history.fill(0f)
        remainder.clear()
        probabilityWindow.fill(0f)
        probabilityWindowIndex = 0
        previousIsVoice = false
        segmentPowerSum = 0.0
        segmentPowerHops = 0
        lastSplitSample = -1L
        model.reset()
    }

    /**
     * Ends the stream, closing an open segment. An open segment also gets the last partial hop and
     * [paddingSamples] of silence, because the recognizer needs some audio after the last word to decode
     * it, which a stop pressed right after speaking leaves out.
     */
    fun stop(paddingSamples: Int = 0) {
        isActive = false
        if (previousIsVoice) {
            currentSegment?.let { segment ->
                segment.audio.append(remainder.data, 0, remainder.size)
                if (paddingSamples > 0) segment.audio.append(FloatArray(paddingSamples), 0, paddingSamples)
            }
            remainder.clear()
            onVoiceEnd()
            previousIsVoice = false
        }
    }

    fun processAudio(audio: FloatArray, offset: Int = 0, length: Int = audio.size - offset) {
        if (!isActive) return
        for (s in segments) s.justUpdated = false

        var pos = offset
        val end = offset + length
        // Complete a pending partial hop first.
        if (remainder.size > 0) {
            val need = hopSize - remainder.size
            val n = minOf(need, end - pos)
            remainder.append(audio, pos, n)
            pos += n
            if (remainder.size == hopSize) {
                System.arraycopy(remainder.data, 0, hop, 0, hopSize)
                remainder.clear()
                processHop(hop)
            }
        }
        while (end - pos >= hopSize) {
            System.arraycopy(audio, pos, hop, 0, hopSize)
            pos += hopSize
            processHop(hop)
        }
        if (pos < end) remainder.append(audio, pos, end - pos)
    }

    private fun processHop(chunk: FloatArray) {
        samplesProcessed += hopSize
        System.arraycopy(history, hopSize, history, 0, history.size - hopSize)
        System.arraycopy(chunk, 0, history, history.size - hopSize, hopSize)

        var smoothed: Float
        if (threshold > 0f) {
            probabilityWindow[probabilityWindowIndex] = model.predict(chunk)
            probabilityWindowIndex = (probabilityWindowIndex + 1) % probabilityWindow.size
            smoothed = probabilityWindow.sum() / probabilityWindow.size
        } else {
            smoothed = 1.0f
        }

        val current = currentSegment
        val fadeSamples = maxSegmentSamples * 2 / 3
        // Where in this hop to cut the open segment, or -1.
        var splitOffset = -1
        if (maxSegmentSamples > 0 && current != null && previousIsVoice) {
            if (!splitAtPause) {
                if (current.audio.size > fadeSamples) {
                    smoothed *= (current.audio.size - fadeSamples).toFloat() / fadeSamples
                }
            } else {
                segmentPowerSum += meanPower(chunk, 0, hopSize)
                segmentPowerHops++
                if (current.audio.size > fadeSamples) splitOffset = findSplit(chunk, current.audio.size, fadeSamples)
            }
        }

        val isVoice = smoothed > threshold
        if (splitOffset >= 0 && isVoice) {
            current!!.audio.append(chunk, 0, splitOffset)
            lastSplitSample = samplesProcessed - hopSize + splitOffset
            onVoiceEnd()
            current.endTime = lastSplitSample / 16000f
            // Speech goes on, so the rest of this hop starts the next segment.
            startSegment(continuesPrevious = true)
        } else if (isVoice && !previousIsVoice) {
            startSegment(continuesPrevious = false)
        } else if (!isVoice && previousIsVoice) {
            current!!.audio.append(chunk, 0, hopSize)
            onVoiceEnd()
        } else if (isVoice && previousIsVoice) {
            current!!.audio.append(chunk, 0, hopSize)
            current.endTime = samplesProcessed / 16000f
            current.justUpdated = true
        }
        previousIsVoice = isVoice
    }

    private fun startSegment(continuesPrevious: Boolean) {
        var lookBehindSize = minOf(lookBehindSamples.toLong(), samplesProcessed)
        if (continuesPrevious) {
            // Re-read the end of the segment that was just cut.
            lookBehindSize = minOf(history.size.toLong(), samplesProcessed - lastSplitSample + splitOverlapSamples)
        } else if (lastSplitSample >= 0) {
            // Speech stopped right at a forced cut, so don't re-read the cut segment.
            lookBehindSize = minOf(lookBehindSize, samplesProcessed - lastSplitSample)
            lastSplitSample = -1L
        }
        val size = lookBehindSize.toInt()
        val segment = VoiceActivitySegment(segments.size)
        segment.audio.append(history, history.size - size, size)
        val now = samplesProcessed / 16000f
        segment.startTime = now - size / 16000f
        segment.endTime = now
        segment.justUpdated = true
        segment.continuesPrevious = continuesPrevious
        segments.add(segment)
        currentSegment = segment
        segmentPowerSum = 0.0
        segmentPowerHops = 0
    }

    /**
     * Returns where in [chunk] to cut a segment that is already [size] samples
     * long, or -1 to keep going. It is the middle of the first [SPLIT_FRAME] that is
     * at least the required depth below the segment's mean power. The depth
     * goes from [SPLIT_DEPTH_START_DB] at [softLimit] to [SPLIT_DEPTH_END_DB]
     * at the hard limit, where the segment is cut at the hop's quietest frame
     * regardless.
     */
    private fun findSplit(chunk: FloatArray, size: Int, softLimit: Int): Int {
        val progress = (size - softLimit).toFloat() / (maxSegmentSamples - softLimit)
        val depthDb = SPLIT_DEPTH_START_DB + (SPLIT_DEPTH_END_DB - SPLIT_DEPTH_START_DB) * progress
        val limitDb = powerDb(segmentPowerSum / segmentPowerHops.coerceAtLeast(1)) - depthDb
        var quietest = 0
        var quietestPower = Double.MAX_VALUE
        var f = 0
        while (f + SPLIT_FRAME <= hopSize) {
            val p = meanPower(chunk, f, SPLIT_FRAME)
            if (p < quietestPower) {
                quietestPower = p
                quietest = f
            }
            if (powerDb(p) < limitDb) return f + SPLIT_FRAME / 2
            f += SPLIT_FRAME
        }
        return if (size + hopSize >= maxSegmentSamples) quietest + SPLIT_FRAME / 2 else -1
    }

    private fun onVoiceEnd() {
        val segment = currentSegment ?: return
        segment.endTime = samplesProcessed / 16000f
        segment.isComplete = true
        segment.justUpdated = true
        currentSegment = null
    }

    companion object {
        private const val SPLIT_FRAME = 256
        private const val SPLIT_DEPTH_START_DB = 35f
        private const val SPLIT_DEPTH_END_DB = 15f

        private fun meanPower(x: FloatArray, offset: Int, length: Int): Double {
            var sum = 0.0
            for (i in offset until offset + length) sum += (x[i] * x[i]).toDouble()
            return sum / length
        }

        private fun powerDb(p: Double): Float = (10.0 * kotlin.math.log10(p + 1e-12)).toFloat()
    }
}
