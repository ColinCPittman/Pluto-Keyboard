package org.futo.voiceinput.context

import org.futo.inputmethod.latin.uix.TypingFix
import org.futo.inputmethod.latin.uix.TypingRevertMemory
import org.futo.inputmethod.latin.uix.shiftFixes
import org.futo.voiceinput.shared.context.ContextLm
import org.futo.voiceinput.shared.context.SmartCorrectionSensitivity
import org.futo.voiceinput.shared.context.SoundAlikeLexicon
import org.futo.voiceinput.shared.context.TypingConfusions
import org.futo.voiceinput.shared.context.TypingEdits
import org.futo.voiceinput.shared.context.TypingCorrectionConfig
import org.futo.voiceinput.shared.context.TypingCorrector
import org.futo.voiceinput.shared.context.OwnTypingPair
import org.futo.voiceinput.shared.context.TypingPairEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word-level fake LM where log P(word) depends on the previous word only (bigram table, else -3). */
private class BigramLm(private val bigrams: Map<Pair<String, String>, Float>) : ContextLm {
    private val ids = LinkedHashMap<String, Int>()
    private val pieces = ArrayList<String>()
    private var seq0 = IntArray(0)

    private fun id(p: String) = ids.getOrPut(p) { pieces.add(p); pieces.size - 1 }

    override fun tokenize(text: String, addSpecial: Boolean): IntArray {
        if (addSpecial) return intArrayOf(id("<prompt>"))
        return Regex("\\s*[A-Za-z']+|\\s*[^\\sA-Za-z']").findAll(text).map { id(it.value) }.toList().toIntArray()
    }

    override fun pieceBytes(token: Int): ByteArray = pieces[token].toByteArray()
    override fun ensureCapacity(nTokens: Int) {}

    private fun word(t: Int) = pieces[t].trim().lowercase()
    private fun lp(tokens: IntArray, i: Int, token: Int): Float =
        bigrams[(if (i > 0) word(tokens[i - 1]) else "") to word(token)] ?: -3f

    override fun evalBase(tokens: IntArray, keep: Int, needFrom: Int, wantOff: IntArray, wantIds: IntArray): ContextLm.BaseEval {
        var lcp = 0
        while (lcp < seq0.size && lcp < tokens.size && seq0[lcp] == tokens[lcp]) lcp++
        val first = maxOf(minOf(keep, lcp, tokens.size - 1) + 1, needFrom)
        val out = ArrayList<Float>()
        for (i in first until tokens.size) {
            out.add(lp(tokens, i, tokens[i]))
            for (q in wantOff[i] until wantOff[i + 1]) out.add(lp(tokens, i, wantIds[q]))
        }
        seq0 = tokens
        return ContextLm.BaseEval(first, out.toFloatArray())
    }

    override fun evalVariants(variants: List<IntArray>, starts: IntArray): List<FloatArray> =
        variants.mapIndexed { v, t -> FloatArray(t.size - starts[v]) { lp(t, starts[v] + it, t[starts[v] + it]) } }

    override fun close() {}
}

class TypingCorrectorTest {
    private val confusions = TypingConfusions.parse(sequenceOf("an and any", "or of for", "is it"))

    // "salt and pepper" is likely, "an pepper" is not, and "an apple" is likely, "and apple" is not
    private val lm = BigramLm(
        mapOf(
            ("salt" to "and") to -1f, ("and" to "pepper") to -1f, ("salt" to "any") to -6f, ("any" to "pepper") to -8f, ("salt" to "an") to -4f, ("an" to "pepper") to -12f,
            ("an" to "apple") to -1f, ("and" to "apple") to -10f, ("ate" to "an") to -1f, ("ate" to "and") to -6f,
            ("king" to "of") to -1f, ("of" to "the") to -1f, ("king" to "or") to -3f, ("or" to "the") to -9f,
        )
    )

