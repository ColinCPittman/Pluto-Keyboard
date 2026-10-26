package org.futo.inputmethod.latin.uix.actions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.ContextModels
import org.futo.inputmethod.latin.uix.DictationFollowUp
import org.futo.inputmethod.latin.uix.DictationRecorder
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.MOONSHINE_LAST_TIMING
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.PunctuationModels
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_NAMES
import org.futo.voiceinput.shared.context.PersonalDictionary
import org.futo.inputmethod.latin.dictionarytools.LearnedWords
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VERBOSE_PROGRESS
import org.futo.inputmethod.latin.uix.VOICE_INPUT_SHOW_TIMING
import org.futo.inputmethod.latin.uix.VoiceInputEngines
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.engine.RecognitionEngineConfiguration
import org.futo.voiceinput.shared.engine.RecognitionStats
import org.futo.voiceinput.shared.engine.TranscriptPostProcessor
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { it, _ ->
        it.triggerSystemVoiceInput()
    },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false
)


@Composable
fun NoModelInstalled(locale: Locale) {
    val context = LocalContext.current
    Box(modifier = Modifier
        .fillMaxSize()
        .clickable(
            enabled = true,
            onClickLabel = null,
            onClick = {
                context.openURI("https://keyboard.futo.tech/voice-input-models", true)
            },
            role = null,
            indication = null,
            interactionSource = remember { MutableInteractionSource() })) {
        Text(
            stringResource(
                R.string.action_voice_input_no_model_for_language_x_installed,
                locale.getDisplayName(locale)
            ), modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp), textAlign = TextAlign.Center)
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = ModelManager(manager.getContext())
    val soundPlayer = SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    override suspend fun cleanUp() {
        modelManager.cleanUp()
    }

    override fun close() {
        runBlocking { modelManager.cleanUp() }
        userDictionaryObserver.unregister()
    }
}

/** At most this many learned names are passed to smart corrections. */
private const val MAX_LEARNED_NAMES = 500

/**
 * Pluto: hold-to-talk on the mic button. Holding the button starts a dictation without opening
 * the voice window (so the button stays on screen), and letting go finishes it, like tapping the
 * voice window does. The button shows the mic level while listening and a spinner while the text
 * is being finished.
 */
object HoldToTalk {
    enum class Phase { Idle, Starting, Listening, Finishing }

    val phase: MutableState<Phase> = mutableStateOf(Phase.Idle)

    /** The mic button's centre in the keyboard's root coordinates, where the bubble is drawn. */
    val micCenter: MutableState<androidx.compose.ui.geometry.Offset?> = mutableStateOf(null)
    private val session: MutableState<VoiceInputActionWindow?> = mutableStateOf(null)

    /** The live mic level while listening, or 0. */
    val magnitude: Float get() = session.value?.recognizer?.magnitude?.value ?: 0f

    /**
     * Starts a dictation for a held mic button. Returns false (and starts nothing) when one is
     * already running, the mic permission is missing or no voice model is installed, so the
     * caller can open the voice window instead, which explains what's missing.
     */
    fun start(manager: KeyboardManagerForAction, state: VoiceInputPersistentState): Boolean {
        if (phase.value != Phase.Idle) return false
        val context = manager.getContext()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val locales = manager.getActiveLocales()
        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(context, locales.firstOrNull() ?: Locale.ROOT)
            ?: return false
        phase.value = Phase.Starting
        session.value = VoiceInputActionWindow(manager, state, model, locales, headless = true)
        return true
    }

    /**
     * The press turned out to be a tap. Drops the dictation quietly (no sounds, nothing typed).
     * When [windowOpens], the voice window opens right after, so this doesn't wait for the setup
     * to stop and leaves the models loading for the window.
     */
    fun drop(windowOpens: Boolean) {
        session.value?.endHeadless(discard = true, quick = windowOpens)
    }

    /** The press is a hold. Plays the start sound if it was held back. */
    fun confirm() {
        session.value?.confirmHold()
    }

    /** The button was let go. Finishes the dictation, or drops it if recording hadn't started yet. */
    fun release() {
        val s = session.value ?: return
        when (phase.value) {
            Phase.Starting -> s.endHeadless(discard = true)
            Phase.Listening -> {
                phase.value = Phase.Finishing
                s.recognizer?.finish()
            }
            else -> {}
        }
    }

