package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.CorrectionPhrase
import org.futo.voiceinput.shared.context.CorrectionPhrases
import org.futo.voiceinput.shared.context.FillerWords
import org.futo.voiceinput.shared.context.PersonalNames
import org.futo.voiceinput.shared.context.SoundAlikeLexicon
import org.futo.voiceinput.shared.context.SpokenEdits
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.futo.voiceinput.shared.context.VoiceCommand
import org.futo.voiceinput.shared.context.VoiceCommandAction
import org.futo.voiceinput.shared.context.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's editable voice command list, covering storage format and use by the command rules. */
class VoiceCommandsTest {
    @Test
    fun blankMeansBuiltIn() {
        assertEquals(VoiceCommands.DEFAULTS, VoiceCommands.parse(""))
    }

    @Test
    fun roundTrip() {
        val list = listOf(
            VoiceCommand("next line", VoiceCommandAction.NEW_LINE),
            VoiceCommand("Smiley colon!", VoiceCommandAction.COLON),
        )
        val parsed = VoiceCommands.parse(VoiceCommands.serialize(list))
        assertEquals(listOf("next line", "smiley colon"), parsed.map { it.phrase })
        assertEquals(list.map { it.action }, parsed.map { it.action })
    }

    @Test
    fun skipsBadLines() {
        val parsed = VoiceCommands.parse("NEW_LINE\tnew line\nBOGUS\tfoo\nCOMMA\t\nNEW_LINE\tNew line\nPERIOD\ta b c d e")
        assertEquals(listOf("new line"), parsed.map { it.phrase })
    }

    @Test
    fun phraseRules() {
        assertTrue(VoiceCommands.isValidPhrase("new line"))
        assertFalse(VoiceCommands.isValidPhrase("  ,. "))
        assertFalse(VoiceCommands.isValidPhrase("one two three four five"))
    }

    @Test
    fun correctionPhrases() {
        assertEquals(CorrectionPhrases.DEFAULTS, CorrectionPhrases.enabledPhrases(CorrectionPhrases.parse("")))
        val own = listOf(CorrectionPhrase("swap that"), CorrectionPhrase("I mean", enabled = false))
        assertEquals(own, CorrectionPhrases.parse(CorrectionPhrases.serialize(own)))
        val markers = CorrectionPhrases.markers(CorrectionPhrases.enabledPhrases(own, builtIn = false))
        // the user's own phrase needs a pause, but two words may start a sentence
        val swap = markers.single()
        assertTrue(swap.needsPause && swap.atSentenceStart)
        val text = "Book a table for four, swap that five."
        val groups = SpokenEdits.selfCorrectionGroups(SpokenEdits.Words(text), emptySet(), markers)
        assertEquals(1, groups.size)
        val e = groups[0].alternatives.first()
        assertTrue(TranscriptGuard.checkEdit(text, e, markers = markers).accepted)
        // not with the built-in list
        assertTrue(SpokenEdits.selfCorrectionGroups(SpokenEdits.Words(text), emptySet()).isEmpty())
        // a switched-off phrase is not used
        val iMean = "the toaster oven, I mean, air fryer"
        assertTrue(SpokenEdits.selfCorrectionGroups(SpokenEdits.Words(iMean), emptySet(), markers).isEmpty())
    }

    @Test
    fun builtInSelfCorrectionsIncludeOrWait() {
        val text = "He comes from the same town, or wait, city as me."
        assertEquals(1, SpokenEdits.selfCorrectionGroups(SpokenEdits.Words(text), emptySet()).size)
    }

    @Test
    fun switchedOffCommandIsIgnored() {
        val commands = VoiceCommands.DEFAULTS.map { if (it.phrase == "new line") it.copy(enabled = false) else it }
        assertEquals(commands, VoiceCommands.parse(VoiceCommands.serialize(commands)))
        val groups = SpokenEdits.commandGroups(SpokenEdits.Words("Hi John. New line. How are you?"), emptySet(), null,
            org.futo.voiceinput.shared.context.PersonalNames.EMPTY, commands)
        assertTrue(groups.isEmpty())
    }

