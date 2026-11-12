package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.NumberWords
import org.futo.voiceinput.shared.context.PersonalNames
import org.futo.voiceinput.shared.context.SoundAlikeLexicon
import org.futo.voiceinput.shared.context.SpokenEdits
import org.futo.voiceinput.shared.context.SpokenEdits.Kind
import org.futo.voiceinput.shared.context.SpokenEdits.SpanEdit
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Candidates no pause or punctuation marks (`undelimited`, judged by the LM on the sentence alone with
 * stricter margins), the guard's checks of them, and the digit formats of voice models that write
 * numbers themselves (Nemotron). Rules only, no LM: whether the LM takes an unmarked candidate is
 * covered by tools/context-bench/testset_undelimited.jsonl.
 *
 * Unlike the helpers in SmartCorrectionsTest / StressRegressionTest, these keep the unmarked
 * alternatives and check their `undelimited` flag.
 */
class UndelimitedEditsTest {
    /** Case data as the lexicon would give it (a repair capitalized after a pause goes on lowercase). */
    private val lexicon = SoundAlikeLexicon.fromBytes(
        SoundAlikeLexicon.buildV2(emptyMap(), lowercase = setOf("blue", "thanks", "see", "the", "for", "what"))
    )
    private val isWord: (String) -> Boolean = { lexicon.isLowercaseWord(it) }

    private data class Alt(
        val text: String, val undelimited: Boolean, val parallel: Boolean, val certain: Boolean,
        val guard: Boolean, val why: String,
    )

    /**
     * Every alternative of every group of [kind] in [text], applied on its own, with the guard's
     * decision. [focus] is where the dictation starts (text before it was already in the field), and
     * as in the context stage, that start counts as a pause and only alternatives after it are kept.
     */
    private fun alts(text: String, kind: Kind, segments: List<String> = emptyList(), focus: Int = 0): List<Alt> {
        val w = SpokenEdits.Words(text)
        val dictationStart = if (focus > 0) (0 until w.size).firstOrNull { w.starts[it] >= focus } else null
        val seg = SpokenEdits.segmentStarts(text, segments) + listOfNotNull(dictationStart)
        val groups = when (kind) {
            Kind.SELF_CORRECTION -> SpokenEdits.selfCorrectionGroups(w, seg, isWord = isWord)
            Kind.QUOTE -> SpokenEdits.quoteGroups(w, isWord = isWord)
            Kind.NUMBER -> SpokenEdits.numberGroups(w)
            else -> SpokenEdits.commandGroups(w, seg, lexicon, PersonalNames.EMPTY).filter { it.kind == kind }
        }
        return groups.flatMap { g ->
            g.alternatives.filter { it.start >= focus }.map { a ->
                val d = TranscriptGuard.checkEdit(text, a, dictationStart = focus, pauses = seg.mapTo(HashSet()) { w.starts[it] })
                Alt(text.substring(0, a.start) + a.replacement + text.substring(a.end), a.undelimited, a.parallel, g.certain, d.accepted, d.reason)
            }
        }
    }

    private fun texts(text: String, kind: Kind, segments: List<String> = emptyList(), focus: Int = 0) =
        alts(text, kind, segments, focus).map { it.text }

    private fun assertGuarded(alts: List<Alt>) {
        for (a in alts) assertTrue("guard rejects '${a.text}': ${a.why}", a.guard)
    }

    // ------------------------------------------------------------------------------------
    // Self-corrections

