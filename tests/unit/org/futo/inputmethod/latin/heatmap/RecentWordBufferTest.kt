package org.futo.inputmethod.latin.heatmap

import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.KEY_W
import org.futo.inputmethod.latin.heatmap.SyntheticKeyboard.key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentWordBufferTest {
    private fun taps(typed: String) = typed.map {
        val k = key(it.lowercaseChar())
        RecordedTap(it.code, k.centerX.toInt(), k.centerY.toInt(), true)
    }

    private fun entry(word: String, committed: String = word) =
        RecentWordBuffer.Entry(Unit, word, committed, taps(word))

    @Test
    fun wordsBecomeFinalWhenPushedOut() {
        val buffer = RecentWordBuffer<Unit>(2)
        assertTrue(buffer.add(entry("one")).isEmpty())
        assertTrue(buffer.add(entry("two")).isEmpty())
        val evicted = buffer.add(entry("six"))
        assertEquals(listOf("one"), evicted.map { it.committedWord })
        assertEquals(listOf("two", "six"), buffer.drain().map { it.committedWord })
        assertEquals(0, buffer.size)
    }

    @Test
    fun relabelPicksMostRecentMatch() {
        val buffer = RecentWordBuffer<Unit>(10)
        val first = entry("dent")
        val second = entry("dent")
        buffer.add(first)
        buffer.add(entry("the"))
        buffer.add(second)
        assertEquals(second, buffer.relabel("dent", "sent"))
        assertEquals("sent", second.committedWord)
        assertEquals("dent", first.committedWord)
        assertNull(buffer.relabel("missing", "word"))
    }

    @Test
    fun relabelFallsBackToCaseInsensitive() {
        val buffer = RecentWordBuffer<Unit>(10)
        val e = entry("Dent")
        buffer.add(e)
        assertNotNull(buffer.relabel("dent", "sent"))
        assertEquals("sent", e.committedWord)
    }

    @Test
    fun dropNewestForgetsOnlyTheLastWord() {
        val buffer = RecentWordBuffer<Unit>(10)
        buffer.add(entry("one"))
        buffer.add(entry("two"))
        assertEquals("two", buffer.dropNewest()!!.committedWord)
        assertEquals(listOf("one"), buffer.drain().map { it.committedWord })
        assertNull(buffer.dropNewest())
    }

    /** Simulates holding backspace over [chars] characters right after a commit. */
    private fun RecentWordBuffer<Unit>.backspace(chars: Int) {
        repeat(chars) { onDelete(startsRun = it == 0) }
    }

    @Test
    fun deletedWordIsRelabeledWithTheWordTypedInItsPlace() {
        val buffer = RecentWordBuffer<Unit>(10)
        val a = entry("a")
        val dent = entry("dent")
        buffer.add(a)
        buffer.add(dent)
        buffer.backspace(5) // "dent "
        assertEquals(1, buffer.deletedCount)
        val sent = entry("sent")
        buffer.onWordTyped("sent", sent)
        assertEquals(0, buffer.deletedCount)
        assertEquals("sent", dent.committedWord)
        assertEquals(listOf(a, dent, sent), buffer.drain())
    }

    @Test
    fun severalDeletedWordsPairInTextOrder() {
        val buffer = RecentWordBuffer<Unit>(10)
        val the = entry("the")
        val dent = entry("dent")
        buffer.add(entry("x"))
        buffer.add(the)
        buffer.add(dent)
        buffer.backspace(9) // "the dent "
        assertEquals(2, buffer.deletedCount)
        buffer.onWordTyped("the", entry("the"))
        buffer.onWordTyped("sent", entry("sent"))
        assertEquals("the", the.committedWord)
        assertEquals("sent", dent.committedWord)
    }

    @Test
    fun backspaceOverSpaceThenFixingTheWordInPlace() {
        val buffer = RecentWordBuffer<Unit>(10)
        val dent = entry("dent")
        buffer.add(dent)
        buffer.backspace(1) // just the space, so the keyboard resumes "dent"
        buffer.onWordResumed("dent")
        assertEquals(1, buffer.deletedCount)
        assertEquals(dent, buffer.relabel("dent", "sent"))
        assertEquals("sent", dent.committedWord)
        assertEquals(listOf(dent), buffer.drain())
    }

    @Test
    fun movingElsewhereDropsDeletedWords() {
        val buffer = RecentWordBuffer<Unit>(10)
        buffer.add(entry("dent"))
        buffer.backspace(5)
        buffer.onWordResumed("elsewhere")
        assertEquals(0, buffer.deletedCount)
        buffer.onWordTyped("sent", null)
        assertEquals(0, buffer.size)
    }

    @Test
    fun backspacesInsideANewWordDoNotDeleteCommittedWords() {
        val buffer = RecentWordBuffer<Unit>(10)
        buffer.add(entry("dent"))
        buffer.onTypingResumed() // typing the next word
        buffer.onDelete(startsRun = false)
        buffer.onDelete(startsRun = false)
        assertEquals(0, buffer.deletedCount)
        assertEquals(1, buffer.size)
    }

    @Test
    fun correctedWordTeachesTheIntendedKey() {
        // "sent" typed with the first tap landing on the right edge of 'd' (shown as "dent"),
        // later corrected to "sent", so the tap must be learned as a (far right) 's' tap.
        val d = key('d')
        val t = listOf(RecordedTap('d'.code, d.x + 2, d.centerY.toInt(), true)) + taps("ent")
        val buffer = RecentWordBuffer<Unit>(10)
        val e = RecentWordBuffer.Entry(Unit, "dent", "dent", t)
        buffer.add(e)
        buffer.relabel("dent", "sent")

        val r = TapAligner.align(e.typedWord, e.committedWord, e.taps, false, false, SyntheticKeyboard::geometry)
        assertEquals(AlignmentRejection.NONE, r.rejection)
        val s = r.samples!![0]
        assertEquals('s'.code, s.codePoint)
        assertEquals((d.x + 2 - key('s').centerX) / KEY_W, s.dx, 1e-4f)
        assertTrue("tap was to the right of 's'", s.dx > 0.4f)
    }

    @Test
    fun rejectedSwipeIsReplacedByTheNextWord() {
        val buffer = RecentWordBuffer<Unit>(10)
        buffer.add(entry("the"))
        val swipe = RecentWordBuffer.Entry(Unit, "gone", "gone", emptyList())
        assertTrue(buffer.addDeleted(swipe))
        assertEquals(1, buffer.deletedCount)
        buffer.onWordTyped("home", null)
        assertEquals("home", swipe.committedWord)
        assertEquals(listOf("the", "home"), buffer.drain().map { it.committedWord })
    }

    @Test
    fun rejectedSwipeAfterDeletingWordsIsDropped() {
        val buffer = RecentWordBuffer<Unit>(10)
        buffer.add(entry("dent"))
        buffer.onDelete(startsRun = true)
        assertEquals(1, buffer.deletedCount)
        assertTrue(!buffer.addDeleted(RecentWordBuffer.Entry(Unit, "gone", "gone", emptyList())))
        assertEquals(1, buffer.deletedCount)
    }
}
