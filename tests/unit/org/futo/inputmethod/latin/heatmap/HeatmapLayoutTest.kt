package org.futo.inputmethod.latin.heatmap

import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.KEY_H
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.KEY_W
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.key
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.select
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.stockKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class HeatmapLayoutTest {
    private fun identityLayout() = SyntheticKeyboard.layout { null }

    @Test
    fun withoutDataSelectionMatchesStockHitboxes() {
        val layouts = listOf(
            identityLayout(),
            // Keys with samples but no learned bias behave like stock too
            SyntheticKeyboard.layout { EffectiveKeyParams(0f, 0f, 1f, 1f, 0.9f) }
        )
        for (layout in layouts) {
            for (x in 0 until 1000) for (y in 0 until 450 step 3) {
                val stock = stockKey(x, y) ?: continue // non-letter areas are not handled by the heatmap
                assertEquals("at $x,$y", stock, select(layout, x, y))
            }
        }
    }

    /** Trains a model where the user consistently taps 0.4 key heights low on the middle row. */
    private fun trainedLowMiddleRow(): HeatmapLayout {
        val model = HeatmapModel()
        val rnd = Random(42)
        val samples = ArrayList<TapSample>()
        repeat(200) {
            for (c in "asdfghjkl") {
                samples.add(TapSample(c.code,
                    (rnd.nextGaussian() * 0.15).toFloat(),
                    (0.4 + rnd.nextGaussian() * 0.15).toFloat()))
            }
        }
        model.addSamples("p", samples)
        val params = model.profile("p")!!.effectiveParams()
        val layout = SyntheticKeyboard.layout { params[it] }
        assertTrue(layout.hasAdaptation)
        return layout
    }

    @Test
    fun lowTapsOnMiddleRowResolveToMiddleRowKeyAfterLearning() {
        val layout = trainedLowMiddleRow()
        val s = key('s')
        // 0.7 key heights below the centre of 's' -> inside the hitbox of 'z' (top part)
        val x = s.centerX.toInt()
        val y = (s.centerY + 0.7f * KEY_H).toInt()
        assertEquals('z', stockKey(x, y))
        assertEquals('z', select(identityLayout(), x, y))
        assertEquals('s', select(layout, x, y))

        // The centre of 'z' still produces 'z'
        val z = key('z')
        assertEquals('z', select(layout, z.centerX.toInt(), z.centerY.toInt()))
        // and so does the lower half of 'z'
        assertEquals('z', select(layout, z.centerX.toInt(), (z.centerY + 0.3f * KEY_H).toInt()))
    }

    @Test
    fun regionMovesWithLearnedOffsetSoTopEdgeGoesToRowAbove() {
        // Consequence of the shift is that a tap at the very top edge of 's' is now more likely meant
        // for the row above.
        val layout = trainedLowMiddleRow()
        val s = key('s')
        val x = s.x + 10 // left part of 's', below 'w'
        val y = s.y + 3
        assertEquals('s', stockKey(x, y))
        assertEquals('w', select(layout, x, y))
    }

    @Test
    fun coreOfKeyIsNeverStolenEvenByBoostedMaxShiftedNeighbour() {
        // 'd' learned the maximum shift towards 's' and is boosted by the dictionary.
        val layout = SyntheticKeyboard.layout {
            if (it == 'd'.code) EffectiveKeyParams(-HeatmapConfig.MAX_SHIFT, 0f, HeatmapConfig.MAX_SCALE, 1f, 1f)
            else null
        }
        val s = key('s')
        assertEquals('s', select(layout, s.centerX.toInt(), s.centerY.toInt(), setOf('d')))
        assertEquals('s', select(layout, s.centerX.toInt() + 10, s.centerY.toInt(), setOf('d')))
        // ...but the side of 's' next to 'd' does go to 'd'
        assertEquals('d', select(layout, s.hitRight - 15, s.centerY.toInt()))
    }

    @Test
    fun boostingWithoutDataApproximatesStockBoosting() {
        val layout = identityLayout()
        val s = key('s')
        val d = key('d')
        val y = s.centerY.toInt()
        // Stock boosting means boosted 'd' wins where distance to the edge of 'd' is smaller than the
        // distance to the centre of 's'.
        for (x in s.hitLeft until s.hitRight) {
            val stockBoostWins = (d.x - x).toFloat() < abs(x - s.centerX)
            val heatmap = select(layout, x, y, setOf('d'))
            if (abs((d.x - x) - abs(x - s.centerX)) > 10f) { // ignore ~0.1 key around the boundary
                assertEquals("at x=$x", if (stockBoostWins) 'd' else 's', heatmap)
            }
        }
    }

    @Test
    fun keysOutOfReachAreNeverSelected() {
        val layout = SyntheticKeyboard.layout {
            if (it == 'q'.code) EffectiveKeyParams(HeatmapConfig.MAX_SHIFT, 0f, HeatmapConfig.MAX_SCALE, 1f, 1f)
            else null
        }
        val e = key('e')
        val q = layout.slotForCode('q'.code)
        val p = layout.slotForCode('p'.code)
        val slots = intArrayOf(q, p)
        assertEquals(-1, layout.select(e.centerX, e.centerY, slots, null, 2))
        // But 'q' is selectable just beyond its hitbox if it is the only candidate in reach
        val w = key('w')
        assertEquals(q, layout.select(w.hitLeft + 5f, w.centerY, intArrayOf(q, p), null, 2))
    }

    @Test
    fun slotLookupIsCaseInsensitive() {
        val layout = identityLayout()
        assertEquals(layout.slotForCode('a'.code), layout.slotForCode('A'.code))
        assertEquals('m'.code, layout.codeAt(layout.slotForCode('M'.code)))
        assertEquals(-1, layout.slotForCode('1'.code))
    }

    @Test
    fun correctionMapsTapIntoResolvedKeyAndIsIdentityWithoutData() {
        val identity = identityLayout()
        val s = key('s')
        val slot = identity.slotForCode('s'.code)
        val packed = identity.correct(slot, s.x + 7, s.y + 9)
        assertEquals(s.x + 7, HeatmapLayout.unpackX(packed))
        assertEquals(s.y + 9, HeatmapLayout.unpackY(packed))

        val layout = trainedLowMiddleRow()
        val x = s.centerX.toInt()
        val y = (s.centerY + 0.7f * KEY_H).toInt()
        val c = layout.correct(layout.slotForCode('s'.code), x, y)
        val cx = HeatmapLayout.unpackX(c)
        val cy = HeatmapLayout.unpackY(c)
        assertTrue(s.contains(cx, cy))
        assertFalse(s.contains(x, y))
        // Moved up by roughly the learned offset
        assertTrue(cy < y - 0.25f * KEY_H)
        assertEquals(x.toFloat(), cx.toFloat(), 0.1f * KEY_W)
    }

    @Test
    fun packingRoundTrips() {
        for ((x, y) in listOf(0 to 0, 1234 to 5678, -1 to -4, Int.MAX_VALUE to Int.MIN_VALUE)) {
            val p = HeatmapLayout.packXY(x, y)
            assertEquals(x, HeatmapLayout.unpackX(p))
            assertEquals(y, HeatmapLayout.unpackY(p))
        }
    }
}
