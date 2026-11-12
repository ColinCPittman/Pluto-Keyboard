package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import org.futo.inputmethod.latin.uix.settings.Route
import androidx.compose.foundation.layout.Box
import org.futo.inputmethod.latin.uix.settings.tourTarget
import org.futo.inputmethod.latin.uix.settings.TourTargets
import org.futo.inputmethod.latin.uix.settings.SetupTour
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.CONTEXT_MODEL_ENABLED
import org.futo.inputmethod.latin.uix.CONTEXT_MODEL_ID
import org.futo.inputmethod.latin.uix.ContextModels
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.MOONSHINE_ACCELERATOR
import org.futo.inputmethod.latin.uix.PlutoLinks
import org.futo.inputmethod.latin.uix.MOONSHINE_AUTO_STOP_MS
import org.futo.inputmethod.latin.uix.MOONSHINE_LAST_TIMING
import org.futo.inputmethod.latin.uix.MOONSHINE_LIVE_TEXT
import org.futo.inputmethod.latin.uix.MOONSHINE_MODEL_ID
import org.futo.inputmethod.latin.uix.MOONSHINE_REFRESH_MS
import org.futo.inputmethod.latin.uix.MOONSHINE_THREADS
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.PUNCTUATION_MODEL_ID
import org.futo.inputmethod.latin.uix.PUNCTUATION_RESTORATION_ENABLED
import org.futo.inputmethod.latin.uix.PunctuationModels
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_COMMANDS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_FILLERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_NAMES
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_NUMBERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SELF_CORRECTIONS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SOUND_ALIKE
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.TRANSCRIBE_MODEL_ID
import org.futo.inputmethod.latin.uix.VOICE_ENGINE_MOONSHINE
import org.futo.inputmethod.latin.uix.VOICE_ENGINE_TRANSCRIBE
import org.futo.inputmethod.latin.uix.VOICE_ENGINE_WHISPER
import org.futo.inputmethod.latin.uix.VOICE_INPUT_ENGINE
import org.futo.inputmethod.latin.uix.VOICE_INPUT_SHOW_TIMING
import org.futo.inputmethod.latin.uix.VoiceInputEngines
import org.futo.inputmethod.latin.uix.findActivity
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.DropDownPicker
import org.futo.inputmethod.latin.uix.settings.IMPORT_RESOURCE_FILE_REQUEST
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.engine.RecognitionEngineConfiguration
import org.futo.voiceinput.shared.engine.VoiceModelProfile
import org.futo.voiceinput.shared.modelpackage.InstalledModelPackage
import org.futo.voiceinput.shared.modelpackage.ModelPackageKinds
import org.futo.voiceinput.shared.modelpackage.ModelPackages
import org.futo.voiceinput.shared.moonshine.core.CpuTopology
import org.futo.voiceinput.shared.moonshine.core.OrtAccelerator
import org.futo.voiceinput.shared.transcribe.TranscribeModelFiles
import java.util.Locale

/*
 * Fork additions to voice input settings:
 *  - which voice model (Whisper, a Moonshine package or a Nemotron / Parakeet package), punctuation model and context model a language uses
 *    live in Languages & Models (per language card, see [VoiceModelScreen], [PunctuationModelScreen] and
 *    [ContextModelScreen]).
 *  - the punctuation and context pass on/off switches live on the Voice Input page
 *    ([PunctuationRestorationSetting], [ContextModelSetting]).
 *  - benchmarking options live under Voice Input -> Advanced options ([VoiceInputAdvancedMenu]).
 */

/** Opens the system file picker for a model file or model package, and the result goes to ImportResourceActivity. */
fun openModelPackageImporter(context: Context) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
        )
    }

    findActivity(context)!!.startActivityForResult(intent, IMPORT_RESOURCE_FILE_REQUEST)
}

/** Package name as shown in settings, since punctuation/context packages are already under a "... Model" heading. */
val InstalledModelPackage.displayName: String
    get() = when (kind) {
        ModelPackageKinds.PUNCTUATION -> name.removePrefix("Punctuation: ")
        ModelPackageKinds.CONTEXT -> name.removePrefix("Context: ")
        else -> name
    }

