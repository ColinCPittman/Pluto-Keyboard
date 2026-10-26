package org.futo.inputmethod.latin.heatmap

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tunables for adaptive swipe learning. Distances are in key units, like [HeatmapConfig].
 */
object SwipeConfig {
    // The limits below were 1.0 key at first. On the phone, 8 of 21 correctly swiped words were rejected
    // at 1.00 to 1.49 keys (mostly corner cutting and lifting off past the last key), while real mismatches
    // (a different word, a forgotten letter) were 1.6 keys off or more.

    /** Every letter of the word must have a path point within this distance of its key. */
    const val VISIT_LIMIT = 1.4f

    /** The first point, and the path's closest approach to the last letter, must be this close to those keys. */
    const val END_LIMIT = 1.4f

    /** Every sharp turn must be within this distance of one of the word's keys. */
    const val TURN_LIMIT = 1.4f

    /**
     * The path may run on this far (key units) past its closest approach to the last letter, since fingers
     * often lift off a little past the last key when swiping fast. That overshoot isn't where the user aims,
     * so the last letter is learned from the closest approach instead.
     */
    const val MAX_OVERSHOOT = 1.5f

    /** The path is resampled every this many key units before looking for turns. */
    const val RESAMPLE_STEP = 0.2f

    /** Turns are measured over this many resampled steps on each side (about 0.6 key). */
    const val TURN_SPAN = 3

    /** A direction change of more than this (degrees) is a sharp turn. */
    const val TURN_ANGLE = 70f

    /** Path length must be within [MIN_LENGTH_RATIO] x ideal - slack .. [MAX_LENGTH_RATIO] x ideal + slack. */
    const val MIN_LENGTH_RATIO = 0.6f
    const val MAX_LENGTH_RATIO = 2.2f
    const val LENGTH_SLACK = 1.5f

    /** Learned offsets are clipped to this, like taps. */
    const val MAX_SAMPLE_OFFSET = 0.9f

    /** Spread (key units) of each key's influence when bending a path. */
    const val BEND_SIGMA = 0.6f

    /** Largest distance (key units) any path point is moved. */
    const val MAX_BEND = 0.6f

    /** Strength levels of the settings slider. */
    const val MIN_STRENGTH = 1
    const val MAX_STRENGTH = 5
    const val DEFAULT_STRENGTH = 3

    /** Samples needed before a key's learned offset counts half, per strength level. */
    private val PRIOR = floatArrayOf(60f, 40f, 25f, 15f, 10f)

    /** How much of the learned offset is applied to paths, per strength level. */
    private val SCALE = floatArrayOf(0.5f, 0.75f, 1.0f, 1.2f, 1.4f)

    private fun level(strength: Int) = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH) - MIN_STRENGTH

    fun prior(strength: Int): Float = PRIOR[level(strength)]
    fun scale(strength: Int): Float = SCALE[level(strength)]

    /** The shift (key units) to undo for a key with these statistics, at a strength level. */
    fun appliedShift(stats: KeyTapStats, strength: Int): Pair<Float, Float> {
        val n = stats.effectiveCount
        if (n <= 0f) return Pair(0f, 0f)
        val w = n / (n + prior(strength))
        val s = scale(strength)
        val sx = (w * stats.meanX).coerceIn(-HeatmapConfig.MAX_SHIFT, HeatmapConfig.MAX_SHIFT) * s
        val sy = (w * stats.meanY).coerceIn(-HeatmapConfig.MAX_SHIFT, HeatmapConfig.MAX_SHIFT) * s
        return Pair(sx.coerceIn(-MAX_BEND, MAX_BEND), sy.coerceIn(-MAX_BEND, MAX_BEND))
    }
}

/** The raw points of one swipe, in keyboard coordinates. Never stored, only held while a word can be corrected. */
class SwipePath(@JvmField val xs: FloatArray, @JvmField val ys: FloatArray) {
    val size: Int get() = min(xs.size, ys.size)
}

