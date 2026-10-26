package org.futo.voiceinput.shared.moonshine.core

import kotlin.math.ceil
import kotlin.math.min

/**
 * Streaming transcription of one recording (port of the stream path of
 * moonshine's `Transcriber`, namely `transcribe_stream`,
 * `update_transcript_from_segments`, `transcribe_segment_with_streaming_model`).
 *
 * How it works:
 * - Audio is buffered by [addAudio]. [update] runs once at least
 *   [Options.transcriptionInterval] seconds of new audio have accumulated
 *   (or when forced), so slow devices automatically process larger batches.
 * - The VAD splits the stream into utterances ("lines"), at most
 *   [Options.vadMaxSegmentDuration] seconds long. Each line has its own model
 *   state, so arbitrarily long dictation never grows the decoder input.
 * - For the line that is still being spoken, only the audio not yet seen is
 *   pushed through the frontend (in 80 ms chunks), the encoder emits frames
 *   except for the last `total_lookahead` ones, and the decoder re-decodes the
 *   whole line from the start. With speculative decoding the previous
 *   hypothesis is verified in a single batched decoder pass and decoding
 *   continues from the first mismatch, which keeps re-decodes cheap.
 * - When the VAD closes a line (or the stream stops) the line is finalized, so
 *   lookahead frames are flushed (`is_final`) and decoded one last time.
 *
 * [addAudio] may be called from a different thread than [update]/[stop],
 * but the model itself is only ever used from the thread calling [update]/[stop].
 */