    private fun corrector(caution: (String, String, String, String) -> Double = { _, _, _, _ -> 0.0 }) =
        TypingCorrector(lm, confusions, TypingCorrectionConfig(margin = 5.0, openingMargin = 4.0), caution = caution)

    @Test
    fun builtInListHasTheCommonSlips() {
        val list = TypingConfusions.builtIn
        assertTrue(list.size > 50)
        assertTrue("and" in list.candidates("an"))
        assertTrue("of" in list.candidates("or"))
        assertTrue("from" in list.candidates("form"))
        assertTrue("than" in list.candidates("then"))
        assertTrue("and" in list.candidates("An"))
        // a slip works both ways when both words are common
        assertTrue("trick" in list.candidates("truck"))
        assertTrue("truck" in list.candidates("trick"))
    }

    @Test
    fun fixesASlipTheNextWordGivesAway() {
        val text = "I bought some salt an pepper shakers."
        val fix = corrector().check(text).fix
        assertNotNull(fix)
        assertEquals("an", fix!!.original)
        assertEquals("and", fix.replacement)
        assertEquals(text.indexOf(" an ") + 1, fix.start)
        assertTrue(fix.tailGain > 0)
    }

    @Test
    fun fixesMistForMust() {
        val bigrams = mapOf(
            ("we" to "must") to -1f, ("must" to ".") to -1f, ("we" to "mist") to -9f, ("mist" to ".") to -9f,
        )
        val c = TypingCorrector(
            BigramLm(bigrams),
            TypingConfusions.builtIn,
            TypingCorrectionConfig(margin = 6.5),
        )
        assertTrue("must" in TypingConfusions.builtIn.candidates("mist"))
        val fix = c.check("I think we do what we mist.").fix
        assertNotNull(fix)
        assertEquals("must", fix!!.replacement)
    }

    @Test
    fun comparativeThenNeedsLessEvidence() {
        val bigrams = mapOf(("taller" to "than") to -3f, ("taller" to "then") to -6.5f, ("than" to "her") to -1f, ("then" to "her") to -3f)
        val conf = TypingConfusions.parse(sequenceOf("then than"))
        val text = "She is taller then her brother."
        // gain 3.5 + 2, which is under the plain 6.5 margin but enough after the comparative relief
        assertNull(TypingCorrector(BigramLm(bigrams), conf, TypingCorrectionConfig(margin = 6.5, comparativeRelief = 0.0)).check(text).fix)
        assertEquals("than", TypingCorrector(BigramLm(bigrams), conf, TypingCorrectionConfig(margin = 6.5)).check(text).fix?.replacement)
        // with a comma after it, "taller then, ..." is left alone
        assertNull(TypingCorrector(BigramLm(bigrams), conf, TypingCorrectionConfig(margin = 6.5)).check("She was taller then, her brother.").fix)
    }

    @Test
    fun yourOwnPairsNeedLessEvidence() {
        val bigrams = mapOf(("wouldn't" to "pout") to -6f, ("wouldn't" to "pour") to -8f, ("pout" to "like") to -2f, ("pour" to "like") to -4f)
        val text = "I wish you wouldn't pour like that."
        // gain 2 + 2 = 4, which is under the 6.5 margin as a built-in pair but enough as one of yours
        val asBuiltIn = TypingConfusions.parse(sequenceOf("pour pout"))
        assertNull(TypingCorrector(BigramLm(bigrams), asBuiltIn, TypingCorrectionConfig(margin = 6.5)).check(text).fix)
        val asYours = TypingPairEdits.merge(TypingConfusions(emptyMap()), TypingPairEdits(own = listOf(OwnTypingPair("pour", listOf("pout")))))
        assertEquals("pout", TypingCorrector(BigramLm(bigrams), asYours, TypingCorrectionConfig(margin = 6.5)).check(text).fix?.replacement)
        // but the swapped text must still be clearly more likely
        assertNull(TypingCorrector(BigramLm(bigrams), asYours, TypingCorrectionConfig(margin = 6.5, ownMinNeed = 5.0)).check(text).fix)
    }

