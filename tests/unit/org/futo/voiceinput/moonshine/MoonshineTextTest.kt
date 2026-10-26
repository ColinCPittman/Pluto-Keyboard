package org.futo.voiceinput.moonshine

import org.futo.voiceinput.shared.moonshine.core.MoonshineText
import org.junit.Assert.assertEquals
import org.junit.Test

class MoonshineTextTest {
    @Test
    fun joinsSplitDecades() {
        assertEquals("Previously in my twenties, I would", MoonshineText.fixSplitDecades("Previously in my twenty s, I would"))
        assertEquals("the eighties and the nineties", MoonshineText.fixSplitDecades("the eighty s and the ninety s"))
        assertEquals("back in the forties.", MoonshineText.fixSplitDecades("back in the forty s."))
        assertEquals("Twenties", MoonshineText.fixSplitDecades("Twenty s"))
        assertEquals("in the 90s", MoonshineText.fixSplitDecades("in the 90 s"))
    }

    @Test
    fun leavesOtherTextAlone() {
        for (s in listOf("twenty seconds", "twenty's best", "it was twenty", "plan s and t", "21 s", "s twenty")) {
            assertEquals(s, MoonshineText.fixSplitDecades(s))
        }
    }
}
