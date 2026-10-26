package org.futo.inputmethod.latin.heatmap

import kotlin.math.abs

/** Visual geometry of a key in keyboard coordinates (pixels). */
class KeyGeometry(
    @JvmField val centerX: Float,
    @JvmField val centerY: Float,
    @JvmField val width: Float,
    @JvmField val height: Float
)

/** A recorded tap of the composing word, in keyboard coordinates. */
class RecordedTap(
    @JvmField val codePoint: Int,
    @JvmField val x: Int,
    @JvmField val y: Int,
    /** True only for plain software key taps (no gesture, key repeat, resumed text, etc). */
    @JvmField val isPlainTap: Boolean
)

enum class AlignmentRejection {
    NONE,
    EMPTY,
    BATCH_OR_RESUMED,
    LENGTH_MISMATCH,
    NOT_PLAIN_TAP,
    TAP_TEXT_MISMATCH,
    NOT_LETTERS,
    TOO_MANY_SUBSTITUTIONS,
    TRANSPOSITION,
    UNKNOWN_KEY,
    IMPLAUSIBLE_TAP,
    OUTLIER
}

class AlignmentResult(
    @JvmField val samples: List<TapSample>?,
    @JvmField val rejection: AlignmentRejection
)

/**
 * Aligns the taps of a finalized word with the word that was finally committed, producing
 * per-key offset samples. Only unambiguous cases are learned from, namely the same number of plain taps
 * as letters (substitutions only), letter-only words, and few substitutions.
 */
object TapAligner {
    private fun reject(r: AlignmentRejection) = AlignmentResult(null, r)

    private fun codePoints(s: String): IntArray = s.codePoints().toArray()

    @JvmStatic
    fun align(
        typedWord: String,
        committedWord: String,
        taps: List<RecordedTap>?,
        isBatchMode: Boolean,
        isResumed: Boolean,
        keyLookup: (Int) -> KeyGeometry?
    ): AlignmentResult {
        if (isBatchMode || isResumed) return reject(AlignmentRejection.BATCH_OR_RESUMED)
        val typed = codePoints(typedWord)
        val committed = codePoints(committedWord)
        if (typed.isEmpty() || taps == null) return reject(AlignmentRejection.EMPTY)
        if (typed.size != committed.size || taps.size != typed.size) {
            return reject(AlignmentRejection.LENGTH_MISMATCH)
        }
        val n = typed.size

        for (i in 0 until n) {
            if (!taps[i].isPlainTap) return reject(AlignmentRejection.NOT_PLAIN_TAP)
        }
        for (i in 0 until n) {
            if (Character.toLowerCase(taps[i].codePoint) != Character.toLowerCase(typed[i])) {
                return reject(AlignmentRejection.TAP_TEXT_MISMATCH)
            }
        }
        for (i in 0 until n) {
            if (!Character.isLetter(typed[i]) || !Character.isLetter(committed[i])) {
                return reject(AlignmentRejection.NOT_LETTERS)
            }
        }

        val t = IntArray(n) { Character.toLowerCase(typed[it]) }
        val c = IntArray(n) { Character.toLowerCase(committed[it]) }

        var substitutions = 0
        for (i in 0 until n) if (t[i] != c[i]) substitutions++
        // More than ~1/3 substituted letters likely means a different word was chosen,
        // rather than a mistyped one.
        if (substitutions * 3 > n) return reject(AlignmentRejection.TOO_MANY_SUBSTITUTIONS)

        // Adjacent transpositions ("teh" -> "the") would misalign taps with letters.
        for (i in 0 until n - 1) {
            if (t[i] != c[i] && t[i] == c[i + 1] && t[i + 1] == c[i]) {
                return reject(AlignmentRejection.TRANSPOSITION)
            }
        }

        val samples = ArrayList<TapSample>(n)
        for (i in 0 until n) {
            val tap = taps[i]
            val typedKey = keyLookup(t[i]) ?: return reject(AlignmentRejection.UNKNOWN_KEY)
            val intendedKey = if (t[i] == c[i]) typedKey
                else keyLookup(c[i]) ?: return reject(AlignmentRejection.UNKNOWN_KEY)

            // Sanity check that the coordinates belong to the main keyboard (e.g. not to a
            // long-press popup) by checking the tap is reasonably close to the key it produced.
            val tx = (tap.x - typedKey.centerX) / typedKey.width
            val ty = (tap.y - typedKey.centerY) / typedKey.height
            if (abs(tx) > HeatmapConfig.MAX_TYPED_KEY_DISTANCE
                || abs(ty) > HeatmapConfig.MAX_TYPED_KEY_DISTANCE
            ) {
                return reject(AlignmentRejection.IMPLAUSIBLE_TAP)
            }

            var dx = (tap.x - intendedKey.centerX) / intendedKey.width
            var dy = (tap.y - intendedKey.centerY) / intendedKey.height
            val limit = HeatmapConfig.MAX_SAMPLE_OFFSET
            if (t[i] != c[i]) {
                // A corrected tap far from the intended key suggests a misalignment.
                if (abs(dx) > limit || abs(dy) > limit) {
                    return reject(AlignmentRejection.OUTLIER)
                }
            } else {
                // Correct taps can legitimately be far from the key centre (e.g. in the extended
                // hitbox of an edge key), so they are clipped to keep them from dominating the mean.
                dx = dx.coerceIn(-limit, limit)
                dy = dy.coerceIn(-limit, limit)
            }
            samples.add(TapSample(c[i], dx, dy))
        }
        return AlignmentResult(samples, AlignmentRejection.NONE)
    }
}

/**
 * Records the raw tap of each code point of the composing word, indexed by position in the word
 * (mirroring how WordComposer stores InputPointers, where deleting and retyping overwrites the slot).
 */
class TapRecorder(private val capacity: Int) {
    private val codes = IntArray(capacity)
    private val xs = IntArray(capacity)
    private val ys = IntArray(capacity)
    private val plain = BooleanArray(capacity)

    fun record(index: Int, codePoint: Int, x: Int, y: Int, isPlainTap: Boolean) {
        if (index < 0 || index >= capacity) return
        codes[index] = codePoint
        xs[index] = x
        ys[index] = y
        plain[index] = isPlainTap
    }

    /** Returns the taps for a word of the given size, or null if it can't be represented. */
    fun snapshot(size: Int): List<RecordedTap>? {
        if (size <= 0 || size > capacity) return null
        return List(size) { RecordedTap(codes[it], xs[it], ys[it], plain[it]) }
    }
}
