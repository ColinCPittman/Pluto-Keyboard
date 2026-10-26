package org.futo.inputmethod.latin.uix.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ContextModels
import org.futo.inputmethod.latin.uix.PunctuationModels
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.VoiceInputEngines
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.voiceinput.shared.modelpackage.ModelPackages

/**
 * Pluto: the setup tutorial. The first time settings open (with no models installed), it walks you
 * through getting each model: Languages & Models, the model's row, the release page, the import,
 * then the setting the model switched on. Each step spotlights what to tap, and every step can be
 * skipped (or the whole tutorial). Help can start it again.
 */

/** The step the tutorial is on ([SetupTourStep] name), "" before it ever ran, or [TOUR_DONE]. */
val SETUP_TOUR_STEP = SettingsKey(
    key = stringPreferencesKey("setup_tour_step"),
    default = ""
)

private const val TOUR_DONE = "done"

/** The saved step's prefix while the tutorial is replayed from Help. */
private const val REPLAY_PREFIX = "replay:"

enum class TourModel { Voice, Punctuation, SmartCorrections }

private enum class StepKind { Row, Download, Import, Setting }

/** Targets a step spotlights. A screen marks its target with [tourTarget]. */
object TourTargets {
    const val VOICE_ROW = "tour_voice_row"
    const val PUNCTUATION_ROW = "tour_punctuation_row"
    const val SMART_CORRECTIONS_ROW = "tour_smart_corrections_row"
    const val RELEASE_PAGE = "tour_release_page"
    const val IMPORT = "tour_import"
    const val PUNCTUATION_SETTING = "tour_punctuation_setting"
    const val SMART_CORRECTIONS_VOICE = "tour_smart_corrections_voice"
    const val SMART_CORRECTIONS_TYPING = "tour_smart_corrections_typing"
}

private const val ROUTE_LANGUAGES = "languages"

enum class SetupTourStep(
    val model: TourModel,
    private val kind: StepKind,
    val target: String,
    val title: Int,
    val text: Int,
    /** For setting steps, the settings page the step is on. */
    val settingRoute: String? = null
) {
    VoiceRow(TourModel.Voice, StepKind.Row, TourTargets.VOICE_ROW, R.string.tour_voice_row_title, R.string.tour_voice_row_text),
    VoiceDownload(TourModel.Voice, StepKind.Download, TourTargets.RELEASE_PAGE, R.string.tour_voice_download_title, R.string.tour_voice_download_text),
    VoiceImport(TourModel.Voice, StepKind.Import, TourTargets.IMPORT, R.string.tour_import_title, R.string.tour_voice_import_text),

    PunctuationRow(TourModel.Punctuation, StepKind.Row, TourTargets.PUNCTUATION_ROW, R.string.tour_punctuation_row_title, R.string.tour_punctuation_row_text),
    PunctuationDownload(TourModel.Punctuation, StepKind.Download, TourTargets.RELEASE_PAGE, R.string.tour_punctuation_download_title, R.string.tour_punctuation_download_text),
    PunctuationImport(TourModel.Punctuation, StepKind.Import, TourTargets.IMPORT, R.string.tour_import_title, R.string.tour_punctuation_import_text),
    PunctuationSetting(TourModel.Punctuation, StepKind.Setting, TourTargets.PUNCTUATION_SETTING, R.string.tour_punctuation_setting_title, R.string.tour_punctuation_setting_text, "voiceInput"),

    SmartCorrectionsRow(TourModel.SmartCorrections, StepKind.Row, TourTargets.SMART_CORRECTIONS_ROW, R.string.tour_smart_corrections_row_title, R.string.tour_smart_corrections_row_text),
    SmartCorrectionsDownload(TourModel.SmartCorrections, StepKind.Download, TourTargets.RELEASE_PAGE, R.string.tour_smart_corrections_download_title, R.string.tour_smart_corrections_download_text),
    SmartCorrectionsImport(TourModel.SmartCorrections, StepKind.Import, TourTargets.IMPORT, R.string.tour_import_title, R.string.tour_smart_corrections_import_text),
    SmartCorrectionsVoice(TourModel.SmartCorrections, StepKind.Setting, TourTargets.SMART_CORRECTIONS_VOICE, R.string.tour_smart_corrections_voice_title, R.string.tour_smart_corrections_voice_text, "smartCorrections"),
    SmartCorrectionsTyping(TourModel.SmartCorrections, StepKind.Setting, TourTargets.SMART_CORRECTIONS_TYPING, R.string.tour_smart_corrections_typing_title, R.string.tour_smart_corrections_typing_text, "typingSmartCorrections"),
    /** The last step, back on the Smart corrections page, pointing out its other switches (nothing spotlighted). */
    SmartCorrectionsExplore(TourModel.SmartCorrections, StepKind.Setting, "", R.string.tour_smart_corrections_explore_title, R.string.tour_smart_corrections_explore_text, "smartCorrections");

    val isSetting get() = kind == StepKind.Setting
    val isLast get() = this == SmartCorrectionsExplore
    val isRow get() = kind == StepKind.Row
    val isImport get() = kind == StepKind.Import
    val isDownload get() = kind == StepKind.Download

    /** Whether the step belongs on the screen [route] (a NavHost destination route). */
    fun isOn(route: String?): Boolean {
        if (route == null) return false
        return when (kind) {
            StepKind.Row -> route == ROUTE_LANGUAGES
            StepKind.Download, StepKind.Import -> route.contains(modelScreenRoute(model))
            StepKind.Setting -> route == settingRoute
        }
    }
}