class MoonshineStreamingTranscriber(
    private val model: MoonshineStreamingModel,
    vadModel: SpeechProbabilityModel,
    private val options: Options = Options()
) : AutoCloseable {
    data class Options(
        val transcriptionInterval: Float = 0.5f,
        val vadThreshold: Float = 0.5f,
        val vadWindowDuration: Float = 0.5f,
        val vadHopSize: Int = 512,
        val vadLookBehindSamples: Int = 8192,
        val vadMaxSegmentDuration: Float = 15.0f,
        /** Cut over-long utterances at a gap between words (see [VoiceActivityDetector]). */
        val vadSplitAtPause: Boolean = true,
        /** Audio before such a cut that the next utterance re-reads (merged in [Transcript.text]). */
        val vadSplitOverlapDuration: Float = 0.75f,
        val maxTokensPerSecond: Float = 6.5f,
        val useSpeculativeDecoding: Boolean = true,
        val decodeIncompleteLines: Boolean = true
    )

    data class Line(
        val index: Int,
        val startTime: Float,
        val endTime: Float,
        val text: String,
        val isComplete: Boolean,
        /** Continues the previous line, which was cut for length mid-speech. */
        val continuesPrevious: Boolean = false
    )

    class Transcript(val lines: List<Line>) {
        /** Lines joined exactly like the reference library would show them. */
        val rawText: String get() = lines.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        /** Lines joined with [MoonshineText.joinLines] (casing cleanup, overlap merge at forced cuts). */
        val text: String get() = MoonshineText.joinLines(lines.map { it.text }, lines.map { it.continuesPrevious })
    }

    /** Timing information for benchmarking. */
    data class Stats(
        val audioSeconds: Double,
        val computeSeconds: Double,
        val finalizeSeconds: Double,
        val updates: Int
    ) {
        val realTimeFactor: Double get() = if (audioSeconds > 0) computeSeconds / audioSeconds else 0.0
    }

    private val vad = VoiceActivityDetector(
        model = vadModel,
        threshold = options.vadThreshold,
        windowDurationSec = options.vadWindowDuration,
        hopSize = options.vadHopSize,
        lookBehindSamples = options.vadLookBehindSamples,
        maxSegmentSamples = (options.vadMaxSegmentDuration * 16000).toInt(),
        splitAtPause = options.vadSplitAtPause,
        splitOverlapSamples = (options.vadSplitOverlapDuration * 16000).toInt()
    )

    private val lock = Object()
    private var pending = FloatList(16000)
    private var stopped = false
    private var totalSamples = 0L

    private val state = model.createState()
    private var currentSegmentId = -1
    private var segmentSamplesProcessed = 0
    private var lastTokens = IntArray(0)

    private val lines = ArrayList<Line>()

    private var computeNanos = 0L
    private var finalizeNanos = 0L
    private var updateCount = 0

    fun start() {
        synchronized(lock) {
            pending.clear()
            stopped = false
            totalSamples = 0
        }
        vad.start()
        state.reset()
        currentSegmentId = -1
        segmentSamplesProcessed = 0
        lastTokens = IntArray(0)
        lines.clear()
        computeNanos = 0
        finalizeNanos = 0
        updateCount = 0
    }

    /** Adds 16 kHz mono float samples. Thread-safe with respect to [update]. */
    fun addAudio(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        synchronized(lock) {
            if (stopped) return
            pending.append(samples, offset, length)
            totalSamples += length
        }
    }

    /** Seconds of audio added so far. */
    val audioSeconds: Double get() = synchronized(lock) { totalSamples / 16000.0 }

    /** Seconds of audio waiting for the next [update]. */
    val pendingSeconds: Double get() = synchronized(lock) { pending.size / 16000.0 }

    /**
     * Processes buffered audio if there is at least one transcription interval
     * of it (or any, when [force]). Returns the updated transcript, or null if
     * nothing was processed.
     */
    fun update(force: Boolean = false): Transcript? {
        val audio: FloatList
        synchronized(lock) {
            val enough = pending.size >= (options.transcriptionInterval * 16000).toInt()
            if (pending.size == 0 || !(enough || force)) return null
            audio = pending
            pending = FloatList(16000)
        }
        val start = System.nanoTime()
        vad.processAudio(audio.data, 0, audio.size)
        updateFromSegments()
        computeNanos += System.nanoTime() - start
        updateCount++
        return Transcript(lines.toList())
    }

    /**
     * Ends the stream. It processes the remaining audio, closes the open line and
     * finalizes it. Returns the final transcript.
     */
    fun stop(): Transcript {
        val audio: FloatList
        synchronized(lock) {
            stopped = true
            audio = pending
            pending = FloatList(16)
        }
        val start = System.nanoTime()
        if (audio.size > 0) vad.processAudio(audio.data, 0, audio.size)
        vad.stop(paddingSamples = STOP_PADDING_SAMPLES)
        updateFromSegments()
        val elapsed = System.nanoTime() - start
        computeNanos += elapsed
        finalizeNanos = elapsed
        updateCount++
        return Transcript(lines.toList())
    }

    val stats: Stats
        get() = Stats(
            audioSeconds = audioSeconds,
            computeSeconds = computeNanos / 1e9,
            finalizeSeconds = finalizeNanos / 1e9,
            updates = updateCount
        )

    private fun updateFromSegments() {
        for (segment in vad.segments) {
            if (!segment.justUpdated) continue
            // A finished line is final because its audio is released, so decoding it again (stop() right
            // after the update that finished it, with no new audio) would erase its text.
            if (segment.index < lines.size && lines[segment.index].isComplete) continue
            val text = MoonshineText.fixSplitDecades(transcribeSegment(segment))
            val line = Line(
                segment.index, segment.startTime, segment.endTime, text, segment.isComplete, segment.continuesPrevious
            )
            if (segment.index < lines.size) lines[segment.index] = line else lines.add(line)
            if (segment.isComplete) segment.audio.release()
        }
    }

    private fun transcribeSegment(segment: VoiceActivitySegment): String {
        val audioLength = segment.audioLength
        if (audioLength == 0) return ""
        val isFinal = segment.isComplete
        val config = model.config

        val isNewSegment = segment.index != currentSegmentId
        if (isNewSegment) {
            state.reset()
            currentSegmentId = segment.index
            segmentSamplesProcessed = 0
            lastTokens = IntArray(0)
        }

        if (segmentSamplesProcessed < audioLength) {
            // Whole 80 ms chunks only, and the remainder waits for the next update.
            // The published English frontend graphs mishandle chunk lengths
            // that aren't a multiple of 80 samples (see transcriber.cpp).
            val newLength = audioLength - segmentSamplesProcessed
            val chunkCount = newLength / CHUNK_SIZE
            val data = segment.audioData
            for (i in 0 until chunkCount) {
                model.processAudioChunk(state, data, segmentSamplesProcessed + i * CHUNK_SIZE, CHUNK_SIZE)
            }
            model.encode(state, isFinal)
            segmentSamplesProcessed += chunkCount * CHUNK_SIZE
        }

        if (state.memoryFrames == 0) return ""
        if (!isFinal && !options.decodeIncompleteLines) return ""

        model.decoderReset(state)

        val tokens: IntArray
        if (options.useSpeculativeDecoding && !isNewSegment && lastTokens.isNotEmpty()) {
            val draft = lastTokens.filter { it != config.bosId && it != config.eosId }.toIntArray()
            val out = model.decodeFull(state, if (draft.isEmpty()) null else draft)
            tokens = IntArray(out.size + 1)
            tokens[0] = config.bosId
            System.arraycopy(out, 0, tokens, 1, out.size)
        } else {
            val durationSec = audioLength / 16000f
            val maxTokens = min(ceil(durationSec * options.maxTokensPerSecond).toInt(), 256)
            val list = ArrayList<Int>()
            list.add(config.bosId)
            var current = config.bosId
            for (step in 0 until maxTokens) {
                val next = model.decodeStep(state, current)
                list.add(next)
                current = next
                if (next == config.eosId) break
            }
            tokens = list.toIntArray()
        }

        lastTokens = tokens
        return model.tokensToText(tokens)
    }

    override fun close() {
        state.close()
    }

    companion object {
        const val CHUNK_SIZE = 1280

        /** Silence added after the last word when the stream stops mid-speech (0.5 s). */
        const val STOP_PADDING_SAMPLES = 8000
    }
}

object MoonshineText {
    private val pronounI = setOf("I", "I'm", "I'll", "I've", "I'd")