    @Test
    fun keepsLiteralUses() {
        assertNull(corrector().check("I ate an apple today.").fix)
    }

    @Test
    fun onlyTheCheckedSentenceChanges() {
        val text = "Some salt an pepper. I ate an apple."
        val second = text.indexOf("I ate")
        assertNull(corrector().check(text, second, text.length).fix)
        assertNotNull(corrector().check(text, 0, second).fix)
    }

    @Test
    fun handCorrectedWordsAreSkipped() {
        val text = "Some salt an pepper shakers."
        val at = text.indexOf(" an ") + 1
        assertNull(corrector().check(text, skip = { it == at }).fix)
    }

    @Test
    fun revertedSwapsNeedMoreEvidence() {
        val text = "Some salt an pepper shakers."
        assertNull(corrector { a, b, _, _ -> if (a == "an" && b == "and") 20.0 else 0.0 }.check(text).fix)
    }

    @Test
    fun atMostOneFixPerSentenceTheBestOne() {
        val text = "He is king or the salt an pepper shakers."
        val fix = corrector().check(text).fix!!
        // or->of gains 2 + 8 = 10, an->and gains 3 + 11 = 14
        assertEquals("and", fix.replacement)
    }

    @Test
    fun earlyCheckNeedsWordsAfterTheSlip() {
        assertNull(corrector().check("Some salt an pepper", finished = false).fix)
        assertNotNull(corrector().check("Some salt an pepper shakers for", finished = false).fix)
    }

    @Test
    fun shortSentencesAreNotChecked() {
        assertNull(TypingCorrector(lm, confusions, TypingCorrectionConfig(margin = 5.0, minWords = 4)).check("salt an pepper.").fix)
    }

    @Test
    fun matchesCase() {
        assertEquals("And", TypingCorrector.matchCase("An", "and"))
        assertEquals("AND", TypingCorrector.matchCase("AN", "and"))
        assertEquals("and", TypingCorrector.matchCase("an", "and"))
    }

    @Test
    fun splitsSentences() {
        val text = "First one. Second one? \"Third!\" Last"
        val s = TypingCorrector.sentences(text).map { text.substring(it.first, it.last + 1).trim() }
        assertEquals(listOf("First one.", "Second one?", "\"Third!\"", "Last"), s)
    }
}

/** The edits for slips made while editing, each kind with a bigram LM that likes the fixed text. */
class TypingEditsTest {
    private val none = TypingConfusions(emptyMap())
    private val allOn = TypingCorrectionConfig(missingWords = true, leftoverWords = true, wordEndings = true, wordOrder = true, joinSplit = true)

    private fun check(bigrams: Map<Pair<String, String>, Float>, text: String, config: TypingCorrectionConfig = allOn, lexicon: SoundAlikeLexicon? = null) =
        TypingCorrector(BigramLm(bigrams), none, config, lexicon).check(text)

    private val doubled = mapOf(("going" to "to") to -1f, ("to" to "to") to -10f, ("to" to "call") to -1f)

    @Test
    fun nothingChangesWhenTheEditsAreOff() {
        val text = "I was going to to call you."
        assertNull(check(doubled, text, TypingCorrectionConfig()).fix)
        assertNotNull(check(doubled, text).fix)
        // the missed-typo path does not see a real word either
        assertNull(check(doubled, text, TypingCorrectionConfig(missedTypos = true)).fix)
    }

    @Test
    fun removesAnExactRepeat() {
        val text = "I was going to to call you."
        val fix = check(doubled, text, TypingCorrectionConfig(leftoverWords = true)).fix!!
        assertEquals("remove", fix.kind)
        assertEquals("to to", fix.original)
        assertEquals("to", fix.replacement)
        assertEquals(text.indexOf("to to"), fix.start)
        assertEquals(text.indexOf("to to") + 5, fix.end)
    }

