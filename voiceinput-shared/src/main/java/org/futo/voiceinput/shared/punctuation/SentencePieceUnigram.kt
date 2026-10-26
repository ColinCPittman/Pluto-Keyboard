package org.futo.voiceinput.shared.punctuation

import java.io.File

/**
 * Minimal SentencePiece *unigram* tokenizer that loads an original `.model` file (a serialized
 * sentencepiece ModelProto) without any native code.
 *
 * This supports model_type UNIGRAM, identity normalizer (no precompiled charsmap), add_dummy_prefix,
 * whitespace -> U+2581, unknown characters mapped to the unk piece (no byte fallback).
 * This is exactly what `spe_32k_lc_en.model` (punctuation_fullstop_truecase_english) requires.
 *
 * Text is tokenized one whitespace-delimited word at a time. SentencePiece splits on whitespace
 * before running Viterbi (split_by_whitespace), so this is equivalent to encoding a whole sentence.
 */
internal class SentencePieceUnigram private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val types: IntArray,
    val unkId: Int,
    val bosId: Int,
    val eosId: Int,
    val padId: Int,
    private val addDummyPrefix: Boolean,
) {
    private val lookup = HashMap<String, Int>()
    private val unkScore: Float
    private var maxPieceCodePoints = 1

    init {
        var minScore = Float.MAX_VALUE
        for (i in pieces.indices) {
            val t = types[i]
            if (t == TYPE_NORMAL || t == TYPE_USER_DEFINED) {
                lookup[pieces[i]] = i
                if (t == TYPE_NORMAL && scores[i] < minScore) minScore = scores[i]
                val cp = pieces[i].codePointCount(0, pieces[i].length)
                if (cp > maxPieceCodePoints) maxPieceCodePoints = cp
            }
        }
        if (minScore == Float.MAX_VALUE) minScore = 0f
        unkScore = minScore - UNK_PENALTY
    }

    val size: Int get() = pieces.size

    fun piece(id: Int): String = pieces[id]

    /** Encodes one word (no whitespace) to piece ids. The dummy prefix U+2581 is added if the model says so. */
    fun encodeWord(word: String): IntArray {
        val text = if (addDummyPrefix) "▁$word" else word
        val cps = text.codePoints().toArray()
        val n = cps.size
        if (n == 0) return IntArray(0)

        // Viterbi over code points. best[i] = best score of a segmentation of cps[0, i)
        val best = FloatArray(n + 1)
        val prevStart = IntArray(n + 1) { -1 }
        val prevId = IntArray(n + 1)
        val set = BooleanArray(n + 1)
        set[0] = true

        // UTF-16 offsets of each code point for substring lookup.
        val u16 = IntArray(n + 1)
        run {
            var o = 0
            for (i in 0 until n) { u16[i] = o; o += Character.charCount(cps[i]) }
            u16[n] = o
        }

        // SentencePiece breaks score ties in favour of the candidate with the earliest start
        // (longest piece), so iterate starts ascending and replace only on a strictly better score.
        for (end in 1..n) {
            val minStart = maxOf(0, end - maxPieceCodePoints)
            for (start in minStart until end) {
                if (!set[start]) continue
                val id = lookup[text.substring(u16[start], u16[end])]
                val cand: Float
                val candId: Int
                if (id != null) {
                    cand = best[start] + scores[id]
                    candId = id
                } else if (end - start == 1) {
                    // Unknown single character
                    cand = best[start] + unkScore
                    candId = unkId
                } else continue
                if (!set[end] || cand > best[end]) {
                    set[end] = true
                    best[end] = cand
                    prevStart[end] = start
                    prevId[end] = candId
                }
            }
        }
        val out = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            out.add(prevId[pos])
            pos = prevStart[pos]
        }
        out.reverse()
        // Consecutive unknown pieces are merged into one by SentencePiece
        val merged = ArrayList<Int>(out.size)
        for (id in out) {
            if (id == unkId && merged.isNotEmpty() && merged.last() == unkId) continue
            merged.add(id)
        }
        return merged.toIntArray()
    }

    companion object {
        const val TYPE_NORMAL = 1
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4
        private const val UNK_PENALTY = 10.0f

        fun load(file: File): SentencePieceUnigram = parse(file.readBytes())

        fun parse(data: ByteArray): SentencePieceUnigram {
            val pieces = ArrayList<String>()
            val scores = ArrayList<Float>()
            val types = ArrayList<Int>()
            var unk = 0
            var bos = 1
            var eos = 2
            var pad = -1
            var modelType = 1
            var dummyPrefix = true
            var charsmapSize = 0

            val r = ProtoReader(data, 0, data.size)
            while (r.hasMore()) {
                val tag = r.readVarint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                when {
                    field == 1 && wire == 2 -> {
                        val sub = r.readMessage()
                        var piece = ""
                        var score = 0f
                        var type = TYPE_NORMAL
                        while (sub.hasMore()) {
                            val t = sub.readVarint().toInt()
                            when {
                                (t ushr 3) == 1 && (t and 7) == 2 -> piece = sub.readString()
                                (t ushr 3) == 2 && (t and 7) == 5 -> score = sub.readFloat()
                                (t ushr 3) == 3 && (t and 7) == 0 -> type = sub.readVarint().toInt()
                                else -> sub.skip(t and 7)
                            }
                        }
                        pieces.add(piece); scores.add(score); types.add(type)
                    }
                    field == 2 && wire == 2 -> {
                        val sub = r.readMessage()
                        while (sub.hasMore()) {
                            val t = sub.readVarint().toInt()
                            val f = t ushr 3
                            val w = t and 7
                            if (w == 0) {
                                val v = sub.readVarint().toInt()
                                when (f) {
                                    3 -> modelType = v
                                    40 -> unk = v
                                    41 -> bos = v
                                    42 -> eos = v
                                    43 -> pad = v
                                }
                            } else sub.skip(w)
                        }
                    }
                    field == 3 && wire == 2 -> {
                        val sub = r.readMessage()
                        while (sub.hasMore()) {
                            val t = sub.readVarint().toInt()
                            val f = t ushr 3
                            val w = t and 7
                            when {
                                f == 2 && w == 2 -> charsmapSize = sub.readMessage().remaining()
                                f == 3 && w == 0 -> dummyPrefix = sub.readVarint() != 0L
                                else -> sub.skip(w)
                            }
                        }
                    }
                    else -> r.skip(wire)
                }
            }
            require(modelType == 1) { "Only SentencePiece UNIGRAM models are supported (model_type=$modelType)" }
            require(charsmapSize == 0) { "SentencePiece models with a precompiled normalization charsmap are not supported" }
            require(pieces.isNotEmpty()) { "Not a SentencePiece model file" }
            val unkFromType = types.indexOf(TYPE_UNKNOWN)
            if (unkFromType >= 0) unk = unkFromType
            return SentencePieceUnigram(
                pieces.toTypedArray(), scores.toFloatArray(), types.toIntArray(),
                unk, bos, eos, pad, dummyPrefix
            )
        }
    }

    /** Tiny protobuf wire-format reader. */
    private class ProtoReader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end
        fun remaining() = end - pos

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 70) { "Malformed varint" }
            }
        }

        fun readMessage(): ProtoReader {
            val len = readVarint().toInt()
            val r = ProtoReader(buf, pos, pos + len)
            pos += len
            return r
        }

        fun readString(): String {
            val len = readVarint().toInt()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun readFloat(): Float {
            val bits = (buf[pos].toInt() and 0xff) or
                ((buf[pos + 1].toInt() and 0xff) shl 8) or
                ((buf[pos + 2].toInt() and 0xff) shl 16) or
                ((buf[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return java.lang.Float.intBitsToFloat(bits)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> pos += 4
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wire")
            }
        }
    }
}