    @Test
    fun selfCorrectionsWithoutPauses() {
        // a one-word cue said without a pause is offered for the LM to judge on the sentence alone
        val chairs = alts("Bring three sorry four chairs", Kind.SELF_CORRECTION)
        assertEquals(listOf("Bring four chairs"), chairs.map { it.text })
        assertTrue(chairs.single().undelimited && chairs.single().parallel)
        // a repair of another kind ("oranges" for "apples") offers each length up to the repair's
        val apples = alts("Pick up some apples I mean oranges on the way home", Kind.SELF_CORRECTION)
        assertEquals(listOf("Pick up some oranges on the way home", "Pick oranges on the way home"), apples.map { it.text })
        assertTrue(apples.all { it.undelimited && !it.parallel })
        // names mirror names
        val names = alts("Send it to Sam sorry Alex by tonight", Kind.SELF_CORRECTION)
        assertEquals("Send it to Alex by tonight", names.first().text)
        assertTrue(names.first().parallel && names.all { it.undelimited })
        // numbers mirror numbers, and "make it" only between numbers
        assertEquals(listOf("The party starts at nine"), texts("The party starts at eight actually nine", Kind.SELF_CORRECTION))
        assertEquals(listOf("Book a table for five"), texts("Book a table for four make it five", Kind.SELF_CORRECTION))
        // a phrase made for corrections needs no pause ("no wait"), so it is not unmarked
        val wait = alts("Let's meet at five no wait six o'clock", Kind.SELF_CORRECTION)
        assertEquals(listOf("Let's meet at six o'clock"), wait.map { it.text })
        assertFalse(wait.single().undelimited)
        for (s in listOf(chairs, apples, names, wait)) assertGuarded(s)
    }

    @Test
    fun numberRepairReachesBackToItsNumber() {
        // "three cups" replaces "two cups of flour", never "We need two cups of three cups"
        val flour = alts("We need two cups of flour sorry three cups", Kind.SELF_CORRECTION)
        assertEquals(listOf("We need three cups"), flour.map { it.text })
        assertTrue(flour.single().parallel && flour.single().undelimited)
        assertEquals(listOf("Add one cup"), texts("Add two cups of sugar sorry one cup", Kind.SELF_CORRECTION))
        // also across the sentence end the voice model writes at the pause
        val across = alts("We need two cups of flour. Sorry, three cups.", Kind.SELF_CORRECTION)
        assertEquals(listOf("We need three cups."), across.map { it.text })
        assertGuarded(flour + across)
        // more than four words back is out of reach, and nothing else is offered in its place
        assertEquals(emptyList<String>(), texts("I need two big bags of rice sorry three bags", Kind.SELF_CORRECTION))
    }

    @Test
    fun selfCorrectionsAcrossTheModelsSentenceEnd() {
        // a voice model that punctuates ends a sentence right at the cue, and the candidate is unmarked
        val bag = alts("Get the red bag. Sorry, the blue bag.", Kind.SELF_CORRECTION)
        assertEquals(listOf("Get the blue bag."), bag.map { it.text })
        assertTrue(bag.single().undelimited)
        assertEquals(listOf("I'll see you at the library."), texts("I'll see you at the park. Sorry, the library.", Kind.SELF_CORRECTION))
        assertEquals(listOf("Meet me on Saturday."), texts("Meet me on Friday. Actually, Saturday.", Kind.SELF_CORRECTION))
        // the repair the model capitalized after its period goes on lowercase
        assertTrue("Get the blue bag." in texts("Get the red bag. Sorry. Blue bag.", Kind.SELF_CORRECTION))
        assertGuarded(alts("Get the red bag. Sorry. Blue bag.", Kind.SELF_CORRECTION) + bag)
        // new thoughts after a sentence end are only ever offered unmarked (the LM keeps them)
        for (s in listOf(
            "That was my fault. Sorry, the kids were loud.", "I'm late. Sorry. Traffic was bad.",
            "I watched the movie. I mean, the ending was weird.",
        )) assertTrue(s, alts(s, Kind.SELF_CORRECTION).all { it.undelimited })
    }

    @Test
    fun guardTakesASentenceEndOnlyForAnUnmarkedCandidate() {
        val text = "Get the red bag. Sorry, the blue bag."
        val e = SpokenEdits.selfCorrectionGroups(SpokenEdits.Words(text), emptySet()).single().alternatives.single()
        assertTrue(e.undelimited)
        assertTrue(TranscriptGuard.checkEdit(text, e).accepted)
        // the same deletion, not judged on the sentence alone, crosses a sentence end
        assertFalse(TranscriptGuard.checkEdit(text, e.copy(undelimited = false)).accepted)
        // and the flag never lets through more than the cue's own sentence end
        val two = "It was big. Really big. Sorry, huge."
        val a = two.indexOf("big. Really")
        val b = two.indexOf("huge")
        assertFalse(TranscriptGuard.checkEdit(two, SpanEdit(a, b, "", Kind.SELF_CORRECTION, undelimited = true)).accepted)
    }

