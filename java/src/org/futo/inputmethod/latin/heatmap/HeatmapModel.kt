package org.futo.inputmethod.latin.heatmap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tunables for the adaptive typing heatmap. All offsets/sigmas are expressed in "key units",
 * i.e. a horizontal offset of 1.0 is one key width and a vertical offset of 1.0 is one key height.
 *
 * This file and the other pure model files in this package have no Android dependencies so they
 * can be unit-tested on the JVM.
 */
object HeatmapConfig {
    /** Shrinkage prior strength. A key with n samples gets weight n / (n + PRIOR_STRENGTH). */
    const val PRIOR_STRENGTH = 20f

    /**
     * Cap for the effective sample count. Once reached, the statistics become exponentially
     * weighted with alpha = 1 / MAX_EFFECTIVE_COUNT (half-life of ~208 samples per key), so the
     * model keeps adapting to changes in how the user types.
     */
    const val MAX_EFFECTIVE_COUNT = 300f

    /** Maximum learned shift of a key's input region, in key units (per axis). */
    const val MAX_SHIFT = 0.5f

    /** Clamp for the learned relative spread (region scale) per axis. */
    const val MIN_SCALE = 0.8f
    const val MAX_SCALE = 1.25f

    /** Prior tap standard deviation used when there is no data to pool, in key units. */
    const val PRIOR_SIGMA = 0.3f
    const val MIN_SIGMA = 0.05f

    /**
     * Margin (key units) subtracted from the region distance of keys boosted by the dictionary
     * ("Smart key-hit detection"). With unadapted keys, 0.5 lets a boosted key take over the
     * quarter of its neighbour closest to it, which is where the stock boosting heuristic
     * (distance to boosted key's edge vs distance to the other key's centre) puts the boundary.
     */
    const val BOOST_MARGIN = 0.5f

    /**
     * A key can only be selected when the touch lies within this distance (key units) of its
     * stock hitbox. Prevents a tap from ever resolving to a far-away key.
     */
    const val MAX_REACH = 0.5f

    /**
     * Touches at least this deep (key units) inside a key's stock hitbox, i.e. within ~0.2 key of
     * its centre, always resolve to that key.
     */
    const val CORE_DEPTH = 0.3f

    /** Taps further than this from the intended key centre (key units) are considered outliers. */
    const val MAX_SAMPLE_OFFSET = 0.9f

    /** A typed letter's tap must be within this distance of the typed key (sanity check). */
    const val MAX_TYPED_KEY_DISTANCE = 1.5f

    /** Number of recent offsets kept per key for the visualization. */
    const val RESERVOIR_SIZE = 100

    const val FILE_VERSION = 1
}

/** Region adaptation for a key derived from its statistics. Shifts are in key units. */
class EffectiveKeyParams(
    @JvmField val shiftX: Float,
    @JvmField val shiftY: Float,
    @JvmField val scaleX: Float,
    @JvmField val scaleY: Float,
    @JvmField val weight: Float
) {
    companion object {
        @JvmField val IDENTITY = EffectiveKeyParams(0f, 0f, 1f, 1f, 0f)
    }
}

/**
 * Aggregated tap statistics for one key. Only aggregates and a bounded set of recent offsets
 * (which carry no ordering information across keys) are kept, so typed text cannot be
 * reconstructed from this.
 */
class KeyTapStats {
    var effectiveCount = 0f
        private set
    var totalCount = 0L
        private set
    var meanX = 0f
        private set
    var meanY = 0f
        private set
    var varX = 0f
        private set
    var varY = 0f
        private set
    var covXY = 0f
        private set

    private val pointsX = FloatArray(HeatmapConfig.RESERVOIR_SIZE)
    private val pointsY = FloatArray(HeatmapConfig.RESERVOIR_SIZE)
    var pointCount = 0
        private set
    private var pointCursor = 0

    fun add(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        effectiveCount = min(effectiveCount + 1f, HeatmapConfig.MAX_EFFECTIVE_COUNT)
        totalCount++
        // Welford's update while effectiveCount < cap, and exponentially weighted after that.
        val alpha = 1f / effectiveCount
        val ddx = dx - meanX
        val ddy = dy - meanY
        meanX += alpha * ddx
        meanY += alpha * ddy
        varX = (1f - alpha) * (varX + alpha * ddx * ddx)
        varY = (1f - alpha) * (varY + alpha * ddy * ddy)
        covXY = (1f - alpha) * (covXY + alpha * ddx * ddy)
        addPoint(dx, dy)
    }

