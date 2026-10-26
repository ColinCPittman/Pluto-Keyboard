package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.NumberWords
import org.futo.voiceinput.shared.context.PersonalDictionary
import org.futo.voiceinput.shared.context.PersonalNames
import org.futo.voiceinput.shared.context.SmartCorrectionOptions
import org.futo.voiceinput.shared.context.SoundAlikeLexicon
import org.futo.voiceinput.shared.context.SpokenEdits
import org.futo.voiceinput.shared.context.SpokenEdits.Kind
import org.futo.voiceinput.shared.context.SpokenEdits.SpanEdit
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules of the Smart corrections options (no LM), covering candidates, the guard's checks and the lexicon. */
class SmartCorrectionsTest {
    // ------------------------------------------------------------------------------------
    // Numbers

    private fun forms(s: String) = NumberWords.forms(s)

    @Test
    fun numberForms() {
        assertEquals(listOf("$25"), forms("twenty five dollars"))
        assertEquals(listOf("3:30 PM"), forms("three thirty pm"))
        assertEquals(listOf("3:30 PM"), forms("three thirty p.m."))
        assertEquals(listOf("50%"), forms("fifty percent"))
        assertEquals(listOf("2.5%"), forms("two point five percent"))
        assertTrue("2026" in forms("two thousand twenty six"))
        assertEquals(listOf("1995"), forms("nineteen ninety five"))
        assertTrue("2026" in forms("twenty twenty six"))
        assertEquals(listOf("$450"), forms("four hundred and fifty dollars"))
        assertEquals(listOf("$12.50"), forms("twelve dollars and fifty cents"))
        assertEquals(listOf("$15,000"), forms("fifteen thousand dollars"))
        assertEquals(listOf("10 AM"), forms("ten am"))
        assertEquals(listOf("21st"), forms("twenty first"))
        assertEquals(listOf("6:45"), forms("six forty five"))
        assertEquals(listOf("3:05"), forms("three oh five"))
        assertEquals(listOf("37"), forms("thirty-seven"))
        assertEquals(listOf("120"), forms("one hundred and twenty"))
    }

    @Test
    fun numbersLeftAlone() {
        // below ten without a unit, ungrammatical runs, ordinals below ten
        for (s in listOf("one", "two", "first", "second", "ten four", "twenty four seven", "four seven", "three and four", "oh")) {
            assertEquals(s, emptyList<String>(), forms(s))
        }
        assertNull(NumberWords.cardinal(listOf("five", "six")))
        assertEquals(2500L, NumberWords.cardinal(listOf("twenty", "five", "hundred")))
    }

    private fun numberSpans(text: String): List<String> {
        val w = SpokenEdits.Words(text)
        return SpokenEdits.numberGroups(w).map { g -> text.substring(g.start, g.end) }
    }

    @Test
    fun numberCandidatesInText() {
        assertEquals(listOf("twenty five dollars"), numberSpans("The tickets were twenty five dollars each."))
        assertEquals(listOf("three thirty p.m."), numberSpans("Meet at three thirty p.m. tomorrow."))
        assertEquals(emptyList<String>(), numberSpans("One of them is broken."))
        assertEquals(emptyList<String>(), numberSpans("That song was huge in the nineteen eighties."))
        assertEquals(emptyList<String>(), numberSpans("We watched Ocean's Eleven last night."))
        assertEquals(emptyList<String>(), numberSpans("That store is open twenty four seven."))
    }

