package org.futo.inputmethod.latin.heatmap

import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.KEY_H
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.KEY_W
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TapAlignerTest {
    private val eps = 1e-4f

    /** Taps on the given letters at an offset (key units) from their centres. */
    private fun taps(typed: String, dx: Float = 0f, dy: Float = 0f, plain: Boolean = true) =
        typed.map {
            val k = key(it.lowercaseChar())
            RecordedTap(it.code, (k.centerX + dx * KEY_W).toInt(), (k.centerY + dy * KEY_H).toInt(), plain)
        }

    private fun align(
        typed: String, committed: String, t: List<RecordedTap>? = taps(typed),
        batch: Boolean = false, resumed: Boolean = false
    ) = TapAligner.align(typed, committed, t, batch, resumed, SyntheticKeyboard::geometry)

    @Test
    fun exactWordProducesSamplesForEveryLetter() {
        val r = align("the", "the", taps("the", dy = 0.2f))
        assertEquals(AlignmentRejection.NONE, r.rejection)
        val samples = r.samples!!
        assertEquals(listOf('t', 'h', 'e'), samples.map { it.codePoint.toChar() })
        samples.forEach {
            assertEquals(0.2f, it.dy, 0.01f)
            assertEquals(0f, it.dx, 0.01f)
        }
    }

    @Test
    fun substitutionIsLearnedRelativeToIntendedKey() {
        // User hit 'w' while meaning 'e' (autocorrected "thw" -> "the")
        val t = taps("th") + RecordedTap('w'.code, key('w').x + KEY_W - 2, key('w').centerY.toInt(), true)
        val r = align("thw", "the", t)
        assertEquals(AlignmentRejection.NONE, r.rejection)
        val e = r.samples!![2]
        assertEquals('e'.code, e.codePoint)
        val expectedDx = ((key('w').x + KEY_W - 2) - key('e').centerX) / KEY_W
        assertEquals(expectedDx, e.dx, eps)
        assertEquals(0f, e.dy, 0.01f)
    }

    @Test
    fun caseIsIgnored() {
        val r = align("The", "the", taps("The"))
        assertEquals(AlignmentRejection.NONE, r.rejection)
        assertEquals('t'.code, r.samples!![0].codePoint)
    }

    @Test
    fun rejections() {
        assertEquals(AlignmentRejection.BATCH_OR_RESUMED, align("the", "the", batch = true).rejection)
        assertEquals(AlignmentRejection.BATCH_OR_RESUMED, align("the", "the", resumed = true).rejection)
        assertEquals(AlignmentRejection.EMPTY, align("", "", emptyList()).rejection)
        assertEquals(AlignmentRejection.EMPTY, align("the", "the", null).rejection)
        assertEquals(AlignmentRejection.LENGTH_MISMATCH, align("th", "the", taps("th")).rejection)
        assertEquals(AlignmentRejection.LENGTH_MISMATCH, align("the", "the", taps("th")).rejection)
        assertEquals(AlignmentRejection.NOT_PLAIN_TAP, align("the", "the", taps("the", plain = false)).rejection)
        assertEquals(AlignmentRejection.TAP_TEXT_MISMATCH, align("the", "the", taps("thr")).rejection)
        // Too many substitutions, so probably a different word was picked
        assertEquals(AlignmentRejection.TOO_MANY_SUBSTITUTIONS, align("cat", "dog", taps("cat")).rejection)
        assertEquals(AlignmentRejection.TOO_MANY_SUBSTITUTIONS, align("if", "of", taps("if")).rejection)
        // Transposition would misalign taps with letters
        assertEquals(AlignmentRejection.TRANSPOSITION, align("stirng", "string", taps("stirng")).rejection)
        // Far-away substitution is an outlier
        assertEquals(AlignmentRejection.OUTLIER, align("qat", "pat", taps("qat")).rejection)
        // Unknown key on this keyboard
        assertEquals(AlignmentRejection.UNKNOWN_KEY, align("caé", "caé",
            taps("ca") + RecordedTap('é'.code, 10, 10, true)).rejection)
        // Coordinates that clearly aren't on the typed key (e.g. from a long-press popup)
        assertEquals(AlignmentRejection.IMPLAUSIBLE_TAP, align("mom", "mom",
            listOf(RecordedTap('m'.code, 5, 5, true)) + taps("om")).rejection)
    }

    @Test
    fun nonLetterWordsAreRejected() {
        val apostrophe = listOf(RecordedTap('\''.code, 5, 5, true))
        assertEquals(AlignmentRejection.NOT_LETTERS,
            align("it'", "it'", taps("it") + apostrophe).rejection)
        assertEquals(AlignmentRejection.LENGTH_MISMATCH, align("dont", "don't", taps("dont")).rejection)
    }

    @Test
    fun correctTapsFarFromCentreAreClipped() {
        // Tap in the extended hitbox of 'a' at the left keyboard edge
        val a = key('a')
        val t = listOf(RecordedTap('a'.code, 1, a.centerY.toInt(), true)) + taps("t")
        val r = align("at", "at", t)
        assertNotNull(r.samples)
        assertEquals(-HeatmapConfig.MAX_SAMPLE_OFFSET, r.samples!![0].dx, eps)
    }

    @Test
    fun tapRecorderSnapshot() {
        val rec = TapRecorder(4)
        rec.record(0, 'a'.code, 1, 2, true)
        rec.record(1, 'x'.code, 3, 4, true)
        rec.record(1, 'b'.code, 5, 6, false) // deleted and retyped, so overwritten
        rec.record(9, 'z'.code, 0, 0, true) // out of range, so ignored
        val s = rec.snapshot(2)!!
        assertEquals('b'.code, s[1].codePoint)
        assertEquals(5, s[1].x)
        assertEquals(false, s[1].isPlainTap)
        assertNull(rec.snapshot(5))
        assertNull(rec.snapshot(0))
    }
}
