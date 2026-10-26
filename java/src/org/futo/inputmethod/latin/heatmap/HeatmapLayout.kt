package org.futo.inputmethod.latin.heatmap

import kotlin.math.abs
import kotlin.math.max

/** Geometry of one adaptable (letter) key used to build a [HeatmapLayout]. Pixels. */
class LayoutKey(
    /** Lowercase code point. */
    @JvmField val code: Int,
    @JvmField val hitLeft: Int,
    @JvmField val hitTop: Int,
    @JvmField val hitRight: Int,
    @JvmField val hitBottom: Int,
    /** Visual key size, the unit in which learned offsets are expressed. */
    @JvmField val visualWidth: Int,
    @JvmField val visualHeight: Int
)

/**
 * Precomputed, immutable per-keyboard parameters for adaptive key detection.
 *
 * Each letter key's effective input region is its stock hitbox, translated by the learned
 * (shrunk, clamped) mean tap offset and scaled by the learned relative spread. A touch is scored
 * against a key by its signed Chebyshev distance to that region, in key units (negative inside,
 * i.e. minus the depth inside the region). The key with the lowest score wins. Where regions
 * overlap the touch goes to the region it is deeper inside, and in gaps to the closest region.
 *
 * For keys without data the region is exactly the stock hitbox, and since stock hitboxes don't
 * overlap, a touch scores <= 0 only for the key whose hitbox contains it, so with no learned data
 * this reproduces stock behaviour exactly.
 *
 * Scoring is allocation-free.
 */