private val InstalledModelPackage.sizeText: String get() = "%.0f MB".format(sizeBytes / 1e6)

/** Name of the voice input model [locale] uses, for the Languages & Models card, or null if none. */
fun voiceInputModelDisplayName(context: Context, locale: Locale): String? =
    when (val engine = VoiceInputEngines.resolve(context, locale)) {
        is RecognitionEngineConfiguration.MoonshineStreaming -> engine.modelName
        is RecognitionEngineConfiguration.Transcribe -> engine.modelName
        RecognitionEngineConfiguration.Whisper ->
            ResourceHelper.tryFindingVoiceInputModelForLocale(context, locale)?.name?.let { context.getString(it) }
    }

/**
 * The voice model voice typing in a language uses, and what it does well itself (see [currentVoiceModel]).
 * Only what settings need is exposed: the engine's other options (threads, live text) aren't part of
 * the cache key, so they may be out of date.
 */
class CurrentVoiceModel(private val engine: RecognitionEngineConfiguration) {
    /** The package name, or "Whisper" (Whisper's file names, like "English-39", mean little here). */
    val name: String get() = when (engine) {
        is RecognitionEngineConfiguration.MoonshineStreaming -> engine.modelName
        is RecognitionEngineConfiguration.Transcribe -> engine.modelName
        RecognitionEngineConfiguration.Whisper -> "Whisper"
    }
    val profile: VoiceModelProfile get() = engine.profile
    val isWhisper: Boolean get() = engine is RecognitionEngineConfiguration.Whisper
    val isMoonshine: Boolean get() = engine is RecognitionEngineConfiguration.MoonshineStreaming
    val isTranscribe: Boolean get() = engine is RecognitionEngineConfiguration.Transcribe
}

/**
 * The voice model [locale] uses (the picked one, or Whisper when it is missing or doesn't handle the
 * language), read live so settings redraw when the choice or the installed models change. Settings
 * use it to say which features the model makes unnecessary instead of offering them.
 */
@Composable
fun currentVoiceModel(locale: Locale = Locale.ENGLISH): CurrentVoiceModel {
    val context = LocalContext.current
    val engine = useDataStoreValue(VOICE_INPUT_ENGINE)
    val moonshineId = useDataStoreValue(MOONSHINE_MODEL_ID)
    val transcribeId = useDataStoreValue(TRANSCRIBE_MODEL_ID)
    val changes by ModelPackages.changes.collectAsState()
    return remember(engine, moonshineId, transcribeId, changes, locale) {
        // resolving reads the model packages from disk, so every row of a page shares one answer
        val key = listOf(engine, moonshineId, transcribeId, changes)
        currentVoiceModelCache[locale]?.takeIf { it.first == key }?.second
            ?: CurrentVoiceModel(VoiceInputEngines.resolve(context, locale))
                .also { currentVoiceModelCache[locale] = key to it }
    }
}

// one answer per language (the Languages & Models page shows several); only used from composition
private val currentVoiceModelCache = HashMap<Locale, Pair<List<Any?>, CurrentVoiceModel>>()

/**
 * Pluto: shown when you tap the punctuation model or switch while the voice model of [locale]
 * punctuates by itself. [onOpenAnyway] (opening the punctuation model page, to import or delete one)
 * is optional.
 */
@Composable
fun PunctuationIsForMoonshineDialog(
    onDismiss: () -> Unit,
    onOpenAnyway: (() -> Unit)? = null,
    locale: Locale = Locale.ENGLISH,
) {
    val navController = LocalNavController.current
    val model = currentVoiceModel(locale)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.punctuation_whisper_dialog_title)) },
        text = { Text(stringResource(R.string.punctuation_whisper_dialog_text, model.name)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                navController?.navigate(Route.VoiceModel(locale.toLanguageTag()))
            }) { Text(stringResource(R.string.punctuation_whisper_dialog_choose)) }
        },
        dismissButton = {
            Row {
                if (onOpenAnyway != null) {
                    TextButton(onClick = { onDismiss(); onOpenAnyway() }) {
                        Text(stringResource(R.string.punctuation_whisper_dialog_open))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.punctuation_whisper_dialog_close)) }
            }
        }
    )
}

