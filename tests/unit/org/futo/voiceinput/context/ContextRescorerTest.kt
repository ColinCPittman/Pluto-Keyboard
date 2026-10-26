package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.ContextLm
import org.futo.voiceinput.shared.context.ContextRescoreConfig
import org.futo.voiceinput.shared.context.ContextRescorer
import org.futo.voiceinput.shared.context.SmartCorrectionOptions
import org.futo.voiceinput.shared.context.SoundAlikeLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word-level fake LM where tokens are " word" / punctuation pieces, and the log-prob of a token
 * depends on the previous word only (bigram table), so the rescorer's decisions are predictable.
 * Honors the ContextLm contract (base reuse, firstReturned, variant starts).
 */
private class FakeLm(
    private val bigrams: Map<Pair<String, String>, Float>,
    /** Sentences starting with these words are questions. */
    private val questionStarters: Set<String> = setOf("did", "can", "is"),
) : ContextLm {
    private val ids = LinkedHashMap<String, Int>()
    private val pieces = ArrayList<String>()
    private var seq0 = IntArray(0)
    var evalBaseCalls = 0
    var variantCalls = 0

    private fun id(p: String) = ids.getOrPut(p) { pieces.add(p); pieces.size - 1 }

    override fun tokenize(text: String, addSpecial: Boolean): IntArray {
        if (addSpecial) return intArrayOf(id("<prompt>"))
        return Regex("\\s*[A-Za-z']+|\\s*[^\\sA-Za-z']").findAll(text).map { id(it.value) }.toList().toIntArray()
    }

    override fun pieceBytes(token: Int): ByteArray = pieces[token].toByteArray()

    override fun ensureCapacity(nTokens: Int) {}

    private fun word(t: Int) = pieces[t].trim().lowercase()

    /** log P(token at i | tokens before i) */
    private fun lp(tokens: IntArray, i: Int, token: Int): Float {
        val prev = if (i > 0) word(tokens[i - 1]) else ""
        val w = word(token)
        bigrams[prev to w]?.let { return it }
        if (w == "?" || w == "." || w == ",") {
            // find the first word of the current sentence
            var j = i - 1
            while (j > 0 && word(tokens[j - 1]) !in setOf(".", "?", "!", "<prompt>")) j--
            val question = word(tokens[j]) in questionStarters
            return when (w) {
                "?" -> if (question) -0.5f else -4f
                "." -> if (question) -3f else -0.5f
                else -> -2f
            }
        }
        return -1f
    }

    override fun evalBase(tokens: IntArray, keep: Int, needFrom: Int, wantOff: IntArray, wantIds: IntArray): ContextLm.BaseEval {
        evalBaseCalls++
        var lcp = 0
        while (lcp < seq0.size && lcp < tokens.size && seq0[lcp] == tokens[lcp]) lcp++
        val k = minOf(keep, lcp, tokens.size - 1)
        val first = maxOf(k + 1, needFrom)
        val out = ArrayList<Float>()
        for (i in first until tokens.size) {
            out.add(lp(tokens, i, tokens[i]))
            for (q in wantOff[i] until wantOff[i + 1]) out.add(lp(tokens, i, wantIds[q]))
        }
        seq0 = tokens
        return ContextLm.BaseEval(first, out.toFloatArray())
    }

    override fun evalVariants(variants: List<IntArray>, starts: IntArray): List<FloatArray> {
        variantCalls++
        return variants.mapIndexed { v, t ->
            for (i in 0 until starts[v] - 1) check(t[i] == seq0[i]) { "variant does not share the base prefix" }
            FloatArray(t.size - starts[v]) { lp(t, starts[v] + it, t[starts[v] + it]) }
        }
    }

    override fun close() {}
}

class ContextRescorerTest {
    private val lexicon = SoundAlikeLexicon.fromBytes(
        SoundAlikeLexicon.build(
            mapOf(
                "bulls" to listOf("balls", "bowls", "bills"),
                "meat" to listOf("meet", "met"),
                "flower" to listOf("flour"),
                "soup" to listOf("soap"),
                "can't" to listOf("can"),
            )
        )
    )

    private val bigrams = mapOf(
        ("need" to "bulls") to -9f, ("need" to "bowls") to -2f, ("need" to "balls") to -4f, ("need" to "bills") to -3f,
        ("bowls" to "and") to -0.5f, ("bulls" to "and") to -3f,
        ("let's" to "meat") to -8f, ("let's" to "meet") to -1f, ("let's" to "met") to -9f,
        ("of" to "flower") to -6f, ("of" to "flour") to -5f,  // too small a gain, so kept
        ("the" to "soup") to -2f, ("the" to "soap") to -1.5f,  // not surprising, so never checked
    )

    private fun rescorer(lm: ContextLm = FakeLm(bigrams)) = ContextRescorer(lm, lexicon, ContextRescoreConfig())

    @Test
    fun lexiconLookups() {
        assertEquals(listOf("balls", "bowls", "bills"), lexicon.candidates("Bulls"))
        assertEquals(listOf("can"), lexicon.candidates("can’t"))
        assertEquals(emptyList<String>(), lexicon.candidates("unknown"))
        assertEquals(emptyList<String>(), lexicon.candidates("is"))  // function words never change
        assertEquals(5, lexicon.size)
    }

    @Test
    fun fixesSurprisingSoundAlike() {
        val r = rescorer().correct("We need bulls and spoons for the soup.")
        assertEquals("We need bowls and spoons for the soup.", r.text)
        assertEquals(listOf("word"), r.edits.map { it.kind })
        assertEquals("bowls", r.edits[0].replacement)
        assertTrue(r.edits[0].delta > 2.0)
    }

