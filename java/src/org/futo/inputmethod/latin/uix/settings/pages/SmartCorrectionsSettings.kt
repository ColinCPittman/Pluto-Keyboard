package org.futo.inputmethod.latin.uix.settings.pages

import org.futo.inputmethod.latin.uix.settings.tourTarget
import org.futo.inputmethod.latin.uix.settings.TourTargets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.CONTEXT_MODEL_ENABLED
import org.futo.inputmethod.latin.uix.ContextModels
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_COMMANDS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_EMOJI
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_COMMAND_LIST
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_CORRECTION_PHRASES
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_FILLERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_NAMES
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_NUMBERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_QUOTES
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SELF_CORRECTIONS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SOUND_ALIKE
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_COMMANDS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_EMOJI
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_FILLERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_NAMES
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_NUMBERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_QUESTIONS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_QUOTES
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_SELF_CORRECTIONS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SENSITIVITY_SOUND_ALIKE
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.DropDownPicker
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.voiceinput.shared.context.CorrectionPhrases
import org.futo.voiceinput.shared.context.SmartCorrectionSensitivity
import org.futo.voiceinput.shared.context.VoiceCommand
import org.futo.voiceinput.shared.context.VoiceCommandAction
import org.futo.voiceinput.shared.context.VoiceCommands
import org.futo.voiceinput.shared.modelpackage.ModelPackages

/*
 * Voice Input → Smart corrections (a page of its own, with the on/off switch first, then what the
 * context pass may change) → Voice commands (its own switch, then the editable command list).
 */

/** Whether a smart corrections model is installed and the phone can run it. */
@Composable
private fun smartCorrectionsAvailable(): Boolean {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val hasModel = remember(changes) { ContextModels.installed(context).isNotEmpty() }
    return hasModel && ContextModels.deviceSupported
}

/** Smart corrections are switched on and can run (the options below the switch are usable). */
@Composable
internal fun smartCorrectionsOn(): Boolean = smartCorrectionsAvailable() && useDataStoreValue(CONTEXT_MODEL_ENABLED)

/** A row that opens another page, greyed out (and not tappable) when [disabled]. */
@Composable
private fun SubpageRow(title: String, subtitle: String, disabled: Boolean, onClick: () -> Unit) {
    SettingItem(title = title, subtitle = subtitle, onClick = onClick, disabled = disabled) {
        Icon(Icons.Default.ArrowForward, contentDescription = null)
    }
}

/** "Currently enabled. <description>" / "Currently disabled. <description>" for a row that opens a page. */
@Composable
private fun stateText(on: Boolean, description: Int): String =
    stringResource(if (on) R.string.smart_corrections_state_on else R.string.smart_corrections_state_off, stringResource(description))

/** Voice Input page that opens the Smart corrections page, and the subtitle says whether it is on. */
val ContextModelSetting = UserSetting(
    name = R.string.voice_input_settings_context,
    subtitle = R.string.voice_input_settings_context_subtitle,
    visibilityCheck = visibilityCheckBuiltinVoiceInput
) {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val hasModel = remember(changes) { ContextModels.installed(context).isNotEmpty() }
    val enabled = useDataStoreValue(CONTEXT_MODEL_ENABLED)
    val navController = LocalNavController.current
    SubpageRow(
        title = stringResource(R.string.voice_input_settings_context),
        subtitle = when {
            !ContextModels.deviceSupported -> stringResource(R.string.context_models_unsupported_device)
            !hasModel -> stringResource(R.string.voice_input_settings_context_no_model)
            else -> stateText(
                enabled,
                if (whisperIsVoiceModel()) R.string.smart_corrections_row_description_whisper
                else R.string.smart_corrections_row_description
            )
        },
        disabled = false,
        onClick = { navController?.navigate(SmartCorrectionsMenu.navPath) }
    )
}

