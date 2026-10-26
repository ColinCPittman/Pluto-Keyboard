package org.futo.inputmethod.latin.uix

import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.shared.engine.TranscriptPostProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

/** The post-processing chain, where the context stage gets the engine's segments (its voice commands need the pauses). */
class VoiceInputEnginesTest {
    @Test
    fun contextStageGetsOriginalSegments() {
        val seen = ArrayList<List<String>>()
        val punct = TranscriptPostProcessor { text, segs -> seen.add(segs); text.uppercase() }
        val other = TranscriptPostProcessor { text, segs -> seen.add(segs); text }
        val context = TranscriptPostProcessor { text, segs -> seen.add(segs); text + "!" }
        val chain = VoiceInputEngines.chain(
            listOf(
                VoiceInputEngines.Stage("punctuation", punct),
                VoiceInputEngines.Stage("other", other),
                VoiceInputEngines.Stage("context", context, originalSegments = true),
            )
        )
        val out = runBlocking { chain.process("hi john new line how are you", listOf("hi john", "new line", "how are you")) }
        assertEquals("HI JOHN NEW LINE HOW ARE YOU!", out)
        assertEquals(listOf("hi john", "new line", "how are you"), seen[0])
        assertEquals(listOf("HI JOHN NEW LINE HOW ARE YOU"), seen[1])
        assertEquals(listOf("hi john", "new line", "how are you"), seen[2])
    }
}