    @Test
    fun keepsCaseOfReplacedWord() {
        assertEquals("Let's meet at noon.", rescorer().correct("Let's meat at noon.").text)
    }

    @Test
    fun smallGainIsNotEnough() {
        val r = rescorer().correct("Two cups of flower please.")
        assertEquals("Two cups of flower please.", r.text)
        assertEquals(0, r.edits.size)
    }

    @Test
    fun questionMarks() {
        assertEquals("Did you feed the cat?", rescorer().correct("Did you feed the cat.").text)
        assertEquals("You fed the cat.", rescorer().correct("You fed the cat.").text)
        // a question mark the ASR produced is never removed
        assertEquals("You fed the cat?", rescorer().correct("You fed the cat?").text)
    }

    @Test
    fun controlsUnchangedAndOnlyTwoPasses() {
        val lm = FakeLm(bigrams)
        val r = rescorer(lm).correct("I put the soup on the stove.")
        assertEquals("I put the soup on the stove.", r.text)
        assertEquals(1, lm.evalBaseCalls)  // nothing changed, so no second pass
        assertEquals(0, lm.variantCalls)
    }

    @Test
    fun chunksLongTranscripts() {
        val text = "We need bulls and spoons. " + "You fed the cat. ".repeat(10) + "Let's meat at noon."
        val r = ContextRescorer(FakeLm(bigrams), lexicon, ContextRescoreConfig(maxChars = 60)).correctAll(text)
        assertEquals("We need bowls and spoons. " + "You fed the cat. ".repeat(10) + "Let's meet at noon.", r)
        val chunks = ContextRescorer.splitChunks(text, 60)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
    }

    @Test
    fun optionsOffMeansNoWork() {
        val lm = FakeLm(bigrams)
        val r = ContextRescorer(lm, lexicon, ContextRescoreConfig(), SmartCorrectionOptions.NONE).correct("We need bulls and spoons.")
        assertEquals("We need bulls and spoons.", r.text)
        assertEquals(0, lm.evalBaseCalls)
    }

    @Test
    fun defaultOptionsMatchLegacyWithoutNewData() {
        // a version 1 lexicon and no personal words, so the new default options change nothing
        for (t in listOf("We need bulls and spoons for the soup.", "Did you feed the cat.", "Let's meat at noon.", "I put the soup on the stove.")) {
            val legacy = ContextRescorer(FakeLm(bigrams), lexicon, ContextRescoreConfig(), SmartCorrectionOptions.LEGACY).correct(t).text
            val now = ContextRescorer(FakeLm(bigrams), lexicon, ContextRescoreConfig(), SmartCorrectionOptions()).correct(t).text
            assertEquals(t, legacy, now)
        }
    }

    @Test
    fun questionsOptionOff() {
        val r = ContextRescorer(FakeLm(bigrams), lexicon, ContextRescoreConfig(), SmartCorrectionOptions(questions = false))
        assertEquals("Did you feed the cat.", r.correct("Did you feed the cat.").text)
    }

    @Test
    fun fillerRemovedWhenTheLmPrefersIt() {
        val lm = FakeLm(bigrams + mapOf(("so" to "um") to -9f, ("um" to ",") to -1f, ("so" to ",") to -1f))
        val r = ContextRescorer(lm, lexicon, ContextRescoreConfig(), SmartCorrectionOptions.NONE.copy(fillers = true))
        assertTrue(r.correct("So, um, I left.").text in setOf("So, I left.", "So I left."))
        // the option off, so unchanged
        assertEquals("So, um, I left.", ContextRescorer(FakeLm(bigrams), lexicon).correct("So, um, I left.").text)
    }

    @Test
    fun configParsing() {
        val c = ContextRescoreConfig.parse("""{"word_margin": 3.5, "threads": 4, "tag_words": ["Right"], "unknown": 1}""")
        assertEquals(3.5, c.wordMargin, 0.0)
        assertEquals(4, c.threads)
        assertEquals(setOf("right"), c.tagWords)
        assertEquals(-4.0, c.surprise, 0.0)
        assertEquals("Transcript of a casual voice message:\n", c.prompt)
    }

    @Test
    fun sentenceBreakVetoJoinsOnlyBreaksTheAsrDidNotMake() {
        val lex = SoundAlikeLexicon.fromBytes(SoundAlikeLexicon.buildV2(
            mapOf("bulls" to listOf("bowls")), lowercase = setOf("that", "the", "is", "dog", "barked")))
        val lm = { FakeLm(bigrams + mapOf(("manner" to ".") to -8f, ("manner" to "?") to -8f, ("manner" to "that") to -1f)) }
        val text = "She spoke in a manner. That is easier to follow."
        val r = ContextRescorer(lm(), lex)
        // no pause in the utterance, so the punctuation stage made the break
        assertEquals("She spoke in a manner that is easier to follow.",
            r.correct(text, segments = listOf("she spoke in a manner that is easier to follow")).text)
        // with a pause at the break, or the break already in the ASR's text, it is kept
        assertEquals(text, ContextRescorer(lm(), lex).correct(text, segments = listOf("she spoke in a manner", "that is easier to follow")).text)
        assertEquals(text, ContextRescorer(lm(), lex).correct(text, segments = listOf("She spoke in a manner. That is easier to follow.")).text)
        // unknown segments, so nothing is vetoed
        assertEquals(text, ContextRescorer(lm(), lex).correct(text).text)
        // a real break the LM likes is kept
        val keep = "I locked the door. The dog barked."
        assertEquals(keep, ContextRescorer(lm(), lex).correct(keep, segments = listOf("i locked the door the dog barked")).text)
    }
}