    @Test
    fun keepsARepeatTheLmFindsFine() {
        // "had had" is likely enough, so dropping one gains less than the repeat margin
        val bigrams = mapOf(("she" to "had") to -1f, ("had" to "had") to -2f, ("had" to "enough") to -1f)
        assertNull(check(bigrams, "She had had enough of it.").fix)
    }

    @Test
    fun neverRemovesTheFirstWordOfASentence() {
        // dropping "Was" would make the text far more likely, but a sentence opener is never removed
        val bigrams = mapOf(("<prompt>" to "was") to -12f, ("<prompt>" to "it") to -0.5f, ("was" to "it") to -1f)
        val r = check(bigrams, "Was it fun at the park?", TypingCorrectionConfig(leftoverWords = true))
        assertNull(r.fix)
    }

    @Test
    fun aRemovalIsJudgedByTheWordsAfterIt() {
        // dropping the surprising "of" gains 10 in all, but "milk" gets no more likely, so it stays
        val bigrams = mapOf(("out" to "of") to -10f, ("of" to "milk") to -3f, ("out" to "milk") to -3f)
        val r = check(bigrams, "We ran out of milk again.", TypingCorrectionConfig(leftoverWords = true))
        assertNull(r.fix)
        assertTrue(r.trace.joinToString(), r.trace.any { it.startsWith("remove of milk->milk gain=10.00 tail=0.00") })
        // a leftover word makes the next one far more likely once it is gone, and it may be any word
        val leftover = mapOf(("got" to "the") to -1f, ("the" to "about") to -12f, ("got" to "about") to -2f)
        val fix = check(leftover, "She got the about ten dollars back.", TypingCorrectionConfig(leftoverWords = true)).fix!!
        assertEquals("the about", fix.original)
        assertEquals("about", fix.replacement)
        assertEquals(10.0, fix.tailGain, 1e-6)
    }

    @Test
    fun aRemovalWaitsWhereAWordMayAsWellBeMissing() {
        // "ran short milk": dropping "short" helps "milk" (11), but so does adding "of" (10), so no removal
        val bigrams = mapOf(("short" to "milk") to -12f, ("short" to "of") to -1f, ("of" to "milk") to -1f, ("ran" to "milk") to -1f)
        val config = TypingCorrectionConfig(missingWords = true, leftoverWords = true, editBudget = 40)
        val r = check(bigrams, "We ran short milk again.", config)
        assertEquals("of milk", r.fix?.replacement)
        assertTrue(r.trace.joinToString(), r.trace.any { it.startsWith("remove short milk->milk") && "ambiguous" in it })
        assertEquals("milk", check(bigrams, "We ran short milk again.", config.copy(ambiguityGain = 99.0, removeMargin = 5.0)).fix?.replacement)
    }

    @Test
    fun aCleanSentenceHasNoEditsToScore() {
        // no word is surprising, so no edit is tried (the one pass is all it costs)
        val r = check(emptyMap(), "We ran out of milk again.")
        assertNull(r.fix)
        assertTrue(r.trace.joinToString(), r.trace.any { it.startsWith("edits scored 0/0 of") })
    }

    @Test
    fun addsAMissingSmallWord() {
        val bigrams = mapOf(("out" to "milk") to -12f, ("out" to "of") to -1f, ("of" to "milk") to -1f)
        val text = "We ran out milk again."
        val fix = check(bigrams, text, TypingCorrectionConfig(missingWords = true)).fix!!
        assertEquals("add", fix.kind)
        assertEquals("milk", fix.original)
        assertEquals("of milk", fix.replacement)
        assertEquals(text.indexOf("milk"), fix.start)
    }

    @Test
    fun theBudgetLimitsWhatIsScoredButRepeatsAlwaysAre() {
        val bigrams = mapOf(("out" to "milk") to -12f, ("out" to "of") to -1f, ("of" to "milk") to -1f) + doubled
        val none = TypingCorrectionConfig(missingWords = true, leftoverWords = true, editBudget = 0)
        assertNull(check(bigrams, "We ran out milk again.", none).fix)
        assertEquals("to", check(bigrams, "I was going to to call you.", none).fix?.replacement)
    }