    @Test
    fun amounts() {
        // digits the voice model wrote, the unit said as a word
        assertEquals("$800", NumberWords.digitAmountForm("800 dollars"))
        assertEquals("$1,200", NumberWords.digitAmountForm("1,200 dollars"))
        assertEquals("€3.50", NumberWords.digitAmountForm("3.50 euros"))
        assertEquals("30%", NumberWords.digitAmountForm("30 percent"))
        assertEquals("$1", NumberWords.digitAmountForm("1 dollar"))
        assertNull(NumberWords.digitAmountForm("5 dollar"))
        assertEquals(listOf("$800"), forms("800 dollars"))
        assertEquals(listOf("800 dollars"), numberSpans("I made 800 dollars last week."))
        assertEquals(listOf("1,200 dollars"), numberSpans("It costs 1,200 dollars."))
        for (s in listOf("He paid with a 5 dollar bill.", "It was $800 dollars.", "The dollars are 800 miles away.")) {
            assertEquals(s, emptyList<String>(), numberSpans(s))
        }
        // amounts with their unit are written with digits without the LM's vote, also at a sentence start
        val groups = SpokenEdits.numberGroups(SpokenEdits.Words("Six hundred dollars profit and 40 percent off at three thirty pm."))
        assertEquals(listOf(true, true, false), groups.sortedBy { it.start }.map { it.certain })
    }

    // ------------------------------------------------------------------------------------
    // Lexicon (format version 2)

    private val lexV2 = SoundAlikeLexicon.fromBytes(
        SoundAlikeLexicon.buildV2(
            table = mapOf("bulls" to listOf("bowls")),
            boundaries = mapOf("into" to listOf("in to", "in two"), "in to" to listOf("into"), "every day" to listOf("everyday")),
            capitalized = mapOf("jordan" to "Jordan"),
            lowercase = setOf("toaster", "milk", "how"),
        )
    )

    @Test
    fun lexiconV2() {
        assertEquals(2, lexV2.version)
        assertEquals(listOf("bowls"), lexV2.candidates("Bulls"))
        assertEquals(listOf("in to", "in two"), lexV2.boundaryCandidates("Into"))
        assertEquals(listOf("into"), lexV2.boundaryCandidates("In  to"))
        assertEquals("Jordan", lexV2.capitalized("jordan"))
        assertTrue(lexV2.isLowercaseWord("Toaster"))
        assertFalse(lexV2.isLowercaseWord("jordan"))
        assertTrue(lexV2.hasBoundaries && lexV2.hasCaseData)
        assertTrue(SoundAlikeLexicon.isLexiconMagic("FCXLEX02"))
    }

    @Test
    fun lexiconV1HasNoNewData() {
        val v1 = SoundAlikeLexicon.fromBytes(SoundAlikeLexicon.build(mapOf("bulls" to listOf("bowls"))))
        assertEquals(1, v1.version)
        assertEquals(listOf("bowls"), v1.candidates("bulls"))
        assertEquals(emptyList<String>(), v1.boundaryCandidates("into"))
        assertNull(v1.capitalized("jordan"))
        assertFalse(v1.isLowercaseWord("toaster"))
        assertFalse(v1.hasBoundaries || v1.hasCaseData)
    }

    // ------------------------------------------------------------------------------------
    // Names

    @Test
    fun personalNames() {
        val names = PersonalNames(listOf("Xiomara", "Caitlin", "Kai", "toaster", "NASA"))
        assertEquals(listOf("Xiomara"), names.soundAlike(listOf("see", "ya", "mara")))
        assertEquals(listOf("Caitlin"), names.soundAlike(listOf("Katelyn")))
        assertEquals(emptyList<String>(), names.soundAlike(listOf("key")))  // one-sound codes are too weak
        assertEquals(emptyList<String>(), names.soundAlike(listOf("Caitlin")))  // same spelling, so nothing to do
        assertEquals("Xiomara", names.name("xiomara"))
        assertNull(names.name("toaster"))  // lowercase entries are not names
        assertNull(names.name("nasa"))  // nor all-caps ones
        assertTrue(names.contains("Toaster"))
        assertTrue(names.isSoundAlikeName(listOf("see", "ya", "mara"), "Xiomara"))
        assertFalse(names.isSoundAlikeName(listOf("more"), "Xiomara"))
        // words run together need the name's main sound code, not an alternate or a silent "h"
        val far = PersonalNames(listOf("Hadorn", "Thornton"))
        assertEquals(emptyList<String>(), far.soundAlike(listOf("oh", "darn")))
        assertEquals(emptyList<String>(), far.soundAlike(listOf("torn", "ton")))
    }

