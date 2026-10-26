package org.futo.voiceinput.shared.context

/** One entry of the "Filler words" list, a word or short phrase to remove, switched on or off. */
typealias FillerWord = PhraseEntry

/** The filler words (the "Filler words" settings page), meaning the built-in ones and your changes. */
object FillerWords : PhraseList() {
    /** Hesitation sounds and interjections, which are the first built-in list and what other rules count as fillers. */
    val SOUNDS: List<String> = listOf("um", "uh", "er", "erm", "hmm", "umm", "uhm", "uhh", "hm", "ah", "eh")

    override val DEFAULTS: List<String> = SOUNDS + listOf(
        "ummm", "uhhh", "hmmm",
        "like", "kind of", "sort of", "kinda", "sorta", "basically", "literally", "you see",
        "so yeah",
    )

    override val SAVED_DEFAULTS: List<List<String>> = listOf(SOUNDS)

    /**
     * Built-in fillers that are also interjections ("Ah, I see."), removed only inside a sentence.
     * Fillers the user adds are treated the same way.
     */
    private val ANYWHERE = setOf("um", "uh", "er", "erm", "hmm", "umm", "uhm", "uhh", "hm", "ummm", "uhhh", "hmmm")

    /**
     * Fillers that are ordinary words too ("I like it", "what kind of car", "you know where it
     * is"), removed only inside a sentence with a pause on both sides ("it was, like, huge").
     * "you know" is not built in (it is often meant), but added by you it is treated the same way.
     */
    private val BETWEEN_PAUSES = setOf(
        "you know", "like", "kind of", "sort of", "kinda", "sorta", "basically", "literally", "you see",
    )

    /** Fillers that close a sentence after a pause ("we went home, so yeah."), or are one on their own. */
    private val SENTENCE_END = setOf("so yeah")

    /**
     * A filler as the rules use it, holding its words and whether it may be removed at a sentence start or
     * end ([anywhere]), only between pauses ([betweenPauses]), or only at a sentence end ([atEnd]).
     */
    class Filler(val words: List<String>, val anywhere: Boolean, val betweenPauses: Boolean = false, val atEnd: Boolean = false)

    /** Fillers for [phrases] (the switched-on entries), longest first. */
    fun fillers(phrases: List<String>): List<Filler> = phrases
        .map { VoiceCommands.phraseWords(it) }
        .filter { it.size in 1..3 }
        .distinct()
        .map {
            val key = it.joinToString(" ")
            Filler(it, it.size == 1 && it[0] in ANYWHERE, key in BETWEEN_PAUSES, key in SENTENCE_END)
        }
        .sortedByDescending { it.words.size }

    /** Whether a filler phrase may contain [phrase] (1 to 3 words). */
    override fun isValidPhrase(phrase: String): Boolean = VoiceCommands.phraseWords(phrase).size in 1..3
}