enum class SwipeRejection {
    NONE,
    TOO_SHORT,
    NOT_LETTERS,
    UNKNOWN_KEY,
    START_TOO_FAR,
    END_TOO_FAR,
    MISSED_LETTER,
    EXTRA_TURN,
    WRONG_LENGTH
}

class SwipeAlignmentResult(
    @JvmField val samples: List<TapSample>?,
    @JvmField val rejection: SwipeRejection,
    /** Why it was rejected, in distances only (never letters or words), for the log. */
    @JvmField val detail: String = ""
)

/**
 * Checks that a swipe path agrees with the word it was finally taken to mean, and if so produces per-key
 * offset samples from the parts of the path that show where the user aims (the start, the end, and sharp
 * turns). A path that doesn't agree (it never went near one of the letters, turned toward a key that isn't in
 * the word, or is far too short or long) is rejected, since it was a botched swipe rather than a slightly off one.
 */
object SwipeAligner {
    private fun reject(r: SwipeRejection, detail: String = "") = SwipeAlignmentResult(null, r, detail)

    private fun f2(v: Float) = String.format(java.util.Locale.ROOT, "%.2f", v)

    @JvmStatic
    fun align(word: String, path: SwipePath, keyLookup: (Int) -> KeyGeometry?): SwipeAlignmentResult {
        val codes = word.codePoints().toArray()
        if (codes.any { !Character.isLetter(it) }) return reject(SwipeRejection.NOT_LETTERS)
        // A swipe can't show a double letter, so "hello" is swiped as h-e-l-o.
        val letters = ArrayList<Int>(codes.size)
        for (c in codes) {
            val lc = Character.toLowerCase(c)
            if (letters.isEmpty() || letters.last() != lc) letters.add(lc)
        }
        if (letters.size < 2 || path.size < 2) return reject(SwipeRejection.TOO_SHORT)
        val keys = letters.map { keyLookup(it) ?: return reject(SwipeRejection.UNKNOWN_KEY) }
        val n = letters.size

        // Common unit for lengths and turns (the keys of one layout are about the same size).
        val unitW = keys.sumOf { it.width.toDouble() }.toFloat() / n
        val unitH = keys.sumOf { it.height.toDouble() }.toFloat() / n

        fun dist(k: KeyGeometry, x: Float, y: Float): Float {
            val dx = (x - k.centerX) / k.width
            val dy = (y - k.centerY) / k.height
            return sqrt(dx * dx + dy * dy)
        }

        val last = path.size - 1
        val startDist = dist(keys[0], path.xs[0], path.ys[0])
        if (startDist > SwipeConfig.END_LIMIT) {
            return reject(SwipeRejection.START_TOO_FAR, "start ${f2(startDist)} keys from the first letter (limit ${f2(SwipeConfig.END_LIMIT)}), $n letters")
        }

        // Resample by arc length (in key units), so turn detection doesn't depend on finger speed.
        val rx = ArrayList<Float>()
        val ry = ArrayList<Float>()
        rx.add(path.xs[0]); ry.add(path.ys[0])
        var pathLength = 0f
        var carry = 0f
        for (i in 1..last) {
            val x0 = path.xs[i - 1]; val y0 = path.ys[i - 1]
            val x1 = path.xs[i]; val y1 = path.ys[i]
            val ux = (x1 - x0) / unitW
            val uy = (y1 - y0) / unitH
            val seg = sqrt(ux * ux + uy * uy)
            if (seg <= 0f) continue
            pathLength += seg
            var pos = SwipeConfig.RESAMPLE_STEP - carry
            while (pos <= seg) {
                val t = pos / seg
                rx.add(x0 + (x1 - x0) * t); ry.add(y0 + (y1 - y0) * t)
                pos += SwipeConfig.RESAMPLE_STEP
            }
            carry = seg - (pos - SwipeConfig.RESAMPLE_STEP)
        }
        if (rx.last() != path.xs[last] || ry.last() != path.ys[last]) {
            rx.add(path.xs[last]); ry.add(path.ys[last])
        }
        val m = rx.size

        var ideal = 0f
        for (i in 1 until n) {
            val dx = (keys[i].centerX - keys[i - 1].centerX) / unitW
            val dy = (keys[i].centerY - keys[i - 1].centerY) / unitH
            ideal += sqrt(dx * dx + dy * dy)
        }
        if (pathLength < SwipeConfig.MIN_LENGTH_RATIO * ideal - SwipeConfig.LENGTH_SLACK
            || pathLength > SwipeConfig.MAX_LENGTH_RATIO * ideal + SwipeConfig.LENGTH_SLACK
        ) return reject(SwipeRejection.WRONG_LENGTH, "path ${f2(pathLength)} keys long, straight lines ${f2(ideal)} keys, $n letters")

        // Visit check: assign each letter, in order, the path point that best fits (least total squared
        // distance, first letter at the start and last letter at the end). Every letter must be close to its point.
        val inf = Float.MAX_VALUE
        var prev = FloatArray(m) { if (it == 0) 0f else inf }
        val from = Array(n) { IntArray(m) }
        for (i in 1 until n) {
            val cur = FloatArray(m) { inf }
            var bestPrev = inf
            var bestIdx = -1
            for (j in 0 until m) {
                if (prev[j] < bestPrev) { bestPrev = prev[j]; bestIdx = j }
                if (bestIdx < 0) continue
                val d = dist(keys[i], rx[j], ry[j])
                cur[j] = bestPrev + d * d
                from[i][j] = bestIdx
            }
            prev = cur
        }
        // The last letter goes to the best point near the end of the path (the overshoot after it is ignored).
        val assigned = IntArray(n)
        val tailStart = maxOf(0, m - 1 - kotlin.math.ceil(SwipeConfig.MAX_OVERSHOOT / SwipeConfig.RESAMPLE_STEP).toInt())
        var lastIdx = m - 1
        for (j in tailStart until m) if (prev[j] < prev[lastIdx]) lastIdx = j
        assigned[n - 1] = lastIdx
        for (i in n - 1 downTo 1) assigned[i - 1] = from[i][assigned[i]]
        val endDist = dist(keys[n - 1], rx[lastIdx], ry[lastIdx])
        if (endDist > SwipeConfig.END_LIMIT) {
            return reject(SwipeRejection.END_TOO_FAR, "end came ${f2(endDist)} keys close to the last letter at best (limit ${f2(SwipeConfig.END_LIMIT)}), $n letters")
        }
        for (i in 1 until n - 1) {
            val d = dist(keys[i], rx[assigned[i]], ry[assigned[i]])
            if (d > SwipeConfig.VISIT_LIMIT) {
                return reject(SwipeRejection.MISSED_LETTER, "letter ${i + 1} of $n: path came ${f2(d)} keys close at best (limit ${f2(SwipeConfig.VISIT_LIMIT)})")
            }
        }

        // Turn check: every sharp turn must be near one of the word's keys.
        // Turns in the overshoot after the last letter don't count.
        val turns = findTurns(rx, ry, unitW, unitH).filter { it <= lastIdx }
        for (t in turns) {
            val nearest = keys.minOf { dist(it, rx[t], ry[t]) }
            if (nearest > SwipeConfig.TURN_LIMIT) {
                return reject(SwipeRejection.EXTRA_TURN, "a turn ${f2(nearest)} keys from the nearest letter (limit ${f2(SwipeConfig.TURN_LIMIT)}), $n letters")
            }
        }

        val samples = ArrayList<TapSample>(n)
        samples.add(sample(letters[0], keys[0], path.xs[0], path.ys[0]))
        // Middle letters only count where the path turns at them. A letter passed on a straight run says
        // more about corner cutting than about where the user aims for that key.
        for (i in 1 until n - 1) {
            val t = turns.minByOrNull { abs(it - assigned[i]) } ?: continue
            if (abs(t - assigned[i]) > SwipeConfig.TURN_SPAN) continue
            if (dist(keys[i], rx[t], ry[t]) > SwipeConfig.VISIT_LIMIT) continue
            samples.add(sample(letters[i], keys[i], rx[t], ry[t]))
        }
        samples.add(sample(letters[n - 1], keys[n - 1], rx[lastIdx], ry[lastIdx]))
        return SwipeAlignmentResult(samples, SwipeRejection.NONE)
    }