    @Test
    fun fixesAnIrregularFormWithoutALexicon() {
        val bigrams = mapOf(("we" to "was") to -12f, ("we" to "were") to -1f, ("were" to "supposed") to -1f, ("was" to "supposed") to -1f)
        val fix = check(bigrams, "Then we was supposed to go.", TypingCorrectionConfig(wordEndings = true)).fix!!
        assertEquals("ending", fix.kind)
        assertEquals("were", fix.replacement)
    }

    @Test
    fun endingsComeFromRealWords() {
        val lex = SoundAlikeLexicon.fromBytes(SoundAlikeLexicon.buildV2(emptyMap(), lowercase = setOf("cat", "cats", "dog")))
        val edits = TypingEdits(TypingCorrectionConfig(wordEndings = true), lex)
        assertEquals(listOf("cats"), edits.forms("cat"))
        assertEquals(listOf("cat"), edits.forms("cats"))
        // without a lexicon, only the irregular pairs
        assertEquals(emptyList<String>(), TypingEdits(TypingCorrectionConfig(), null).forms("cat"))
        assertEquals(listOf("your"), TypingEdits(TypingCorrectionConfig(), null).forms("you"))
        val bigrams = mapOf(("three" to "cat") to -12f, ("three" to "cats") to -1f, ("cats" to "and") to -1f, ("cat" to "and") to -1f)
        val fix = check(bigrams, "She has three cat and a dog.", TypingCorrectionConfig(wordEndings = true), lex).fix!!
        assertEquals("cats", fix.replacement)
    }

    @Test
    fun swapsTwoWords() {
        val bigrams = mapOf(
            ("rough" to "of") to -10f, ("of" to "sense") to -10f, ("rough" to "sense") to -1f, ("sense" to "of") to -1f, ("of" to "how") to -1f,
        )
        val text = "I have a rough of sense how long."
        val fix = check(bigrams, text, TypingCorrectionConfig(wordOrder = true)).fix!!
        assertEquals("order", fix.kind)
        assertEquals("of sense", fix.original)
        assertEquals("sense of", fix.replacement)
        assertEquals(text.indexOf("of sense"), fix.start)
    }

    @Test
    fun aSwapLosesToABetterSwapOfTheSameWordThatWasNotScoredAtFirst() {
        // "running" is one place off: moving it back ("running out") is not scored at first (its first
        // word is less likely there than the typed one), and moving it on ("of running") passes alone
        val bigrams = mapOf(
            ("are" to "out") to -1f, ("out" to "running") to -10f, ("running" to "of") to -10f, ("of" to "time") to -1f,
            ("are" to "running") to -1.5f, ("running" to "out") to -1f, ("out" to "of") to -1f,
            ("of" to "running") to -6f, ("running" to "time") to -1f,
        )
        val text = "We are out running of time today."
        val fix = check(bigrams, text, TypingCorrectionConfig(wordOrder = true)).fix!!
        assertEquals("order", fix.kind)
        assertEquals("out running", fix.original)
        assertEquals("running out", fix.replacement)
    }

    @Test
    fun addsAMissingApostrophe() {
        val bigrams = mapOf(("think" to "thats") to -10f, ("thats" to "the") to -10f, ("think" to "that's") to -1f, ("that's" to "the") to -1f)
        val fix = check(bigrams, "I think thats the plan.", TypingCorrectionConfig(joinSplit = true)).fix!!
        assertEquals("join", fix.kind)
        assertEquals("that's", fix.replacement)
        val its = mapOf(("<prompt>" to "its") to -10f, ("<prompt>" to "it's") to -1f, ("it's" to "going") to -1f, ("its" to "going") to -9f)
        // at the start of a sentence it needs the opening margin too, and gets it here
        assertEquals("It's", check(its, "Its going to rain.", TypingCorrectionConfig(joinSplit = true)).fix?.replacement)
    }