    private val SPLIT_DECADE_WORD = Regex("\\b(twen|thir|for|fif|six|seven|eigh|nine)ty\\s+s\\b(?!['’])", RegexOption.IGNORE_CASE)
    private val SPLIT_DECADE_DIGITS = Regex("\\b(\\d*0)\\s+s\\b(?!['’])")

    /**
     * The model sometimes writes a decade as the number and a lone "s", mostly when the speaker
     * pauses right after it ("in my twenty s, I would"). Joins them again into "twenties" or "20s".
     */
    fun fixSplitDecades(line: String): String {
        if (!line.contains(" s") && !line.contains(" S")) return line
        return line
            .replace(SPLIT_DECADE_WORD) { it.groupValues[1] + "ties" }
            .replace(SPLIT_DECADE_DIGITS) { it.groupValues[1] + "s" }
    }

    /**
     * Joins per-utterance lines. Each utterance is decoded independently, so
     * the model capitalizes its first word even when it continues a sentence
     * ("It was nice, So we stayed"). When the previous line ends with a comma
     * the next line's first letter is lowercased (except for "I" forms).
     *
     * A line in [continuesPrevious] picks up mid-speech where the previous one
     * was cut for length. Its audio may start a little before the cut, so words
     * at the start of the line can repeat the end of the previous one, and those
     * are merged (see [mergeOverlap]). The model's sentence end at the cut is
     * an artifact, so the previous line's final period is dropped and the first
     * letter is lowercased as after a comma.
     */
    fun joinLines(lines: List<String>, continuesPrevious: List<Boolean> = emptyList()): String {
        val sb = StringBuilder()
        var lastLineStart = 0
        for ((index, raw) in lines.withIndex()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val continues = continuesPrevious.getOrElse(index) { false }
                if (continues) {
                    val merged = mergeOverlap(sb.substring(lastLineStart), line)
                    sb.setLength(lastLineStart)
                    sb.append(merged.first)
                    line = merged.second
                    if (line.isEmpty()) continue
                    if (sb.last() == '.' && !sb.endsWith("..")) sb.setLength(sb.length - 1)
                }
                if (continues || sb.last() == ',') {
                    val firstWord = line.substringBefore(' ')
                    if (firstWord !in pronounI && line.length > 1 && line[0].isUpperCase() &&
                        firstWord.drop(1).none { it.isUpperCase() }
                    ) {
                        line = line[0].lowercaseChar() + line.substring(1)
                    }
                }
                sb.append(' ')
            }
            lastLineStart = sb.length
            sb.append(line)
        }
        return sb.toString()
    }

    /**
     * Merges the words [next] repeats from the end of [previous]. It finds the
     * longest run of words (ignoring case and punctuation) that both share
     * near the cut and keeps it once. Words of [previous] after the run were
     * decoded from a word cut off at the end of its audio, and words of [next]
     * before the run from audio the previous line already covered, so both are
     * dropped. The first word of [next] may also be the cut-off tail of a word
     * in [previous]. A single shared word only lets words be dropped if it is
     * at least [MERGE_MIN_SINGLE_WORD] letters long. Returns the new
     * (previous, next) texts, unchanged when nothing matches.
     */
    fun mergeOverlap(previous: String, next: String): Pair<String, String> {
        val prev = previous.split(' ').filter { it.isNotEmpty() }
        val nxt = next.split(' ').filter { it.isNotEmpty() }
        val p = prev.map(::normalizeWord)
        val n = nxt.map(::normalizeWord)
        var best: Triple<Int, Int, Int>? = null // prevStart, nextStart, length
        var bestJunk = 0
        for (i in maxOf(0, p.size - MERGE_WINDOW) until p.size) {
            for (j in 0 until minOf(n.size, MERGE_MAX_SKIP + 1)) {
                var k = 0
                while (i + k < p.size && j + k < n.size && p[i + k].isNotEmpty() &&
                    (p[i + k] == n[j + k] || (k == 0 && j == 0 && isFragmentOf(n[0], p[i])))
                ) k++
                if (k == 0) continue
                val junk = (p.size - i - k) + j
                if (p.size - i - k > MERGE_MAX_SKIP) continue
                if (k == 1 && junk > 0 && p[i].length < MERGE_MIN_SINGLE_WORD) continue
                if (best == null || k > best.third || (k == best.third && junk < bestJunk)) {
                    best = Triple(i, j, k)
                    bestJunk = junk
                }
            }
        }
        val (i, j, k) = best ?: return previous to next
        return prev.subList(0, i + k).joinToString(" ") to nxt.subList(j + k, nxt.size).joinToString(" ")
    }

    private fun normalizeWord(word: String): String = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    /** "graphs" decoded from the tail of "photographs" that the overlap cut off. */
    private fun isFragmentOf(fragment: String, word: String): Boolean =
        fragment.length >= 3 && word.length > fragment.length && word.endsWith(fragment)

    /** A single shared word only anchors a merge that drops words if it is at least this long. */
    private const val MERGE_MIN_SINGLE_WORD = 4

    /** How many words at the end of the previous line may take part in a merge. */
    private const val MERGE_WINDOW = 6

    /** How many words a merge may drop on either side. */
    private const val MERGE_MAX_SKIP = 2
}
