package org.futo.inputmethod.latin.heatmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapModelTest {
    private val eps = 1e-4f

    @Test
    fun welfordMeanAndVariance() {
        val s = KeyTapStats()
        s.add(0.1f, -0.2f)
        s.add(0.3f, 0.2f)
        assertEquals(0.2f, s.meanX, eps)
        assertEquals(0.0f, s.meanY, eps)
        assertEquals(0.01f, s.varX, eps)
        assertEquals(0.04f, s.varY, eps)
        assertEquals(0.02f, s.covXY, eps)
        assertEquals(2f, s.effectiveCount, eps)
        assertEquals(2L, s.totalCount)
    }

    @Test
    fun effectiveCountIsCappedSoModelKeepsAdapting() {
        val s = KeyTapStats()
        repeat(1000) { s.add(0f, 0f) }
        assertEquals(HeatmapConfig.MAX_EFFECTIVE_COUNT, s.effectiveCount, eps)
        repeat(300) { s.add(0.5f, 0f) }
        // Exponential forgetting, so after one "cap" worth of new samples, ~63% of the way there.
        assertTrue(s.meanX > 0.3f && s.meanX < 0.33f)
        assertEquals(1300L, s.totalCount)
    }

    @Test
    fun shrinkageTowardsPriorForFewSamples() {
        val few = KeyTapStats()
        repeat(5) { few.add(0.4f, 0f) }
        val p1 = few.effective(0.3f, 0.3f)
        assertEquals(0.4f * 5f / 25f, p1.shiftX, eps)
        assertEquals(0f, p1.shiftY, eps)

        val many = KeyTapStats()
        repeat(200) { many.add(0.4f, 0f) }
        val p2 = many.effective(0.3f, 0.3f)
        assertEquals(0.4f * 200f / 220f, p2.shiftX, eps)

        assertEquals(EffectiveKeyParams.IDENTITY.shiftX, KeyTapStats().effective(0.3f, 0.3f).shiftX, eps)
        assertEquals(0f, KeyTapStats().effective(0.3f, 0.3f).weight, eps)
    }

    @Test
    fun shiftAndScaleAreClamped() {
        val s = KeyTapStats()
        repeat(500) { i -> s.add(0.9f, if (i % 2 == 0) -0.9f else 0.9f) }
        val p = s.effective(0.1f, 0.1f)
        assertEquals(HeatmapConfig.MAX_SHIFT, p.shiftX, eps)
        assertTrue(p.scaleX >= HeatmapConfig.MIN_SCALE && p.scaleX <= HeatmapConfig.MAX_SCALE)
        assertEquals(HeatmapConfig.MAX_SCALE, p.scaleY, eps) // huge spread vs reference

        val tight = KeyTapStats()
        repeat(500) { tight.add(-0.9f, 0f) }
        val q = tight.effective(0.3f, 0.3f)
        assertEquals(-HeatmapConfig.MAX_SHIFT, q.shiftX, eps)
        assertEquals(HeatmapConfig.MIN_SCALE, q.scaleX, eps) // zero spread vs reference
    }

    @Test
    fun relativeSpreadUsesPooledReference() {
        val model = HeatmapModel()
        val rnd = java.util.Random(1)
        // Same spread everywhere -> scale ~1 even though spread != PRIOR_SIGMA.
        val samples = ArrayList<TapSample>()
        for (c in "asdf") repeat(300) {
            samples.add(TapSample(c.code, (rnd.nextGaussian() * 0.1).toFloat(), (rnd.nextGaussian() * 0.1).toFloat()))
        }
        model.addSamples("p", samples)
        val params = model.profile("p")!!.effectiveParams()
        for (c in "asdf") {
            assertEquals(1f, params[c.code]!!.scaleX, 0.1f)
            assertEquals(1f, params[c.code]!!.scaleY, 0.1f)
        }
    }

    @Test
    fun reservoirIsBounded() {
        val s = KeyTapStats()
        repeat(250) { s.add(it / 1000f, 0f) }
        assertEquals(HeatmapConfig.RESERVOIR_SIZE, s.pointCount)
        val xs = s.orderedPointsX()
        assertEquals(0.150f, xs.first(), eps) // oldest kept
        assertEquals(0.249f, xs.last(), eps) // newest
    }

    @Test
    fun jsonRoundTrip() {
        val model = HeatmapModel()
        model.addSamples("qwerty|portrait", listOf(TapSample('a'.code, 0.1f, 0.2f), TapSample('a'.code, 0.3f, 0.4f),
            TapSample('ß'.code, -0.1f, 0f)))
        model.profile("qwerty|portrait")!!.schematic =
            KeyboardSchematic(2.5f, listOf(SchematicKey('a'.code, "a", 0.05f, 0.33f, 0.09f, 0.3f)))
        val restored = HeatmapModel.fromJson(model.toJson())
        assertNotNull(restored)
        val p = restored!!.profile("qwerty|portrait")!!
        val a = p.keys['a'.code]!!
        assertEquals(0.2f, a.meanX, eps)
        assertEquals(0.3f, a.meanY, eps)
        assertEquals(2f, a.effectiveCount, eps)
        assertEquals(2, a.pointCount)
        assertEquals(0.3f, a.orderedPointsX()[1], eps)
        assertNotNull(p.keys['ß'.code])
        assertEquals(2.5f, p.schematic!!.aspect, eps)
        // Further updates after restore continue from the restored state
        a.add(0.2f, 0.3f)
        assertEquals(0.2f, a.meanX, eps)
        assertEquals(3, a.pointCount)
    }

    @Test
    fun corruptOrIncompatibleDataIsDiscarded() {
        assertNull(HeatmapModel.fromJson(""))
        assertNull(HeatmapModel.fromJson("{not json"))
        assertNull(HeatmapModel.fromJson("""{"version":999,"profiles":{}}"""))
        assertNull(HeatmapModel.fromJson(
            """{"version":1,"profiles":{"p":{"keys":{"97":{"n":1,"total":1,"mx":1e30,"my":0,"vx":0,"vy":0,"cxy":0}}}}}"""))
        val ok = HeatmapModel.fromJson("""{"version":1,"profiles":{}}""")
        assertNotNull(ok)
        assertTrue(ok!!.isEmpty())
    }
}