    @Test
    fun joinsAndSplitsWords() {
        val lex = SoundAlikeLexicon.fromBytes(SoundAlikeLexicon.buildV2(emptyMap(), lowercase = setOf("every", "thing", "everything", "up", "to")))
        val joined = mapOf(("<prompt>" to "every") to -3f, ("every" to "thing") to -12f, ("<prompt>" to "everything") to -2f, ("everything" to "is") to -1f, ("thing" to "is") to -3f)
        assertEquals("Everything", check(joined, "Every thing is ready now.", TypingCorrectionConfig(joinSplit = true), lex).fix?.replacement)
        val split = mapOf(("take" to "upto") to -12f, ("upto" to "three") to -12f, ("take" to "up") to -2f, ("up" to "to") to -1f, ("to" to "three") to -1f)
        assertEquals("up to", check(split, "It can take upto three days.", TypingCorrectionConfig(joinSplit = true), lex).fix?.replacement)
    }

    @Test
    fun anEditWaitsForWordsAfterItBeforeTheSentenceEnds() {
        val c = TypingCorrector(BigramLm(doubled), none, TypingCorrectionConfig(leftoverWords = true))
        assertNull(c.check("I was going to to call", finished = false).fix)
        assertNotNull(c.check("I was going to to call you about it", finished = false).fix)
    }

    @Test
    fun handCorrectedWordsAreNotEdited() {
        val text = "I was going to to call you."
        val at = text.indexOf("to to") + 3
        assertNull(TypingCorrector(BigramLm(doubled), none, TypingCorrectionConfig(leftoverWords = true)).check(text, skip = { it == at }).fix)
    }

    @Test
    fun aMissedTypoFixedOnlyByDroppingItsFirstLetterNeedsMore() {
        val lex = SoundAlikeLexicon.fromBytes(SoundAlikeLexicon.buildV2(emptyMap(), lowercase = setOf("haul", "rented", "move", "for", "the")))
        // "haul" gains 5 and costs 1 slip (score 3), which beats the plain need (2) but not the first-letter one
        val bigrams = mapOf(("a" to "haul") to -2f, ("a" to "uhaul") to -6f, ("haul" to "for") to -2f, ("uhaul" to "for") to -3f)
        val text = "We rented a uhaul for the move."
        val config = TypingCorrectionConfig(missedTypos = true, realWords = false)
        val r = TypingCorrector(BigramLm(bigrams), none, config, lex).check(text, unknown = { true })
        assertNull(r.trace.joinToString(), r.fix)
        assertEquals("haul", TypingCorrector(BigramLm(bigrams), none, config.copy(firstLetterNeed = 0.0), lex).check(text, unknown = { true }).fix?.replacement)
    }

    @Test
    fun sensitivityMovesTheEditMargins() {
        val c = TypingCorrectionConfig().withSensitivity(SmartCorrectionSensitivity.MAX)
        assertTrue(c.addMargin < TypingCorrectionConfig().addMargin)
        assertTrue(c.removeMargin < TypingCorrectionConfig().removeMargin)
    }
}

class TypingFixTest {
    private val fix = TypingFix(1, 20, "an", "and", before = "some new salt ", after = " pepper shakers")

    @Test
    fun matchesUnchangedText() {
        assertTrue(fix.matches("some new salt and pepper shakers and more"))
        // the cursor is right after the fix, so the text after it is cut short
        assertTrue(fix.matches("some new salt and pep"))
    }

    @Test
    fun editedTextIsStale() {
        assertFalse(fix.matches("some new salt an pepper shakers"))
        assertFalse(fix.matches("some old salt and pepper shakers"))
        assertFalse(fix.matches("some new salt and peppers"))
    }