    private fun addPoint(dx: Float, dy: Float) {
        pointsX[pointCursor] = dx
        pointsY[pointCursor] = dy
        pointCursor = (pointCursor + 1) % HeatmapConfig.RESERVOIR_SIZE
        pointCount = min(pointCount + 1, HeatmapConfig.RESERVOIR_SIZE)
    }

    fun pointX(i: Int): Float = pointsX[i]
    fun pointY(i: Int): Float = pointsY[i]

    /**
     * Shrinks the learned statistics towards the prior (no shift, reference spread) and clamps
     * them so a key's region can never move too far.
     */
    fun effective(refSigmaX: Float, refSigmaY: Float): EffectiveKeyParams {
        if (effectiveCount <= 0f) return EffectiveKeyParams.IDENTITY
        val w = effectiveCount / (effectiveCount + HeatmapConfig.PRIOR_STRENGTH)
        val shiftX = clampAbs(w * meanX, HeatmapConfig.MAX_SHIFT)
        val shiftY = clampAbs(w * meanY, HeatmapConfig.MAX_SHIFT)
        val scaleX = shrunkScale(varX, refSigmaX, w)
        val scaleY = shrunkScale(varY, refSigmaY, w)
        return EffectiveKeyParams(shiftX, shiftY, scaleX, scaleY, w)
    }

    fun copy(): KeyTapStats {
        val c = KeyTapStats()
        c.restore(effectiveCount, totalCount, meanX, meanY, varX, varY, covXY,
            orderedPointsX(), orderedPointsY())
        return c
    }

    /** Points from oldest to newest. */
    fun orderedPointsX(): FloatArray = ordered(pointsX)
    fun orderedPointsY(): FloatArray = ordered(pointsY)

    private fun ordered(arr: FloatArray): FloatArray {
        val out = FloatArray(pointCount)
        val start = if (pointCount < HeatmapConfig.RESERVOIR_SIZE) 0 else pointCursor
        for (i in 0 until pointCount) out[i] = arr[(start + i) % HeatmapConfig.RESERVOIR_SIZE]
        return out
    }

    internal fun restore(
        effectiveCount: Float, totalCount: Long,
        meanX: Float, meanY: Float, varX: Float, varY: Float, covXY: Float,
        px: FloatArray, py: FloatArray
    ) {
        this.effectiveCount = effectiveCount.coerceIn(0f, HeatmapConfig.MAX_EFFECTIVE_COUNT)
        this.totalCount = max(0L, totalCount)
        this.meanX = meanX
        this.meanY = meanY
        this.varX = max(0f, varX)
        this.varY = max(0f, varY)
        this.covXY = covXY
        pointCount = 0
        pointCursor = 0
        val n = min(px.size, py.size)
        // Keep the newest points if there are too many.
        for (i in max(0, n - HeatmapConfig.RESERVOIR_SIZE) until n) addPoint(px[i], py[i])
    }

    companion object {
        private fun clampAbs(v: Float, limit: Float) = v.coerceIn(-limit, limit)

        private fun shrunkScale(variance: Float, refSigma: Float, w: Float): Float {
            val ref = max(refSigma, HeatmapConfig.MIN_SIGMA)
            val v = w * variance + (1f - w) * ref * ref
            return (sqrt(v) / ref).coerceIn(HeatmapConfig.MIN_SCALE, HeatmapConfig.MAX_SCALE)
        }
    }
}

