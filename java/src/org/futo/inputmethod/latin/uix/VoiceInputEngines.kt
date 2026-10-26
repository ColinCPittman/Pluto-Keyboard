package org.futo.inputmethod.latin.uix

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.futo.voiceinput.shared.context.PersonalDictionary
import org.futo.voiceinput.shared.engine.RecognitionEngineConfiguration
import org.futo.voiceinput.shared.engine.TranscriptPostProcessor
import org.futo.voiceinput.shared.modelpackage.InstalledModelPackage
import org.futo.voiceinput.shared.modelpackage.ModelPackageKinds
import org.futo.voiceinput.shared.modelpackage.ModelPackages
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.moonshine.core.MoonshineModelFiles
import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingTranscriber
import org.futo.voiceinput.shared.moonshine.core.OrtAccelerator
import org.futo.voiceinput.shared.moonshine.core.OrtRuntimeOptions
import java.util.Locale

/** Chooses the speech recognition engine for built-in voice input from the user's settings. */
object VoiceInputEngines {
    fun installedMoonshineModels(context: Context): List<InstalledModelPackage> =
        ModelPackages.list(context, ModelPackageKinds.MOONSHINE_STREAMING)

    /** The selected Moonshine package, falling back to the first installed one. */
    fun selectedMoonshineModel(context: Context): InstalledModelPackage? {
        val installed = installedMoonshineModels(context)
        val id = context.getSetting(MOONSHINE_MODEL_ID)
        return installed.firstOrNull { it.id == id } ?: installed.firstOrNull()
    }

    fun accelerator(context: Context): OrtAccelerator =
        OrtAccelerator.entries.firstOrNull { it.name == context.getSetting(MOONSHINE_ACCELERATOR) } ?: OrtAccelerator.CPU

    /**
     * Returns the engine to use for [locale]. Moonshine is English-only, so any
     * other language (or a missing/broken Moonshine package) uses whisper.
     */
    fun resolve(context: Context, locale: Locale): RecognitionEngineConfiguration {
        if (context.getSetting(VOICE_INPUT_ENGINE) != VOICE_ENGINE_MOONSHINE) return RecognitionEngineConfiguration.Whisper
        if (locale.language != "en") return RecognitionEngineConfiguration.Whisper

        val model = selectedMoonshineModel(context) ?: return RecognitionEngineConfiguration.Whisper
        if (MoonshineModelFiles.validateDirectory(model.dir).isNotEmpty()) return RecognitionEngineConfiguration.Whisper

        return RecognitionEngineConfiguration.MoonshineStreaming(
            modelDir = model.dir,
            modelName = model.name,
            runtime = OrtRuntimeOptions(
                accelerator = accelerator(context),
                threads = context.getSetting(MOONSHINE_THREADS).coerceIn(0, 16)
            ),
            streaming = MoonshineStreamingTranscriber.Options(
                transcriptionInterval = context.getSetting(MOONSHINE_REFRESH_MS).coerceIn(100, 5000) / 1000f,
                decodeIncompleteLines = context.getSetting(MOONSHINE_LIVE_TEXT)
            )
        )
    }

    /** Choices for [MOONSHINE_REFRESH_MS] and [MOONSHINE_AUTO_STOP_MS] (Voice Input -> Advanced options). */
    val REFRESH_CHOICES_MS = listOf(250, 500, 1000)
    val AUTO_STOP_CHOICES_MS = listOf(1000, 1500, 2000, 3000, 5000)

    /** How long a silence ends a dictation on [engine], and only Moonshine has the setting. */
    fun autoStopSilenceMs(context: Context, engine: RecognitionEngineConfiguration): Int =
        if (engine is RecognitionEngineConfiguration.MoonshineStreaming) {
            context.getSetting(MOONSHINE_AUTO_STOP_MS).coerceIn(500, 10000)
        } else {
            RecordingSettings.DEFAULT_AUTO_STOP_SILENCE_MS
        }

    /**
     * A post-processing stage of voice input, for the chain built by [postProcessorFor].
     * With [originalSegments], the stage gets the engine's segments (the utterances between pauses)
     * even when it is not the first stage, and otherwise later stages get the previous text as one segment.
     */
    class Stage(val name: String, val processor: TranscriptPostProcessor, val originalSegments: Boolean = false)

    /**
     * Post-processing applied to the final transcript of [engine] before it is inserted, as a
     * chain of optional stages, namely punctuation restoration ([PunctuationModels]), then the context
     * model ([ContextModels]), each if it is enabled and a model supporting [locale] is
     * installed. The identity if no stage applies.
     *
     * The first stage gets the engine's segments, and later stages get the previous stage's text as
     * one segment, except the context stage, which gets the engine's segments too (its spoken
     * commands only count at pauses). A failing stage is skipped (its input is passed on), so the
     * transcript is never lost. [onStageDone] receives each stage's name and duration (for the
     * timing info). [personal] holds the user's personal words, for the context stage's names option. [leftContext] is
     * the text in the field before the dictation (read when the context stage runs), which the context stage reads but never edits.
     * Whisper punctuates its own output, so the punctuation stage only runs after Moonshine.
     */
    fun postProcessorFor(
        context: Context,
        engine: RecognitionEngineConfiguration,
        locale: Locale,
        personal: PersonalDictionary = PersonalDictionary.EMPTY,
        leftContext: () -> String = { "" },
        onStageDone: (name: String, millis: Long) -> Unit = { _, _ -> }
    ): TranscriptPostProcessor {
        val stages = listOfNotNull(
            PunctuationModels.processorFor(context, locale)
                ?.takeIf { engine !is RecognitionEngineConfiguration.Whisper }
                ?.let { Stage(STAGE_PUNCTUATION, it) },
            ContextModels.processorFor(context, locale, personal, leftContext)?.let { Stage(STAGE_CONTEXT, it, originalSegments = true) },
        )
        // a private tuning hook (DictationRecorder does nothing in public builds)
        if (DictationRecorder.isEnabled(context)) {
            return TranscriptPostProcessor { raw, segments ->
                val outputs = ArrayList<Pair<String, String>>()
                val millis = ArrayList<Pair<String, Long>>()
                val result = if (stages.isEmpty()) raw else
                    chain(stages, { name, ms -> millis.add(name to ms); onStageDone(name, ms) }) { name, text -> outputs.add(name to text) }.process(raw, segments)
                DictationRecorder.record(context, locale, raw, segments, outputs, millis)
                result
            }
        }
        if (stages.isEmpty()) return TranscriptPostProcessor.Identity
        return chain(stages, onStageDone)
    }

    const val STAGE_PUNCTUATION = "punctuation"
    const val STAGE_CONTEXT = "context"

    fun chain(
        stages: List<Stage>,
        onStageDone: (String, Long) -> Unit = { _, _ -> },
        onStageOutput: (name: String, text: String) -> Unit = { _, _ -> },
    ): TranscriptPostProcessor =
        TranscriptPostProcessor { raw, segments ->
            var text = raw
            var segs = segments
            for (stage in stages) {
                val start = System.nanoTime()
                text = try {
                    stage.processor.process(text, if (stage.originalSegments) segments else segs).ifBlank { text }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e("VoiceInputEngines", "Voice input ${stage.name} stage failed, keeping its input", e)
                    text
                }
                segs = listOf(text)
                onStageDone(stage.name, (System.nanoTime() - start) / 1_000_000)
                onStageOutput(stage.name, text)
            }
            text
        }
}
