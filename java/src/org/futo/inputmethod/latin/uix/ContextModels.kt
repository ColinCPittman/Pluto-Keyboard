package org.futo.inputmethod.latin.uix

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.context.ContextModel
import org.futo.voiceinput.shared.context.ContextModelFiles
import org.futo.voiceinput.shared.context.PersonalDictionary
import org.futo.voiceinput.shared.context.SmartCorrectionOptions
import org.futo.voiceinput.shared.context.SmartCorrectionSensitivity
import org.futo.voiceinput.shared.context.VoiceCommands
import org.futo.voiceinput.shared.context.CorrectionPhrases
import org.futo.voiceinput.shared.context.FillerWords
import org.futo.voiceinput.shared.context.QuoteTriggers
import org.futo.voiceinput.shared.context.TypingConfusions
import org.futo.voiceinput.shared.context.TypingCorrector
import org.futo.voiceinput.shared.context.TypingCorrectionConfig
import org.futo.voiceinput.shared.context.TypingPairEdits
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.futo.voiceinput.shared.engine.FastCores
import org.futo.voiceinput.shared.engine.SmartFeaturePolicy
import org.futo.voiceinput.shared.engine.TranscriptPostProcessor
import org.futo.voiceinput.shared.engine.VoiceModelProfile
import org.futo.voiceinput.shared.modelpackage.InstalledModelPackage
import org.futo.voiceinput.shared.modelpackage.ModelPackageKinds
import org.futo.voiceinput.shared.modelpackage.ModelPackages
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Third pass for voice input (after punctuation). A small LM judges the finished transcript and
 * fixes misheard sound-alike words, question marks and whatever else the "Smart corrections"
 * options enable (see ContextRescorer, SmartCorrectionOptions). Uses an imported `context` model
 * package (GGUF + sound-alike lexicon).
 *
 * Lifecycle (the model costs a few hundred MB while loaded). Loading starts when voice input
 * opens ([preload]), the model stays loaded while the voice window is open, and it is released
 * as soon as the transcript has been processed or the window closes ([release]). Nothing is
 * kept around while voice input is not in use. The GGUF is memory-mapped.
 */
object ContextModels {
    private const val TAG = "ContextModels"

    private class Loaded(val id: String, val dir: File, val stamp: Long, val model: ContextModel)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** How long the model stays loaded after the voice window closes. */
    private const val VOICE_KEEP_MS = 2 * 60 * 1000L
    private val mutex = Mutex()
    private var loaded: Loaded? = null

    /** The model a running correction uses (to abort it from [release] without the lock). */
    @Volatile
    private var busy: ContextModel? = null

    fun installed(context: Context): List<InstalledModelPackage> =
        ModelPackages.list(context, ModelPackageKinds.CONTEXT)

    /** The selected context package, falling back to the first installed one. */
    fun selected(context: Context): InstalledModelPackage? {
        val installed = installed(context)
        val id = context.getSetting(CONTEXT_MODEL_ID)
        return installed.firstOrNull { it.id == id } ?: installed.firstOrNull()
    }

    /** Context packages are per language ("en"), and none declare "multi" today but it is accepted. */
    fun supportsLocale(pkg: InstalledModelPackage, locale: Locale): Boolean {
        val language = pkg.manifest.language
        return language.isNullOrEmpty() || language == "multi" || language == locale.language
    }