private fun smartCorrectionOption(title: Int, subtitle: Int, setting: SettingsKey<Boolean>) =
    userSettingToggleDataStore(
        title = title,
        subtitle = subtitle,
        setting = setting,
        disabled = { !smartCorrectionsOn() }
    )

/** Voice Input → Smart corrections, with the switch, then which kinds of edits the context pass may make. */
val SmartCorrectionsMenu = UserSettingsMenu(
    title = R.string.smart_corrections_title,
    navPath = "smartCorrections", registerNavPath = true,
    settings = listOf(
        UserSetting(name = R.string.smart_corrections_enable, subtitle = R.string.voice_input_settings_context_subtitle) {
            val context = LocalContext.current
            val changes by ModelPackages.changes.collectAsState()
            val hasModel = remember(changes) { ContextModels.installed(context).isNotEmpty() }
            val supported = ContextModels.deviceSupported
            SettingToggleDataStore(
                title = stringResource(R.string.smart_corrections_enable),
                subtitle = stringResource(
                    when {
                        !supported -> R.string.context_models_unsupported_device
                        hasModel -> R.string.voice_input_settings_context_subtitle
                        else -> R.string.voice_input_settings_context_no_model
                    }
                ),
                setting = CONTEXT_MODEL_ENABLED,
                disabled = !hasModel || !supported,
            )
        }.opensVoiceSetupGuideWhen { ContextModels.installed(it).isEmpty() && ContextModels.deviceSupported }
            .tourTarget(TourTargets.SMART_CORRECTIONS_VOICE),
        userSettingDecorationOnly {
            if (whisperIsVoiceModel()) Tip(stringResource(R.string.smart_corrections_whisper_note))
        },
        userSettingDecorationOnly {
            Text(
                stringResource(R.string.smart_corrections_intro),
                style = Typography.SmallMl,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // lined up with the settings' text
                modifier = Modifier.padding(start = 80.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            )
        },
        smartCorrectionOption(R.string.smart_corrections_sound_alike, R.string.smart_corrections_sound_alike_subtitle, SMART_CORRECTIONS_SOUND_ALIKE),
        smartCorrectionOption(R.string.smart_corrections_names, R.string.smart_corrections_names_subtitle, SMART_CORRECTIONS_NAMES),
        smartCorrectionOption(R.string.smart_corrections_numbers, R.string.smart_corrections_numbers_subtitle, SMART_CORRECTIONS_NUMBERS),
        // the options with a list you can edit open their own page
        subpageOption(R.string.smart_corrections_fillers, R.string.smart_corrections_fillers_subtitle, SMART_CORRECTIONS_FILLERS) {
            FillerWordsMenu.navPath
        },
        subpageOption(R.string.smart_corrections_quotes, R.string.smart_corrections_quotes_subtitle, SMART_CORRECTIONS_QUOTES) {
            QuotedSpeechMenu.navPath
        },
        subpageOption(R.string.smart_corrections_emoji, R.string.smart_corrections_emoji_subtitle, SMART_CORRECTIONS_EMOJI) {
            EmojiMenu.navPath
        },
        subpageOption(R.string.smart_corrections_commands, R.string.smart_corrections_commands_subtitle, SMART_CORRECTIONS_COMMANDS) {
            VoiceCommandsMenu.navPath
        },
        subpageOption(R.string.smart_corrections_self_corrections, R.string.smart_corrections_self_corrections_subtitle, SMART_CORRECTIONS_SELF_CORRECTIONS) {
            SelfCorrectionsMenu.navPath
        },
        UserSetting(name = R.string.smart_corrections_sensitivity, subtitle = R.string.smart_corrections_sensitivity_subtitle) {
            val navController = LocalNavController.current
            SubpageRow(
                title = stringResource(R.string.smart_corrections_sensitivity),
                subtitle = stringResource(R.string.smart_corrections_sensitivity_subtitle),
                disabled = !smartCorrectionsOn(),
                onClick = { navController?.navigate(SmartCorrectionsSensitivityMenu.navPath) }
            )
        },
    )
)

/** One option's sensitivity, in five steps from fewest to most changes. */
internal fun sensitivitySlider(title: Int, setting: SettingsKey<Int>, subtitle: Int? = null) = UserSetting(name = title, appearsInSearch = false) {
    val labels = listOf(
        R.string.smart_corrections_sensitivity_fewest, R.string.smart_corrections_sensitivity_fewer,
        R.string.smart_corrections_sensitivity_default, R.string.smart_corrections_sensitivity_more,
        R.string.smart_corrections_sensitivity_most,
    ).map { stringResource(it) }
    SettingSlider(
        title = stringResource(title),
        setting = setting,
        range = SmartCorrectionSensitivity.MIN.toFloat()..SmartCorrectionSensitivity.MAX.toFloat(),
        transform = { kotlin.math.round(it).toInt() },
        indicator = { labels[(it - SmartCorrectionSensitivity.MIN).coerceIn(0, labels.size - 1)] },
        steps = SmartCorrectionSensitivity.MAX - SmartCorrectionSensitivity.MIN - 1,
        subtitle = subtitle?.let { stringResource(it) },
    )
}

/** Smart corrections → Sensitivity, which sets how readily each option changes the text. */
val SmartCorrectionsSensitivityMenu = UserSettingsMenu(
    title = R.string.smart_corrections_sensitivity,
    navPath = "smartCorrectionsSensitivity", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            if (!smartCorrectionsOn()) Tip(stringResource(R.string.smart_corrections_needs_main_switch))
        },
        userSettingDecorationOnly {
            Text(
                stringResource(R.string.smart_corrections_sensitivity_intro),
                style = Typography.SmallMl,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        },
        sensitivitySlider(R.string.smart_corrections_sound_alike, SMART_CORRECTIONS_SENSITIVITY_SOUND_ALIKE),
        sensitivitySlider(R.string.smart_corrections_names, SMART_CORRECTIONS_SENSITIVITY_NAMES),
        sensitivitySlider(R.string.smart_corrections_questions, SMART_CORRECTIONS_SENSITIVITY_QUESTIONS),
        sensitivitySlider(R.string.smart_corrections_numbers, SMART_CORRECTIONS_SENSITIVITY_NUMBERS),
        sensitivitySlider(R.string.smart_corrections_fillers, SMART_CORRECTIONS_SENSITIVITY_FILLERS),
        sensitivitySlider(R.string.smart_corrections_quotes, SMART_CORRECTIONS_SENSITIVITY_QUOTES),
        sensitivitySlider(R.string.smart_corrections_emoji, SMART_CORRECTIONS_SENSITIVITY_EMOJI),
        sensitivitySlider(R.string.smart_corrections_commands, SMART_CORRECTIONS_SENSITIVITY_COMMANDS),
        sensitivitySlider(R.string.smart_corrections_self_corrections, SMART_CORRECTIONS_SENSITIVITY_SELF_CORRECTIONS),
    )
)

