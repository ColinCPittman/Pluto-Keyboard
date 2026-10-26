package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_CORRECTION_PHRASES
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_CORRECTION_PHRASES_BUILT_IN
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_FILLERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_FILLER_BUILT_IN
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_FILLER_LIST
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_QUOTES
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_QUOTE_TRIGGERS
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_QUOTE_TRIGGERS_BUILT_IN
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_SELF_CORRECTIONS
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.voiceinput.shared.context.CorrectionPhrases
import org.futo.voiceinput.shared.context.FillerWords
import org.futo.voiceinput.shared.context.PhraseEntry
import org.futo.voiceinput.shared.context.PhraseList
import org.futo.voiceinput.shared.context.QuoteTriggers
import org.futo.voiceinput.shared.context.VoiceCommands

/*
 * Smart corrections → Self-corrections, → Filler words and → Quoted speech. Each page has its
 * switch, then its phrase list (the built-in phrases and your own, every one with its own switch).
 */

/** Texts of one editable phrase list page. */
private class PhraseListTexts(
    val header: Int,
    val explanation: Int,
    val none: Int,
    val add: Int,
    val edit: Int,
    val hint: Int,
    val duplicate: Int,
    val tooLong: Int,
    val reset: Int,
    val resetConfirm: Int,
    val builtIn: Int,
    val builtInSubtitle: Int,
)

/** Add / edit dialog for one phrase. [original] is null when adding. */
@Composable
private fun PhraseDialog(
    texts: PhraseListTexts,
    maxWords: Int,
    original: String?,
    others: List<String>,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var phrase by remember { mutableStateOf(original ?: "") }
    val words = VoiceCommands.phraseWords(phrase)
    val error = when {
        phrase.isBlank() -> null
        words.size !in 1..maxWords -> stringResource(texts.tooLong, maxWords)
        others.any { VoiceCommands.phraseWords(it) == words } -> stringResource(texts.duplicate)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (original == null) texts.add else texts.edit)) },
        text = {
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text(stringResource(R.string.voice_commands_phrase_label)) },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: stringResource(texts.hint)) }
            )
        },
        confirmButton = {
            TextButton(enabled = phrase.isNotBlank() && error == null, onClick = { onSave(phrase.trim()) }) {
                Text(stringResource(R.string.voice_commands_save))
            }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.voice_commands_delete)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_commands_cancel)) }
        }
    )
}

/**
 * An editable phrase list with the "Built-in" switch, your own phrases (each with its switch, and tap one
 * to change or delete it), then the built-in phrases, each with its switch. With the built-in
 * phrases off they are still listed, greyed, so you see what turning them on adds. Only your
 * changes are saved ([PhraseList.serialize]), and the switch never changes them.
 */