/** The part of the model screen's (typed) route that names it. */
private fun modelScreenRoute(model: TourModel) = when (model) {
    TourModel.Voice -> ".Route.VoiceModel"
    TourModel.Punctuation -> ".Route.PunctuationModel"
    TourModel.SmartCorrections -> ".Route.ContextModel"
}

private fun modelInstalled(context: Context, model: TourModel) = when (model) {
    TourModel.Voice -> VoiceInputEngines.installedMoonshineModels(context).isNotEmpty()
    TourModel.Punctuation -> PunctuationModels.installed(context).isNotEmpty()
    TourModel.SmartCorrections -> ContextModels.installed(context).isNotEmpty()
}

object SetupTour {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The step being shown, or null when the tutorial isn't running. */
    val step = mutableStateOf<SetupTourStep?>(null)

    /** Where each marked target is on screen (window coordinates). */
    internal val targets = mutableStateMapOf<String, Rect>()

    /**
     * Replayed from Help: every step shows, even for models already installed (as a reminder of
     * where everything is), and their steps move on with Next instead of skipping the model.
     */
    val replay = mutableStateOf(false)

    /** Whether the current step's model was already installed when the step came up. */
    private var hadModelAtStep = false

    private var loaded = false

    /** Reads the saved step once, and starts the tutorial on a first run with no models installed. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSetting(SETUP_TOUR_STEP)
        when {
            saved == TOUR_DONE -> {}
            saved.isEmpty() -> {
                val anyModel = TourModel.entries.any { modelInstalled(context, it) }
                // people who already set things up (an update, or an imported config) don't need it
                if (anyModel) save(context, TOUR_DONE) else go(context, SetupTourStep.VoiceRow)
            }
            else -> {
                replay.value = saved.startsWith(REPLAY_PREFIX)
                val name = saved.removePrefix(REPLAY_PREFIX)
                step.value = SetupTourStep.entries.firstOrNull { it.name == name }?.let { applicable(context, it) }
                hadModelAtStep = step.value?.let { modelInstalled(context, it.model) } ?: false
            }
        }
    }

    /** Help's "Show the setup tutorial again". */
    fun restart(context: Context) {
        loaded = true
        replay.value = true
        go(context, SetupTourStep.VoiceRow)
    }

    fun skipTutorial(context: Context) {
        step.value = null
        replay.value = false
        save(context, TOUR_DONE)
    }

    /**
     * Skip step. Skipping a row or an import skips that model (its later steps need it), and skipping
     * the download moves on to the import (the file may already be on the phone).
     */
    fun skipStep(context: Context) {
        val current = step.value ?: return
        if (current.isRow || current.isImport) go(context, firstStepAfter(current.model)) else next(context)
    }

    /** The current step is done. */
    fun next(context: Context) {
        val current = step.value ?: return
        go(context, SetupTourStep.entries.getOrNull(current.ordinal + 1))
    }

    /** Back to the voice model's row (Set up Moonshine, on a step that notes it was skipped). */
    fun setUpMoonshine(context: Context) {
        go(context, SetupTourStep.VoiceRow)
    }

    /** Back to the row of the current step's model (the paused bar's Continue). */
    fun backToRow(context: Context) {
        val current = step.value ?: return
        go(context, SetupTourStep.entries.first { it.model == current.model && it.isRow })
    }

    /** Called when the release page link is opened from a model screen. */
    fun releasePageOpened(context: Context) {
        if (step.value?.isDownload == true) next(context)
    }

