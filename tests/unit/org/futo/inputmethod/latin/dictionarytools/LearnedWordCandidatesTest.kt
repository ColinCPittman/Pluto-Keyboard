package org.futo.inputmethod.latin.dictionarytools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LearnedWordCandidatesTest {
    private val mainDictionary = setOf("hello", "the", "keyboard", "paris", "Paris")
    private val personal = setOf("Xiomara")

    private fun w(word: String, p: Int = 100, locale: String = "en_US") = LearnedWord(word, locale, p)

    private fun select(learned: List<LearnedWord>, hidden: Set<String> = emptySet()) = LearnedWordCandidates.select(
        learned,
        isKnownWord = { it.word in mainDictionary || it.word.lowercase() in mainDictionary },
        inPersonalDictionary = { it.word in personal },
        hidden = hidden
    )

    @Test
    fun knownWordsAreExcluded() {
        val result = select(listOf(w("hello"), w("Paris"), w("McKenna"), w("yeet"), w("Keyboard")))
        assertEquals(listOf("McKenna", "yeet"), result.normal.map { it.word }.sorted())
        assertTrue(result.sensitive.isEmpty())
    }

    @Test
    fun personalDictionaryWordsAreExcluded() {
        val result = select(listOf(w("Xiomara"), w("Brontë")))
        assertEquals(listOf("Brontë"), result.normal.map { it.word })
    }

    @Test
    fun hiddenWordsAreSkippedIgnoringCase() {
        val result = select(listOf(w("McKenna"), w("yeet"), w("Tr0ub4dor")), hidden = setOf("mckenna", "TR0UB4DOR"))
        assertEquals(listOf("yeet"), result.normal.map { it.word })
        assertTrue(result.sensitive.isEmpty())
    }

    @Test
    fun sensitiveWordsAreSeparated() {
        val result = select(listOf(w("Tr0ub4dor", 50), w("yeet", 10), w("colin@example.com", 70)))
        assertEquals(listOf("colin@example.com", "Tr0ub4dor"), result.sensitive.map { it.word })
        assertEquals(listOf("yeet"), result.normal.map { it.word })
    }

    @Test
    fun duplicatesKeepMostProbableSpellingAndOrder() {
        val result = select(listOf(w("mckenna", 5), w("McKenna", 90), w("yeet", 40, "en_GB"), w("yeet", 30)))
        assertEquals(listOf("McKenna", "yeet"), result.normal.map { it.word })
        assertEquals("en_GB", result.normal[1].locale)
    }

    @Test
    fun junkIsSkipped() {
        val result = select(listOf(w("a"), w(""), w("--"), w(" padded ")))
        assertTrue(result.normal.isEmpty())
        assertTrue(result.sensitive.isEmpty())
    }

    @Test
    fun hiddenListStorage() {
        val dir = Files.createTempDirectory("hidden-words").toFile()
        try {
            val file = File(dir, HiddenLearnedWords.FILE_NAME)
            val store = HiddenLearnedWords(file)
            assertTrue(store.all().isEmpty())

            store.add("McKenna")
            store.add("mckenna") // same word in a different case, kept once
            store.add("Brontë")
            store.add("  ")
            assertEquals(listOf("McKenna", "Brontë"), store.all())
            assertTrue(store.contains("MCKENNA"))

            // survives a new instance (i.e. app restart)
            val reopened = HiddenLearnedWords(file)
            assertEquals(listOf("McKenna", "Brontë"), reopened.all())

            reopened.remove("mckenna")
            assertEquals(listOf("Brontë"), HiddenLearnedWords(file).all())
            assertFalse(HiddenLearnedWords(file).contains("McKenna"))

            reopened.remove("Brontë")
            assertTrue(HiddenLearnedWords(file).all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hiddenListToleratesMissingDirectory() {
        val dir = Files.createTempDirectory("hidden-words").toFile()
        try {
            val store = HiddenLearnedWords(File(File(dir, "sub"), HiddenLearnedWords.FILE_NAME))
            assertTrue(store.all().isEmpty())
            store.add("yeet")
            assertEquals(listOf("yeet"), store.all())
        } finally {
            dir.deleteRecursively()
        }
    }
}