    @Test
    fun cueWordsInOrdinarySentences() {
        // no candidate at all, marked or not
        for (s in listOf(
            "I'm sorry I missed your call earlier.", "I feel sorry for the guy who lost his wallet.",
            "The movie was good actually.", "I'd rather stay home tonight.", "We were sorry to see them go.",
            "There were actually four people at the party.", "She is sorry about the mess.",
            "He apologized, sorry he couldn't come.", "I actually liked the second movie more than the first one.",
            "Sorry to bother you but is the store still open?", "I mean to call her tomorrow morning.",
        )) assertEquals(s, emptyList<String>(), texts(s, Kind.SELF_CORRECTION))
        // a cue that is also an ordinary word ("a rather long meeting") is at most offered unmarked
        for (s in listOf("It was a rather long meeting but we got through it.", "The rather old house on the corner sold.")) {
            assertTrue(s, alts(s, Kind.SELF_CORRECTION).all { it.undelimited })
        }
    }

    /**
     * "scratch that" as an ordinary verb: the command rule declines it unless the sentence is said
     * again (see [scratchThatAsAnOrdinaryVerb]), but the same words are a self-correction cue, and
     * without a pause that rule offers the deletion anyway ("Can you scratch that itch" -> "Can itch",
     * "I'll scratch that off the list." -> "off the list."), leaving it all to the LM.
     */
    @Test
    fun scratchThatAsAVerbIsNoSelfCorrection() {
        for (s in listOf("Can you scratch that itch for me?", "I'll scratch that off the list.", "My cat likes to scratch that old chair.")) {
            assertEquals(s, emptyList<String>(), texts(s, Kind.SELF_CORRECTION))
        }
    }

    // ------------------------------------------------------------------------------------
    // Commands

    @Test
    fun commaBesideACommandCountsAsAPause() {
        val after = alts("Hi Sam, new paragraph I wanted to tell you about the trip", Kind.COMMAND)
        assertEquals(listOf("Hi Sam,\n\nI wanted to tell you about the trip"), after.map { it.text })
        val before = alts("Hi Sam new paragraph, I wanted to tell you about the trip", Kind.COMMAND)
        assertEquals(listOf("Hi Sam\n\nI wanted to tell you about the trip"), before.map { it.text })
        val both = alts("Dear Sam, Newline, thanks for the update", Kind.COMMAND)
        assertEquals(listOf("Dear Sam,\nThanks for the update"), both.map { it.text })
        for (a in after + before + both) assertFalse(a.text, a.undelimited)
        // (a comma is no pause the ASR reported, so the LM still votes)
        for (a in after + before + both) assertFalse(a.text, a.certain)
        assertGuarded(after + before + both)
    }

    @Test
    fun commandsWithoutPauses() {
        for ((s, out) in listOf(
            "Dear Sam Newline thanks for the update" to "Dear Sam\nThanks for the update",
            "Thanks for everything newline see you soon" to "Thanks for everything\nSee you soon",
            "Thanks comma see you soon" to "Thanks, see you soon",
        )) {
            val a = alts(s, Kind.COMMAND)
            assertEquals(s, listOf(out), a.map { it.text })
            assertTrue(s, a.single().undelimited && !a.single().certain)
            assertGuarded(a)
        }
    }

    @Test
    fun oneWordSpellingOfACommand() {
        val newLine = listOf("new", "line")
        assertEquals(1, SpokenEdits.phraseLength(listOf("newline"), 0, newLine))
        assertEquals(1, SpokenEdits.phraseLength(listOf("new-line"), 0, newLine))
        assertEquals(2, SpokenEdits.phraseLength(listOf("new", "line"), 0, newLine))
        assertEquals(0, SpokenEdits.phraseLength(listOf("newlines"), 0, newLine))
        assertEquals(0, SpokenEdits.phraseLength(listOf("renewline"), 0, newLine))
        assertEquals(0, SpokenEdits.phraseLength(listOf("new"), 0, newLine))
        assertEquals(1, SpokenEdits.phraseLength(listOf("newparagraph"), 0, listOf("new", "paragraph")))
    }