    /** Models changed (an import finished): an import step whose model is now installed is done. */
    fun modelsChanged(context: Context) {
        val current = step.value ?: return
        // on a replay, a model that was already there doesn't count as just imported
        if (hadModelAtStep) return
        if ((current.isImport || current.isDownload) && modelInstalled(context, current.model)) {
            go(context, SetupTourStep.entries.firstOrNull { it.model == current.model && it.isSetting } ?: firstStepAfter(current.model))
        }
    }

    private fun firstStepAfter(model: TourModel) = SetupTourStep.entries.firstOrNull { it.model.ordinal > model.ordinal }

    private fun go(context: Context, to: SetupTourStep?) {
        val next = to?.let { applicable(context, it) }
        hadModelAtStep = next?.let { modelInstalled(context, it.model) } ?: false
        step.value = next
        if (next == null) {
            replay.value = false
            save(context, TOUR_DONE)
            Toast.makeText(context, context.getString(R.string.tour_finished), Toast.LENGTH_LONG).show()
        } else {
            save(context, (if (replay.value) REPLAY_PREFIX else "") + next.name)
        }
    }

    /** [from], or the first step from it that applies (getting a model that's already there is skipped). */
    private fun applicable(context: Context, from: SetupTourStep): SetupTourStep? {
        var s: SetupTourStep? = from
        while (s != null) {
            val skip = when {
                s.model == TourModel.SmartCorrections && !ContextModels.deviceSupported -> true
                // a model already there needs no getting, so its steps go straight to the settings it
                // turns on (except on a replay from Help, which shows everything as a reminder)
                !replay.value && (s.isRow || s.isDownload || s.isImport) && modelInstalled(context, s.model) -> true
                s.isSetting && !modelInstalled(context, s.model) -> true
                // the punctuation switch is greyed out while Whisper is the voice model
                s == SetupTourStep.PunctuationSetting && !modelInstalled(context, TourModel.Voice) -> true
                else -> false
            }
            if (!skip) return s
            s = SetupTourStep.entries.getOrNull(s.ordinal + 1)
        }
        return null
    }

    private fun save(context: Context, value: String) {
        scope.launch { context.setSetting(SETUP_TOUR_STEP, value) }
    }
}

/** Marks what a tutorial step spotlights, and scrolls it into view when that step comes up. */
fun Modifier.tourTarget(key: String): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val current = SetupTour.step.value?.target == key
    LaunchedEffect(current) {
        if (current) {
            delay(250)
            requester.bringIntoView()
        }
    }
    DisposableEffect(key) {
        onDispose { SetupTour.targets.remove(key) }
    }
    this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { SetupTour.targets[key] = it.boundsInWindow() }
}

/** Marks a whole setting row as a tutorial target. */
fun UserSetting.tourTarget(key: String): UserSetting = copy(component = {
    Box(Modifier.tourTarget(key)) { this@tourTarget.component() }
})

/**
 * The tutorial over the settings screens: the screen dimmed except the step's target, and a card
 * with the step's text. Taps go through to the screen (the dim doesn't catch them), so the
 * highlighted item works as usual.
 */
@Composable
fun SetupTourOverlay(navController: NavHostController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SetupTour.load(context) }

    val changes by ModelPackages.changes.collectAsState()
    LaunchedEffect(changes) { SetupTour.modelsChanged(context) }

    val step = SetupTour.step.value ?: return
    val entry by navController.currentBackStackEntryFlow.collectAsState(initial = navController.currentBackStackEntry)
    val route = entry?.destination?.route

    // a setting step comes up on its own page, and a row step back on Languages & Models
    LaunchedEffect(step) {
        if (step.isOn(navController.currentBackStackEntry?.destination?.route)) return@LaunchedEffect
        if (step.isSetting) {
            if (!navController.popBackStack(step.settingRoute!!, inclusive = false)) navController.navigate(step.settingRoute)
        } else if (step.isDownload || step.isImport) {
            // Next on a row (a replay) opens the model's page, like tapping the row does
            navController.navigate(when (step.model) {
                TourModel.Voice -> Route.VoiceModel("en")
                TourModel.Punctuation -> Route.PunctuationModel("en")
                TourModel.SmartCorrections -> Route.ContextModel("en")
            })
        } else if (step.isRow) {
            if (!navController.popBackStack(ROUTE_LANGUAGES, inclusive = false)) navController.navigate(ROUTE_LANGUAGES)
        }
    }
    // on a row step, opening that model's screen is the step done
    LaunchedEffect(step, route) {
        val next = SetupTourStep.entries.getOrNull(step.ordinal + 1)
        if (step.isRow && next != null && next.model == step.model && next.isOn(route)) SetupTour.next(context)
    }

    if (!step.isOn(route)) {
        TourPausedBar(step, navController)
        return
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val ringColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val heightPx = constraints.maxHeight.toFloat()
        // a target still off screen (it scrolls into view in a moment) gets no spotlight yet
        val target = SetupTour.targets[step.target]?.translate(-origin)
            ?.takeIf { it.top >= 0f && it.bottom <= heightPx }
        val pad = with(density) { 6.dp.toPx() }
        if (step.target.isNotEmpty()) Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.55f))
            if (target != null) {
                val hole = Rect(target.left + pad, target.top, target.right - pad, target.bottom)
                val corner = CornerRadius(16.dp.toPx())
                drawRoundRect(Color.Transparent, hole.topLeft, hole.size, corner, blendMode = BlendMode.Clear)
                drawRoundRect(ringColor, hole.topLeft, hole.size, corner, style = Stroke(2.dp.toPx()))
            }
        }

        val below = target == null || target.center.y < heightPx / 2
        val gap = with(density) { 12.dp.toPx() }
        val cardModifier = if (target == null) {
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        } else if (below) {
            Modifier.align(Alignment.TopCenter).padding(top = with(density) { (target.bottom + gap).coerceAtLeast(0f).toDp() })
        } else {
            Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { (heightPx - target.top + gap).coerceAtLeast(0f).toDp() })
        }
        TourCard(step, cardModifier)
    }
}