    /** Applies the first alternative of every command group in [text] (each checked by the guard). */
    private fun applyCommands(text: String, segments: List<String> = emptyList(), lexicon: SoundAlikeLexicon? = null): String {
        val groups = SpokenEdits.commandGroups(SpokenEdits.Words(text), SpokenEdits.segmentStarts(text, segments), lexicon,
            org.futo.voiceinput.shared.context.PersonalNames.EMPTY)
        var out = text
        for (g in groups.sortedByDescending { it.start }) {
            val e = g.alternatives.first()
            assertTrue("guard: ${e.replacement}", TranscriptGuard.checkEdit(text, e).accepted)
            out = out.replaceRange(e.start, e.end, e.replacement)
        }
        return out
    }

    @Test
    fun symbolCommands() {
        assertEquals("Rock & roll music.", applyCommands("Rock. Ampersand. roll music."))
        assertEquals("It is well-known.", applyCommands("It is well. Hyphen. known."))
        assertEquals("Call me. (Maybe) Thanks.", applyCommands("Call me. Open parenthesis. Maybe. Close parenthesis. Thanks."))
        assertEquals("She said \"Hello\" to me.", applyCommands("She said. Open quote. Hello. Close quote. to me."))
        assertEquals("Wait for it… Yes.", applyCommands("Wait for it. Ellipsis. Yes."))
        assertEquals("Use and/or here.", applyCommands("Use and. Forward slash. or here."))
        assertEquals("See you soon :) Bye.", applyCommands("See you soon. Smiley face. Bye."))
        assertEquals("Follow #travel now.", applyCommands("Follow. Hashtag. travel now."))
        // the actions picker offers more than 25 actions
        assertTrue(VoiceCommandAction.entries.size >= 25)
    }

    private val lowercaseFor = SoundAlikeLexicon.fromBytes(SoundAlikeLexicon.buildV2(emptyMap(), lowercase = setOf("for")))

    @Test
    fun hyphenatedCommands() {
        // the punctuation model joins a command's words with hyphens
        assertEquals("He called himself \"X-ray\" for some reason.",
            applyCommands("He called himself open-quote x-ray, close-quote, for some reason."))
        assertEquals("That's all for today.", applyCommands("That's all for today full-stop"))
        val nl = listOf("Thanks for coming,", "new-line,", "see you soon.")
        assertEquals("Thanks for coming,\nSee you soon.", applyCommands(nl.joinToString(" "), nl))
        // a hyphenated word that only contains a command word is not a command
        assertEquals("It was a period-appropriate dress.", applyCommands("It was a period-appropriate dress."))
        // the same dictation, unhyphenated, as Moonshine segments it, so the clause after the
        // closing quote goes on lowercase
        val segs = listOf("He called himself,", "Open quote.", "X-ray", "Close quote.", "For some reason,")
        assertEquals("He called himself \"X-ray\" for some reason,", applyCommands(segs.joinToString(" "), segs, lowercaseFor))
        // a new sentence after the closing quote keeps its capital, and the ASR's period goes inside
        assertEquals("She said \"I'm on my way.\" Then she left.",
            applyCommands("She said open quote I'm on my way close quote. Then she left."))
    }

    @Test
    fun ownersExamples() {
        // "new line" used as words is not a command
        val line = "I was waiting in line but then a new line formed and started moving faster."
        assertEquals(line, applyCommands(line))
        // a filler inside spoken quotes stays because commands run first, then fillers skip quoted words
        val quoted = applyCommands("And then she said. Open quotes. Um, what are you doing? Close quotes.")
        assertEquals("And then she said \"Um, what are you doing?\"", quoted)
        assertTrue(SpokenEdits.fillerGroups(SpokenEdits.Words(quoted)).isEmpty())
        // outside quotes it is still removed
        assertEquals(1, SpokenEdits.fillerGroups(SpokenEdits.Words("And then, um, she left.")).size)
    }