    @Test
    fun laterFixesShift() {
        val other = fix.copy(id = 2, start = 40)
        val shifted = shiftFixes(listOf(fix, other), at = 20, delta = -1)
        assertEquals(20, shifted[0].start)
        assertEquals(39, shifted[1].start)
    }

    @Test
    fun revertMemoryIsPerSwapAndContext() {
        val store = HashMap<String, Int>()
        val memory = TypingRevertMemory({ store[it] ?: 0 }, { store.putAll(it) })
        assertEquals(0.0, memory.caution("an", "and", "salt", "pepper"), 0.0)
        memory.recordRevert("an", "and", "salt", "pepper")
        val here = memory.caution("an", "and", "salt", "pepper")
        val elsewhere = memory.caution("an", "and", "milk", "bread")
        assertEquals(TypingRevertMemory.PAIR_STEP + TypingRevertMemory.CONTEXT_STEP, here, 1e-9)
        assertEquals(TypingRevertMemory.PAIR_STEP, elsewhere, 1e-9)
        assertEquals(0.0, memory.caution("or", "of", "salt", "pepper"), 0.0)
        // no words are stored, only the swap and a hash of its neighbours
        assertTrue(store.keys.none { "salt" in it || "pepper" in it })
    }

    @Test
    fun missedFixesMakeASwapMoreReady() {
        val store = HashMap<String, Int>()
        val memory = TypingRevertMemory({ store[it] ?: 0 }, { store.putAll(it) })
        memory.recordMiss("pour", "pout", "you", "every")
        assertEquals(-(TypingRevertMemory.MISS_PAIR_STEP + TypingRevertMemory.MISS_CONTEXT_STEP), memory.caution("pour", "pout", "you", "every"), 1e-9)
        assertEquals(-TypingRevertMemory.MISS_PAIR_STEP, memory.caution("pour", "pout", "can", "me"), 1e-9)
        // limited
        repeat(10) { memory.recordMiss("pour", "pout", "you", "every") }
        assertEquals(-TypingRevertMemory.MAX_READY, memory.caution("pour", "pout", "you", "every"), 1e-9)
        // an undo cancels one missed fix (and the other way round)
        val s2 = HashMap<String, Int>()
        val m2 = TypingRevertMemory({ s2[it] ?: 0 }, { s2.putAll(it) })
        m2.recordMiss("pour", "pout", "you", "every")
        m2.recordRevert("pour", "pout", "you", "every")
        assertEquals(0.0, m2.caution("pour", "pout", "you", "every"), 1e-9)
        m2.recordRevert("pour", "pout", "you", "every")
        m2.recordMiss("pour", "pout", "you", "every")
        assertEquals(0.0, m2.caution("pour", "pout", "you", "every"), 1e-9)
    }

    @Test
    fun learningNeverGoesBelowTheFloor() {
        // "pout" barely more likely (gain 1), so even made as ready as possible, it needs the floor (1.5)
        val bigrams = mapOf(("you" to "pout") to -5f, ("you" to "pour") to -6f)
        val conf = TypingConfusions.parse(sequenceOf("pour pout"))
        val text = "I see you pour every day."
        val ready = TypingCorrector(BigramLm(bigrams), conf, TypingCorrectionConfig(margin = 6.5)) { _, _, _, _ -> -100.0 }
        val r = ready.check(text)
        assertNull(r.fix)
        assertTrue(r.trace.joinToString(), r.trace.any { "need=1.50" in it })
        // a clearer case (gain 3) is fixed once it is ready enough, not before
        val clearer = mapOf(("you" to "pout") to -3f, ("you" to "pour") to -6f)
        assertNull(TypingCorrector(BigramLm(clearer), conf, TypingCorrectionConfig(margin = 6.5)).check(text).fix)
        assertEquals("pout", TypingCorrector(BigramLm(clearer), conf, TypingCorrectionConfig(margin = 6.5)) { _, _, _, _ -> -TypingRevertMemory.MAX_READY }.check(text).fix?.replacement)
    }
}