/** Name of the punctuation model [locale] uses, for the Languages & Models card, or null if none. */
fun punctuationModelDisplayName(context: Context, locale: Locale): String? {
    if (!context.getSetting(PUNCTUATION_RESTORATION_ENABLED)) return null
    val pkg = PunctuationModels.selected(context) ?: return null
    return if (PunctuationModels.supportsLocale(pkg, locale)) pkg.displayName else null
}

/** Name of the context model [locale] uses, for the Languages & Models card, or null if none. */
fun contextModelDisplayName(context: Context, locale: Locale): String? {
    if (!context.getSetting(CONTEXT_MODEL_ENABLED)) return null
    val pkg = ContextModels.selected(context) ?: return null
    return if (ContextModels.supportsLocale(pkg, locale)) pkg.displayName else null
}

/** Pluto: link to the models page with Pluto's model packages (opens the browser, because the app has no internet access). */
@Composable
private fun PlutoReleasePageItem() {
    val context = LocalContext.current
    Box(Modifier.tourTarget(TourTargets.RELEASE_PAGE)) {
        NavigationItem(
            title = stringResource(R.string.pluto_voice_setup_release_page),
            subtitle = stringResource(R.string.pluto_voice_setup_release_page_subtitle),
            style = NavigationItemStyle.ExternalLink,
            navigate = {
                SetupTour.releasePageOpened(context)
                context.openURI(PlutoLinks.MODELS_PAGE, true)
            }
        )
    }
}

@Composable
private fun RadioSettingItem(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    SettingItem(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        icon = { RadioButton(selected = selected, onClick = null) }
    ) { }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun DeleteModelPackageDialog(pkg: InstalledModelPackage, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.voice_models_delete_confirm, pkg.displayName)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (pkg.kind == ModelPackageKinds.PUNCTUATION) PunctuationModels.release()
                if (pkg.kind == ModelPackageKinds.CONTEXT) ContextModels.release()
                ModelPackages.delete(context, pkg.kind, pkg.id)
                onDeleted()
            }) { Text(stringResource(R.string.voice_models_delete_yes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_models_delete_no)) }
        }
    )
}