    @Test
    fun quotedSpeechCommands() {
        // no pauses, and quote commands are unmistakable
        assertEquals("and I was like \"Um what you doing\"",
            applyCommands("and I was like open quote um what you doing close quote"))
        // "close quote question mark" puts the question mark inside
        assertEquals("and I was like \"Um what are you doing?\"",
            applyCommands("and I was like open quote um what are you doing close quote question mark"))
        // "um comma" with a pause after "comma"
        val text = "and I was like open quote um comma what are you doing close quote question mark"
        assertEquals("and I was like \"Um, what are you doing?\"",
            applyCommands(text, listOf("and I was like open quote um comma", "what are you doing close quote question mark")))
        // the filler inside the quote stays
        assertTrue(SpokenEdits.fillerGroups(SpokenEdits.Words("and I was like \"Um, what are you doing?\"")).isEmpty())
    }

    /** Applies the quoted-speech groups of [text] (each checked by the guard). */
    private fun applyQuotes(text: String): String {
        var out = text
        for (g in SpokenEdits.quoteGroups(SpokenEdits.Words(text)).sortedByDescending { it.start }) {
            val e = g.alternatives.first()
            assertTrue("guard: ${e.replacement}", TranscriptGuard.checkEdit(text, e).accepted)
            out = out.replaceRange(e.start, e.end, e.replacement)
        }
        return out
    }

    @Test
    fun quotedSpeech() {
        assertEquals("and I was like \"Um, what are you doing?\"", applyQuotes("and I was like um what are you doing"))
        assertEquals("And I was like \"Um, what are you doing?\"", applyQuotes("And I was like, um, what are you doing."))
        assertEquals("She said \"Hey, come over here.\"", applyQuotes("She said hey, come over here."))
        assertEquals("He asked \"Can you help me?\" Then he left.", applyQuotes("He asked can you help me. Then he left."))
        assertEquals("And he fired back with \"Why do you care?\"", applyQuotes("And he fired back with why do you care."))
        assertEquals("I'm like \"Wow, really?\"", applyQuotes("I'm like wow, really?"))
        assertEquals("She said \"No, I can't.\"", applyQuotes("She said no, I can't."))
        // reports, not quotes
        for (s in listOf("She said she was tired.", "It was like what you said.", "I was like five minutes late.",
            "He said what he wanted.", "They were like the best team.", "He fired back with a shotgun.",
            "He was like ten feet tall.", "He said not to.", "We said sorry to the neighbors.",
            "She said no to the offer.", "He said hi to everyone.", "She said yes.")) {
            assertEquals(s, applyQuotes(s))
        }
        // certain (no LM vote) when there is a filler first, or a pause after "said" and then a question or "no,"
        fun certain(s: String) = SpokenEdits.quoteGroups(SpokenEdits.Words(s)).map { it.certain }
        assertEquals(listOf(true), certain("She goes, what are you doing here?"))
        assertEquals(listOf(true), certain("So I said, no, I can't make it."))
        assertEquals(listOf(true), certain("I was like, um, are you serious?"))
        assertEquals("My boss was like \"Hmm, can you stay late?\"", applyQuotes("My boss was like, hmm, can you stay late?"))
        assertEquals("The kids were like, so excited.", applyQuotes("The kids were like, so excited."))
        assertEquals(listOf(false), certain("She said no, I can't."))
        assertEquals(listOf(false), certain("My mom said yes, so we're going."))
    }

    @Test
    fun deleteLastWord() {
        assertEquals("I want the blue car.", applyCommands("I want the red. Delete last word. blue car."))
        // not without a pause before it
        assertEquals("I said delete last word yesterday.", applyCommands("I said delete last word yesterday."))
    }

