package org.futo.voiceinput.shared.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What a speech model does well on its own, so the stages after it (punctuation restoration,
 * Smart corrections) only do what the model needs. The user never sees these; they decide which
 * of the user's switched-on features actually run (see [SmartFeaturePolicy]).
 */
data class VoiceModelProfile(
    /** How good the model's own punctuation and sentence breaks are. */
    val punctuation: Quality,
    /** How good the model's own capitalization is. */
    val casing: Quality,
    /** The engine reports one segment per pause (spoken commands and quotes are found at pauses). */
    val pauseSegments: Boolean,
    /** Text appears while the user is still talking. */
    val streaming: Boolean,
    /** The model leaves out "um", "uh" and words said twice by accident by itself. */
    val dropsFillers: Boolean = false,
    /**
     * Whether the model mixes up words that sound alike ("bulls" for "bowls"), the mistakes Smart
     * corrections' sound-alike fixes catch. GOOD: those fixes found nothing to change in its text (it
     * still mishears other ways, which they don't fix), so they would only add a wait.
     */
    val wordAccuracy: Quality = Quality.WEAK,
) {
    enum class Quality { NONE, WEAK, GOOD }

    /** The model punctuates well enough that a punctuation model would only second-guess it. */
    val punctuatesItself: Boolean get() = punctuation == Quality.GOOD
    val capitalizesItself: Boolean get() = casing == Quality.GOOD
    /** The model doesn't mix up sound-alike words, so sound-alike fixes have nothing to do. */
    val hearsWordsItself: Boolean get() = wordAccuracy == Quality.GOOD

    /**
     * This profile with the overrides of a model package's optional `capabilities` object, as in
     * `"capabilities": {"punctuation": "good", "casing": "good", "pause_segments": true, "streaming": true, "drops_fillers": true, "word_accuracy": "good"}`.
     * Unknown keys and values are ignored.
     */
    fun withOverrides(capabilities: JsonObject?): VoiceModelProfile {
        if (capabilities == null) return this
        fun quality(key: String): Quality? = (capabilities[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { v -> Quality.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } }
        fun flag(key: String): Boolean? = (capabilities[key] as? JsonPrimitive)?.booleanOrNull
        return copy(
            punctuation = quality("punctuation") ?: punctuation,
            casing = quality("casing") ?: casing,
            pauseSegments = flag("pause_segments") ?: pauseSegments,
            streaming = flag("streaming") ?: streaming,
            dropsFillers = flag("drops_fillers") ?: dropsFillers,
            wordAccuracy = quality("word_accuracy") ?: wordAccuracy,
        )
    }

    companion object {
        /** whisper.cpp: punctuates and capitalizes, one segment for the whole recording. */
        val WHISPER = VoiceModelProfile(Quality.GOOD, Quality.GOOD, pauseSegments = false, streaming = false)

        /**
         * Moonshine streaming: its own marks are unreliable (false periods at forced cuts, missed
         * question marks, capitals at every utterance), which is what the punctuation model and the
         * sentence-break veto are for.
         */
        val MOONSHINE = VoiceModelProfile(Quality.WEAK, Quality.WEAK, pauseSegments = true, streaming = true)

        /**
         * NVIDIA Nemotron streaming (EN and 3.5): trained with punctuation and capitalization, but it
         * leaves out final marks (24 of 44 phone dictations) and question marks on questions said flat
         * (4 of 40, spoken set 2), and keeps some "um" (2 of 4 on the phone). The punctuation model
         * and the question and filler fixes after it get 1927 of 2344 set-2 lines right against 1632
         * without them (`ablate.py`, 2026-11-01). Its capitals miss names and months ("march
         * twelfth", "Hi mom"), so the dictionary's casing still runs.
         */
        val NEMOTRON = VoiceModelProfile(Quality.WEAK, Quality.WEAK, pauseSegments = true, streaming = true)

        /**
         * NVIDIA Parakeet (TDT/CTC), run on the whole recording. Keeps "um" and "uh". Its capitals
         * miss months ("march twelfth") and come after pauses mid-sentence ("pasta tonight |
         * Actually | Tacos", "the Truck"), so the dictionary's casing still runs (spoken bench).
         * It does mix up sound-alikes ("By two loaves", "knew paragraph"), so those fixes stay, and
         * misses question marks the question fix adds (10 fixed, 0 broken on set 2), so the
         * punctuation model and that fix run after it (1831 of 2344 against 1802, 2026-11-01).
         */
        val PARAKEET = VoiceModelProfile(Quality.WEAK, Quality.WEAK, pauseSegments = true, streaming = false)
    }
}