/** Languages & Models -> Voice Input Model, to choose between Whisper and installed Nemotron, Parakeet and Moonshine models for [locale]. */
@Composable
fun VoiceModelScreen(navController: NavHostController, locale: Locale) {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val engine = useDataStore(VOICE_INPUT_ENGINE)
    val moonshineId = useDataStore(MOONSHINE_MODEL_ID)

    val isEnglish = locale.language == "en"
    val moonshineModels = remember(changes) {
        if (isEnglish) VoiceInputEngines.installedMoonshineModels(context) else emptyList()
    }
    val selectedMoonshine = moonshineModels.firstOrNull { it.id == moonshineId.value } ?: moonshineModels.firstOrNull()
    val usingMoonshine = isEnglish && engine.value == VOICE_ENGINE_MOONSHINE && selectedMoonshine != null

    // Nemotron / Parakeet packages that handle this language (English ones, or multilingual Nemotron 3.5)
    val transcribeId = useDataStore(TRANSCRIBE_MODEL_ID)
    val allTranscribeModels = remember(changes) { VoiceInputEngines.installedTranscribeModels(context) }
    val transcribeModels = allTranscribeModels.filter { VoiceInputEngines.transcribeSupports(it, locale) }
    // the same pick as VoiceInputEngines.selectedTranscribeModel: when it is for another language,
    // this language uses Whisper, so Whisper shows as selected
    val selectedTranscribe = (allTranscribeModels.firstOrNull { it.id == transcribeId.value } ?: allTranscribeModels.firstOrNull())
        ?.takeIf { pkg -> transcribeModels.any { it.id == pkg.id } }
    val usingTranscribe = engine.value == VOICE_ENGINE_TRANSCRIBE && selectedTranscribe != null

    val whisperModel = remember(changes) { ResourceHelper.tryFindingVoiceInputModelForLocale(context, locale) }
    val whisperImported = remember(changes) {
        ResourceHelper.findFileForKind(context, locale, FileKind.VoiceInput)?.exists() == true
    }

    var deleteTarget by remember { mutableStateOf<InstalledModelPackage?>(null) }
    deleteTarget?.let { pkg ->
        DeleteModelPackageDialog(pkg, onDismiss = { deleteTarget = null }) {
            if (moonshineId.value == pkg.id) moonshineId.setValue("")
            if (transcribeId.value == pkg.id) transcribeId.setValue("")
        }
    }

    ScrollableList {
        ScreenTitle(stringResource(R.string.voice_models_title, locale.getDisplayLanguage(locale)), showBack = true, navController)

        SectionLabel(stringResource(R.string.voice_models_section_models))
        RadioSettingItem(
            title = stringResource(R.string.voice_models_whisper_title, whisperModel?.let { stringResource(it.name) }
                ?: stringResource(R.string.language_settings_resource_none)),
            subtitle = stringResource(R.string.voice_models_whisper_subtitle),
            selected = !usingMoonshine && !usingTranscribe,
            onClick = { engine.setValue(VOICE_ENGINE_WHISPER) }
        )
        transcribeModels.forEach { pkg ->
            val profile = remember(pkg) { TranscribeModelFiles.profile(pkg.manifest) }
            RadioSettingItem(
                title = pkg.displayName,
                subtitle = stringResource(
                    if (profile.streaming) R.string.voice_models_transcribe_streaming_subtitle else R.string.voice_models_transcribe_subtitle,
                    pkg.sizeText
                ),
                selected = usingTranscribe && pkg.id == selectedTranscribe?.id,
                onClick = {
                    transcribeId.setValue(pkg.id)
                    engine.setValue(VOICE_ENGINE_TRANSCRIBE)
                }
            )
        }
        moonshineModels.forEach { pkg ->
            RadioSettingItem(
                title = pkg.displayName,
                subtitle = stringResource(R.string.voice_models_moonshine_subtitle, pkg.sizeText),
                selected = usingMoonshine && pkg.id == selectedMoonshine?.id,
                onClick = {
                    moonshineId.setValue(pkg.id)
                    engine.setValue(VOICE_ENGINE_MOONSHINE)
                }
            )
        }
        if (isEnglish && moonshineModels.isEmpty() && transcribeModels.isEmpty()) {
            Tip(stringResource(R.string.voice_models_moonshine_none_installed))
        }

        SectionLabel(stringResource(R.string.voice_models_section_manage))
        Box(Modifier.tourTarget(TourTargets.IMPORT)) {
            NavigationItem(
                title = stringResource(R.string.voice_models_import),
                subtitle = stringResource(if (isEnglish) R.string.voice_models_import_subtitle_en else R.string.voice_models_import_subtitle),
                style = NavigationItemStyle.Misc,
                navigate = { openModelPackageImporter(context) }
            )
        }
        if (isEnglish) PlutoReleasePageItem()
        NavigationItem(
            title = stringResource(R.string.pluto_explore_whisper_models),
            style = NavigationItemStyle.Misc,
            navigate = { context.openURI(FileKind.VoiceInput.getAddonUrlForLocale(locale), true) }
        )
        if (whisperImported) {
            NavigationItem(
                title = stringResource(R.string.voice_models_remove_imported_whisper),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = { ResourceHelper.deleteResourceForLanguage(context, FileKind.VoiceInput, locale) }
            )
        }
        (transcribeModels + moonshineModels).forEach { pkg ->
            NavigationItem(
                title = stringResource(R.string.voice_models_delete, pkg.displayName),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = { deleteTarget = pkg }
            )
        }
    }
}