    @Test
    fun sensitivityMovesMargins() {
        val base = org.futo.voiceinput.shared.context.ContextRescoreConfig()
        val s = org.futo.voiceinput.shared.context.SmartCorrectionSensitivity(names = -2, fillers = 1, selfCorrections = 2, quotes = 1)
        val c = s.adjust(base)
        assertEquals(base.nameMargin + 3.0, c.nameMargin, 1e-9)
        assertEquals(base.fillerMargin - 1.5, c.fillerMargin, 1e-9)
        assertEquals(base.selfCorrectionMargin - 0.8, c.selfCorrectionMargin, 1e-9)
        assertEquals(base.wordMargin, c.wordMargin, 1e-9)
        assertEquals(1.5, s.quoteShift(), 1e-9)
        // out-of-range values are clamped
        assertEquals(base.nameMargin + 3.0, s.copy(names = -9).adjust(base).nameMargin, 1e-9)
        // typing has one setting
        val typing = org.futo.voiceinput.shared.context.TypingCorrectionConfig()
        assertEquals(typing.margin - 3.0, typing.withSensitivity(2).margin, 1e-9)
        assertEquals(typing, typing.withSensitivity(0))
    }

    @Test
    fun learnedWordsAsNames() {
        val names = PersonalNames.from(
            PersonalDictionary.of(words = listOf("Toaster"), learned = listOf("Xiomara", "The", "Milk", "Jordan")), lexV2
        )
        assertEquals("Xiomara", names.name("xiomara"))
        assertEquals("Jordan", names.name("jordan"))
        assertNull(names.name("the"))  // a function word
        assertNull(names.name("milk"))  // a common lowercase word the user once capitalized
        assertEquals("Toaster", names.name("toaster"))  // the personal dictionary is trusted as it is
    }

    @Test
    fun homophones() {
        // exact sound-alikes vs near ones (those need far more evidence to be swapped)
        assertTrue(org.futo.voiceinput.shared.context.Homophones.isLoaded)
        assertTrue(org.futo.voiceinput.shared.context.Homophones.same("meat", "Meet"))
        assertTrue(org.futo.voiceinput.shared.context.Homophones.same("their", "there"))
        assertTrue(org.futo.voiceinput.shared.context.Homophones.same("too", "to"))
        assertFalse(org.futo.voiceinput.shared.context.Homophones.same("pool", "poll"))
        assertFalse(org.futo.voiceinput.shared.context.Homophones.same("noise", "news"))
        assertFalse(org.futo.voiceinput.shared.context.Homophones.same("kite", "cat"))
        // near sound-alikes a voice model can confuse, and those it can't
        assertTrue(org.futo.voiceinput.shared.context.Homophones.confusable("bulls", "bowls"))
        assertTrue(org.futo.voiceinput.shared.context.Homophones.confusable("Pool", "poll"))
        assertFalse(org.futo.voiceinput.shared.context.Homophones.confusable("fan", "phone"))
        assertFalse(org.futo.voiceinput.shared.context.Homophones.confusable("kite", "cat"))
    }

    @Test
    fun namesOnlyReplaceMisheardNames() {
        val names = PersonalNames.from(PersonalDictionary.of(words = listOf("kubectl"), learned = listOf("Sarah", "Milke")), lexV2)
        // a compound, or a lowercase word the voice model wrote, is never a misheard name
        assertEquals(emptyList<String>(), names.soundAlike(listOf("x-ray")))
        assertEquals(emptyList<String>(), names.soundAlike(listOf("xray")))
        // nor a common word, even capitalized at a sentence start
        assertEquals(emptyList<String>(), names.soundAlike(listOf("Milk")))
        assertEquals(emptyList<String>(), names.soundAlike(listOf("milk")))
        // a capitalized word the lexicon doesn't know may be one
        assertEquals(listOf("Sarah"), names.soundAlike(listOf("Sara")))
        val policy = TranscriptGuard.Policy(names = names, caseChanges = true, strictCase = true)
        assertEquals("He called himself x-ray.", TranscriptGuard.guard("He called himself x-ray.", "He called himself Sarah.", policy).text)
    }

