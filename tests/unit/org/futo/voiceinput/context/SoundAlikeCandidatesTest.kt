package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.Homophones
import org.futo.voiceinput.shared.context.PersonalNames
import org.futo.voiceinput.shared.context.SpokenEdits
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sound-alikes the lexicon doesn't pair, and commands written with a homophone ("knew paragraph"). */
class SoundAlikeCandidatesTest {
    @Test
    fun handListedHomophonesBothWays() {
        assertTrue(Homophones.isLoaded)
        // "High Jordan" (Nemotron) for "Hi Jordan": the lexicon has no candidate for "high"
        assertTrue("hi" in Homophones.extraCandidates("High"))
        assertTrue("high" in Homophones.extraCandidates("hi"))
        assertTrue(Homophones.same("High", "hi"))
        assertTrue(Homophones.same("hi", "high"))
        // the generated groups still hold ("hi" was in one already, and the hand list joins it)
        assertTrue(Homophones.same("hi", "hye"))
        assertTrue(Homophones.same("meat", "meet"))
        assertFalse(Homophones.same("high", "how"))
    }

    @Test
    fun handListedNearSoundAlikes() {
        // "Bring four cheers" (Parakeet, Nemotron): a vowel before "r", near and not exact
        assertTrue("chairs" in Homophones.extraCandidates("cheers"))
        assertTrue("cheers" in Homophones.extraCandidates("chairs"))
        assertTrue(Homophones.confusable("Cheers", "chairs"))
        assertTrue(Homophones.confusable("chairs", "cheers"))
        assertFalse(Homophones.same("cheers", "chairs"))
        // the generated near pairs still hold
        assertTrue(Homophones.confusable("bulls", "bowls"))
    }

    @Test
    fun noHandListedFunctionWords() {
        // function words are never swapped, so the list has none ("would" / "wood", "been" / "bean")
        for (w in TranscriptGuard.FUNCTION_LOCK) assertEquals(w, emptyList<String>(), Homophones.extraCandidates(w))
    }

    @Test
    fun possessiveSoundsLikeItsPlural() {
        // "First, the egg's" (Nemotron) for "the eggs"
        assertEquals(listOf("eggs"), Homophones.possessiveVariants("egg's"))
        assertEquals(listOf("eggs"), Homophones.possessiveVariants("Egg’s"))
        assertEquals(listOf("kids"), Homophones.possessiveVariants("kids'"))
        assertTrue(Homophones.same("egg's", "eggs"))
        assertTrue(Homophones.same("eggs", "egg's"))
        // contractions are words of their own, and so are words without a plural "s"
        for (w in listOf("it's", "let's", "he's", "that's", "what's", "there's", "today's", "boss's", "his", "eggs")) {
            assertEquals(w, emptyList<String>(), Homophones.possessiveVariants(w))
        }
        assertFalse(Homophones.same("egg's", "legs"))
    }

    @Test
    fun commandWithOneHomophone() {
        val newParagraph = listOf("new", "paragraph")
        // "Hi mom knew paragraph I wanted ..." (Parakeet)
        assertEquals(2, SpokenEdits.phraseLength(listOf("knew", "paragraph"), 0, newParagraph, soundAlike = true))
        assertEquals(0, SpokenEdits.phraseLength(listOf("knew", "paragraph"), 0, newParagraph))
        // one word only, and the other must be the command's
        assertEquals(0, SpokenEdits.phraseLength(listOf("knew", "paragraphs"), 0, newParagraph, soundAlike = true))
        assertEquals(0, SpokenEdits.phraseLength(listOf("gnu", "knew"), 0, listOf("new", "new"), soundAlike = true))
        // a one-word command never matches a homophone of it
        assertEquals(0, SpokenEdits.phraseLength(listOf("knew"), 0, listOf("new"), soundAlike = true))
    }

    @Test
    fun commandGroupsFindTheHomophoneAndTheGuardAgrees() {
        val text = "Hi mom knew paragraph I wanted to tell you about the trip."
        val w = SpokenEdits.Words(text)
        val on = SpokenEdits.commandGroups(w, emptySet(), null, PersonalNames.EMPTY, soundAlike = true)
        val e = on.flatMap { it.alternatives }.single { '\n' in it.replacement }
        assertEquals("\n\nI", e.replacement)
        assertTrue(TranscriptGuard.checkEdit(text, e, soundAlikeCommands = true).accepted)
        // without the option the rules don't propose it, and the guard doesn't let it through
        assertFalse(TranscriptGuard.checkEdit(text, e).accepted)
        val off = SpokenEdits.commandGroups(w, emptySet(), null, PersonalNames.EMPTY)
        assertTrue(off.none { g -> g.alternatives.any { '\n' in it.replacement } })
    }
}