    /**
     * Whether this device can run the native library, which takes arm64 with the dot-product and fp16
     * arithmetic extensions it is compiled for (ARMv8.2+, practically every phone since 2018),
     * or x86_64 (emulator builds).
     */
    val deviceSupported: Boolean by lazy {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        when (abi) {
            "arm64-v8a" -> try {
                val features = File("/proc/cpuinfo").readLines()
                    .filter { it.startsWith("Features") }
                    .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }
                    .toSet()
                "asimddp" in features && "asimdhp" in features
            } catch (e: Exception) {
                false
            }
            "x86_64" -> true
            else -> false
        }
    }

    /** The package to use for a transcript in [locale], or null if the context stage doesn't apply. */
    private fun packageFor(context: Context, locale: Locale): InstalledModelPackage? {
        if (!context.getSetting(CONTEXT_MODEL_ENABLED)) return null
        if (!deviceSupported) return null
        val pkg = selected(context) ?: return null
        if (!supportsLocale(pkg, locale)) return null
        if (ContextModelFiles.validate(pkg.manifest.engine, pkg.dir).isNotEmpty()) return null
        return pkg
    }

    /**
     * The options of the "Smart corrections" settings page. The emoji names (about 1,900, read from
     * the emoji data) are only filled in with [withEmojiNames], when a transcript is corrected.
     */
    fun options(context: Context, withEmojiNames: Boolean = false): SmartCorrectionOptions = SmartCorrectionOptions(
        soundAlike = context.getSetting(SMART_CORRECTIONS_SOUND_ALIKE),
        // adding missed question marks is part of smart corrections itself (said by the main switch)
        questions = true,
        names = context.getSetting(SMART_CORRECTIONS_NAMES),
        numbers = context.getSetting(SMART_CORRECTIONS_NUMBERS),
        commands = context.getSetting(SMART_CORRECTIONS_COMMANDS),
        fillers = context.getSetting(SMART_CORRECTIONS_FILLERS),
        selfCorrections = context.getSetting(SMART_CORRECTIONS_SELF_CORRECTIONS),
        quotes = context.getSetting(SMART_CORRECTIONS_QUOTES),
        emoji = context.getSetting(SMART_CORRECTIONS_EMOJI),
        emojiNames = if (withEmojiNames && context.getSetting(SMART_CORRECTIONS_EMOJI))
            EmojiNameDefaults.nameMap(context, context.getSetting(SMART_CORRECTIONS_EMOJI_LIST)) else emptyMap(),
        quoteTriggerPhrases = QuoteTriggers.enabledPhrases(
            QuoteTriggers.parse(context.getSetting(SMART_CORRECTIONS_QUOTE_TRIGGERS)),
            builtIn = context.getSetting(SMART_CORRECTIONS_QUOTE_TRIGGERS_BUILT_IN)),
        commandList = VoiceCommands.parse(context.getSetting(SMART_CORRECTIONS_COMMAND_LIST)),
        correctionPhrases = CorrectionPhrases.enabledPhrases(
            CorrectionPhrases.parse(context.getSetting(SMART_CORRECTIONS_CORRECTION_PHRASES)),
            builtIn = context.getSetting(SMART_CORRECTIONS_CORRECTION_PHRASES_BUILT_IN)),
        fillerPhrases = FillerWords.enabledPhrases(
            FillerWords.parse(context.getSetting(SMART_CORRECTIONS_FILLER_LIST)),
            builtIn = context.getSetting(SMART_CORRECTIONS_FILLER_BUILT_IN)),
        sensitivity = SmartCorrectionSensitivity(
            soundAlike = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_SOUND_ALIKE),
            names = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_NAMES),
            questions = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_QUESTIONS),
            numbers = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_NUMBERS),
            commands = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_COMMANDS),
            fillers = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_FILLERS),
            selfCorrections = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_SELF_CORRECTIONS),
            quotes = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_QUOTES),
            emoji = context.getSetting(SMART_CORRECTIONS_SENSITIVITY_EMOJI),
        ),
    )

    /**
     * [options] for transcripts from a voice model with [profile], without what that model already
     * does well. [punctuationRan] says whether the punctuation stage runs before this one.
     */
    fun optionsFor(
        context: Context,
        profile: VoiceModelProfile,
        punctuationRan: Boolean,
        withEmojiNames: Boolean = false,
    ): SmartCorrectionOptions = SmartFeaturePolicy.smartCorrections(options(context, withEmojiNames), profile, punctuationRan)

    /** Whether the context stage will run for [locale] (enabled, a model installed, an option on). */
    fun isActive(context: Context, locale: Locale): Boolean =
        packageFor(context, locale) != null && options(context).any

    /**
     * The context stage for transcripts in [locale] from a voice model with [profile], or null if it
     * is off, unavailable or has nothing to do for that model ([optionsFor]).
     * [personal] is the user's personal-dictionary / learned words (names), read when a transcript
     * is corrected. The stage gets the ASR's original segments (pauses) as `segments`.
     */
    fun processorFor(
        context: Context,
        locale: Locale,
        profile: VoiceModelProfile,
        punctuationRan: Boolean,
        personal: PersonalDictionary = PersonalDictionary.EMPTY,
        leftContext: () -> String = { "" },
    ): TranscriptPostProcessor? {
        val pkg = packageFor(context, locale) ?: return null
        val options = optionsFor(context, profile, punctuationRan)
        if (!options.any) return null
        return TranscriptPostProcessor { raw, segments ->
            // usually ready, since preload started reading the names when voice input opened
            val withNames = if (options.emoji) withContext(Dispatchers.Default) {
                optionsFor(context, profile, punctuationRan, withEmojiNames = true)
            } else options
            correct(context, pkg, raw, segments, withNames, personal, leftContext()).also { out ->
                if (out != raw) withContext(Dispatchers.Main) { FeatureTips.offer(context, FeatureTip.VOICE_SMART_CORRECTIONS) }
            }
        }
    }

    /**
     * Whether Smart corrections for typing can run for [locale], which takes English, a usable context
     * package installed (the voice switch doesn't matter, because typing has its own).
     */
    fun typingAvailable(context: Context, locale: Locale): Boolean = typingPackage(context, locale) != null

    private fun typingPackage(context: Context, locale: Locale): InstalledModelPackage? {
        if (locale.language != "en" || !deviceSupported) return null
        val pkg = selected(context) ?: return null
        if (!supportsLocale(pkg, locale)) return null
        if (ContextModelFiles.validate(pkg.manifest.engine, pkg.dir).isNotEmpty()) return null
        return pkg
    }

    private var typingCorrector: Pair<ContextModel, TypingCorrector>? = null
    private var typingPairsOf: TypingConfusions? = null
    @Volatile private var mergedPairs: Pair<Pair<String, Boolean>, TypingConfusions>? = null

    /**
     * The typing settings, covering which kinds of fixes and how readily. Joined, split and
     * apostrophe words go with missed typos, and word order with missing and extra words.
     */
    fun typingConfig(context: Context): TypingCorrectionConfig {
        val missed = context.getSetting(TYPING_FIX_MISSED_TYPOS)
        val words = context.getSetting(TYPING_FIX_MISSING_WORDS)
        return TypingCorrectionConfig(
            realWords = context.getSetting(TYPING_FIX_REAL_WORDS),
            missedTypos = missed,
            missingWords = words,
            leftoverWords = words,
            wordOrder = words,
            wordEndings = context.getSetting(TYPING_FIX_WORD_ENDINGS),
            joinSplit = missed,
        ).withSensitivity(context.getSetting(TYPING_SMART_CORRECTIONS_SENSITIVITY))
    }

    /** The typing pairs in use, which are the built-in ones with your changes (the same object until they change). */
    fun typingPairs(context: Context): TypingConfusions {
        val key = context.getSetting(TYPING_PAIRS_LIST) to context.getSetting(TYPING_PAIRS_BUILT_IN)
        mergedPairs?.let { if (it.first == key) return it.second }
        val merged = TypingPairEdits.merge(TypingConfusions.builtIn, TypingPairEdits.parse(key.first), key.second)
        mergedPairs = key to merged
        return merged
    }
    private var typingRelease: Job? = null

    /**
     * What a typing check came to. [ran] is false when it didn't really run (voice input was open,
     * it was aborted or failed), so the sentence still needs a check. [ms] is how long it took.
     */
    class TypingCheck(
        val ran: Boolean, val fix: TypingCorrector.Fix? = null, val ms: Long = 0,
        /** It didn't run because the model failed on it (not because voice input took over). */
        val failed: Boolean = false,
    )

    /** Typing checks wait while voice input is open. */
    val typingPaused: Boolean get() = dictating

    /** Called when voice input closes, so typing checks can look at what was dictated. */
    @Volatile var typingResumed: (() -> Unit)? = null

    /**
     * Smart corrections for typing checks the sentence [from]..[to] of [text] (see
     * [TypingCorrector.check]). The model stays loaded while typing continues and is released a
     * minute after the last check ([releaseTypingSoon] sooner). Cancelling aborts the check.
     */
    suspend fun checkTyping(
        context: Context,
        locale: Locale,
        text: String,
        from: Int,
        to: Int,
        finished: Boolean,
        skip: (Int) -> Boolean,
        caution: (String, String, String, String) -> Double,
        unknown: (Int) -> Boolean = { false },
    ): TypingCheck {
        // the speech model needs the fast cores while voice input is open (these checks halved its speed)
        if (dictating) return TypingCheck(false)
        val pkg = typingPackage(context, locale) ?: return TypingCheck(false)
        typingRelease?.cancel()
        return mutex.withLock {
            if (dictating) return@withLock TypingCheck(false)
            try {
                val model = obtainLocked(pkg)
                // your changes to the pairs are read for every check, so they apply at once
                val pairs = typingPairs(context)
                val config = typingConfig(context)
                val corrector = typingCorrector?.takeIf {
                    it.first === model && typingPairsOf === pairs && it.second.config == config
                }?.second
                    ?: TypingCorrector(
                        model.lm,
                        pairs,
                        config,
                        model.lexicon,
                        caution = { a, b, c, d -> typingCaution(a, b, c, d) }
                    ).also {
                        typingCorrector = model to it
                        typingPairsOf = pairs
                    }
                typingCaution = caution
                model.lm.setAbort(false)
                busy = model
                typingBusy = model
                coroutineScope {
                    var done = false
                    val watcher = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            if (!done) model.lm.setAbort(true)
                        }
                    }
                    try {
                        // voice input may have opened while the model loaded ([voiceOpened] aborts from here on)
                        if (dictating) return@coroutineScope TypingCheck(false)
                        val ctx = coroutineContext
                        val start = System.nanoTime()
                        // a failed evaluation (seen once, right after a check) loses the sentence, so
                        // clear the abort flag and try once more
                        val r = try {
                            corrector.check(text, from, to, finished, skip, { ctx.ensureActive() }, unknown)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            ctx.ensureActive()
                            // aborted for voice input (voiceOpened): no second try
                            if (dictating) throw e
                            Log.w(TAG, "Typing check failed (${e.message}), trying again")
                            model.lm.setAbort(false)
                            corrector.check(text, from, to, finished, skip, { ctx.ensureActive() }, unknown)
                        }
                        val ms = (System.nanoTime() - start) / 1_000_000
                        // counts and timings only, never the text
                        Log.i(TAG, "Typing check over ${to - from} chars in $ms ms" + if (r.fix != null) " (fix)" else "")
                        // voice input opened during it, so it may have been cut short
                        if (dictating) TypingCheck(false) else TypingCheck(true, r.fix, ms)
                    } finally {
                        done = true
                        watcher.cancel()
                        busy = null
                        typingBusy = null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (dictating) Log.i(TAG, "Typing check stopped for voice input") else Log.e(TAG, "Typing check failed", e)
                TypingCheck(false, failed = !dictating)
            } finally {
                releaseTypingSoon(60_000L)
            }
        }
    }

    private var typingCaution: (String, String, String, String) -> Double = { _, _, _, _ -> 0.0 }

    /** Frees the model after [delayMs] unless another check comes first (the text field closed). */
    fun releaseTypingSoon(delayMs: Long = 5_000L) {
        typingRelease?.cancel()
        typingRelease = scope.launch {
            delay(delayMs)
            // voice input may be using the same model (it is kept while the voice window is open)
            mutex.withLock { if (busy == null && !voiceOpen) releaseLocked() }
        }
    }

    /** Voice input's window is open, so the model stays loaded (typing's release timers wait). */
    @Volatile
    private var voiceOpen = false

    /**
     * The open voice input session (the voice window or a hold-to-talk dictation), while typing
     * checks wait (see [voiceOpened]). The latest one counts, since hold-to-talk hands over to the
     * voice window when the press was a tap.
     */
    private val voiceSession = AtomicReference<Any?>(null)
    private val dictating: Boolean get() = voiceSession.get() != null

    /** The model a running typing check uses (to abort it from [voiceOpened], never a voice correction). */
    @Volatile
    private var typingBusy: ContextModel? = null

    /**
     * Voice input [session] opened: typing checks stop until it closes ([voiceClosed]), and a
     * running one is aborted, so the speech model has the fast cores to itself (on a phone with 2
     * fast cores, a typing check alongside made Nemotron about twice as slow).
     */
    fun voiceOpened(session: Any) {
        voiceSession.set(session)
        typingBusy?.lm?.setAbort(true)
    }

    private var voiceRelease: Job? = null

    /**
     * Voice input [session] closed. The model stays loaded for [VOICE_KEEP_MS] in case another
     * dictation follows (reloading it takes about a second), then it is freed. A session that
     * another one has replaced since changes nothing.
     */
    fun voiceClosed(session: Any) {
        if (!voiceSession.compareAndSet(session, null)) return
        typingResumed?.invoke()
        voiceOpen = false
        voiceRelease?.cancel()
        voiceRelease = scope.launch {
            delay(VOICE_KEEP_MS)
            mutex.withLock { if (busy == null && !voiceOpen) releaseLocked() }
        }
    }

    /**
     * Builds the emoji names in the background a few seconds after the keyboard starts, so the
     * first dictation doesn't wait for them (building them takes several seconds on a phone).
     */
    fun warmUp(context: Context) {
        if (!deviceSupported) return
        val app = context.applicationContext
        scope.launch {
            // (the settings are read after the wait, once they have loaded)
            delay(3_000L)
            if (!app.getSetting(CONTEXT_MODEL_ENABLED) || !app.getSetting(SMART_CORRECTIONS_EMOJI)) return@launch
            EmojiNameDefaults.nameMap(app, app.getSetting(SMART_CORRECTIONS_EMOJI_LIST))
        }
    }

    /**
     * Starts loading the model for [locale] in the background (voice input just opened), unless it
     * has nothing to do for a voice model with [profile].
     */
    fun preload(context: Context, locale: Locale, profile: VoiceModelProfile, punctuationRan: Boolean) {
        // (only for an open session, which frees the model when it closes)
        if (!dictating) return
        val pkg = packageFor(context, locale) ?: return
        if (!optionsFor(context, profile, punctuationRan).any) return
        voiceOpen = true
        voiceRelease?.cancel()
        // the emoji names are built now too, so the first dictation doesn't wait for them
        if (context.getSetting(SMART_CORRECTIONS_EMOJI)) scope.launch {
            EmojiNameDefaults.nameMap(context, context.getSetting(SMART_CORRECTIONS_EMOJI_LIST))
        }
        scope.launch {
            try {
                mutex.withLock { obtainLocked(pkg) }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to preload context model ${pkg.id}", e)
            }
        }
    }

    /**
     * Frees the model (voice input closed, or a package was deleted). A correction that is still
     * running is aborted first, and it then returns its input unchanged.
     */
    fun release() {
        busy?.lm?.setAbort(true)
        scope.launch {
            mutex.withLock { releaseLocked() }
        }
    }

    private fun releaseLocked() {
        typingCorrector = null
        loaded?.let {
            it.model.close()
            Log.i(TAG, "Released context model ${it.id}")
        }
        loaded = null
    }

    private suspend fun correct(
        context: Context,
        pkg: InstalledModelPackage,
        text: String,
        segments: List<String>,
        options: SmartCorrectionOptions,
        personal: PersonalDictionary,
        left: String = "",
        queued: Long = System.nanoTime(),
    ): String = mutex.withLock {
        run {
            val start = System.nanoTime()
            DictationRecorder.note(context, "context_wait_ms", (start - queued) / 1_000_000)
            DictationRecorder.note(context, "context_left_chars", left.length.toLong())
            val model = obtainLocked(pkg)
            DictationRecorder.note(context, "context_load_ms", (System.nanoTime() - start) / 1_000_000)
            model.lm.setAbort(false)
            busy = model
            coroutineScope {
                // abort the native evaluation (which does not check for cancellation) when the
                // voice input session is cancelled
                var done = false
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!done) model.lm.setAbort(true)
                    }
                }
                try {
                    val ctx = coroutineContext
                    val result = model.rescorer(options, personal).correctAll(text, segments, { ctx.ensureActive() }, left)
                    Log.i(TAG, "Context pass over ${text.length} chars in ${(System.nanoTime() - start) / 1_000_000} ms" +
                        if (result != text) " (changed)" else "")
                    result
                } finally {
                    done = true
                    watcher.cancel()
                    busy = null
                }
            }
        }
        // the model stays loaded for the next dictation (voiceClosed frees it a little later)
    }

    private fun obtainLocked(pkg: InstalledModelPackage): ContextModel {
        val stamp = pkg.dir.lastModified()
        loaded?.let {
            if (it.id == pkg.id && it.dir == pkg.dir && it.stamp == stamp) return it.model
            it.model.close()
            loaded = null
        }
        val start = System.nanoTime()
        val config = ContextModelFiles.loadConfig(pkg.dir)
        val cpus = FastCores.fastest(config.threads)
        val model = ContextModel.load(pkg.dir, config.threads, cpus)
        Log.i(TAG, "Loaded context model ${pkg.id} (${model.lm.description}) in ${(System.nanoTime() - start) / 1_000_000} ms" +
            (cpus?.let { ", cpus ${it.joinToString(",")}" } ?: ""))
        loaded = Loaded(pkg.id, pkg.dir, stamp, model)
        return model
    }
}