    // ------------------------------------------------------------------------------------
    // Fillers, repeats, self-corrections, commands (candidate rules)

    private fun groups(text: String, kind: Kind, segments: List<String> = emptyList()): List<String> {
        val w = SpokenEdits.Words(text)
        val segStarts = SpokenEdits.segmentStarts(text, segments)
        val gs = when (kind) {
            Kind.FILLER, Kind.REPEAT -> SpokenEdits.fillerGroups(w).filter { it.kind == kind }
            Kind.SELF_CORRECTION -> SpokenEdits.selfCorrectionGroups(w, segStarts)
            else -> SpokenEdits.commandGroups(w, segStarts, lexV2, PersonalNames.EMPTY)
        }
        return gs.flatMap { g -> g.alternatives.map { a -> text.substring(0, a.start) + a.replacement + text.substring(a.end) } }
    }

    @Test
    fun fillerCandidates() {
        assertEquals(listOf("I think we should go."), groups("Um, I think we should go.", Kind.FILLER).map { it.trimStart() }.map { it })
        assertEquals(listOf("I was, thinking about it.", "I was thinking about it."), groups("I was, uh, thinking about it.", Kind.FILLER))
        assertEquals(emptyList<String>(), groups("We went to the ER last night.", Kind.FILLER))
        assertEquals(emptyList<String>(), groups("Uh-oh, I forgot my keys.", Kind.FILLER))
        assertEquals(listOf("I think it's fine."), groups("I I think it's fine.", Kind.REPEAT))
        assertEquals(listOf("I think we're lost."), groups("I think I think we're lost.", Kind.REPEAT))
        assertEquals(emptyList<String>(), groups("I knew that that was wrong.", Kind.REPEAT))
        assertEquals(emptyList<String>(), groups("He had had enough.", Kind.REPEAT))
        assertEquals(emptyList<String>(), groups("It's very very cold.", Kind.REPEAT))
        assertEquals(emptyList<String>(), groups("I'm done. Done with it.", Kind.REPEAT))
        // hesitation sounds are certain, interjections ("Ah, I see") are left to the LM
        fun certain(s: String) = SpokenEdits.fillerGroups(SpokenEdits.Words(s)).filter { it.kind == Kind.FILLER }.map { it.certain }
        assertEquals(listOf(true), certain("Hmm, let me think about it."))
        assertEquals(listOf(true), certain("I was, um, uh, thinking."))
        assertEquals(listOf(false), certain("I think, eh, we should go."))
    }

    @Test
    fun irregularVerbFormsAreNotSoundAlikes() {
        assertTrue(TranscriptGuard.isTenseChange("took", "take"))
        assertTrue(TranscriptGuard.isTenseChange("Get", "got"))
        assertFalse(TranscriptGuard.isTenseChange("get", "get"))
        assertFalse(TranscriptGuard.isTenseChange("knew", "new"))
        assertFalse(TranscriptGuard.soundsAlike("took", "take").first)
        assertTrue(NumberWords.isSpokenNumber("nine"))
        assertTrue(NumberWords.isSpokenNumber("twenty-five"))
        assertFalse(NumberWords.isSpokenNumber("noon"))
        assertFalse(NumberWords.isSpokenNumber("oh"))
    }