    /** Built-in fillers that are ordinary words too only count between pauses, and "so yeah" at a sentence end. */
    @Test
    fun wordFillers() {
        fun fillers(s: String) = SpokenEdits.fillerGroups(SpokenEdits.Words(s)).filter { it.kind == SpokenEdits.Kind.FILLER }
        assertEquals(1, fillers("It's, like, really far.").size)
        assertEquals(1, fillers("We went home, so yeah.").size)
        assertEquals(1, fillers("It was, kind of, cold.").size)
        for (s in listOf(
            "I like it a lot.", "It was like, so cool.", "What kind of car is it?", "It's one of a kind, of course.",
            "You know, I never liked him.", "We waited, like, ten minutes.", "So yeah, that's the plan.", "So yeah?",
        )) assertTrue(s, fillers(s).isEmpty())
    }

    /** "make it" is a self-correction only between numbers, and the new quote phrases need actual speech. */
    @Test
    fun numberAndSpeechOnlyPhrases() {
        fun corrections(s: String) = SpokenEdits.selfCorrectionGroups(SpokenEdits.Words(s), emptySet())
        assertEquals(1, corrections("Book a table for four, make it five.").size)
        assertTrue(corrections("Hurry up, make it quick.").isEmpty())
        fun quotes(s: String) = SpokenEdits.quoteGroups(SpokenEdits.Words(s))
        assertEquals(1, quotes("She texted me, are you coming tonight?").size)
        for (s in listOf("He answered the phone!", "She was all smiles!", "He yelled at me!", "He said that?")) {
            assertTrue(s, quotes(s).isEmpty())
        }
    }

    @Test
    fun customFillers() {
        assertEquals(FillerWords.DEFAULTS, FillerWords.enabledPhrases(FillerWords.parse("")))
        // "you know" is not built in, so it stays unless you add it
        val builtIn = FillerWords.fillers(FillerWords.DEFAULTS)
        assertTrue(SpokenEdits.fillerGroups(SpokenEdits.Words("I was, you know, thinking about it."), builtIn).isEmpty())
        val entries = FillerWords.parse("um\t0\nyou know\n")
        val fillers = FillerWords.fillers(FillerWords.enabledPhrases(entries))
        val text = "I was, you know, thinking about it."
        val groups = SpokenEdits.fillerGroups(SpokenEdits.Words(text), fillers)
        assertEquals(1, groups.size)
        assertTrue(TranscriptGuard.checkEdit(text, groups[0].alternatives.first(), fillers = fillers).accepted)
        // "um" is switched off
        assertTrue(SpokenEdits.fillerGroups(SpokenEdits.Words("I was, um, thinking."), fillers).isEmpty())
        // the user's own fillers stay at a sentence start
        assertTrue(SpokenEdits.fillerGroups(SpokenEdits.Words("You know, it was fine."), fillers).isEmpty())
    }

    @Test
    fun personalDictionaryWordsCount() {
        val names = PersonalNames(listOf("Xiomara", "kubectl", "toaster")) { it == "toaster" }
        assertEquals("kubectl", names.name("Kubectl"))
        assertEquals(null, names.name("toaster"))
        assertTrue(names.isCapitalizedName("xiomara"))
        assertFalse(names.isCapitalizedName("kubectl"))
    }

    @Test
    fun customCommandIsUsedAndGuarded() {
        val commands = listOf(VoiceCommand("next line", VoiceCommandAction.NEW_LINE))
        val text = "Hi John. Next line. How are you?"
        val w = SpokenEdits.Words(text)
        val groups = SpokenEdits.commandGroups(w, emptySet(), null, org.futo.voiceinput.shared.context.PersonalNames.EMPTY, commands)
        assertEquals(1, groups.size)
        val e = groups[0].alternatives[0]
        assertTrue(TranscriptGuard.checkEdit(text, e, commands).accepted)
        // a removed built-in command is no longer proposed or accepted
        val noDefaults = SpokenEdits.commandGroups(SpokenEdits.Words("Hi John. New line. How are you?"), emptySet(), null,
            org.futo.voiceinput.shared.context.PersonalNames.EMPTY, commands)
        assertTrue(noDefaults.isEmpty())
    }
}
