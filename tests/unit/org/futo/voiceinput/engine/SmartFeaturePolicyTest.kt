package org.futo.voiceinput.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.futo.voiceinput.shared.context.SmartCorrectionOptions
import org.futo.voiceinput.shared.engine.SmartFeaturePolicy
import org.futo.voiceinput.shared.engine.VoiceModelProfile
import org.futo.voiceinput.shared.engine.VoiceModelProfile.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the user's voice features run for each kind of speech model. */
class SmartFeaturePolicyTest {
    private val everything = SmartCorrectionOptions.ALL

    @Test
    fun punctuationStageOnlyForModelsThatNeedIt() {
        // Nemotron and Parakeet leave out end marks and question marks (spoken set 2), so they get it too
        for (p in listOf(VoiceModelProfile.MOONSHINE, VoiceModelProfile.NEMOTRON, VoiceModelProfile.PARAKEET)) {
            assertTrue(SmartFeaturePolicy.punctuationStage(p, enabled = true))
            assertFalse(SmartFeaturePolicy.punctuationStage(p, enabled = false))
        }
        assertFalse(SmartFeaturePolicy.punctuationStage(VoiceModelProfile.WHISPER, enabled = true))
    }

    @Test
    fun moonshineKeepsEveryFix() {
        val o = SmartFeaturePolicy.smartCorrections(everything, VoiceModelProfile.MOONSHINE, punctuationRan = true)
        assertEquals(everything, o)
    }

    @Test
    fun nemotronAndParakeetKeepEveryFixAfterThePunctuationStage() {
        // questions, fillers and the break veto each fixed lines for them on set 2 (ablate.py)
        for (p in listOf(VoiceModelProfile.NEMOTRON, VoiceModelProfile.PARAKEET)) {
            assertEquals(everything, SmartFeaturePolicy.smartCorrections(everything, p, punctuationRan = true))
        }
    }

    @Test
    fun goodModelsDropOnlyWhatTheyDoThemselves() {
        val o = SmartFeaturePolicy.smartCorrections(everything, VoiceModelProfile.WHISPER, punctuationRan = false)
        assertFalse(o.questions)
        assertFalse(o.sentenceBreaks)
        assertFalse(o.lexiconCasing)
        // what the user asked for, and what no speech model does, stays
        assertEquals(everything.copy(questions = false, sentenceBreaks = false, lexiconCasing = false), o)
        assertTrue(o.any)
    }

    @Test
    fun fillersStayForModelsThatKeepThem() {
        for (p in listOf(VoiceModelProfile.MOONSHINE, VoiceModelProfile.WHISPER, VoiceModelProfile.NEMOTRON, VoiceModelProfile.PARAKEET)) {
            assertTrue(SmartFeaturePolicy.smartCorrections(everything, p, punctuationRan = false).fillers)
        }
        val dropping = VoiceModelProfile.NEMOTRON.copy(dropsFillers = true)
        assertFalse(SmartFeaturePolicy.smartCorrections(everything, dropping, punctuationRan = false).fillers)
    }

    @Test
    fun nothingLeftMeansNoStage() {
        // only question marks on: a model that punctuates well leaves the stage nothing to do
        val onlyQuestions = SmartCorrectionOptions.NONE.copy(questions = true)
        assertFalse(SmartFeaturePolicy.smartCorrections(onlyQuestions, VoiceModelProfile.WHISPER, punctuationRan = false).any)
        assertTrue(SmartFeaturePolicy.smartCorrections(onlyQuestions, VoiceModelProfile.MOONSHINE, punctuationRan = false).any)
    }

    @Test
    fun breakVetoFollowsThePunctuationStage() {
        // Moonshine with the punctuation switch off: no stage made breaks, so there are none to check
        assertFalse(SmartFeaturePolicy.smartCorrections(everything, VoiceModelProfile.MOONSHINE, punctuationRan = false).sentenceBreaks)
    }

    @Test
    fun manifestOverrides() {
        val caps = Json.parseToJsonElement("""{"punctuation": "weak", "pause_segments": false, "casing": "nonsense", "x": 1}""").jsonObject
        val p = VoiceModelProfile.PARAKEET.withOverrides(caps)
        assertEquals(Quality.WEAK, p.punctuation)
        assertEquals(VoiceModelProfile.PARAKEET.casing, p.casing)
        assertFalse(p.pauseSegments)
        assertFalse(p.dropsFillers)
        val dropping = Json.parseToJsonElement("""{"drops_fillers": true}""").jsonObject
        assertTrue(VoiceModelProfile.PARAKEET.withOverrides(dropping).dropsFillers)
        assertEquals(VoiceModelProfile.PARAKEET, VoiceModelProfile.PARAKEET.withOverrides(null))
        val mishearing = Json.parseToJsonElement("""{"word_accuracy": "weak"}""").jsonObject
        assertFalse(VoiceModelProfile.PARAKEET.withOverrides(mishearing).hearsWordsItself)
    }

    @Test
    fun soundAlikeOnlyDroppedWhenAProfileSaysSo() {
        // no built-in profile claims it yet: that needs a bench with sound-alike traps
        for (p in listOf(VoiceModelProfile.MOONSHINE, VoiceModelProfile.WHISPER, VoiceModelProfile.NEMOTRON, VoiceModelProfile.PARAKEET)) {
            assertTrue(SmartFeaturePolicy.smartCorrections(everything, p, punctuationRan = false).soundAlike)
        }
        val accurate = VoiceModelProfile.PARAKEET.copy(wordAccuracy = Quality.GOOD)
        val o = SmartFeaturePolicy.smartCorrections(everything, accurate, punctuationRan = false)
        assertFalse(o.soundAlike)
        assertTrue(o.names)
    }
}
