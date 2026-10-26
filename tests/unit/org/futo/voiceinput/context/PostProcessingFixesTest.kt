package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.NumberWords
import org.junit.Assert.assertEquals
import org.junit.Test

/** Rules behind the voice post-processing fixes, such as spoken times (no models). */
class PostProcessingFixesTest {
    @Test
    fun spokenTimeForms() {
        assertEquals(listOf("2:47 PM"), NumberWords.formsOfWords(listOf("two", "forty", "seven", "p", "m")))
        assertEquals(listOf("6:15 AM"), NumberWords.formsOfWords(listOf("six", "fifteen", "a", "m")))
        assertEquals(listOf("7 PM"), NumberWords.forms("seven p.m."))
    }
}