    @Test
    fun selfCorrectionCandidates() {
        // the repair restarts with the reparandum's first word (that length only)
        assertEquals(listOf("I'll take the blue one."), groups("I'll take the red one, sorry, the blue one.", Kind.SELF_CORRECTION))
        // otherwise at most as long as the repair
        assertEquals(
            listOf("What is in the toaster air fryer?", "What is in the air fryer?"),
            groups("What is in the toaster oven, I mean, air fryer?", Kind.SELF_CORRECTION)
        )
        // a reparandum starting with "on" needs a repair that does too
        assertEquals(listOf("The meeting is on Tuesday."), groups("The meeting is on Monday, I mean, Tuesday.", Kind.SELF_CORRECTION))
        assertEquals(
            listOf("Let's meet on Wednesday at noon.", "Let's Wednesday at noon."),
            groups("Let's meet on Tuesday, no wait, Wednesday at noon.", Kind.SELF_CORRECTION)
        )
        // repairs that mirror the reparandum need less of the LM's approval
        fun parallel(s: String) = SpokenEdits.selfCorrectionGroups(SpokenEdits.Words(s), emptySet()).flatMap { g -> g.alternatives.map { it.parallel } }
        assertEquals(listOf(true), parallel("Text Mom at five. I mean, at six."))
        assertEquals(listOf(true), parallel("Text Sam, sorry, Alex about it."))
        assertEquals(listOf(true), parallel("The party starts at eight, actually, nine."))
        assertEquals(listOf(false), parallel("We need eggs, I mean, milk from the store.").take(1))
        for (s in listOf(
            "I mean it, you did a great job.", "What I mean is that we need more time.", "Actually, I think it's fine.",
            "Sorry I'm late.", "I'm sorry for your loss.", "I'd rather stay home.", "It was actually pretty good.",
            "I'm so sorry, I didn't see you.", "No wait, I have an idea.", "The movie was good, actually.",
            "We could stay, or rather than driving, take the train.",
            // a cue that opens the dictation corrects an earlier message, so nothing here to replace
            "No, I mean the blue folder.", "Sorry, I meant Thursday.", "Oh, no wait, the other door.",
            "Um, I mean the small box.", "Scratch that, the bus leaves at noon.",
        )) assertEquals(s, emptyList<String>(), groups(s, Kind.SELF_CORRECTION))
    }

    @Test
    fun commandCandidates() {
        assertEquals(
            listOf("Hi John.\nHow are you?"),
            groups("Hi John. New line. How are you?", Kind.COMMAND, listOf("Hi John.", "New line.", "How are you?"))
        )
        assertEquals(listOf("See you tomorrow."), groups("See you tomorrow period", Kind.COMMAND, listOf("See you tomorrow period")))
        assertEquals(listOf("Hi Mom, how are you"), groups("Hi Mom comma How are you", Kind.COMMAND, listOf("Hi Mom comma", "How are you")))
        assertEquals(listOf("I'll be there at six."), groups("I'll be there at five. Scratch that. I'll be there at six.", Kind.SCRATCH))
        for (s in listOf(
            "Put a comma here.", "The period of time was too long.", "That was a long period.", "We need a new line of products.",
            "Can you scratch that itch?", "Delete that file please.", "I sent you a photo. Delete that.", "You're not going, period.",
            "My comma key is broken.", "Scratch that off the list.",
        )) assertEquals(s, emptyList<String>(), groups(s, Kind.COMMAND, listOf(s)))
    }

    @Test
    fun segmentStartsFollowWords() {
        val text = "Hi John. New line. How are you?"
        assertEquals(setOf(2, 4), SpokenEdits.segmentStarts(text, listOf("Hi John", "new line", "how are you")))
        assertEquals(emptySet<Int>(), SpokenEdits.segmentStarts(text, listOf(text)))
    }

    // ------------------------------------------------------------------------------------
    // Guard checks each option's edits, and only those

    private fun check(text: String, from: String, to: String, kind: Kind): Boolean {
        val a = text.indexOf(from)
        return TranscriptGuard.checkEdit(text, SpanEdit(a, a + from.length, to, kind)).accepted
    }