    @Test
    fun commandWordsInOrdinarySentences() {
        for (s in listOf(
            "The newline character ends each row in the file.", "Write it on a new line, then send it.",
            "Add a newline, then save the file.", "Hold on, period, I'm not done.", "Class starts after second period, right?",
            "The new paragraph, which I wrote today, is long.", "A new line formed outside the store.",
            "There was a long line at the coffee shop this morning.", "The trial period ends next Friday.",
        )) assertEquals(s, emptyList<String>(), texts(s, Kind.COMMAND))
    }

    // ------------------------------------------------------------------------------------
    // Scratch that

    @Test
    fun scratchMidSentenceWhenTheSentenceIsSaidAgain() {
        val one = alts("I hate this sentence scratch that I love this sentence.", Kind.SCRATCH)
        assertEquals(listOf("I love this sentence."), one.map { it.text })
        assertTrue(one.single().undelimited)
        // two content words said again show the intent, so it's judged as a marked command
        // (the context stage capitalizes the new sentence start)
        val again = alts("The store closes at 5 scratch that the store closes at 6", Kind.SCRATCH)
        assertEquals(listOf("the store closes at 6"), again.map { it.text })
        assertFalse(again.single().undelimited)
        val dog = alts("The cat scratched the chair scratch that the dog scratched the chair.", Kind.SCRATCH)
        assertEquals(listOf("the dog scratched the chair."), dog.map { it.text })
        assertFalse(dog.single().undelimited)
        assertGuarded(one + again + dog)
    }

    @Test
    fun scratchThatAsAnOrdinaryVerb() {
        for (s in listOf(
            "Can you scratch that itch for me?", "I'll scratch that off the list.", "My cat likes to scratch that old chair.",
            "Do not scratch that, it will get infected.", "Scratch that off the list.",
        )) assertEquals(s, emptyList<String>(), texts(s, Kind.SCRATCH))
    }

    @Test
    fun scratchBackToThePauseAndToTheSentenceStart() {
        val text = "Okay so I hate this sentence scratch that I love this sentence"
        val a = alts(text, Kind.SCRATCH, listOf("Okay so", "I hate this sentence scratch that I love this sentence"))
        assertEquals(listOf("Okay so I love this sentence", "I love this sentence"), a.map { it.text })
        assertTrue(a.all { it.undelimited })
        assertTrue(a[1].guard)
    }

    /**
     * The scratch rule offers the deletion back to the pause ("Okay so | I hate this sentence scratch
     * that ..."), but the guard only takes a scratch that starts a sentence (or the dictation), so if
     * the LM picks that alternative nothing is applied at all.
     */
    @Test
    fun guardTakesTheScratchBackToThePause() {
        val text = "Okay so I hate this sentence scratch that I love this sentence"
        val a = alts(text, Kind.SCRATCH, listOf("Okay so", "I hate this sentence scratch that I love this sentence"))
        assertGuarded(a)
    }

    @Test
    fun scratchAtTheDictationStart() {
        // the text already in the field has no final mark, and the dictation's start counts as a pause
        val text = "We need three cups I hate this sentence. Scratch that, I love this sentence."
        val focus = text.indexOf("I hate")
        val a = alts(text, Kind.SCRATCH, focus = focus)
        assertEquals(listOf("We need three cups I love this sentence."), a.map { it.text })
        assertGuarded(a)
        val e = SpanEdit(focus, text.indexOf("I love"), "", Kind.SCRATCH)
        assertTrue(TranscriptGuard.checkEdit(text, e, dictationStart = focus).accepted)
        assertFalse(TranscriptGuard.checkEdit(text, e).accepted)
        // unmarked, the dictation's start is where the scratch begins
        val run = "Pick up milk I hate this sentence scratch that I love this sentence"
        val b = alts(run, Kind.SCRATCH, focus = run.indexOf("I hate"))
        assertEquals(listOf("Pick up milk I love this sentence"), b.map { it.text })
        assertGuarded(b)
        // still only one sentence back
        val two = "Pick up milk I hate this. I really do. Scratch that, I love it."
        val f = two.indexOf("I hate")
        assertFalse(TranscriptGuard.checkEdit(two, SpanEdit(f, two.indexOf("I love"), "", Kind.SCRATCH), dictationStart = f).accepted)
    }