/** An option with its own page, where the row says whether it is on and opens the page. */
private fun subpageOption(title: Int, description: Int, setting: SettingsKey<Boolean>, navPath: () -> String) =
    UserSetting(name = title, subtitle = description) {
        val navController = LocalNavController.current
        val on = useDataStoreValue(setting)
        SubpageRow(
            title = stringResource(title),
            subtitle = stateText(on, description),
            disabled = !smartCorrectionsOn(),
            onClick = { navController?.navigate(navPath()) }
        )
    }

@Composable
private fun actionName(action: VoiceCommandAction): String = stringResource(
    when (action) {
        VoiceCommandAction.NEW_PARAGRAPH -> R.string.voice_command_action_new_paragraph
        VoiceCommandAction.NEW_LINE -> R.string.voice_command_action_new_line
        VoiceCommandAction.PERIOD -> R.string.voice_command_action_period
        VoiceCommandAction.COMMA -> R.string.voice_command_action_comma
        VoiceCommandAction.QUESTION_MARK -> R.string.voice_command_action_question_mark
        VoiceCommandAction.EXCLAMATION_MARK -> R.string.voice_command_action_exclamation_mark
        VoiceCommandAction.COLON -> R.string.voice_command_action_colon
        VoiceCommandAction.SEMICOLON -> R.string.voice_command_action_semicolon
        VoiceCommandAction.DELETE_LAST_SENTENCE -> R.string.voice_command_action_delete_last_sentence
        VoiceCommandAction.ELLIPSIS -> R.string.voice_command_action_ellipsis
        VoiceCommandAction.DASH -> R.string.voice_command_action_dash
        VoiceCommandAction.HYPHEN -> R.string.voice_command_action_hyphen
        VoiceCommandAction.OPEN_PARENTHESIS -> R.string.voice_command_action_open_parenthesis
        VoiceCommandAction.CLOSE_PARENTHESIS -> R.string.voice_command_action_close_parenthesis
        VoiceCommandAction.OPEN_BRACKET -> R.string.voice_command_action_open_bracket
        VoiceCommandAction.CLOSE_BRACKET -> R.string.voice_command_action_close_bracket
        VoiceCommandAction.OPEN_QUOTE -> R.string.voice_command_action_open_quote
        VoiceCommandAction.CLOSE_QUOTE -> R.string.voice_command_action_close_quote
        VoiceCommandAction.AMPERSAND -> R.string.voice_command_action_ampersand
        VoiceCommandAction.AT_SIGN -> R.string.voice_command_action_at_sign
        VoiceCommandAction.HASHTAG -> R.string.voice_command_action_hashtag
        VoiceCommandAction.DOLLAR_SIGN -> R.string.voice_command_action_dollar_sign
        VoiceCommandAction.PERCENT_SIGN -> R.string.voice_command_action_percent_sign
        VoiceCommandAction.ASTERISK -> R.string.voice_command_action_asterisk
        VoiceCommandAction.PLUS_SIGN -> R.string.voice_command_action_plus_sign
        VoiceCommandAction.EQUALS_SIGN -> R.string.voice_command_action_equals_sign
        VoiceCommandAction.SLASH -> R.string.voice_command_action_slash
        VoiceCommandAction.UNDERSCORE -> R.string.voice_command_action_underscore
        VoiceCommandAction.SMILEY -> R.string.voice_command_action_smiley
        VoiceCommandAction.FROWN -> R.string.voice_command_action_frown
        VoiceCommandAction.DELETE_LAST_WORD -> R.string.voice_command_action_delete_last_word
    }
)

