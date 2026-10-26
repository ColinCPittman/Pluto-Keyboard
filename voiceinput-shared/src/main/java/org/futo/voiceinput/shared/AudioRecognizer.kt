package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import com.konovalov.vad.models.VadModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.engine.RecognitionEngineConfiguration
import org.futo.voiceinput.shared.engine.TranscriptPostProcessor
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.moonshine.MoonshineStreamingSession
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** How long the microphone stays open after stop with a streaming engine (0.25 s). */
private const val STOP_TAIL_SAMPLES = 4000

private fun getRecordingDeviceKind(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "DOCK_ANALOG"
        else -> "unknown@${type}"
    }
}

data class RecordingSettings(
    val preferBluetoothMic: Boolean,
    val requestAudioFocus: Boolean,
    val canExpandSpace: Boolean,
    val useVADAutoStop: Boolean,
    /** With [useVADAutoStop], how long a silence after speech stops recording. */
    val autoStopSilenceMs: Int = DEFAULT_AUTO_STOP_SILENCE_MS
) {
    companion object {
        const val DEFAULT_AUTO_STOP_SILENCE_MS = 2000
    }
}

data class AudioRecognizerSettings(
    val modelRunConfiguration: MultiModelRunConfiguration,
    val decodingConfiguration: DecodingConfiguration,
    val recordingConfiguration: RecordingSettings,
    /** Engine to use, which is whisper (modelRunConfiguration) unless configured otherwise. */
    val engine: RecognitionEngineConfiguration = RecognitionEngineConfiguration.Whisper,
    /** Applied to the final transcript before [AudioRecognizerListener.finished]. */
    val postProcessor: TranscriptPostProcessor = TranscriptPostProcessor.Identity
)

class ModelDoesNotExistException(val models: List<ModelLoader>) : Throwable()

class AudioRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    modelManager: ModelManager,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings
) {
    private var isRecording = false

    // Streaming engines keep the microphone open a moment after stop is pressed (until this many
    // samples are recorded), so the end of the last word isn't cut off. 0 means stop at once.
    @Volatile private var recordUntilSample = 0L
    @Volatile private var recordedSamples = 0L
    private var recorder: AudioRecord? = null

    private val modelRunner = MultiModelRunner(modelManager)

    private val moonshineManager = modelManager.moonshine
    private val moonshineConfig = settings.engine as? RecognitionEngineConfiguration.MoonshineStreaming
    private val isStreamingEngine = moonshineConfig != null
    @Volatile private var moonshineSession: MoonshineStreamingSession? = null

    private val canExpandSpace = settings.recordingConfiguration.canExpandSpace
    private val useVAD = settings.recordingConfiguration.useVADAutoStop
    // The auto-stop VAD judges 30 ms frames (480 samples).
    private val autoStopSilenceFrames = settings.recordingConfiguration.autoStopSilenceMs / 30

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null

    private var focusRequest: AudioFocusRequest? = null

    private var communicationDevice = "unknown"

    private fun focusAudio() {
        unfocusAudio()

        if(!settings.recordingConfiguration.requestAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = this.audioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = this.audioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices

                return devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } != null
            }
        } catch(_: Exception) {}

        return false
    }

    /**
     * One AudioManager for the whole process, because Android keys a communication-device request to the
     * AudioManager instance that made it, so a clear through another instance (another Context)
     * leaves the route in place, and other apps' audio (read aloud, media) goes to the earpiece.
     */
    private val audioManager: AudioManager
        get() = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Routes recording through a Bluetooth headset mic when the user prefers one and it is there.
     * Otherwise no communication device is set at all, since the VOICE_RECOGNITION source records from
     * the phone's own mic without one, and the old fallback (the first non-Bluetooth device, i.e.
     * the earpiece) only redirected other apps' audio.
     */
    private fun setCommunicationDevice(preferBluetoothMic: Boolean): Pair<Boolean, String> {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = this.audioManager
                val btDevice = audioManager.availableCommunicationDevices
                    .firstOrNull { preferBluetoothMic && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (btDevice == null || !audioManager.setCommunicationDevice(btDevice)) {
                    audioManager.clearCommunicationDevice()
                    return Pair(false, "")
                }
                return Pair(true, btDevice.productName.toString())
            }
        } catch(_: Exception) {}
        return Pair(false, "")
    }

    private fun clearCommunicationDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
        } catch(_: Exception) {}
    }

    @Throws(ModelDoesNotExistException::class)
    private fun verifyModelsExist() {
        // Moonshine model packages are validated when loading (errors are shown in the UI).
        if (isStreamingEngine) return

        val modelsThatDoNotExist = mutableListOf<ModelLoader>()

        if (!settings.modelRunConfiguration.primaryModel.exists(context)) {
            modelsThatDoNotExist.add(settings.modelRunConfiguration.primaryModel)
        }

        for (model in settings.modelRunConfiguration.languageSpecificModels.values) {
            if (!model.exists(context)) {
                modelsThatDoNotExist.add(model)
            }
        }

        if (modelsThatDoNotExist.isNotEmpty()) {
            throw ModelDoesNotExistException(modelsThatDoNotExist)
        }
    }

    init {
        verifyModelsExist()
    }

    fun reset() {
        recordUntilSample = 0L
        recorder?.stop()
        recorderJob?.cancel()

        recorder?.release()
        recorder = null

        modelJob?.cancel()
        isRecording = false

        modelRunner.cancelAll()

        moonshineSession?.cancel()
        moonshineSession = null

        unfocusAudio()

        clearCommunicationDevice()
    }

    fun finish() {
        if(!isRecording) return
        onFinishRecording()
    }

    fun cancel() {
        reset()
        listener.cancelled()
    }

    fun openPermissionSettings() {
        val packageName = context.packageName
        val micPermissionRequester = Intent()
        micPermissionRequester.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
        micPermissionRequester.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(micPermissionRequester)

        cancel()
    }

    fun start() {
        listener.loading()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
        } else {
            startRecording()
        }
    }

    private fun requestPermission() {
        listener.needPermission { wasGranted ->
            if(wasGranted) {
                startRecording()
            }
        }
    }


    @Throws(SecurityException::class)
    private fun createAudioRecorder(): AudioRecord {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16000 * 2 * 5
        )

        this.recorder = recorder

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
        }

        return recorder
    }

    private suspend fun preloadModels() {
        if (moonshineConfig != null) {
            val session = MoonshineStreamingSession(moonshineManager, moonshineConfig) { partial ->
                withContext(Dispatchers.Main) {
                    if (moonshineSession != null) runnerCallback.partialResult(partial)
                }
            }
            moonshineSession = session
            session.start(lifecycleScope)
        } else {
            modelRunner.preload(settings.modelRunConfiguration)
        }
    }

    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(floatSamples.capacity() + 16000 * 30)
            //Log.d("AudioRecognizer", "Allocating extra space: ${floatSamples.capacity() / 16000} -> ${newSampleBuffer.capacity() / 16000}")
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }

    private suspend fun recordingJob(recorder: AudioRecord, vad: VadModel?) {
        var hasTalked = false
        var anyNoiseAtAll = false

        val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                SensorPrivacyManager.Sensors.MICROPHONE
            )
        } else {
            false
        }
        var isMicBlocked = false

        val vadSampleBuffer = ShortBuffer.allocate(480)
        var numConsecutiveNonSpeech = 0
        var numConsecutiveSpeech = 0

        val samples = ShortArray(1600)
        var samplesRecorded = 0L
        val recordedSoFar = { if (isStreamingEngine) samplesRecorded else floatSamples.position().toLong() }

        // Whisper keeps the whole recording in floatSamples (limited to 30 s
        // unless long-form input is enabled). Streaming engines consume the
        // audio as it arrives, so there is no length limit for them.
        val storeSamples: (Int) -> Boolean = { n ->
            samplesRecorded += n
            recordedSamples = samplesRecorded
            val chunk = FloatArray(n) { samples[it].toFloat() / Short.MAX_VALUE.toFloat() }
            if (isStreamingEngine) {
                moonshineSession?.addAudio(chunk)
                true
            } else if (floatSamples.remaining() < n && !expandSpaceIfAllowed()) {
                false
            } else {
                floatSamples.put(chunk)
                true
            }
        }

        while (isRecording || samplesRecorded < recordUntilSample) {
            yield()
            val nRead = recorder.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
            if (nRead <= 0) break
            yield()
            if (!isRecording) {
                // after stop there is only the last moment of audio, no more checks or level updates
                storeSamples(nRead)
                continue
            }

            var isRunningOutOfSpace = !isStreamingEngine &&
                    (floatSamples.remaining() < nRead.coerceAtLeast(1600)) && !expandSpaceIfAllowed()

            val hasNotTalkedRecently = hasTalked && (numConsecutiveNonSpeech > autoStopSilenceFrames) && useVAD
            if (isRunningOutOfSpace || hasNotTalkedRecently) {
                yield()
                withContext(Dispatchers.Main) {
                    finish()
                }
                return
            }

            // Run VAD
            if(useVAD && vad != null) {
                var remainingSamples = nRead
                var offset = 0
                while (remainingSamples > 0) {
                    if (!vadSampleBuffer.hasRemaining()) {
                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                        vadSampleBuffer.clear()
                        vadSampleBuffer.rewind()

                        if (!isSpeech) {
                            numConsecutiveNonSpeech++
                            numConsecutiveSpeech = 0
                        } else {
                            numConsecutiveNonSpeech = 0
                            numConsecutiveSpeech++
                        }
                    }

                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                    for (i in 0 until samplesToRead) {
                        vadSampleBuffer.put(
                            samples[offset]
                        )
                        offset += 1
                        remainingSamples -= 1
                    }
                }
            }

            storeSamples(nRead)

            // Don't set hasTalked if the start sound may still be playing, otherwise on some
            // devices the rms just explodes and `hasTalked` is always true
            val startSoundPassed = (recordedSoFar() > 16000 * 0.6)
            if (!startSoundPassed) {
                numConsecutiveSpeech = 0
                numConsecutiveNonSpeech = 0
            }

            val rms = sqrt(samples.sumOf { (it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2).toDouble() } / samples.size).toFloat()

            if (startSoundPassed && ((rms > 0.01) || (numConsecutiveSpeech > 8))) {
                hasTalked = true
            }

            if (rms > 0.0001) {
                anyNoiseAtAll = true
                isMicBlocked = false
            }

            // Check if mic is blocked
            val blockCheckTimePassed = (recordedSoFar() > 2 * 16000) // two seconds
            if (!anyNoiseAtAll && canMicBeBlocked && blockCheckTimePassed) {
                isMicBlocked = true
            }

            val magnitude = (1.0f - 0.1f.pow(24.0f * rms))

            val state = if (hasTalked) {
                MagnitudeState.TALKING
            } else if (isMicBlocked) {
                MagnitudeState.MIC_MAY_BE_BLOCKED
            } else {
                MagnitudeState.NOT_TALKED_YET
            }

            yield()
            withContext(Dispatchers.Main) {
                listener.updateMagnitude(magnitude, state)
            }

            // Skip ahead as much as possible, in case we are behind (taking more than
            // 100ms to process 100ms)
            while (true) {
                yield()
                val nRead2 = recorder.read(
                    samples, 0, 1600, AudioRecord.READ_NON_BLOCKING
                )
                if (nRead2 > 0) {
                    if (!storeSamples(nRead2)) {
                        yield()
                        withContext(Dispatchers.Main) {
                            finish()
                        }
                        break
                    }
                } else {
                    break
                }
            }
        }
        println("isRecording loop exited")
    }

    private fun createVad(): VadModel {
        return Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE)
            .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setSpeechDurationMs(150).setSilenceDurationMs(300).build()
    }

    @Throws(SecurityException::class)
    private fun createRecorderAndJob(preferBluetoothMic: Boolean): MicrophoneDeviceState {
        isRecording = false
        recorder?.stop()

        val bluetoothInfo = setCommunicationDevice(preferBluetoothMic)

        val task = {
            recorder?.release()

            val recorder = createAudioRecorder()

            recorder.startRecording()
            this.recorder = recorder

            recordUntilSample = 0L
            recordedSamples = 0L
            isRecording = true

            recorderJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    if(useVAD) {
                        createVad().use { vad ->
                            recordingJob(recorder, vad)
                        }
                    } else {
                        recordingJob(recorder, null)
                    }
                }
            }
        }

        if(recorderJob != null) {
            lifecycleScope.launch {
                recorderJob?.cancelAndJoin()
                task()
            }
        } else {
            task()
        }


        return MicrophoneDeviceState(
            bluetoothAvailable = bluetoothInfo.first || isBluetoothAvailable(),
            bluetoothActive = bluetoothInfo.first,
            deviceName = bluetoothInfo.second,
            bluetoothPreferredByUser = settings.recordingConfiguration.preferBluetoothMic,
            setBluetooth = {
                listener.recordingStarted(createRecorderAndJob(it))
            }
        )
    }

    private fun startRecording() {
        val device = try {
            createRecorderAndJob(settings.recordingConfiguration.preferBluetoothMic)
        } catch (e: SecurityException) {
            // It's possible we may have lost permission, so let's just ask for permission again
            clearCommunicationDevice()
            requestPermission()
            return
        }

        focusAudio()

        listener.recordingStarted(device)

        loadModelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    preloadModels()
                } catch(_: InvalidModelException) {
                    withContext(Dispatchers.Main) {
                        reset()
                        listener.modelLoadingFailed()
                    }
                } catch(e: Throwable) {
                    // Also catches errors such as OutOfMemoryError or a missing native
                    // library, so a broken model shows an error instead of crashing.
                    if (!isStreamingEngine || e is CancellationException) throw e
                    Log.e("AudioRecognizer", "Failed to load streaming model", e)
                    withContext(Dispatchers.Main) {
                        reset()
                        listener.modelLoadingFailed(e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private val runnerCallback: ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {
            listener.decodingStatus(state)
        }

        override fun languageDetected(language: Language) {
            listener.languageDetected(language)
        }

        override fun partialResult(string: String) {
            if(isBlankResult(string)) return
            listener.partialResult(string)
        }
    }

    private suspend fun runModel() {
        loadModelJob?.let {
            if (it.isActive) {
                println("Model was not finished loading...")
                it.join()
            }
        }

        if (isStreamingEngine) {
            runStreamingFinish()
            return
        }

        val floatArray = floatSamples.array().sliceArray(0 until floatSamples.position())

        yield()
        val outputText = try {
             modelRunner.run(
                floatArray,
                settings.modelRunConfiguration,
                settings.decodingConfiguration,
                runnerCallback
            ).trim()
        }catch(e: InferenceCancelledException) {
            yield()
            return
        }

        val text = when {
            isBlankResult(outputText) -> ""
            else -> outputText
        }

        deliverResult(text, listOf(text))
    }

    /** Finalizes the Moonshine stream (flushing the remaining audio) and delivers its transcript. */
    private suspend fun runStreamingFinish() {
        val session = moonshineSession ?: return
        val result = try {
            session.finish()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e("AudioRecognizer", "Streaming recognition failed", e)
            withContext(Dispatchers.Main) {
                reset()
                listener.modelLoadingFailed(e.message ?: e.javaClass.simpleName)
            }
            return
        } ?: return
        moonshineSession = null

        withContext(Dispatchers.Main) {
            listener.recognitionStats(result.stats)
        }
        deliverResult(result.text.trim(), result.segments)
    }

    /**
     * Runs the [TranscriptPostProcessor] hook (on Dispatchers.Default, while the
     * UI shows the processing state) and reports the final text.
     */
    private suspend fun deliverResult(rawText: String, segments: List<String>) {
        val text = if (rawText.isEmpty() || settings.postProcessor === TranscriptPostProcessor.Identity) {
            rawText
        } else {
            withContext(Dispatchers.Main) {
                listener.decodingStatus(InferenceState.PostProcessing)
            }
            try {
                withContext(Dispatchers.Default) {
                    settings.postProcessor.process(rawText, segments)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AudioRecognizer", "Transcript post-processing failed, using raw transcript", e)
                rawText
            }
        }

        yield()
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                yield()
                listener.finished(text)
            }
        }
    }

    private fun onFinishRecording() {
        if (!isRecording) {
            throw IllegalStateException("Should not call onFinishRecording when not recording")
        }

        val job = recorderJob
        val keepTail = isStreamingEngine && job != null && job.isActive
        // Record the last moment too (the end of the last word), and set before isRecording turns off.
        if (keepTail) recordUntilSample = recordedSamples + STOP_TAIL_SAMPLES
        isRecording = false
        if (keepTail && job != null) {
            listener.processing()
            modelJob = lifecycleScope.launch {
                withTimeoutOrNull(1000) { job.join() }
                job.cancel()
                recorder?.stop()
                clearCommunicationDevice()
                unfocusAudio()
                withContext(Dispatchers.Default) {
                    runModel()
                }
            }
            return
        }
        job?.cancel()
        recorder?.stop()
        // The microphone is done, so release the call-style audio route and audio focus now, not
        // only when the voice window closes. A route left behind sends other apps' audio (read
        // aloud, media) to the earpiece.
        clearCommunicationDevice()
        unfocusAudio()

        listener.processing()

        modelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                runModel()
            }
        }
    }
}