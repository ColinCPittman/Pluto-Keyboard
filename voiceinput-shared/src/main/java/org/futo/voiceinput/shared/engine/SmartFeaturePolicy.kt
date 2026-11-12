package org.futo.voiceinput.shared.engine

import org.futo.voiceinput.shared.context.SmartCorrectionOptions

/**
 * Decides which of the user's switched-on voice features actually run for a speech model, so a
 * model that punctuates and capitalizes well isn't second-guessed (and doesn't pay for stages it
 * doesn't need). The user's switches say what they want; this only drops what the model already does.
 *
 * Edits the user asked for (numbers, commands, self-corrections, quotes, emoji) and personal names
 * run for every model, since no speech model does those. Sound-alike words are left to a model that
 * doesn't mix them up, and filler words to a model that drops them itself, so deliberate "like" and
 * "basically" stay.
 */
object SmartFeaturePolicy {
    /** Whether the punctuation model runs after [profile], given the user's switch [enabled]. */
    fun punctuationStage(profile: VoiceModelProfile, enabled: Boolean): Boolean =
        enabled && !profile.punctuatesItself

    /**
     * [options] for transcripts of [profile]. Missed question marks and lexicon capitalization are
     * left to a model that does them well, filler words to a model that drops them, and the sentence-break veto only runs after the
     * punctuation stage ([punctuationRan]), whose breaks it checks.
     */
    fun smartCorrections(
        options: SmartCorrectionOptions,
        profile: VoiceModelProfile,
        punctuationRan: Boolean,
    ): SmartCorrectionOptions = options.copy(
        soundAlike = options.soundAlike && !profile.hearsWordsItself,
        questions = options.questions && !profile.punctuatesItself,
        sentenceBreaks = options.sentenceBreaks && punctuationRan,
        lexiconCasing = options.lexiconCasing && !profile.capitalizesItself,
        fillers = options.fillers && !profile.dropsFillers,
    )
}