/**
 * Add / edit dialog. [original] is null when adding. [others] holds the other commands (a phrase may
 * be used only once).
 */
@Composable
private fun VoiceCommandDialog(
    original: VoiceCommand?,
    others: List<VoiceCommand>,
    onSave: (VoiceCommand) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var phrase by remember { mutableStateOf(original?.phrase ?: "") }
    var action by remember { mutableStateOf(original?.action ?: VoiceCommandAction.NEW_LINE) }
    val words = VoiceCommands.phraseWords(phrase)
    val error = when {
        phrase.isBlank() -> null
        !VoiceCommands.isValidPhrase(phrase) -> stringResource(R.string.voice_commands_error_length, VoiceCommands.MAX_WORDS)
        others.any { it.words == words } -> stringResource(R.string.voice_commands_error_duplicate)
        else -> null
    }
    val names = VoiceCommandAction.entries.associateWith { actionName(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (original == null) R.string.voice_commands_add else R.string.voice_commands_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text(stringResource(R.string.voice_commands_phrase_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: stringResource(R.string.voice_commands_phrase_hint)) }
                )
                Text(stringResource(R.string.voice_commands_action_label), style = Typography.SmallMl)
                DropDownPicker(
                    options = VoiceCommandAction.entries.toList(),
                    selection = action,
                    onSet = { action = it },
                    getDisplayName = { names[it] ?: it.name },
                    scrollableOptions = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = phrase.isNotBlank() && error == null,
                onClick = { onSave(VoiceCommand(words.joinToString(" "), action)) }
            ) { Text(stringResource(R.string.voice_commands_save)) }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.voice_commands_delete)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_commands_cancel)) }
        }
    )
}