@Composable
private fun EditablePhraseList(
    texts: PhraseListTexts,
    list: PhraseList,
    maxWords: Int,
    listKey: SettingsKey<String>,
    builtInKey: SettingsKey<Boolean>,
    disabled: Boolean,
) {
    val (stored, setStored) = useDataStore(listKey)
    val (builtIn, setBuiltIn) = useDataStore(builtInKey)
    val changes = remember(stored) { list.parse(stored) }
    val own = remember(changes) { list.own(changes) }
    fun save(newChanges: List<PhraseEntry>) = setStored(list.serialize(newChanges))
    // your own phrases changed, and the built-in phrases you switched off stay as they are
    fun saveOwn(newOwn: List<PhraseEntry>) = save(newOwn + (changes - own.toSet()))

    var editing by remember { mutableStateOf<Int?>(null) }   // index into own, or -1 when adding
    var confirmReset by remember { mutableStateOf(false) }

    Column {
        Text(
            stringResource(texts.header),
            style = Typography.Body.MediumMl,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
        )
        Text(
            stringResource(texts.explanation),
            style = Typography.SmallMl,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        SettingItem(
            title = stringResource(texts.builtIn),
            subtitle = stringResource(texts.builtInSubtitle, list.DEFAULTS.size),
            onClick = { setBuiltIn(!builtIn) },
            disabled = disabled,
            compact = true
        ) {
            Switch(checked = builtIn, enabled = !disabled, onCheckedChange = { setBuiltIn(it) })
        }

        SubHeader(stringResource(R.string.phrase_list_yours))
        if (own.isEmpty()) Tip(stringResource(texts.none))
        own.forEachIndexed { i, entry ->
            SettingItem(title = "“${entry.phrase}”", onClick = { editing = i }, disabled = disabled, compact = true) {
                Switch(
                    checked = entry.enabled,
                    enabled = !disabled,
                    onCheckedChange = { checked -> saveOwn(own.mapIndexed { j, e -> if (j == i) e.copy(enabled = checked) else e }) }
                )
            }
        }
        SettingItem(title = stringResource(texts.add), onClick = { editing = -1 }, disabled = disabled, compact = true) {
            Icon(Icons.Default.Add, contentDescription = null)
        }

        val ownWords = own.map { VoiceCommands.phraseWords(it.phrase) }.toSet()
        val builtIns = list.DEFAULTS.filter { VoiceCommands.phraseWords(it) !in ownWords }
        SubHeader(stringResource(R.string.phrase_list_built_in, builtIns.size))
        if (!builtIn) Text(
            stringResource(R.string.phrase_list_built_in_off_note),
            style = Typography.SmallMl,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        builtIns.forEach { phrase ->
            val on = list.isOn(changes, phrase)
            SettingItem(
                title = "“$phrase”",
                onClick = { save(list.withBuiltIn(changes, phrase, !on)) },
                disabled = disabled || !builtIn,
                compact = true
            ) {
                Switch(
                    checked = builtIn && on,
                    enabled = !disabled && builtIn,
                    onCheckedChange = { checked -> save(list.withBuiltIn(changes, phrase, checked)) }
                )
            }
        }
        if (stored.isNotBlank() || !builtIn) {
            SettingItem(
                title = stringResource(texts.reset),
                subtitle = stringResource(R.string.voice_commands_reset_subtitle),
                onClick = { confirmReset = true },
                disabled = disabled,
                compact = true
            ) { }
        }
    }

    editing?.let { idx ->
        val original = own.getOrNull(idx)
        PhraseDialog(
            texts = texts,
            maxWords = maxWords,
            original = original?.phrase,
            // the built-in phrases are in the list too while they are on
            others = own.filterIndexed { i, _ -> i != idx }.map { it.phrase } + if (builtIn) list.DEFAULTS else emptyList(),
            onSave = { p ->
                saveOwn(if (original == null) own + PhraseEntry(p) else own.mapIndexed { i, e -> if (i == idx) e.copy(phrase = p) else e })
                editing = null
            },
            onDelete = original?.let { { saveOwn(own.filterIndexed { i, _ -> i != idx }); editing = null } },
            onDismiss = { editing = null }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(texts.reset)) },
            text = { Text(stringResource(texts.resetConfirm)) },
            confirmButton = {
                TextButton(onClick = { setStored(""); setBuiltIn(true); confirmReset = false }) {
                    Text(stringResource(R.string.voice_commands_reset_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.voice_commands_cancel)) }
            }
        )
    }
}

/** A small heading inside a phrase list. */
@Composable
private fun SubHeader(text: String) = Text(
    text,
    style = Typography.SmallMl,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
)

private val CorrectionPhraseTexts = PhraseListTexts(
    header = R.string.correction_phrases_header,
    explanation = R.string.correction_phrases_explanation,
    none = R.string.correction_phrases_none,
    add = R.string.correction_phrases_add,
    edit = R.string.correction_phrases_edit,
    hint = R.string.correction_phrases_phrase_hint,
    duplicate = R.string.correction_phrases_error_duplicate,
    tooLong = R.string.voice_commands_error_length,
    reset = R.string.correction_phrases_reset,
    resetConfirm = R.string.correction_phrases_reset_confirm,
    builtIn = R.string.correction_phrases_builtin,
    builtInSubtitle = R.string.correction_phrases_builtin_subtitle,
)

private val FillerWordTexts = PhraseListTexts(
    header = R.string.filler_words_header,
    explanation = R.string.filler_words_explanation,
    none = R.string.filler_words_none,
    add = R.string.filler_words_add,
    edit = R.string.filler_words_edit,
    hint = R.string.filler_words_phrase_hint,
    duplicate = R.string.filler_words_error_duplicate,
    tooLong = R.string.voice_commands_error_length,
    reset = R.string.filler_words_reset,
    resetConfirm = R.string.filler_words_reset_confirm,
    builtIn = R.string.filler_words_builtin,
    builtInSubtitle = R.string.filler_words_builtin_subtitle,
)

