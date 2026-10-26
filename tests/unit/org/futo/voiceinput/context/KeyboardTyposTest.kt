package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.KeyboardTypos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTyposTest {
    @Test
    fun neighbours() {
        assertTrue(KeyboardTypos.adjacent('s', 'a'))
        assertTrue(KeyboardTypos.adjacent('t', 'y'))
        assertTrue(KeyboardTypos.adjacent('g', 'b'))
        assertFalse(KeyboardTypos.adjacent('q', 'p'))
        assertFalse(KeyboardTypos.adjacent('a', 'a'))
    }

    @Test
    fun costs() {
        // three neighbouring keys
        assertEquals(3.0, KeyboardTypos.cost("ststy", "start"), 1e-9)
        // two letters swapped
        assertEquals(KeyboardTypos.SWAPPED, KeyboardTypos.cost("teh", "the"), 1e-9)
        // a letter left out
        assertEquals(KeyboardTypos.MISSED, KeyboardTypos.cost("tomorow", "tomorrow"), 1e-9)
        // a key pressed twice
        assertEquals(KeyboardTypos.EXTRA_NEAR, KeyboardTypos.cost("helllo", "hello"), 1e-9)
        // a key across the keyboard costs more than a neighbour
        assertTrue(KeyboardTypos.cost("cpt", "cat") > KeyboardTypos.cost("cst", "cat"))
        // over the limit
        assertEquals(Double.POSITIVE_INFINITY, KeyboardTypos.cost("abcdef", "uvwxyz", 4.0), 0.0)
    }

    @Test
    fun candidatesCheapestFirst() {
        val words = listOf("start", "stay", "stat", "study", "apple", "starts")
        val c = KeyboardTypos.candidates("ststy", words, 4.0, 10).map { it.first }
        assertTrue("start" in c)
        assertFalse("apple" in c)
        assertEquals("stat", KeyboardTypos.candidates("stst", words, 4.0, 1).single().first)
    }
}