/** Languages & Models -> Punctuation Model, to choose the punctuation model used after dictation for [locale]. */
@Composable
fun PunctuationModelScreen(navController: NavHostController, locale: Locale) {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val enabled = useDataStore(PUNCTUATION_RESTORATION_ENABLED)
    val modelId = useDataStore(PUNCTUATION_MODEL_ID)

    val installed = remember(changes) { PunctuationModels.installed(context) }
    val forLocale = installed.filter { PunctuationModels.supportsLocale(it, locale) }
    val selected = installed.firstOrNull { it.id == modelId.value } ?: installed.firstOrNull()
    val active = if (enabled.value && selected != null && PunctuationModels.supportsLocale(selected, locale)) selected else null

    var deleteTarget by remember { mutableStateOf<InstalledModelPackage?>(null) }
    deleteTarget?.let { pkg ->
        DeleteModelPackageDialog(pkg, onDismiss = { deleteTarget = null }) {
            if (modelId.value == pkg.id) modelId.setValue("")
        }
    }

    ScrollableList {
        ScreenTitle(stringResource(R.string.punctuation_models_title, locale.getDisplayLanguage(locale)), showBack = true, navController)
        Tip(stringResource(R.string.punctuation_models_explanation))

        SectionLabel(stringResource(R.string.voice_models_section_models))
        RadioSettingItem(
            title = stringResource(R.string.punctuation_models_none),
            subtitle = stringResource(R.string.punctuation_models_none_subtitle),
            selected = active == null,
            onClick = { enabled.setValue(false) }
        )
        forLocale.forEach { pkg ->
            RadioSettingItem(
                title = pkg.displayName,
                subtitle = stringResource(
                    if (pkg.manifest.engine == "horizon") R.string.punctuation_models_horizon_subtitle
                    else R.string.punctuation_models_pcs_subtitle,
                    pkg.sizeText
                ),
                selected = active?.id == pkg.id,
                onClick = {
                    modelId.setValue(pkg.id)
                    enabled.setValue(true)
                }
            )
        }
        if (forLocale.isEmpty()) {
            Tip(stringResource(R.string.punctuation_models_none_installed))
        }

        SectionLabel(stringResource(R.string.voice_models_section_manage))
        Box(Modifier.tourTarget(TourTargets.IMPORT)) {
            NavigationItem(
                title = stringResource(R.string.punctuation_models_import),
                subtitle = stringResource(R.string.punctuation_models_import_subtitle),
                style = NavigationItemStyle.Misc,
                navigate = { openModelPackageImporter(context) }
            )
        }
        PlutoReleasePageItem()
        installed.forEach { pkg ->
            NavigationItem(
                title = stringResource(R.string.voice_models_delete, pkg.displayName),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = { deleteTarget = pkg }
            )
        }
    }
}