    private fun sample(code: Int, key: KeyGeometry, x: Float, y: Float): TapSample {
        val limit = SwipeConfig.MAX_SAMPLE_OFFSET
        return TapSample(code,
            ((x - key.centerX) / key.width).coerceIn(-limit, limit),
            ((y - key.centerY) / key.height).coerceIn(-limit, limit))
    }

    /** Indices of resampled points where the path turns sharply (local maxima of the turn angle). */
    internal fun findTurns(rx: List<Float>, ry: List<Float>, unitW: Float, unitH: Float): List<Int> {
        val k = SwipeConfig.TURN_SPAN
        val m = rx.size
        if (m < 2 * k + 1) return emptyList()
        val angles = FloatArray(m)
        for (i in k until m - k) {
            val ax = (rx[i] - rx[i - k]) / unitW; val ay = (ry[i] - ry[i - k]) / unitH
            val bx = (rx[i + k] - rx[i]) / unitW; val by = (ry[i + k] - ry[i]) / unitH
            val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
            if (la <= 0f || lb <= 0f) continue
            val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
            angles[i] = Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
        val turns = ArrayList<Int>()
        var i = k
        while (i < m - k) {
            if (angles[i] > SwipeConfig.TURN_ANGLE) {
                // Take the sharpest point of this run of turning points.
                var best = i
                while (i < m - k && angles[i] > SwipeConfig.TURN_ANGLE) {
                    if (angles[i] > angles[best]) best = i
                    i++
                }
                turns.add(best)
            } else {
                i++
            }
        }
        return turns
    }
}

/**
 * Moves swipe path points to undo this user's learned offsets, the swipe version of [HeatmapLayout.correct].
 * Each point is moved by a smooth blend of the shifts of the keys near it, so a path passing where the user
 * usually swipes over "e" is moved toward the stock "e". Far from any adapted key nothing moves.
 * Immutable, so it can be used from the suggestion thread.
 */
class SwipeBendField(
    private val cx: FloatArray,
    private val cy: FloatArray,
    private val w: FloatArray,
    private val h: FloatArray,
    /** Shift to undo, in key units (where this user's points land relative to the key centre). */
    private val shiftX: FloatArray,
    private val shiftY: FloatArray
) {
    val isActive: Boolean = shiftX.any { it != 0f } || shiftY.any { it != 0f }

    /** Offset (pixels) to add to a point. */
    fun offset(x: Float, y: Float): Pair<Float, Float> {
        val twoSigma2 = 2f * SwipeConfig.BEND_SIGMA * SwipeConfig.BEND_SIGMA
        // A background weight with no shift makes the field fade out away from the keys.
        var sum = exp(-(1.5f * 1.5f) / twoSigma2)
        var ox = 0f
        var oy = 0f
        for (i in cx.indices) {
            val u = (x - (cx[i] + shiftX[i] * w[i])) / w[i]
            val v = (y - (cy[i] + shiftY[i] * h[i])) / h[i]
            val d2 = u * u + v * v
            if (d2 > 9f) continue
            val wt = exp(-d2 / twoSigma2)
            sum += wt
            ox -= wt * shiftX[i] * w[i]
            oy -= wt * shiftY[i] * h[i]
        }
        return Pair(ox / sum, oy / sum)
    }

    companion object {
        class Key(val code: Int, val geometry: KeyGeometry)

        fun build(keys: List<Key>, shift: (Int) -> Pair<Float, Float>?): SwipeBendField {
            val n = keys.size
            val sx = FloatArray(n)
            val sy = FloatArray(n)
            keys.forEachIndexed { i, k ->
                val s = shift(k.code)
                if (s != null) { sx[i] = s.first; sy[i] = s.second }
            }
            return SwipeBendField(
                FloatArray(n) { keys[it].geometry.centerX }, FloatArray(n) { keys[it].geometry.centerY },
                FloatArray(n) { max(1f, keys[it].geometry.width) }, FloatArray(n) { max(1f, keys[it].geometry.height) },
                sx, sy
            )
        }
    }
}