/** The editable list of voice commands. */
private val VoiceCommandList = UserSetting(name = R.string.voice_commands_list_header, appearsInSearch = false) {
    val (stored, setStored) = useDataStore(SMART_CORRECTIONS_COMMAND_LIST)
    val commands = remember(stored) { VoiceCommands.parse(stored) }
    val disabled = !smartCorrectionsOn() || !useDataStoreValue(SMART_CORRECTIONS_COMMANDS)
    var editing by remember { mutableStateOf<Int?>(null) }   // index, or -1 when adding
    var confirmReset by remember { mutableStateOf(false) }
    fun save(list: List<VoiceCommand>) {
        setStored(if (list == VoiceCommands.DEFAULTS) "" else VoiceCommands.serialize(list))
    }

    Column {
        Text(
            stringResource(R.string.voice_commands_list_header),
            style = Typography.Body.MediumMl,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
        )
        if (commands.isEmpty()) Tip(stringResource(R.string.voice_commands_none))
        commands.forEachIndexed { i, c ->
            SettingItem(
                title = "“${c.phrase}”",
                subtitle = actionName(c.action),
                onClick = { editing = i },
                disabled = disabled,
                compact = true
            ) {
                Switch(
                    checked = c.enabled,
                    enabled = !disabled,
                    onCheckedChange = { on -> save(commands.mapIndexed { j, o -> if (j == i) o.copy(enabled = on) else o }) }
                )
            }
        }
        SettingItem(
            title = stringResource(R.string.voice_commands_add),
            onClick = { editing = -1 },
            disabled = disabled,
            compact = true
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
        if (stored.isNotBlank()) {
            SettingItem(
                title = stringResource(R.string.voice_commands_reset),
                subtitle = stringResource(R.string.voice_commands_reset_subtitle),
                onClick = { confirmReset = true },
                disabled = disabled,
                compact = true
            ) { }
        }
    }

    editing?.let { idx ->
        val original = commands.getOrNull(idx)
        VoiceCommandDialog(
            original = original,
            others = commands.filterIndexed { i, _ -> i != idx },
            onSave = { c ->
                save(if (original == null) commands + c else commands.mapIndexed { i, o -> if (i == idx) c.copy(enabled = o.enabled) else o })
                editing = null
            },
            onDelete = original?.let { { save(commands.filterIndexed { i, _ -> i != idx }); editing = null } },
            onDismiss = { editing = null }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.voice_commands_reset)) },
            text = { Text(stringResource(R.string.voice_commands_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = { setStored(""); confirmReset = false }) { Text(stringResource(R.string.voice_commands_reset_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.voice_commands_cancel)) }
            }
        )
    }
}

/** Smart corrections → Voice commands, with the switch, then the commands (tap one to change it). */
val VoiceCommandsMenu = UserSettingsMenu(
    title = R.string.smart_corrections_commands,
    navPath = "voiceCommands", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            if (!smartCorrectionsOn()) Tip(stringResource(R.string.smart_corrections_needs_main_switch))
        },
        userSettingToggleDataStore(
            title = R.string.smart_corrections_commands,
            subtitle = R.string.smart_corrections_commands_subtitle,
            setting = SMART_CORRECTIONS_COMMANDS,
            disabled = { !smartCorrectionsOn() }
        ),
        VoiceCommandList,
    )
)