class HeatmapLayout private constructor(
    private val codes: IntArray,
    private val stockCx: FloatArray,
    private val stockCy: FloatArray,
    private val stockHalfW: FloatArray,
    private val stockHalfH: FloatArray,
    private val cx: FloatArray,
    private val cy: FloatArray,
    private val halfW: FloatArray,
    private val halfH: FloatArray,
    private val hitLeft: IntArray,
    private val hitTop: IntArray,
    private val hitRight: IntArray,
    private val hitBottom: IntArray,
    private val unitW: FloatArray,
    private val unitH: FloatArray,
    /** Slot indices sorted by code, for [slotForCode]. */
    private val sortedCodes: IntArray,
    private val sortedSlots: IntArray,
    /** True if at least one key has been adapted. */
    val hasAdaptation: Boolean
) {
    val size: Int get() = codes.size

    fun codeAt(slot: Int): Int = codes[slot]

    /** Returns the slot for a code point (case-insensitive), or -1. */
    fun slotForCode(codePoint: Int): Int {
        val code = Character.toLowerCase(codePoint)
        var lo = 0
        var hi = sortedCodes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sortedCodes[mid]
            if (v < code) lo = mid + 1 else if (v > code) hi = mid - 1 else {
                // Return the first slot with this code
                var m = mid
                while (m > 0 && sortedCodes[m - 1] == code) m--
                return sortedSlots[m]
            }
        }
        return -1
    }

    /** Signed distance (key units) to the stock hitbox, where <= 0 means inside. */
    fun stockDistance(slot: Int, x: Float, y: Float): Float = max(
        (abs(x - stockCx[slot]) - stockHalfW[slot]) / unitW[slot],
        (abs(y - stockCy[slot]) - stockHalfH[slot]) / unitH[slot]
    )

    /** Signed distance (key units) to the adapted region, where <= 0 means inside. */
    fun score(slot: Int, x: Float, y: Float): Float = max(
        (abs(x - cx[slot]) - halfW[slot]) / unitW[slot],
        (abs(y - cy[slot]) - halfH[slot]) / unitH[slot]
    )

    /**
     * Chooses the best key among candidate slots.
     *
     * @param slots candidate slots (first [count] entries are used)
     * @param boosted optional per-candidate flags for dictionary-boosted keys
     * @return the selected slot or -1 if no candidate is within reach
     */
    fun select(x: Float, y: Float, slots: IntArray, boosted: BooleanArray?, count: Int): Int {
        // A touch in the core of a key always produces that key, whatever its neighbours learned.
        for (i in 0 until count) {
            val s = slots[i]
            if (stockDistance(s, x, y) <= -HeatmapConfig.CORE_DEPTH) return s
        }

        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in 0 until count) {
            val s = slots[i]
            if (stockDistance(s, x, y) > HeatmapConfig.MAX_REACH) continue
            var score = score(s, x, y)
            if (boosted != null && boosted[i]) score -= HeatmapConfig.BOOST_MARGIN
            if (score < bestScore || (score == bestScore && best >= 0 && codes[s] > codes[best])) {
                best = s
                bestScore = score
            }
        }
        return best
    }

    /**
     * Maps a touch resolved to [slot] from the key's adapted region back into its stock region
     * (inverse of the region's translation/scale), clamped into the key's hitbox. This is the
     * tap position the suggestion engines should see, and it always lies on the resolved key.
     *
     * @return x and y packed with [packXY]
     */
    fun correct(slot: Int, x: Int, y: Int): Long {
        var nx = stockCx[slot] + (x - cx[slot]) * (stockHalfW[slot] / halfW[slot])
        var ny = stockCy[slot] + (y - cy[slot]) * (stockHalfH[slot] / halfH[slot])
        nx = nx.coerceIn(hitLeft[slot].toFloat(), (hitRight[slot] - 1).toFloat())
        ny = ny.coerceIn(hitTop[slot].toFloat(), (hitBottom[slot] - 1).toFloat())
        return packXY(Math.round(nx), Math.round(ny))
    }

    companion object {
        @JvmStatic
        fun packXY(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

        @JvmStatic
        fun unpackX(packed: Long): Int = (packed shr 32).toInt()

        @JvmStatic
        fun unpackY(packed: Long): Int = packed.toInt()

        @JvmStatic
        fun build(keys: List<LayoutKey>, params: (Int) -> EffectiveKeyParams?): HeatmapLayout {
            val n = keys.size
            val codes = IntArray(n)
            val stockCx = FloatArray(n)
            val stockCy = FloatArray(n)
            val stockHalfW = FloatArray(n)
            val stockHalfH = FloatArray(n)
            val cx = FloatArray(n)
            val cy = FloatArray(n)
            val halfW = FloatArray(n)
            val halfH = FloatArray(n)
            val hitLeft = IntArray(n)
            val hitTop = IntArray(n)
            val hitRight = IntArray(n)
            val hitBottom = IntArray(n)
            val unitW = FloatArray(n)
            val unitH = FloatArray(n)
            var adapted = false
            for (i in 0 until n) {
                val k = keys[i]
                codes[i] = k.code
                hitLeft[i] = k.hitLeft
                hitTop[i] = k.hitTop
                hitRight[i] = max(k.hitRight, k.hitLeft + 1)
                hitBottom[i] = max(k.hitBottom, k.hitTop + 1)
                stockCx[i] = (hitLeft[i] + hitRight[i]) / 2f
                stockCy[i] = (hitTop[i] + hitBottom[i]) / 2f
                stockHalfW[i] = (hitRight[i] - hitLeft[i]) / 2f
                stockHalfH[i] = (hitBottom[i] - hitTop[i]) / 2f
                unitW[i] = max(k.visualWidth, 1).toFloat()
                unitH[i] = max(k.visualHeight, 1).toFloat()
                val p = params(k.code) ?: EffectiveKeyParams.IDENTITY
                if (p.weight > 0f) adapted = true
                cx[i] = stockCx[i] + p.shiftX * k.visualWidth
                cy[i] = stockCy[i] + p.shiftY * k.visualHeight
                halfW[i] = stockHalfW[i] * p.scaleX
                halfH[i] = stockHalfH[i] * p.scaleY
            }
            val order = (0 until n).sortedWith(compareBy({ codes[it] }, { it }))
            val sortedCodes = IntArray(n) { codes[order[it]] }
            val sortedSlots = IntArray(n) { order[it] }
            return HeatmapLayout(
                codes, stockCx, stockCy, stockHalfW, stockHalfH, cx, cy, halfW, halfH,
                hitLeft, hitTop, hitRight, hitBottom, unitW, unitH, sortedCodes, sortedSlots, adapted
            )
        }
    }
}
