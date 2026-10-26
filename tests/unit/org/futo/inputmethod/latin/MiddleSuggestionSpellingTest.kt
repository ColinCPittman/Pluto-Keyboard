package org.futo.inputmethod.latin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which corrections may take the middle slot from a word the keyboard doesn't know. */
class MiddleSuggestionSpellingTest {
    @Test
    fun typoSizedCorrectionsQualify() {
        assertTrue(Suggest.isCloseSpelling("teh", "the"))
        assertTrue(Suggest.isCloseSpelling("beautfil", "beautiful"))
        assertTrue(Suggest.isCloseSpelling("recieve", "receive"))
        assertTrue(Suggest.isCloseSpelling("Helo", "Hello"))
        assertTrue(Suggest.isCloseSpelling("alot", "a lot"))
    }

    @Test
    fun farOffGuessesDoNot() {
        assertFalse(Suggest.isCloseSpelling("Blahflarg", "Black-flat"))
        assertFalse(Suggest.isCloseSpelling("Blahflarg", "Flagglatt"))
        assertFalse(Suggest.isCloseSpelling("blahblahflarg", "blah blah flag"))
        assertFalse(Suggest.isCloseSpelling("xq", "quite"))
    }
}
