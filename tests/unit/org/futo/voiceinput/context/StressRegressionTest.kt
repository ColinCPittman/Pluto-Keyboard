package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.ContextRescorer
import org.futo.voiceinput.shared.context.NumberWords
import org.futo.voiceinput.shared.context.SpokenEdits
import org.futo.voiceinput.shared.context.SpokenEdits.Kind
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Misfires found by the stress test (tools/context-bench/stress, LOG.md), as rules without the
 * LM, covering every candidate the rules propose for these sentences, each checked by the guard.
 */
class StressRegressionTest {
    /** Common lowercase words, as the lexicon's case data would say. */
    private val common = setOf("what", "thinking", "at", "blue", "for", "no", "the", "can", "four")
    private val isWord: (String) -> Boolean = { it.lowercase() in common }

    /** Every alternative of every group of [kind], applied to [text] on its own (guard-checked). */
    private fun candidates(text: String, kind: Kind, segments: List<String> = emptyList()): List<String> {
        val w = SpokenEdits.Words(text)
        val seg = SpokenEdits.segmentStarts(text, segments)
        val groups = when (kind) {
            Kind.FILLER, Kind.REPEAT -> SpokenEdits.fillerGroups(w, isWord = isWord).filter { it.kind == kind }
            Kind.SELF_CORRECTION -> SpokenEdits.selfCorrectionGroups(w, seg, isWord = isWord)
            Kind.QUOTE -> SpokenEdits.quoteGroups(w, isWord = isWord)
            else -> SpokenEdits.commandGroups(w, seg, null, org.futo.voiceinput.shared.context.PersonalNames.EMPTY)
        }
        return groups.flatMap { g ->
            g.alternatives.map { a ->
                assertTrue("guard: '${a.replacement}' in '$text'", TranscriptGuard.checkEdit(text, a).accepted)
                text.substring(0, a.start) + a.replacement + text.substring(a.end)
            }
        }
    }

    private fun none(kind: Kind, vararg texts: String) {
        for (t in texts) assertEquals(t, emptyList<String>(), candidates(t, kind))
    }

    @Test
    fun quotes() {
        // a filler after a reporting verb is a hesitation, not the start of a quote
        none(Kind.QUOTE, "I went, uh, to the doctor yesterday.", "She said, um, that she'd be late.", "I told him, uh, to wait outside.")
        // the voice model ended a sentence at the pause after the interjection
        assertEquals(listOf("And then she was like \"Um, what are you doing here?\""),
            candidates("And then she was like, um. What are you doing here?", Kind.QUOTE))
        assertEquals(listOf("My boss was like \"Hey! Can you stay late?\""),
            candidates("My boss was like, hey! Can you stay late?", Kind.QUOTE))
        // after "said", a lone "no." is not joined to the next sentence
        none(Kind.QUOTE, "She said, no. That was the end of it.")
    }

    @Test
    fun fillersAndRepeats() {
        none(Kind.FILLER, "Uh huh, that's what I thought.", "Uh oh, I left the stove on.")
        none(Kind.REPEAT, "Got it, got it, thanks.", "I know, I know, I should have called.", "Sorry, sorry, I didn't see you.",
            "I ordered the mahi mahi.", "We went to Bora Bora.", "Do you, you know, want to come along?")
        assertEquals(listOf("I think we should go."), candidates("I I think we should go.", Kind.REPEAT))
        // a filler the voice model made its own sentence, or join across it
        assertTrue("I was thinking we could go." in candidates("I was. Um. Thinking we could go.", Kind.FILLER))
        assertTrue("The meeting is at 2." in candidates("The meeting is, um... At 2.", Kind.FILLER))
    }

    @Test
    fun selfCorrections() {
        none(Kind.SELF_CORRECTION,
            "Thanks, sorry, gotta run.", "I can't make it tonight, sorry, my car broke down.", "It was a long, rather boring meeting.",
            "Let me check, oh wait, the store is closed on Sundays.", "Add two cups of sugar, sorry, one cup.",
            "I'm late. Sorry. Traffic was bad.")
        // across the sentence ends the voice model writes at pauses
        assertEquals(listOf("The party is on Saturday."), candidates("The party is on Friday, no wait. Saturday.", Kind.SELF_CORRECTION))
        assertEquals(listOf("We need four chairs."), candidates("We need three. Actually, four chairs.", Kind.SELF_CORRECTION))
        assertTrue("Get the blue bag." in candidates("Get the red bag, no wait. Blue bag.", Kind.SELF_CORRECTION))
        assertEquals(listOf("Meet me at six."), candidates("Meet me at 5. I mean, six.", Kind.SELF_CORRECTION))
    }

    @Test
    fun questionShape() {
        for (s in listOf("Serve with rice or bread.", "How nice of you.", "Kinda tired, might skip the gym.", "What a mess.",
            "Do your homework.", "Have a great holiday.", "The fur coat.", "All set.", "How cool.")) {
            assertFalse(s, ContextRescorer.questionShaped(s))
        }
        for (s in listOf("Are you coming tonight.", "Wait, so you already paid for the tickets.", "You're coming, right.",
            "What time is it.", "So, what do you want for dinner.", "Do you want pizza.", "The kids are asleep.", "Have you eaten.", "How come.")) {
            assertTrue(s, ContextRescorer.questionShaped(s))
        }
        assertTrue(ContextRescorer.INDIRECT_QUESTION.containsMatchIn("I was wondering if you're free tonight."))
    }

    @Test
    fun numbersAndCommands() {
        // a time without am/pm only after "at", "by", ...
        assertEquals(0, NumberWords.candidates(listOf("Preheat", "the", "oven", "to", "three", "fifty"),
            listOf("", " ", " ", " ", " ", " ", "."), { it == 0 }).size)
        assertEquals(1, NumberWords.candidates(listOf("Meet", "at", "three", "thirty"), listOf("", " ", " ", " ", "."), { it == 0 }).size)
        // "time" makes "period" a noun, not "comma"
        val segs = listOf("Once upon a time comma", "there was a princess")
        assertEquals(listOf("Once upon a time, there was a princess"), candidates(segs.joinToString(" "), Kind.COMMAND, segs))
        assertEquals(emptyList<String>(), candidates("We're still in the trial period.", Kind.COMMAND, listOf("We're still in the trial period.")))
        // a symbol being talked about
        assertEquals(emptyList<String>(), candidates("At sign or at symbol?", Kind.COMMAND))
        assertEquals(emptyList<String>(), candidates("Asterisk means footnote.", Kind.COMMAND))
        assertEquals(listOf("Follow us at #travel"), candidates("Follow us at, hashtag travel", Kind.COMMAND, listOf("Follow us at,", "hashtag travel")))
    }
}