    @Test
    fun guardChecksSpanEdits() {
        assertTrue(check("So, um, what time is it?", "um, ", "", Kind.FILLER))
        assertFalse(check("So, um, what time is it?", "um, what ", "", Kind.FILLER))  // deletes a word
        assertFalse(check("We went to the ER.", "ER", "", Kind.FILLER))
        assertTrue(check("Pass the the salt.", "the ", "", Kind.REPEAT))
        assertFalse(check("Pass the salt.", "the ", "", Kind.REPEAT))  // not repeated
        assertFalse(check("I knew that that was it.", "that ", "", Kind.REPEAT))
        assertTrue(check("In the toaster oven, I mean, air fryer.", "toaster oven, I mean, ", "", Kind.SELF_CORRECTION))
        assertFalse(check("In the toaster oven, I mean, air fryer.", "the toaster oven, ", "", Kind.SELF_CORRECTION))  // no marker
        assertFalse(check("It was big. Really, I mean, huge.", "big. Really, I mean, ", "", Kind.SELF_CORRECTION))
        assertTrue(check("I'll be there at five. Scratch that. Six.", "I'll be there at five. Scratch that. ", "", Kind.SCRATCH))
        assertFalse(check("One. Two. Scratch that. Six.", "One. Two. Scratch that. ", "", Kind.SCRATCH))  // two sentences
        assertTrue(check("It was twenty five dollars.", "twenty five dollars", "$25", Kind.NUMBER))
        assertFalse(check("It was twenty five dollars.", "twenty five dollars", "$26", Kind.NUMBER))
        assertFalse(check("It was twenty five dollars.", "twenty five dollars", "25 bucks", Kind.NUMBER))
        assertTrue(check("Hi John. New line. How", ". New line. How", ".\nHow", Kind.COMMAND))
        assertTrue(check("See you period", " period", ".", Kind.COMMAND))
        assertFalse(check("See you period", " period", "!", Kind.COMMAND))
        assertFalse(check("Hi John new line How", " new line How", "\nWhat", Kind.COMMAND))  // changes a word
    }

    @Test
    fun guardPolicy() {
        val names = PersonalNames(listOf("Xiomara"))
        val strict = TranscriptGuard.Policy(
            boundary = { s, d -> lexV2.boundaryCandidates(s.joinToString(" ")).contains(d.joinToString(" ") { it.lowercase() }) },
            names = names, caseChanges = true, strictCase = true,
        )
        val noCase = TranscriptGuard.Policy(boundary = strict.boundary, strictCase = true)
        // listed boundary change passes, an unlisted one doesn't (the legacy guard took any respacing)
        assertEquals("Put it into the box.", TranscriptGuard.guard("Put it in to the box.", "Put it into the box.", strict).text)
        assertEquals("I set up camp.", TranscriptGuard.guard("I set up camp.", "I setup camp.", strict).text)
        assertEquals("I setup camp.", TranscriptGuard.guard("I set up camp.", "I setup camp.").text)
        // names
        assertEquals("Tell Xiomara hi.", TranscriptGuard.guard("Tell see ya mara hi.", "Tell Xiomara hi.", strict).text)
        assertEquals("Tell see ya mara hi.", TranscriptGuard.guard("Tell see ya mara hi.", "Tell Xiomara hi.", noCase).text)
        // case-only changes away from sentence starts happen only with the option, and never for "I"
        assertEquals("In the toaster oven.", TranscriptGuard.guard("In the Toaster oven.", "In the toaster oven.", strict).text)
        assertEquals("In the Toaster oven.", TranscriptGuard.guard("In the Toaster oven.", "In the toaster oven.", noCase).text)
        assertEquals("So I think.", TranscriptGuard.guard("So I think.", "So i think.", strict).text)
        // sentence-start capitals follow punctuation as before
        assertEquals("Right? And bowls.", TranscriptGuard.guard("Right, and bowls.", "Right? And bowls.", noCase).text)
        // line breaks survive a punctuation change
        assertEquals("Hi John?\nHow are you?", TranscriptGuard.guard("Hi John.\nHow are you.", "Hi John?\nHow are you?", noCase).text)
    }

    @Test
    fun optionsParsing() {
        assertEquals(SmartCorrectionOptions(), SmartCorrectionOptions.parse("default"))
        assertEquals(SmartCorrectionOptions.ALL, SmartCorrectionOptions.parse("all"))
        val o = SmartCorrectionOptions.parse("soundalike, questions")
        assertEquals(SmartCorrectionOptions.LEGACY, o)
        assertEquals(SmartCorrectionOptions(numbers = true), SmartCorrectionOptions.parse("default,numbers"))
        assertFalse(SmartCorrectionOptions.NONE.any)
        assertEquals(listOf("A"), PersonalDictionary.of(listOf("A")).words().toList())
    }
}