    // ------------------------------------------------------------------------------------
    // Quotes

    @Test
    fun pronounShiftMakesAnUnmarkedQuote() {
        for ((s, out) in listOf(
            "She said I'll be there in five minutes" to "She said \"I'll be there in five minutes.\"",
            "They said we're closed on Sundays." to "They said \"We're closed on Sundays.\"",
            "She said I am coming with you." to "She said \"I am coming with you.\"",
            "My boss said I'm not paying for that." to "My boss said \"I'm not paying for that.\"",
        )) {
            val a = alts(s, Kind.QUOTE)
            assertEquals(s, listOf(out), a.map { it.text })
            assertTrue(s, a.single().undelimited && !a.single().certain)
            assertGuarded(a)
        }
    }

    @Test
    fun reportedSpeechIsNoQuote() {
        for (s in listOf(
            // the words go on about the speaker in the third person
            "He said I'm the best cook he knows", "She said I'll be there and she was.", "He said I'll call his brother tomorrow.",
            // "I" / "we" reporting their own words
            "I said I'll be there by noon", "We said we'll be there at noon.",
            // backshifted or past tense
            "She said she'd be there in five minutes.", "She said I was right about the whole thing.",
            "He said I should call you after lunch.", "They said I could borrow the car this weekend.",
        )) assertEquals(s, emptyList<String>(), texts(s, Kind.QUOTE))
    }

    // ------------------------------------------------------------------------------------
    // Numbers a voice model wrote as digits

    private fun fmt(t: String) = NumberWords.digitFormats(t).map { (a, b, f) -> t.substring(a, b) + "=" + f }

    @Test
    fun digitFormatEdgeCases() {
        assertEquals(listOf("1230=12:30"), fmt("Lunch is at 1230 pm."))
        assertEquals(listOf("12 30=12:30"), fmt("Lunch is at 12 30 PM today."))
        assertEquals(listOf("0930=9:30"), fmt("Meet me at 0930 tomorrow."))
        assertEquals(listOf("5 30=5:30"), fmt("Be there by 5 30, okay?"))
        assertEquals(listOf("3.30=3:30"), fmt("It ended at 3.30 and then we left."))
        assertEquals(listOf("5551234567=555-123-4567"), fmt("My number is 5551234567."))
        for (t in listOf(
            "We moved there at 2024.", "They arrived at 7 45 people strong.", "Wake me at 1260.", "Call me at 55512345.",
            "Call 911 now.", "The price is 3.30 at the store.", "I scored at 3 15 percent.", "Gate 315 is closed.",
        )) assertEquals(t, emptyList<String>(), fmt(t))
    }

    /** "number" alone is no phone context: order and tracking numbers are not phone numbers. */
    @Test
    fun orderNumbersAreNoPhoneNumbers() {
        for (t in listOf("Your order number is 1234567.", "The tracking number is 1234567890.")) {
            assertEquals(t, emptyList<String>(), fmt(t))
        }
    }

    @Test
    fun centsEdgeCases() {
        assertEquals("$1.01", NumberWords.centsForm("one dollar and one cent"))
        assertEquals("$2.50", NumberWords.centsForm("two dollars fifty cents"))
        assertEquals("$5.50", NumberWords.centsForm("5 dollars 50 cents"))
        assertEquals("$25.99", NumberWords.centsForm("twenty-five dollars and ninety-nine cents"))
        assertEquals("$1,200.05", NumberWords.centsForm("one thousand two hundred dollars and five cents"))
        assertEquals("$45.99", NumberWords.centsForm("$45 and ninety nine cents"))
        for (s in listOf(
            "ninety nine cents", "forty five dollars and a hundred cents", "45 dollars and the tip was ninety nine cents",
            "five dollars and he gave me fifty cents", "$5 cents", "dollars ninety nine cents", "forty five dollars",
        )) assertNull(s, NumberWords.centsForm(s))
    }

