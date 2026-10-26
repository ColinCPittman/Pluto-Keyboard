package org.futo.voiceinput.shared.context

/** One entry of the "Quoted speech" list, a phrase that introduces quoted speech, switched on or off. */
typealias QuoteTrigger = PhraseEntry

/**
 * The phrases that introduce quoted speech ("she said", "I was like", "he fired back with"),
 * the "Quoted speech" settings page (the built-in ones and your changes). A phrase counts after a
 * person ("I", "she", "Sam"), and "like" on its own counts after "I'm", "she's", "they're", ...
 */
object QuoteTriggers : PhraseList() {
    override val DEFAULTS: List<String> = listOf(
        "said", "says", "asked", "asks", "replied", "yelled", "screamed", "whispered", "texted",
        "goes", "went", "was like", "were like", "is like", "are like", "am like", "like",
        "told me", "told him", "told her", "told them", "fired back with", "snapped back with",
        "shot back with", "came back with",
        "was all", "were all", "is all", "goes like", "asked me", "asked us", "asked him", "asked her",
        "asked them", "told us", "told you", "said to me", "said to him", "said to her", "said to them",
        "said to us", "texted me", "texted us", "texted back", "messaged me", "wrote", "wrote back",
        "shouted", "added", "answered", "responded", "kept saying", "keeps saying", "muttered", "mumbled",
        "explained",
    )

    /**
     * Built-in phrases that are often followed by what someone did rather than said ("she was all
     * smiles!", "he answered the phone!", "he shouted at me!"), so only a question, or speech that
     * opens with "um", "oh", "hey," ..., is quoted after them, not anything ending in ? or !.
     */
    val SPEECH_ONLY: Set<List<String>> = listOf(
        "was all", "were all", "is all", "goes like", "asked me", "asked us", "asked him", "asked her",
        "asked them", "told us", "told you", "said to me", "said to him", "said to her", "said to them",
        "said to us", "texted me", "texted us", "texted back", "messaged me", "wrote", "wrote back",
        "shouted", "added", "answered", "responded", "kept saying", "keeps saying", "muttered", "mumbled",
        "explained",
    ).map { VoiceCommands.phraseWords(it) }.toSet()

    override val SAVED_DEFAULTS: List<List<String>> = listOf(
        listOf(
            "said", "says", "asked", "asks", "replied", "yelled", "screamed", "whispered", "texted",
            "goes", "went", "was like", "were like", "is like", "are like", "am like", "like",
            "told me", "told him", "told her", "told them", "fired back with", "snapped back with",
            "shot back with", "came back with",
        ),
    )

    /** Trigger phrases as normalized words, longest first. */
    fun triggers(phrases: List<String>): List<List<String>> = phrases
        .map { VoiceCommands.phraseWords(it) }
        .filter { it.size in 1..VoiceCommands.MAX_WORDS }
        .distinct()
        .sortedByDescending { it.size }
}