/** Languages & Models -> Context Model, to choose the model that fixes misheard words after dictation for [locale]. */
@Composable
fun ContextModelScreen(navController: NavHostController, locale: Locale) {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val enabled = useDataStore(CONTEXT_MODEL_ENABLED)
    val modelId = useDataStore(CONTEXT_MODEL_ID)

    val installed = remember(changes) { ContextModels.installed(context) }
    val forLocale = installed.filter { ContextModels.supportsLocale(it, locale) }
    val selected = installed.firstOrNull { it.id == modelId.value } ?: installed.firstOrNull()
    val active = if (enabled.value && selected != null && ContextModels.supportsLocale(selected, locale)) selected else null

    var deleteTarget by remember { mutableStateOf<InstalledModelPackage?>(null) }
    deleteTarget?.let { pkg ->
        DeleteModelPackageDialog(pkg, onDismiss = { deleteTarget = null }) {
            if (modelId.value == pkg.id) modelId.setValue("")
        }
    }

    ScrollableList {
        ScreenTitle(stringResource(R.string.context_models_title, locale.getDisplayLanguage(locale)), showBack = true, navController)
        Tip(stringResource(R.string.context_models_explanation))
        if (!ContextModels.deviceSupported) {
            Tip(stringResource(R.string.context_models_unsupported_device))
        }

        SectionLabel(stringResource(R.string.voice_models_section_models))
        RadioSettingItem(
            title = stringResource(R.string.context_models_none),
            subtitle = stringResource(R.string.context_models_none_subtitle),
            selected = active == null,
            onClick = { enabled.setValue(false) }
        )
        forLocale.forEach { pkg ->
            RadioSettingItem(
                title = pkg.displayName,
                subtitle = stringResource(R.string.context_models_rescore_subtitle, pkg.sizeText),
                selected = active?.id == pkg.id,
                onClick = {
                    modelId.setValue(pkg.id)
                    enabled.setValue(true)
                }
            )
        }
        if (forLocale.isEmpty()) {
            Tip(stringResource(R.string.context_models_none_installed))
        }

        SectionLabel(stringResource(R.string.voice_models_section_manage))
        Box(Modifier.tourTarget(TourTargets.IMPORT)) {
            NavigationItem(
                title = stringResource(R.string.context_models_import),
                subtitle = stringResource(R.string.context_models_import_subtitle),
                style = NavigationItemStyle.Misc,
                navigate = { openModelPackageImporter(context) }
            )
        }
        PlutoReleasePageItem()
        installed.forEach { pkg ->
            NavigationItem(
                title = stringResource(R.string.voice_models_delete, pkg.displayName),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = { deleteTarget = pkg }
            )
        }
    }
}

internal val visibilityCheckBuiltinVoiceInput = @Composable {
    useDataStoreValue(USE_SYSTEM_VOICE_INPUT) == false
}

/** Voice Input page, which turns the punctuation second pass on or off (the model is chosen in Languages & Models). */
val PunctuationRestorationSetting = UserSetting(
    name = R.string.voice_input_settings_punctuation,
    subtitle = R.string.voice_input_settings_punctuation_subtitle,
    visibilityCheck = visibilityCheckBuiltinVoiceInput
) {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val hasModel = remember(changes) { PunctuationModels.installed(context).isNotEmpty() }
    // a voice model that punctuates well gets no punctuation pass (SmartFeaturePolicy), whatever the switch says
    val model = currentVoiceModel()
    val notNeeded = model.profile.punctuatesItself
    var explain by remember { mutableStateOf(false) }
    if (explain) PunctuationIsForMoonshineDialog(onDismiss = { explain = false })
    Box(if (hasModel && notNeeded) Modifier.clickable { explain = true } else Modifier) {
        SettingToggleDataStore(
            title = stringResource(R.string.voice_input_settings_punctuation),
            subtitle = when {
                !hasModel -> stringResource(R.string.voice_input_settings_punctuation_no_model)
                notNeeded -> stringResource(R.string.voice_input_settings_punctuation_whisper, model.name)
                else -> stringResource(R.string.voice_input_settings_punctuation_subtitle)
            },
            setting = PUNCTUATION_RESTORATION_ENABLED,
            disabled = !hasModel || notNeeded
        )
    }
}

@Composable
private fun <T> DropDownSetting(
    title: String,
    subtitle: String?,
    options: List<T>,
    selection: T?,
    onSet: (T) -> Unit,
    getDisplayName: (T) -> String,
    disabled: Boolean = false
) {
    // SettingItem shows either a subtitle or subcontent, so the description goes into the subcontent
    SettingItem(
        title = title,
        disabled = disabled,
        subcontent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subtitle != null) {
                    Text(subtitle, style = Typography.SmallMl, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropDownPicker(options, selection, onSet, getDisplayName, enabled = !disabled)
            }
        }
    ) { }
}

/** Auto-stop choice "off" in the Moonshine stop-after-silence picker. */
private const val AUTO_STOP_OFF = 0

/**
 * Voice Input -> Advanced options, covering how Moonshine streams (live text and refresh rate, greyed
 * out unless a Moonshine model is the voice model), stop after silence (greyed out for Whisper) and
 * benchmarking knobs for Moonshine on ONNX Runtime.
 */