    @Test
    fun digitSequenceEdgeCases() {
        assertEquals("007", NumberWords.digitSequenceForm("oh oh seven"))
        assertEquals("12345", NumberWords.digitSequenceForm("one two three four five"))
        assertEquals("40", NumberWords.digitSequenceForm("four oh"))
        assertEquals("21", NumberWords.digitSequenceForm("two one"))
        for (s in listOf("one two three four five six", "two two", "seven", "two and four", "twenty one")) {
            assertNull(s, NumberWords.digitSequenceForm(s))
        }
    }

    @Test
    fun numberCandidatesForDigitsAndCents() {
        for ((s, out) in listOf(
            "It costs $45 ninety nine cents." to "It costs $45.99.",
            "It costs forty five dollars ninety nine cents" to "It costs $45.99",
            "Our flight leaves from gate two one" to "Our flight leaves from gate 21",
            "The meeting is in room two oh four" to "The meeting is in room 204",
            "Pick up the kids at 315 on Friday." to "Pick up the kids at 3:15 on Friday.",
            "Call me at 5551234 tomorrow." to "Call me at 555-1234 tomorrow.",
        )) {
            val a = alts(s, Kind.NUMBER)
            assertEquals(s, listOf(out), a.map { it.text })
            assertTrue(s, a.single().certain)
            assertGuarded(a)
        }
        // cents said apart from the dollars are not joined to them
        val apart = alts("I gave him five dollars and he gave me fifty cents.", Kind.NUMBER)
        assertEquals(listOf("I gave him $5 and he gave me fifty cents.", "I gave him five dollars and he gave me 50 cents."), apart.map { it.text })
        // digits said one by one only after "room", "gate", ..., and not a pair of the same digit
        for (s in listOf("Flight one one leaves soon.", "We need a room two or three nights.", "I'll be at the room four tonight.")) {
            assertEquals(s, emptyList<String>(), texts(s, Kind.NUMBER))
        }
    }

    /** Six digits said one by one are cut after five ("extension 12345 six"). */
    @Test
    fun longDigitRunIsNotCut() {
        val out = texts("Call extension one two three four five six please.", Kind.NUMBER)
        assertTrue(out.toString(), out.none { "12345 six" in it })
    }

    @Test
    fun guardChecksDigitReformats() {
        fun ok(text: String, from: String, to: String): Boolean {
            val a = text.indexOf(from)
            return TranscriptGuard.checkEdit(text, SpanEdit(a, a + from.length, to, Kind.NUMBER)).accepted
        }
        // clock times and phone numbers, the same digits
        assertTrue(ok("The meeting is at 3.30.", "3.30", "3:30"))
        assertTrue(ok("Pick up the kids at 315.", "315", "3:15"))
        assertTrue(ok("Call me at 5551234.", "5551234", "555-1234"))
        assertTrue(ok("My number is 5551234567.", "5551234567", "555-123-4567"))
        assertFalse(ok("The meeting is at 3.30.", "3.30", "3:31"))
        assertFalse(ok("The meeting is at 3.30.", "3.30", "3:30 PM"))
        assertFalse(ok("Call me at 5551234.", "5551234", "555-12-34"))
        assertFalse(ok("Call me at 5551234.", "5551234", "555-1243"))
        // dollars and cents
        assertTrue(ok("It costs forty five dollars ninety nine cents.", "forty five dollars ninety nine cents", "$45.99"))
        assertFalse(ok("It costs forty five dollars ninety nine cents.", "forty five dollars ninety nine cents", "$45.90"))
        assertFalse(ok("It costs forty five dollars ninety nine cents.", "forty five dollars ninety nine cents", "$4,599"))
        // digits said one by one
        assertTrue(ok("Go to room two oh four.", "two oh four", "204"))
        assertFalse(ok("Go to room two oh four.", "two oh four", "24"))
        assertFalse(ok("Go to room two oh four.", "two oh four", "2 0 4"))
    }
}
