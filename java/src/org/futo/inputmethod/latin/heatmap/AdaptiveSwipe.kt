package org.futo.inputmethod.latin.heatmap

import org.futo.inputmethod.accessibility.AccessibilityUtils
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.settings.SettingsValues
import kotlin.math.roundToInt

/**
 * Adaptive swipe learning. Swiped words are learned by [TypingHeatmap] (in its own "swipe" profile per
 * layout, next to the tap profiles) once they are final, and only when the path agrees with the final word
 * (see [SwipeAligner]). This object bends new swipe paths with what was learned, before they reach the
 * swipe recognizer (both the neural decoder and the legacy one read the bent path).
 */
object AdaptiveSwipe {
    private const val TAG = "AdaptiveSwipe"
    private const val SWIPE_PROFILE_SUFFIX = "|swipe"

    @JvmStatic
    fun isEnabled(sv: SettingsValues?): Boolean = sv?.mAdaptiveSwipe ?: false

    fun swipeProfileId(tapProfileId: String): String = tapProfileId + SWIPE_PROFILE_SUFFIX

    @JvmStatic
    fun isSwipeProfile(profileId: String): Boolean = profileId.endsWith(SWIPE_PROFILE_SUFFIX)

    private fun settings(): SettingsValues? = try {
        Settings.getInstance().current
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // Counts of learned and skipped swipes for the developer log (see TypingHeatmap.devLog).

    private val counts = IntArray(SwipeRejection.entries.size)

    internal fun countResult(result: SwipeAlignmentResult, predicted: String, final: String) {
        val rejection = result.rejection
        counts[rejection.ordinal]++
        TypingHeatmap.devLog(TAG) {
            val words = if (predicted == final) "\"$final\"" else "\"$predicted\" -> \"$final\""
            val what = if (rejection == SwipeRejection.NONE) "learned ${result.samples?.size ?: 0} points"
                else if (result.detail.isEmpty()) "skipped ($rejection)" else "skipped ($rejection: ${result.detail})"
            "Swipe $words $what; so far " +
                SwipeRejection.entries.filter { counts[it.ordinal] > 0 }
                    .joinToString(", ") { "${it.name.lowercase()} ${counts[it.ordinal]}" }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Path bending

    private class CachedField(
        val keyboard: Keyboard, val landscape: Boolean, val modelVersion: Int, val strength: Int,
        val field: SwipeBendField?
    )

    @Volatile
    private var cached: CachedField? = null

    private fun currentField(sv: SettingsValues): SwipeBendField? {
        val keyboard = TypingHeatmap.lastAlphabetKeyboard() ?: return null
        val landscape = TypingHeatmap.lastLandscape()
        val version = TypingHeatmap.getModelVersion()
        val strength = sv.mAdaptiveSwipeStrength
        val c = cached
        if (c != null && c.keyboard === keyboard && c.landscape == landscape
            && c.modelVersion == version && c.strength == strength
        ) return c.field

        val stats = TypingHeatmap.copyProfileKeys(swipeProfileId(TypingHeatmap.profileIdFor(keyboard, landscape)))
        val field = if (stats.isNullOrEmpty()) null else {
            SwipeBendField.build(TypingHeatmap.letterKeyGeometries(keyboard)) { code ->
                stats[code]?.let { SwipeConfig.appliedShift(it, strength) }
            }.takeIf { it.isActive }
        }
        cached = CachedField(keyboard, landscape, version, strength, field)
        return field
    }

    /**
     * Returns a copy of [pointers] with every point moved to undo this user's learned swipe offsets, or
     * [pointers] itself when nothing applies. [pointers] is never modified (its arrays are shared with the
     * gesture tracker).
     */
    @JvmStatic
    fun bend(pointers: InputPointers): InputPointers {
        val sv = settings() ?: return pointers
        if (!isEnabled(sv)) return pointers
        val touchExploration = try {
            AccessibilityUtils.getInstance().isTouchExplorationEnabled
        } catch (e: Exception) {
            false
        }
        if (touchExploration) return pointers
        val field = currentField(sv) ?: return pointers

        val out = InputPointers(pointers.pointerSize)
        out.copy(pointers)
        val n = out.pointerSize
        val xs = out.xCoordinates
        val ys = out.yCoordinates
        for (i in 0 until n) {
            val (ox, oy) = field.offset(xs[i].toFloat(), ys[i].toFloat())
            xs[i] = (xs[i] + ox).roundToInt()
            ys[i] = (ys[i] + oy).roundToInt()
        }
        // copy() shares the segment objects, so replace them with bent copies.
        val segments = out.gestureSegments
        val bent = segments.map { seg ->
            val copy = InputPointers.GestureSegment(seg.pointerId)
            copy.x.copy(seg.x)
            copy.y.copy(seg.y)
            copy.t.copy(seg.t)
            val sx = copy.x.primitiveArray
            val sy = copy.y.primitiveArray
            for (i in 0 until minOf(copy.x.length, copy.y.length)) {
                val (ox, oy) = field.offset(sx[i].toFloat(), sy[i].toFloat())
                sx[i] = (sx[i] + ox).roundToInt()
                sy[i] = (sy[i] + oy).roundToInt()
            }
            copy
        }
        segments.clear()
        segments.addAll(bent)
        return out
    }

    /**
     * The raw path of a one-finger swipe, copied, for learning once the word is final. Null for swipes with
     * two fingers (their segments can't be matched to the word's letters in order) or when learning is off.
     */
    @JvmStatic
    fun capturePath(pointers: InputPointers): SwipePath? {
        if (!isEnabled(settings())) return null
        val segments = pointers.gestureSegments.filter { it.x.length > 0 }
        if (segments.size > 1) return null
        val seg = segments.firstOrNull()
        val n: Int
        val xs: IntArray
        val ys: IntArray
        if (seg != null) {
            n = minOf(seg.x.length, seg.y.length)
            xs = seg.x.primitiveArray
            ys = seg.y.primitiveArray
        } else {
            n = pointers.pointerSize
            xs = pointers.xCoordinates
            ys = pointers.yCoordinates
        }
        if (n < 2) return null
        return SwipePath(FloatArray(n) { xs[it].toFloat() }, FloatArray(n) { ys[it].toFloat() })
    }

    /** Deletes everything learned from swipes (the tap heatmap is kept). */
    fun reset() {
        TypingHeatmap.resetProfiles { isSwipeProfile(it) }
        cached = null
    }

    /** Number of samples learned from swipes, over all layouts. */
    fun learnedSamples(): Long = TypingHeatmap.snapshot()
        .filter { isSwipeProfile(it.id) }
        .sumOf { it.profile.totalSamples() }
}