private val CorrectionPhraseList = UserSetting(name = R.string.correction_phrases_header, appearsInSearch = false) {
    EditablePhraseList(
        texts = CorrectionPhraseTexts,
        list = CorrectionPhrases,
        maxWords = VoiceCommands.MAX_WORDS,
        listKey = SMART_CORRECTIONS_CORRECTION_PHRASES,
        builtInKey = SMART_CORRECTIONS_CORRECTION_PHRASES_BUILT_IN,
        disabled = !smartCorrectionsOn() || !useDataStoreValue(SMART_CORRECTIONS_SELF_CORRECTIONS),
    )
}

private val FillerWordList = UserSetting(name = R.string.filler_words_header, appearsInSearch = false) {
    EditablePhraseList(
        texts = FillerWordTexts,
        list = FillerWords,
        maxWords = 3,
        listKey = SMART_CORRECTIONS_FILLER_LIST,
        builtInKey = SMART_CORRECTIONS_FILLER_BUILT_IN,
        disabled = !smartCorrectionsOn() || !useDataStoreValue(SMART_CORRECTIONS_FILLERS),
    )
}

/** A Smart corrections sub-page with a note when smart corrections are off, the switch, then the list. */
internal fun phraseListMenu(title: Int, subtitle: Int, navPath: String, setting: SettingsKey<Boolean>, list: UserSetting) =
    UserSettingsMenu(
        title = title,
        navPath = navPath, registerNavPath = true,
        settings = listOf(
            userSettingDecorationOnly {
                if (!smartCorrectionsOn()) Tip(stringResource(R.string.smart_corrections_needs_main_switch))
            },
            userSettingToggleDataStore(
                title = title,
                subtitle = subtitle,
                setting = setting,
                disabled = { !smartCorrectionsOn() }
            ),
            list,
        )
    )

val SelfCorrectionsMenu = phraseListMenu(
    R.string.smart_corrections_self_corrections, R.string.smart_corrections_self_corrections_subtitle,
    "selfCorrections", SMART_CORRECTIONS_SELF_CORRECTIONS, CorrectionPhraseList
)

private val QuoteTriggerTexts = PhraseListTexts(
    header = R.string.quote_triggers_header,
    explanation = R.string.quote_triggers_explanation,
    none = R.string.quote_triggers_none,
    add = R.string.quote_triggers_add,
    edit = R.string.quote_triggers_edit,
    hint = R.string.quote_triggers_phrase_hint,
    duplicate = R.string.quote_triggers_error_duplicate,
    tooLong = R.string.voice_commands_error_length,
    reset = R.string.quote_triggers_reset,
    resetConfirm = R.string.quote_triggers_reset_confirm,
    builtIn = R.string.quote_triggers_builtin,
    builtInSubtitle = R.string.quote_triggers_builtin_subtitle,
)

private val QuoteTriggerList = UserSetting(name = R.string.quote_triggers_header, appearsInSearch = false) {
    EditablePhraseList(
        texts = QuoteTriggerTexts,
        list = QuoteTriggers,
        maxWords = VoiceCommands.MAX_WORDS,
        listKey = SMART_CORRECTIONS_QUOTE_TRIGGERS,
        builtInKey = SMART_CORRECTIONS_QUOTE_TRIGGERS_BUILT_IN,
        disabled = !smartCorrectionsOn() || !useDataStoreValue(SMART_CORRECTIONS_QUOTES),
    )
}

val QuotedSpeechMenu = phraseListMenu(
    R.string.smart_corrections_quotes, R.string.smart_corrections_quotes_subtitle,
    "quotedSpeech", SMART_CORRECTIONS_QUOTES, QuoteTriggerList
)

val FillerWordsMenu = phraseListMenu(
    R.string.smart_corrections_fillers, R.string.smart_corrections_fillers_subtitle,
    "fillerWords", SMART_CORRECTIONS_FILLERS, FillerWordList
)