/** A schematic of a keyboard, normalized to [0,1] in both axes, used for the visualization. */
@Serializable
data class SchematicKey(
    val code: Int,
    val label: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

@Serializable
data class KeyboardSchematic(
    /** Width / height of the keyboard. */
    val aspect: Float,
    val keys: List<SchematicKey>
)

/** One adaptation profile, e.g. "qwerty" in portrait. */
class HeatmapProfile {
    val keys: MutableMap<Int, KeyTapStats> = HashMap()
    var schematic: KeyboardSchematic? = null

    fun totalSamples(): Long = keys.values.sumOf { it.totalCount }

    /** Pooled per-axis tap standard deviation across keys, used as the spread reference. */
    fun referenceSigma(): Pair<Float, Float> {
        var n = 0f
        var sx = 0f
        var sy = 0f
        for (s in keys.values) {
            n += s.effectiveCount
            sx += s.effectiveCount * s.varX
            sy += s.effectiveCount * s.varY
        }
        if (n <= 0f) return Pair(HeatmapConfig.PRIOR_SIGMA, HeatmapConfig.PRIOR_SIGMA)
        return Pair(
            max(sqrt(sx / n), HeatmapConfig.MIN_SIGMA),
            max(sqrt(sy / n), HeatmapConfig.MIN_SIGMA)
        )
    }

    /** Effective parameters for every key in this profile, keyed by lowercase code point. */
    fun effectiveParams(): Map<Int, EffectiveKeyParams> {
        val (rx, ry) = referenceSigma()
        return keys.mapValues { it.value.effective(rx, ry) }
    }

    fun copy(): HeatmapProfile {
        val p = HeatmapProfile()
        keys.forEach { (k, v) -> p.keys[k] = v.copy() }
        p.schematic = schematic
        return p
    }
}

/** One learned tap, with the intended key (lowercase code point) and the offset from its centre. */
class TapSample(
    @JvmField val codePoint: Int,
    @JvmField val dx: Float,
    @JvmField val dy: Float
)

class HeatmapModel {
    val profiles: MutableMap<String, HeatmapProfile> = HashMap()

    fun profile(id: String): HeatmapProfile? = profiles[id]

    fun addSamples(profileId: String, samples: List<TapSample>) {
        if (samples.isEmpty()) return
        val profile = profiles.getOrPut(profileId) { HeatmapProfile() }
        for (s in samples) {
            profile.keys.getOrPut(s.codePoint) { KeyTapStats() }.add(s.dx, s.dy)
        }
    }

    fun isEmpty(): Boolean = profiles.values.all { it.keys.isEmpty() }

    fun copy(): HeatmapModel {
        val m = HeatmapModel()
        profiles.forEach { (k, v) -> m.profiles[k] = v.copy() }
        return m
    }

    fun toJson(): String {
        val file = HeatmapFile(
            version = HeatmapConfig.FILE_VERSION,
            profiles = profiles.mapValues { (_, p) ->
                ProfileData(
                    keys = p.keys.mapValues { (_, s) ->
                        KeyData(
                            n = s.effectiveCount,
                            total = s.totalCount,
                            mx = s.meanX, my = s.meanY,
                            vx = s.varX, vy = s.varY, cxy = s.covXY,
                            px = s.orderedPointsX().map(::round3),
                            py = s.orderedPointsY().map(::round3)
                        )
                    },
                    schematic = p.schematic
                )
            }
        )
        return json.encodeToString(HeatmapFile.serializer(), file)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = false }

        private fun round3(v: Float): Float = (v * 1000f).roundToInt() / 1000f

        private fun Float.sane(limit: Float): Boolean = isFinite() && abs(this) <= limit

        /**
         * Parses a saved model. Returns null if the data is corrupt or of an unknown version,
         * in which case the caller should discard it.
         */
        fun fromJson(text: String): HeatmapModel? {
            val file = try {
                json.decodeFromString(HeatmapFile.serializer(), text)
            } catch (e: Exception) {
                return null
            }
            if (file.version != HeatmapConfig.FILE_VERSION) return null

            val model = HeatmapModel()
            for ((id, pd) in file.profiles) {
                val profile = HeatmapProfile()
                for ((code, kd) in pd.keys) {
                    if (!kd.n.sane(1e6f) || !kd.mx.sane(10f) || !kd.my.sane(10f)
                        || !kd.vx.sane(100f) || !kd.vy.sane(100f) || !kd.cxy.sane(100f)
                    ) return null
                    val stats = KeyTapStats()
                    val px = kd.px.filter { it.isFinite() }.toFloatArray()
                    val py = kd.py.filter { it.isFinite() }.toFloatArray()
                    stats.restore(kd.n, kd.total, kd.mx, kd.my, kd.vx, kd.vy, kd.cxy, px, py)
                    profile.keys[code] = stats
                }
                profile.schematic = pd.schematic
                model.profiles[id] = profile
            }
            return model
        }
    }
}

@Serializable
internal data class KeyData(
    val n: Float,
    val total: Long,
    val mx: Float,
    val my: Float,
    val vx: Float,
    val vy: Float,
    val cxy: Float,
    val px: List<Float> = emptyList(),
    val py: List<Float> = emptyList()
)

@Serializable
internal data class ProfileData(
    val keys: Map<Int, KeyData>,
    val schematic: KeyboardSchematic? = null
)

@Serializable
internal data class HeatmapFile(
    val version: Int,
    val profiles: Map<String, ProfileData>
)