@Composable
private fun TourCard(step: SetupTourStep, modifier: Modifier) {
    val context = LocalContext.current
    // Moonshine skipped (voice typing uses Whisper), so punctuation has nothing to do and Smart
    // corrections less, and these steps say so
    val changes by ModelPackages.changes.collectAsState()
    val noMoonshine = remember(changes) { !modelInstalled(context, TourModel.Voice) }
    val whisperNote = noMoonshine && step.model != TourModel.Voice
    val title = when {
        whisperNote && step == SetupTourStep.PunctuationRow -> R.string.tour_punctuation_whisper_title
        else -> step.title
    }
    val text = when {
        !whisperNote -> step.text
        step == SetupTourStep.PunctuationRow -> R.string.tour_punctuation_whisper_text
        step == SetupTourStep.SmartCorrectionsRow -> R.string.tour_smart_corrections_whisper_text
        step == SetupTourStep.SmartCorrectionsVoice -> R.string.tour_smart_corrections_voice_whisper_text
        else -> step.text
    }
    // the punctuation step is only a note then, so it has Next instead of Skip step
    val whisperNext = whisperNote && step == SetupTourStep.PunctuationRow
    // on a replay, a model you already have moves on one step at a time
    val replayNext = SetupTour.replay.value && !step.isSetting && !whisperNext &&
        remember(changes, step) { modelInstalled(context, step.model) }
    val nextOnly = step.isSetting || whisperNext || replayNext
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(title), style = Typography.Body.MediumMl, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(text),
                style = Typography.SmallMl,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp)
            )
            if (whisperNote && step.isRow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { SetupTour.setUpMoonshine(context) }) {
                        Text(stringResource(R.string.tour_set_up_moonshine))
                    }
                }
            }
            if (step.isLast) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { SetupTour.next(context) }, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(stringResource(R.string.tour_finish))
                    }
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { SetupTour.skipTutorial(context) }) {
                    Text(stringResource(R.string.tour_skip_tutorial))
                }
                Spacer(Modifier.weight(1f))
                if (nextOnly) {
                    Button(onClick = { if (whisperNext) SetupTour.skipStep(context) else SetupTour.next(context) }) {
                        Text(stringResource(R.string.tour_next))
                    }
                } else {
                    TextButton(onClick = { SetupTour.skipStep(context) }) { Text(stringResource(R.string.tour_skip_step)) }
                }
            }
        }
    }
}

/** Shown when you've left the step's screen, to go back to it or end the tutorial. */
@Composable
private fun TourPausedBar(step: SetupTourStep, navController: NavHostController) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tour_paused),
                    style = Typography.SmallMl,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                )
                TextButton(onClick = { SetupTour.skipTutorial(context) }) {
                    Text(stringResource(R.string.tour_skip_tutorial))
                }
                Button(onClick = {
                    if (step.isSetting) {
                        navController.navigate(step.settingRoute!!)
                    } else {
                        // a download or import step goes back to its row (the model screen needs a language)
                        SetupTour.backToRow(context)
                        if (!navController.popBackStack(ROUTE_LANGUAGES, inclusive = false)) navController.navigate(ROUTE_LANGUAGES)
                    }
                }) { Text(stringResource(R.string.tour_continue)) }
            }
        }
    }
}