val VoiceInputAdvancedMenu = UserSettingsMenu(
    title = R.string.voice_input_advanced_title,
    navPath = "voiceInputAdvanced", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            SectionLabel(stringResource(R.string.voice_input_advanced_section_live))
            if (!currentVoiceModel().isMoonshine) Tip(stringResource(R.string.voice_input_advanced_needs_moonshine))
        },

        UserSetting(
            name = R.string.voice_input_settings_moonshine_live_text,
            subtitle = R.string.voice_input_settings_moonshine_live_text_subtitle
        ) {
            val model = currentVoiceModel()
            if (model.isMoonshine) {
                SettingToggleDataStore(
                    title = stringResource(R.string.voice_input_settings_moonshine_live_text),
                    subtitle = stringResource(R.string.voice_input_settings_moonshine_live_text_subtitle),
                    setting = MOONSHINE_LIVE_TEXT,
                )
            } else {
                // locked: a streaming model always shows words as you talk, the others never do
                SettingToggleRaw(
                    title = stringResource(R.string.voice_input_settings_moonshine_live_text),
                    enabled = model.profile.streaming,
                    setValue = {},
                    subtitle = stringResource(
                        if (model.profile.streaming) R.string.voice_input_settings_live_text_always
                        else R.string.voice_input_settings_live_text_never,
                        model.name
                    ),
                    disabled = true,
                )
            }
        },

        UserSetting(
            name = R.string.voice_input_settings_moonshine_refresh,
            subtitle = R.string.voice_input_settings_moonshine_refresh_subtitle
        ) {
            val refresh = useDataStore(MOONSHINE_REFRESH_MS)
            val liveText = useDataStoreValue(MOONSHINE_LIVE_TEXT)
            val model = currentVoiceModel()
            val res = LocalResources.current
            val choices = VoiceInputEngines.REFRESH_CHOICES_MS
            DropDownSetting(
                title = stringResource(R.string.voice_input_settings_moonshine_refresh),
                subtitle = when {
                    model.isMoonshine -> stringResource(R.string.voice_input_settings_moonshine_refresh_subtitle)
                    model.profile.streaming -> stringResource(R.string.voice_input_settings_only_moonshine_refresh, model.name)
                    else -> stringResource(R.string.voice_input_settings_live_text_never, model.name)
                },
                options = choices,
                selection = choices.minBy { kotlin.math.abs(it - refresh.value) },
                onSet = { refresh.setValue(it) },
                getDisplayName = {
                    res.getString(
                        when (it) {
                            250 -> R.string.voice_input_settings_moonshine_refresh_fast
                            500 -> R.string.voice_input_settings_moonshine_refresh_normal
                            else -> R.string.voice_input_settings_moonshine_refresh_slow
                        },
                        it / 1000f
                    )
                },
                disabled = !model.isMoonshine || !liveText
            )
        },

        UserSetting(
            name = R.string.voice_input_settings_moonshine_auto_stop,
            subtitle = R.string.voice_input_settings_moonshine_auto_stop_subtitle
        ) {
            val autoStop = useDataStore(USE_VAD_AUTOSTOP)
            val silence = useDataStore(MOONSHINE_AUTO_STOP_MS)
            val res = LocalResources.current
            val choices = listOf(AUTO_STOP_OFF) + VoiceInputEngines.AUTO_STOP_CHOICES_MS
            val whisper = currentVoiceModel().isWhisper
            DropDownSetting(
                title = stringResource(R.string.voice_input_settings_moonshine_auto_stop),
                subtitle = stringResource(
                    if (whisper) R.string.voice_input_settings_auto_stop_whisper
                    else R.string.voice_input_settings_moonshine_auto_stop_subtitle
                ),
                options = choices,
                selection = if (!autoStop.value) AUTO_STOP_OFF
                    else VoiceInputEngines.AUTO_STOP_CHOICES_MS.minBy { kotlin.math.abs(it - silence.value) },
                onSet = {
                    if (it == AUTO_STOP_OFF) {
                        autoStop.setValue(false)
                    } else {
                        silence.setValue(it)
                        autoStop.setValue(true)
                    }
                },
                getDisplayName = {
                    when (it) {
                        AUTO_STOP_OFF -> res.getString(R.string.voice_input_settings_moonshine_auto_stop_off)
                        2000 -> res.getString(R.string.voice_input_settings_moonshine_auto_stop_default, it / 1000f)
                        else -> res.getString(R.string.voice_input_settings_moonshine_auto_stop_seconds, it / 1000f)
                    }
                },
                // applies to every model but Whisper (VoiceInputEngines.autoStopSilenceMs)
                disabled = whisper
            )
        },

        userSettingDecorationOnly {
            SectionLabel(stringResource(R.string.voice_input_advanced_section_speed))
        },

        UserSetting(
            name = R.string.voice_input_settings_moonshine_accelerator,
            subtitle = R.string.voice_input_settings_moonshine_accelerator_subtitle
        ) {
            val accelerator = useDataStore(MOONSHINE_ACCELERATOR)
            val res = LocalResources.current
            val model = currentVoiceModel()
            DropDownSetting(
                title = stringResource(R.string.voice_input_settings_moonshine_accelerator),
                subtitle = if (model.isMoonshine) stringResource(R.string.voice_input_settings_moonshine_accelerator_subtitle)
                    else stringResource(R.string.voice_input_settings_only_moonshine_accelerator, model.name),
                options = OrtAccelerator.entries.toList(),
                selection = OrtAccelerator.entries.firstOrNull { it.name == accelerator.value } ?: OrtAccelerator.CPU,
                onSet = { accelerator.setValue(it.name) },
                getDisplayName = {
                    res.getString(
                        when (it) {
                            OrtAccelerator.CPU -> R.string.voice_input_settings_moonshine_accelerator_cpu
                            OrtAccelerator.XNNPACK -> R.string.voice_input_settings_moonshine_accelerator_xnnpack
                            OrtAccelerator.NNAPI -> R.string.voice_input_settings_moonshine_accelerator_nnapi
                        }
                    )
                },
                disabled = !model.isMoonshine
            )
        },

        UserSetting(
            name = R.string.voice_input_settings_moonshine_threads,
            subtitle = R.string.voice_input_settings_moonshine_threads_subtitle
        ) {
            val threads = useDataStore(MOONSHINE_THREADS)
            val res = LocalResources.current
            val auto = remember { CpuTopology.autoThreadCount }
            val model = currentVoiceModel()
            DropDownSetting(
                title = stringResource(R.string.voice_input_settings_moonshine_threads),
                subtitle = if (model.isMoonshine) stringResource(R.string.voice_input_settings_moonshine_threads_subtitle)
                    else stringResource(R.string.voice_input_settings_only_moonshine_threads, model.name),
                options = (0..8).toList(),
                selection = threads.value.coerceIn(0, 8),
                onSet = { threads.setValue(it) },
                getDisplayName = {
                    if (it == 0) res.getString(R.string.voice_input_settings_moonshine_threads_auto, auto) else it.toString()
                },
                disabled = !model.isMoonshine
            )
        },

        UserSetting(
            name = R.string.voice_input_settings_show_timing,
            subtitle = R.string.voice_input_settings_show_timing_subtitle
        ) {
            SettingToggleDataStore(
                title = stringResource(R.string.voice_input_settings_show_timing),
                subtitle = stringResource(R.string.voice_input_settings_show_timing_subtitle),
                setting = VOICE_INPUT_SHOW_TIMING
            )
            val showTiming = useDataStoreValue(VOICE_INPUT_SHOW_TIMING)
            val last = useDataStoreValue(MOONSHINE_LAST_TIMING)
            if (showTiming && last.isNotEmpty()) {
                Text(
                    stringResource(R.string.voice_input_settings_last_timing, last),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 4.dp)
                )
            }
        },
    )
)
