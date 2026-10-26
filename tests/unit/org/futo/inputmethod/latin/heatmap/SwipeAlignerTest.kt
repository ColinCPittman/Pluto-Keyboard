package org.futo.inputmethod.latin.heatmap

import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.KEY_H
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.KEY_W
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SwipeAlignerTest {
    /** A path through the centres of the given keys (offset in key units), with a point every ~8px. */
    private fun path(keys: String, dx: Float = 0f, dy: Float = 0f): SwipePath {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val pts = keys.map { key(it).let { k -> Pair(k.centerX + dx * KEY_W, k.centerY + dy * KEY_H) } }
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]
            val (x1, y1) = pts[i]
            val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val steps = maxOf(1, (len / 8f).toInt())
            for (s in 0 until steps) {
                xs.add(x0 + (x1 - x0) * s / steps)
                ys.add(y0 + (y1 - y0) * s / steps)
            }
        }
        xs.add(pts.last().first)
        ys.add(pts.last().second)
        return SwipePath(xs.toFloatArray(), ys.toFloatArray())
    }

    private fun align(word: String, p: SwipePath) = SwipeAligner.align(word, p, SyntheticKeyboard::geometry)

    @Test
    fun cleanSwipeLearnsStartTurnAndEnd() {
        val r = align("the", path("the"))
        assertEquals(SwipeRejection.NONE, r.rejection)
        assertEquals(listOf('t', 'h', 'e'), r.samples!!.map { it.codePoint.toChar() })
        r.samples!!.forEach {
            assertEquals(0f, it.dx, 0.05f)
            assertEquals(0f, it.dy, 0.05f)
        }
    }

    @Test
    fun offsetSwipeLearnsTheOffset() {
        val r = align("the", path("the", dx = 0.3f, dy = -0.2f))
        assertEquals(SwipeRejection.NONE, r.rejection)
        r.samples!!.forEach {
            assertEquals(0.3f, it.dx, 0.05f)
            assertEquals(-0.2f, it.dy, 0.05f)
        }
    }

    @Test
    fun lettersPassedOnAStraightRunAreNotLearned() {
        // t-y-u-i is one straight line, so only t and i are learned.
        val r = align("tyui", path("tyui"))
        assertEquals(SwipeRejection.NONE, r.rejection)
        assertEquals(listOf('t', 'i'), r.samples!!.map { it.codePoint.toChar() })
    }

    @Test
    fun doubleLettersAreSwipedOnce() {
        val r = align("hello", path("helo"))
        assertEquals(SwipeRejection.NONE, r.rejection)
    }

    @Test
    fun forgottenLetterAcrossTheKeyboardIsRejected() {
        // Meant "taxi" but never went down to the x.
        val r = align("taxi", path("tai"))
        assertEquals(SwipeRejection.MISSED_LETTER, r.rejection)
        assertNull(r.samples)
    }

    @Test
    fun turnTowardAKeyNotInTheWordIsRejected() {
        // Swiped t-b-o, but the word is "to".
        val r = align("to", path("tbo"))
        assertEquals(SwipeRejection.EXTRA_TURN, r.rejection)
    }

    @Test
    fun differentFirstLetterIsRejected() {
        assertEquals(SwipeRejection.START_TOO_FAR, align("home", path("lone")).rejection)
    }

    @Test
    fun neighbouringKeyMistakeIsLearned() {
        // Swiped g-o-n-e but meant "home" (h is next to g, m next to n), so it's a slightly-off swipe.
        assertEquals(SwipeRejection.NONE, align("home", path("gone")).rejection)
    }

    @Test
    fun overshootPastTheLastKeyIsIgnored() {
        // Lifted off a key past the "e" (over the "w"). The "e" is learned from where the path was closest.
        val r = align("the", path("thew"))
        assertEquals(SwipeRejection.NONE, r.rejection)
        val e = r.samples!!.last()
        assertEquals('e'.code, e.codePoint)
        // Within the resampling step (0.2 key along the path).
        assertEquals(0f, e.dx, 0.11f)
        assertEquals(0f, e.dy, 0.11f)
    }

    @Test
    fun goingOnToAnotherLetterFarAwayIsRejected() {
        // Swiped "then" (on to the n, 2+ keys past the e) but the word is "the".
        assertEquals(SwipeRejection.END_TOO_FAR, align("the", path("then")).rejection)
    }

    @Test
    fun differentLastLetterIsRejected() {
        assertEquals(SwipeRejection.END_TOO_FAR, align("tip", path("tim")).rejection)
    }

    @Test
    fun wordsWithSymbolsAreSkipped() {
        assertEquals(SwipeRejection.NOT_LETTERS, align("don't", path("dont")).rejection)
    }

    @Test
    fun singleLetterWordsAreSkipped() {
        assertEquals(SwipeRejection.TOO_SHORT, align("a", path("a")).rejection)
    }

    @Test
    fun bendFieldUndoesTheLearnedShift() {
        val keys = SyntheticKeyboard.keys.map { SwipeBendField.Companion.Key(it.code, SyntheticKeyboard.geometry(it.code)!!) }
        val field = SwipeBendField.build(keys) { if (it == 'e'.code) Pair(0.3f, 0f) else null }
        assertTrue(field.isActive)
        val e = key('e')
        // Where this user swipes over "e", the point moves most of the way back to the stock centre.
        val (ox, oy) = field.offset(e.centerX + 0.3f * KEY_W, e.centerY)
        assertTrue("moved left by $ox", ox < -0.15f * KEY_W && ox > -0.31f * KEY_W)
        assertEquals(0f, oy, 0.5f)
        // Far away nothing moves.
        val m = key('m')
        val (fx, fy) = field.offset(m.centerX, m.centerY)
        assertEquals(0f, fx, 0.5f)
        assertEquals(0f, fy, 0.5f)
    }

    @Test
    fun noShiftMeansInactiveField() {
        val keys = SyntheticKeyboard.keys.map { SwipeBendField.Companion.Key(it.code, SyntheticKeyboard.geometry(it.code)!!) }
        assertTrue(!SwipeBendField.build(keys) { null }.isActive)
    }

    @Test
    fun strengthScalesTheAppliedShift() {
        val stats = KeyTapStats()
        repeat(200) { stats.add(0.3f, 0f) }
        val low = SwipeConfig.appliedShift(stats, SwipeConfig.MIN_STRENGTH).first
        val mid = SwipeConfig.appliedShift(stats, SwipeConfig.DEFAULT_STRENGTH).first
        val high = SwipeConfig.appliedShift(stats, SwipeConfig.MAX_STRENGTH).first
        assertTrue(low < mid && mid < high)
        assertTrue(high <= SwipeConfig.MAX_BEND)
    }
}
