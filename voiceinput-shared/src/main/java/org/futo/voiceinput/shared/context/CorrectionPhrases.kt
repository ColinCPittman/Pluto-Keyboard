package org.futo.voiceinput.shared.context

/**
 * A phrase that marks a self-correction ("the toaster oven, I mean, air fryer"). The words
 * before it are replaced by the words after it.
 *
 * [needsPause] means only after a pause or punctuation ("I mean" vs "what I mean is").
 * [atSentenceStart] means it may also start a new sentence ("... toaster oven? I mean, air fryer?").
 * [numbersOnly] means only between two numbers ("a table for four, make it five" is one, "make it quick" is not).
 */
class CorrectionMarker(
    val words: List<String>, val needsPause: Boolean, val atSentenceStart: Boolean, val numbersOnly: Boolean = false,
)

/** One entry of the "Self-corrections" list, a phrase switched on or off. */
typealias CorrectionPhrase = PhraseEntry

/** The self-correction phrases (the "Self-corrections" settings page), meaning the built-in ones and your changes. */
object CorrectionPhrases : PhraseList() {
    override val DEFAULTS: List<String> = listOf(
        "sorry, I mean", "no, I mean", "or I mean", "I mean", "I meant", "I meant to say", "I mean to say",
        "no wait", "wait no", "or wait", "oh wait", "no sorry", "or rather", "no actually", "or actually",
        "make that", "correction", "scratch that", "sorry", "actually", "rather",
        "sorry, I meant", "no, I meant", "or I meant", "let me rephrase", "let me rephrase that",
        "or should I say", "or I should say", "actually no", "well actually", "sorry no", "no no", "nope",
        "my bad", "my mistake", "make it", "hold on", "I'm sorry", "excuse me",
    )

    override val SAVED_DEFAULTS: List<List<String>> = listOf(
        listOf(
            "sorry, I mean", "no, I mean", "or I mean", "I mean", "I meant", "no wait", "wait no", "no sorry",
            "or rather", "scratch that", "sorry", "actually", "rather",
        ),
        listOf(
            "sorry, I mean", "no, I mean", "or I mean", "I mean", "I meant", "I meant to say", "I mean to say",
            "no wait", "wait no", "or wait", "oh wait", "no sorry", "or rather", "no actually", "or actually",
            "make that", "correction", "scratch that", "sorry", "actually", "rather",
        ),
    )

    /** Built-in phrases that are common enough elsewhere to need a pause before them. */
    private val NEEDS_PAUSE = setOf(
        "i mean", "i meant", "scratch that", "sorry", "actually", "rather", "make that", "correction",
        "let me rephrase", "let me rephrase that", "or should i say", "or i should say", "actually no",
        "well actually", "sorry no", "no no", "nope", "my bad", "my mistake", "make it",
        "hold on", "i'm sorry", "excuse me",
    )

    /**
     * Built-in phrases that also work at the start of a new sentence. "sorry", "actually",
     * "rather" and "correction" alone start ordinary sentences too often.
     */
    private val SENTENCE_START = setOf(
        "i mean", "i meant", "i meant to say", "i mean to say", "no i mean", "sorry i mean", "or i mean",
        "no wait", "wait no", "or wait", "oh wait", "no sorry", "or rather", "no actually", "or actually",
        "make that", "sorry i meant", "no i meant", "or i meant", "let me rephrase", "let me rephrase that",
        "or should i say", "or i should say",
    )

    /** Built-in phrases that are ordinary words between anything but numbers ("make it quick"). */
    private val NUMBERS_ONLY = setOf("make it")

    /**
     * Markers for [phrases], longest first. The user's own phrases always need a pause before
     * them, and those of two or more words may also start a sentence.
     */
    fun markers(phrases: List<String>): List<CorrectionMarker> = phrases
        .map { VoiceCommands.phraseWords(it) }
        .filter { it.size in 1..VoiceCommands.MAX_WORDS }
        .distinct()
        .map { w ->
            val key = w.joinToString(" ")
            val builtIn = isBuiltIn(key)
            CorrectionMarker(
                w,
                needsPause = if (builtIn) key in NEEDS_PAUSE else true,
                atSentenceStart = if (builtIn) key in SENTENCE_START else w.size >= 2,
                numbersOnly = builtIn && key in NUMBERS_ONLY,
            )
        }
        .sortedByDescending { it.words.size }
}