    /** The keyboard closed or the field changed, so nothing is typed. */
    fun cancel() {
        session.value?.endHeadless(discard = true)
    }

    internal fun listening(window: VoiceInputActionWindow) {
        if (session.value === window && phase.value == Phase.Starting) phase.value = Phase.Listening
    }

    internal fun ended(window: VoiceInputActionWindow) {
        if (session.value !== window) return
        session.value = null
        phase.value = Phase.Idle
    }
}

internal class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>,
    /** Hold-to-talk: no window is shown, and [HoldToTalk] is told when the dictation ends. */
    val headless: Boolean = false
) : ActionWindow(), RecognizerViewListener {
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
        val verboseFeedback = false//context.getSetting(VERBOSE_PROGRESS)
        val disallowSymbols = context.getSetting(DISALLOW_SYMBOLS)
        val useBluetoothAudio = context.getSetting(PREFER_BLUETOOTH)
        val requestAudioFocus = context.getSetting(AUDIO_FOCUS)
        val canExpandSpace = context.getSetting(CAN_EXPAND_SPACE)
        val useVAD = context.getSetting(USE_VAD_AUTOSTOP)
        val usePersonalDict = context.getSetting(USE_PERSONAL_DICT)
        val animateBubble = context.getSetting(ANIMATE_BUBBLE)
        // Moonshine (English only) if selected and installed, otherwise whisper
        val engine = VoiceInputEngines.resolve(context, locales.firstOrNull() ?: Locale.ROOT)
        showTimingInfo = context.getSetting(VOICE_INPUT_SHOW_TIMING)
        usingStreamingEngine = engine !is RecognitionEngineConfiguration.Whisper

        val primaryModel = model
        val languageSpecificModels = mutableMapOf<Language, ModelLoader>()
        val allowedLanguages = locales.mapNotNull { getLanguageFromWhisperString(it.language) }.toSet()
        val glossary = if(usePersonalDict) {
            state.userDictionaryObserver.getWords(locales).filter { it.shortcut.isNullOrEmpty() }.map { it.word }
        } else {
            emptyList()
        }

        shouldPlaySounds = enableSound

        // Post-processing chain of punctuation restoration, then the context model (identity if both
        // are off), and the models load while the user speaks. The context model stays loaded
        // a couple of minutes after this window closes, so the next dictation doesn't reload it.
        val locale = locales.firstOrNull() ?: Locale.ROOT
        if (DictationRecorder.isEnabled(context)) DictationFollowUp.check()
        val postProcessor = VoiceInputEngines.postProcessorFor(
            context, engine, locale, personalWordsForSmartCorrections(usePersonalDict, locale),
            leftContext = { inputTransaction.textContext.beforeCursor?.toString() ?: "" }
        ) { name, ms ->
            synchronized(stageTimings) { stageTimings.add(name to ms) }
        }
        hasPostProcessing = postProcessor !== TranscriptPostProcessor.Identity
        PunctuationModels.preload(context, locale)
        ContextModels.preload(context, locale)

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = verboseFeedback,
            shouldAnimateBubble = animateBubble,
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = primaryModel,
                languageSpecificModels = languageSpecificModels
            ),
            decodingConfiguration = DecodingConfiguration(
                glossary = glossary,
                languages = allowedLanguages,
                suppressSymbols = disallowSymbols
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = useBluetoothAudio,
                requestAudioFocus = requestAudioFocus,
                canExpandSpace = canExpandSpace,
                useVADAutoStop = useVAD,
                autoStopSilenceMs = VoiceInputEngines.autoStopSilenceMs(context, engine)
            ),
            engine = engine,
            postProcessor = postProcessor
        )
    }

    /**
     * Personal words for smart corrections' names option, from the Android personal dictionary
     * (read-only, as for the voice glossary) plus a read-only snapshot of capitalized words the
     * keyboard has learned (user history dictionaries of the voice language, via [LearnedWords],
     * most frequent first, at most [MAX_LEARNED_NAMES]). The snapshot is taken in the background
     * now, while the user speaks, so it costs nothing after "Done". If it is not ready by then, or
     * the device is locked, only the personal dictionary is used. Nothing is ever written.
     */
    private fun personalWordsForSmartCorrections(usePersonalDict: Boolean, locale: Locale): PersonalDictionary {
        if (!usePersonalDict || !context.getSetting(SMART_CORRECTIONS_NAMES) || !ContextModels.isActive(context, locale)) {
            return PersonalDictionary.EMPTY
        }
        val dictionaryWords = try {
            state.userDictionaryObserver.getWords(locales).filter { it.shortcut.isNullOrEmpty() }.map { it.word }
        } catch (e: Exception) {
            emptyList()
        }
        val learned = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        if (!manager.isDeviceLocked()) {
            manager.getLifecycleScope().launch(Dispatchers.Default) {
                val start = System.nanoTime()
                val words = try {
                    // read under each dictionary's read lock, on its own executor (LearnedWords.load)
                    val dictLocales = LearnedWords.localesWithLearnedWords(context).filter { it.language == locale.language }
                    LearnedWords.load(context, dictLocales)
                        .filter { w ->
                            val t = w.word
                            t.length >= 2 && t[0].isUpperCase() && t.any { it.isLowerCase() }
                        }
                        .sortedByDescending { it.probability }
                        .map { it.word }
                        .distinct()
                        .take(MAX_LEARNED_NAMES)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyList()
                }
                learned.set(words)
                Log.i("VoiceInputAction", "Learned-words snapshot: ${words.size} names in ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        }
        return object : PersonalDictionary {
            override fun words() = dictionaryWords
            override fun learned() = learned.get()
        }
    }

    private var showTimingInfo: Boolean = false
    private var usingStreamingEngine: Boolean = false
    private var hasPostProcessing: Boolean = false
    private val stageTimings = mutableListOf<Pair<String, Long>>()
    /** Recognition timing waiting for the post-processing times (shown together in [finished]). */
    private var pendingTiming: String? = null

    private var recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private var modelException: MutableState<ModelDoesNotExistException?> = mutableStateOf(null)

    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
        yield()
        val settings = loadSettings()

        yield()
        val recognizerView = try {
            RecognizerView(
                context = manager.getContext(),
                listener = this@VoiceInputActionWindow,
                settings = settings,
                lifecycleScope = manager.getLifecycleScope(),
                modelManager = state.modelManager
            )
        } catch(e: ModelDoesNotExistException) {
            modelException.value = e
            return@launch
        }

        this@VoiceInputActionWindow.recognizerView.value = recognizerView
        // a hold-to-talk press that was dropped while this was being set up
        if (headlessEnded) {
            recognizerView.cancel()
            return@launch
        }

        //yield()
        recognizerView.reset()

        //yield()
        recognizerView.start()
    }

    private var inputTransaction = manager.createInputTransaction()

    val recognizer: RecognizerView? get() = recognizerView.value

    @Volatile private var headlessEnded = false

    // Hold-to-talk starts listening on touch-down, before it's known whether the press is a tap,
    // so its sounds wait until the press is a hold
    private var holdConfirmed = false
    private var startSoundPending = false

    fun confirmHold() {
        holdConfirmed = true
        if (startSoundPending) {
            startSoundPending = false
            state.soundPlayer.playStartSound()
        }
    }

    /**
     * Ends a hold-to-talk dictation (after its text is typed, or dropping it when [discard]).
     * [quick] skips waiting for the setup to stop, for a tap that opens the voice window next.
     */
    fun endHeadless(discard: Boolean = false, quick: Boolean = false) {
        if (!headless || headlessEnded) return
        headlessEnded = true
        if (discard && !wasFinished) {
            discardOnCancel = true
            recognizerView.value?.cancel()
            inputTransaction.discard()
        }
        if (quick) {
            // the voice window takes over the models (and tells ContextModels when it closes)
            initJob.cancel()
            inputTransaction.cancel()
        } else {
            close()
        }
        HoldToTalk.ended(this)
    }

    @Composable
    private fun ModelDownloader(modelException: ModelDoesNotExistException) {
        NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
    }

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                onClickLabel = null,
                onClick = { recognizerView.value?.finish() },
                role = null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() })
            .semantics(mergeDescendants = true) {
                traversalIndex = -1.0f
            }) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                when {
                    modelException.value != null -> ModelDownloader(modelException.value!!)
                    recognizerView.value != null -> recognizerView.value!!.Content()
                }
            }
        }
    }

    @Composable
    override fun WindowTitleBar(rowScope: RowScope) {
        super.WindowTitleBar(rowScope)
        if (recognizerView.value != null && modelException.value == null) {
            // In the title row, across from "Voice Input". Stops recording (or the processing
            // after it) and erases what this dictation typed
            TextButton(
                onClick = { discardDictation() },
                modifier = with(rowScope) { Modifier.align(Alignment.CenterVertically).padding(end = 8.dp) }
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(R.string.voice_input_cancel_discard))
            }
        }
    }

    private var discardOnCancel = false

    /** The Cancel button keeps nothing from this dictation, and the voice window closes. */
    private fun discardDictation() {
        if (wasFinished) return
        discardOnCancel = true
        recognizerView.value?.cancel()
        inputTransaction.discard()
        manager.closeActionWindow()
    }

    override fun close(): CloseResult {
        inputTransaction.cancel()
        runBlocking { initJob.cancelAndJoin() }
        recognizerView.value?.cancel()
        state.modelManager.cancelAll()
        // the context model stays loaded for a couple of minutes, in case another dictation follows
        ContextModels.voiceClosed()
        return CloseResult.Default
    }

    private var wasFinished = false
    private var cancelPlayed = false
    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed && (!headless || holdConfirmed)) {
                state.soundPlayer.playCancelSound()
                cancelPlayed = true
            }
            if (discardOnCancel) inputTransaction.discard() else inputTransaction.cancel()
        }
        endHeadless()
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (shouldPlaySounds) {
            if (headless && !holdConfirmed) startSoundPending = true else state.soundPlayer.playStartSound()
        }
        if (headless) HoldToTalk.listening(this)

        // Only set the setting if bluetooth is available, else it would reset the setting
        // every time it's used without a bluetooth device connected.
        if(device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
            }
        }
    }

    override fun finished(result: String) {
        wasFinished = true
        showTiming()

        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
            val tc = inputTransaction.textContext
            inputTransaction.commit(sanitized)
            if (DictationRecorder.isEnabled(context)) {
                DictationFollowUp.arm(manager.getLatinIMEForDebug(), sanitized, tc.beforeCursor, tc.afterCursor)
            }
            manager.announce(result)
            if (headless) endHeadless() else manager.closeActionWindow()
        }
    }

    override fun partialResult(result: String) {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
            inputTransaction.updatePartial(sanitized)
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        return false
    }

    override fun recognitionStats(stats: RecognitionStats) {
        if (!showTimingInfo) return
        if (hasPostProcessing) {
            // shown with the post-processing times once the final text is ready
            pendingTiming = stats.summary()
            return
        }
        publishTiming(stats.summary())
    }

    /** Timing info after the final text, with recognition (if reported) plus each post-processing stage. */
    private fun showTiming() {
        if (!showTimingInfo) return
        val stages = synchronized(stageTimings) { stageTimings.toList().also { stageTimings.clear() } }
        val recognition = pendingTiming
        pendingTiming = null
        if (recognition == null && stages.isEmpty()) return
        val stageText = stages.joinToString(", ") { (name, ms) ->
            val label = when (name) {
                VoiceInputEngines.STAGE_PUNCTUATION -> "punctuation"
                VoiceInputEngines.STAGE_CONTEXT -> "context"
                else -> name
            }
            if (ms >= 1000) "%s %.2f s".format(label, ms / 1000.0) else "$label $ms ms"
        }
        publishTiming(listOfNotNull(recognition, stageText.ifEmpty { null }).joinToString("; "))
    }

    private fun publishTiming(summary: String) {
        Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
        manager.getLifecycleScope().launch {
            context.setSetting(MOONSHINE_LAST_TIMING, summary)
        }
    }

    override fun openSettings() {
        // Moonshine models are managed in the voice input settings, whisper models per language
        SettingsActivity.openToNavDest(context, if (usingStreamingEngine) "voiceInput" else "languages")
    }
}

private class VoiceInputNoModelWindow(val locale: Locale) : ActionWindow() {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        NoModelInstalled(locale)
    }
}

val VoiceInputAction = Action(icon = R.drawable.mic_fill,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        val locales = manager.getActiveLocales()

        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(manager.getContext(), locales.firstOrNull() ?: Locale.ROOT)

        if(model == null) {
            VoiceInputNoModelWindow(locales.firstOrNull() ?: Locale.ROOT)
        } else {
            VoiceInputActionWindow(
                manager = manager, state = persistentState as VoiceInputPersistentState,
                locales = locales, model = model
            )
        }
    }
)