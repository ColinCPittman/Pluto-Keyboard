package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.CorrectionPhrases
import org.futo.voiceinput.shared.context.FillerWords
import org.futo.voiceinput.shared.context.PhraseEntry
import org.futo.voiceinput.shared.context.PhraseList
import org.futo.voiceinput.shared.context.QuoteTriggers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The filler words, self-correction and quote phrase lists, where only your changes are saved. */
class PhraseListsTest {
    private val lists: List<PhraseList> = listOf(FillerWords, CorrectionPhrases, QuoteTriggers)

    /** A phrase of your own that is valid in every list. */
    private val mine = "so then"

    @Test
    fun noChangesMeansTheBuiltInList() {
        for (list in lists) {
            assertEquals(emptyList<PhraseEntry>(), list.parse(""))
            assertEquals("", list.serialize(emptyList()))
            assertEquals(list.DEFAULTS, list.enabledPhrases(emptyList()))
            assertEquals(emptyList<String>(), list.enabledPhrases(emptyList(), builtIn = false))
        }
    }

    /** A list saved in the old format held the whole list, and it is read as your changes only. */
    @Test
    fun oldSavedListsAreMigrated() {
        for (list in lists) {
            val saved = list.SAVED_DEFAULTS.last()
            val first = saved[0]
            val second = saved[1]
            val deleted = saved[2]
            // the old page had everything, with one switched off, one deleted and one phrase added
            val old = saved.filter { it != deleted }
                .joinToString("\n") { if (it == second) "$it\t0" else it } + "\n$mine"
            val changes = list.parse(old)
            assertEquals(
                setOf(PhraseEntry(second, enabled = false), PhraseEntry(mine), PhraseEntry(deleted, enabled = false)),
                changes.toSet()
            )
            val on = list.enabledPhrases(changes)
            assertTrue(first in on)
            assertFalse(second in on)
            assertFalse(deleted in on)
            assertTrue(mine in on)
            // saved again in the new format, it reads back the same
            assertEquals(changes, list.parse(list.serialize(changes)))
            assertTrue(list.serialize(changes).startsWith("\tchanges\n"))
        }
    }

    /** Phrases the built-in list gained after an old list was saved are not deleted by it. */
    @Test
    fun oldSavedListsKeepNewerBuiltIns() {
        // saved with the first self-correction list, which had no "make that"
        val v1 = listOf(
            "sorry, I mean", "no, I mean", "or I mean", "I mean", "I meant", "no wait", "wait no", "no sorry",
            "or rather", "scratch that", "sorry", "actually", "rather",
        )
        val changes = CorrectionPhrases.parse(v1.filter { it != "rather" }.joinToString("\n"))
        assertEquals(listOf(PhraseEntry("rather", enabled = false)), changes)
        val on = CorrectionPhrases.enabledPhrases(changes)
        assertTrue("make that" in on)
        assertTrue("no actually" in on)
        assertFalse("rather" in on)
        // saved with the second one, so its "make that" missing means you deleted it
        val v2 = CorrectionPhrases.parse((v1 + "no actually").joinToString("\n"))
        assertFalse("make that" in CorrectionPhrases.enabledPhrases(v2))
        // a phrase you added that is built-in now stays yours
        // ("basically" was added to the built-in fillers after the first list)
        val grown = FillerWords.parse(FillerWords.SAVED_DEFAULTS[0].joinToString("\n") + "\nbasically")
        assertEquals(listOf(PhraseEntry("basically")), grown)
        assertEquals(listOf("basically"), FillerWords.enabledPhrases(grown, builtIn = false))
        assertTrue("like" in FillerWords.enabledPhrases(grown))
    }

    @Test
    fun builtInOffMeansOnlyYourPhrases() {
        for (list in lists) {
            val changes = listOf(PhraseEntry(mine), PhraseEntry("not this one", enabled = false), PhraseEntry(list.DEFAULTS[0], enabled = false))
            assertEquals(listOf(mine), list.enabledPhrases(changes, builtIn = false))
            // a built-in phrase you added as your own counts with the built-in ones off
            val ownBuiltIn = listOf(PhraseEntry(list.DEFAULTS[1]))
            assertEquals(listOf(list.DEFAULTS[1]), list.enabledPhrases(ownBuiltIn, builtIn = false))
            assertEquals(list.DEFAULTS, list.enabledPhrases(ownBuiltIn))
        }
    }

    /** The switch never changes the saved list, so off and back on, everything is as it was. */
    @Test
    fun switchRoundTrip() {
        for (list in lists) {
            val changes = list.withBuiltIn(listOf(PhraseEntry(mine)), list.DEFAULTS[0], on = false)
            val stored = list.serialize(changes)
            val before = list.enabledPhrases(list.parse(stored))
            list.enabledPhrases(list.parse(stored), builtIn = false)
            assertEquals(stored, list.serialize(list.parse(stored)))
            assertEquals(before, list.enabledPhrases(list.parse(stored), builtIn = true))
            assertEquals(list.DEFAULTS.drop(1) + mine, before)
        }
    }

    /** With all the built-in phrases on, one you switched off stays off, and on again, it is back. */
    @Test
    fun switchedOffBuiltInStaysOff() {
        for (list in lists) {
            val phrase = list.DEFAULTS.last()
            val off = list.withBuiltIn(emptyList(), phrase, on = false)
            assertFalse(list.isOn(off, phrase))
            assertTrue(list.isOn(off, list.DEFAULTS.first()))
            val read = list.parse(list.serialize(off))
            assertFalse(phrase in list.enabledPhrases(read))
            assertEquals(list.DEFAULTS.size - 1, list.enabledPhrases(read).size)
            // it is not one of your own phrases
            assertEquals(emptyList<PhraseEntry>(), list.own(read))
            assertEquals(emptyList<PhraseEntry>(), list.withBuiltIn(read, phrase, on = true))
        }
    }
}
