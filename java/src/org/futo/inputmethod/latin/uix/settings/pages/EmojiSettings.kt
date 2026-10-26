package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.NumberFormat
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.EmojiNameDefaults
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_EMOJI
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_EMOJI_BUILT_IN
import org.futo.inputmethod.latin.uix.SMART_CORRECTIONS_EMOJI_LIST
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.voiceinput.shared.context.BuiltInNameRow
import org.futo.voiceinput.shared.context.EmojiInfo
import org.futo.voiceinput.shared.context.EmojiNameIndex
import org.futo.voiceinput.shared.context.EmojiNames
import org.futo.voiceinput.shared.context.EmojiNickname
import org.futo.voiceinput.shared.context.VoiceCommands

/*
 * Smart corrections → Emoji shows the switch, then the emoji names. Every emoji has several built-in
 * names (too many to show at once), so they are searched, and each result shows the names that give
 * that emoji. With no search it shows your changes, meaning the names you added or switched off.
 */

/** How many search results are shown at once. */
private const val MAX_RESULTS = 50

/** Add / edit dialog for one of your emoji names. [original] is null (or has only the emoji) when [adding]. */
@Composable
private fun EmojiNameDialog(
    original: EmojiNickname?,
    adding: Boolean,
    otherNames: Set<List<String>>,
    onSave: (EmojiNickname) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var phrase by remember { mutableStateOf(original?.phrase ?: "") }
    var emoji by remember { mutableStateOf(original?.emoji ?: "") }
    val words = VoiceCommands.phraseWords(phrase)
    val nameError = when {
        phrase.isBlank() -> null
        words.size !in 1..EmojiNames.MAX_WORDS -> stringResource(R.string.voice_commands_error_length, EmojiNames.MAX_WORDS)
        words in otherNames -> stringResource(R.string.emoji_names_error_duplicate)
        else -> null
    }
    val emojiError = if (emoji.isNotBlank() && !EmojiNames.isValidEmoji(emoji)) stringResource(R.string.emoji_names_error_emoji) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (adding) R.string.emoji_names_add else R.string.emoji_names_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text(stringResource(R.string.emoji_names_name_label)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = { Text(nameError ?: stringResource(R.string.emoji_names_name_hint)) }
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text(stringResource(R.string.emoji_names_emoji_label)) },
                    singleLine = true,
                    isError = emojiError != null,
                    supportingText = { Text(emojiError ?: stringResource(R.string.emoji_names_emoji_hint)) }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = phrase.isNotBlank() && emoji.isNotBlank() && nameError == null && emojiError == null,
                onClick = { onSave(EmojiNickname(phrase.trim(), emoji.trim(), original?.enabled ?: true)) }
            ) {
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

/** One emoji's names, each with a switch, and a button to add one. */
@Composable
private fun EmojiNamesOfDialog(
    info: EmojiInfo,
    changes: List<EmojiNickname>,
    builtIn: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mine = changes.filter { it.enabled && it.emoji == info.emoji }.map { it.phrase }
    val off = changes.filter { !it.enabled }.mapTo(HashSet()) { VoiceCommands.phraseWords(it.phrase) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emoji_names_names_of, info.emoji)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(info.official, style = Typography.SmallMl, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!builtIn) Text(stringResource(R.string.emoji_names_builtin_off_note), style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                (mine + info.names).distinct().forEach { name ->
                    // with the built-in names off, only your own names are active
                    val active = builtIn || name in mine
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            name, modifier = Modifier.weight(1f),
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Switch(
                            checked = active && VoiceCommands.phraseWords(name) !in off,
                            enabled = active,
                            onCheckedChange = { onToggle(name, it) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.emoji_names_close)) } },
        dismissButton = { TextButton(onClick = onAdd) { Text(stringResource(R.string.emoji_names_add)) } }
    )
}

/** Every built-in name by emoji, each with its switch (greyed when the built-in names are off). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllBuiltInNamesDialog(
    index: EmojiNameIndex,
    changes: List<EmojiNickname>,
    builtIn: Boolean,
    onToggle: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var onlyOff by remember { mutableStateOf(false) }
    // about 5,300 rows, made off the main thread
    val loaded by produceState<List<BuiltInNameRow>?>(null, changes, onlyOff) {
        value = withContext(Dispatchers.Default) { EmojiNames.builtInRows(index, changes, onlyOff) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                    Text(stringResource(R.string.emoji_names_all_title), style = Typography.Body.MediumMl, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.emoji_names_close)) }
                }
                if (!builtIn) Text(
                    stringResource(R.string.emoji_names_all_off), style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)
                )
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(selected = !onlyOff, onClick = { onlyOff = false }, label = { Text(stringResource(R.string.emoji_names_filter_all)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = onlyOff, onClick = { onlyOff = true }, label = { Text(stringResource(R.string.emoji_names_filter_off)) })
                }
                val rows = loaded ?: run {
                    LoadingLine()
                    return@Column
                }
                if (rows.isEmpty()) Tip(stringResource(R.string.emoji_names_none_off))
                val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(rows.size, key = { i -> rows[i].emoji + "\u0000" + (rows[i].name ?: "") }) { i ->
                        val r = rows[i]
                        val name = r.name
                        if (name == null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                                Text(r.emoji, fontSize = 24.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(r.official.replaceFirstChar { it.uppercase() }, style = Typography.SmallMl,
                                    color = if (builtIn) MaterialTheme.colorScheme.primary else dim)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp)) {
                                Text(name, modifier = Modifier.weight(1f), color = if (builtIn) MaterialTheme.colorScheme.onSurface else dim)
                                Switch(checked = r.on, enabled = builtIn, onCheckedChange = { onToggle(name, r.emoji, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Loading emoji names…", while the names are read. */
@Composable
private fun LoadingLine() = Text(
    stringResource(R.string.emoji_names_loading),
    style = Typography.SmallMl,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(16.dp)
)

private val EmojiNameList = UserSetting(name = R.string.emoji_names_header, appearsInSearch = false) {
    val context = LocalContext.current
    // reading the emoji data and making every name's variants takes a moment, so not on the main thread
    val index by produceState<EmojiNameIndex?>(null) {
        value = withContext(Dispatchers.Default) { EmojiNameDefaults.get(context) }
    }
    index?.let { EmojiNameListContent(it) } ?: LoadingLine()
}

@Composable
private fun EmojiNameListContent(index: EmojiNameIndex) {
    val (stored, setStored) = useDataStore(SMART_CORRECTIONS_EMOJI_LIST)
    val (builtIn, setBuiltIn) = useDataStore(SMART_CORRECTIONS_EMOJI_BUILT_IN)
    // your changes (your own names, and built-in names you switched off, kept with the built-in names off),
    // a short list, read right away so a change always starts from the saved one
    val entries = remember(stored) { EmojiNames.parse(stored, index) }
    // the map copies the ~9,000 built-in names, so it is made off the main thread
    val names by produceState<Map<List<String>, String>>(emptyMap(), entries, builtIn) {
        value = withContext(Dispatchers.Default) { EmojiNames.nameMap(index, entries, builtIn) }
    }
    val disabled = !smartCorrectionsOn() || !useDataStoreValue(SMART_CORRECTIONS_EMOJI)

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }   // index, or -1 when adding
    var addingFor by remember { mutableStateOf<String?>(null) }   // the emoji a new name is for
    var showing by remember { mutableStateOf<EmojiInfo?>(null) }
    var showingAll by remember { mutableStateOf(false) }
    val counts = remember(index) { EmojiNames.builtInCounts(index) }
    var confirmReset by remember { mutableStateOf(false) }

    fun save(list: List<EmojiNickname>) = setStored(EmojiNames.serialize(list))

    /** Switches [name] of [emoji] on or off. */
    fun toggle(name: String, emoji: String, on: Boolean) {
        val words = VoiceCommands.phraseWords(name)
        val rest = entries.filter { VoiceCommands.phraseWords(it.phrase) != words }
        val own = entries.firstOrNull { VoiceCommands.phraseWords(it.phrase) == words && it.emoji == emoji }
        save(when {
            own != null -> entries.map { if (it === own) it.copy(enabled = on) else it }
            on -> rest
            else -> rest + EmojiNickname(name, emoji, enabled = false)
        })
    }

    // search a moment after the last key, off the main thread
    var q by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        val t = query.trim()
        if (t.isNotEmpty()) delay(150)
        q = t
    }
    val found by produceState(Triple("", emptyList<EmojiInfo>(), null as String?), entries, names, q) {
        val query = q
        value = withContext(Dispatchers.Default) {
            Triple(query, EmojiNames.search(index, entries, names, query), if (query.isEmpty()) null else EmojiNames.lookup(names, query))
        }
    }
    val results = found.second
    val said = found.third

    Column {
        Text(
            stringResource(R.string.emoji_names_header),
            style = Typography.Body.MediumMl,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
        )
        Text(
            stringResource(R.string.emoji_names_explanation),
            style = Typography.SmallMl,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )
        SettingItem(
            title = stringResource(R.string.emoji_names_builtin),
            subtitle = stringResource(
                R.string.emoji_names_builtin_count,
                NumberFormat.getIntegerInstance().format(counts.first), NumberFormat.getIntegerInstance().format(counts.second)
            ),
            onClick = { setBuiltIn(!builtIn) },
            disabled = disabled,
            compact = true
        ) {
            Switch(checked = builtIn, enabled = !disabled, onCheckedChange = { setBuiltIn(it) })
        }
        SettingItem(
            title = stringResource(R.string.emoji_names_see_all),
            subtitle = stringResource(R.string.emoji_names_see_all_subtitle),
            onClick = { showingAll = true },
            disabled = disabled,
            compact = true
        ) { }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            enabled = !disabled,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.emoji_names_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (q.isEmpty()) {
            // with no search, show the names you added or switched off
            if (entries.isEmpty()) Tip(stringResource(R.string.emoji_names_search_prompt))
            else Text(
                stringResource(R.string.emoji_names_your_changes),
                style = Typography.SmallMl,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
            entries.forEachIndexed { i, e ->
                SettingItem(
                    title = e.phrase,
                    icon = { Text(e.emoji, fontSize = 24.sp) },
                    onClick = { editing = i },
                    disabled = disabled,
                    compact = true
                ) {
                    Switch(
                        checked = e.enabled,
                        enabled = !disabled,
                        onCheckedChange = { checked -> save(entries.mapIndexed { j, x -> if (j == i) x.copy(enabled = checked) else x }) }
                    )
                }
            }
        } else {
            // what saying it gives, so you know before you try
            when {
                said == null -> {}
                said.isEmpty() -> Tip(stringResource(R.string.emoji_names_gives_none, found.first))
                else -> Tip(stringResource(R.string.emoji_names_gives, found.first, said))
            }
            if (found.first == q && results.isEmpty()) Tip(stringResource(R.string.emoji_names_no_matches))
            results.take(MAX_RESULTS).forEach { info ->
                val mine = entries.filter { it.enabled && it.emoji == info.emoji }.map { it.phrase }
                val all = if (builtIn) (mine + info.names).distinct() else mine
                SettingItem(
                    title = info.official.replaceFirstChar { it.uppercase() },
                    subtitle = if (all.isEmpty()) stringResource(R.string.emoji_names_builtin_off_row)
                        else stringResource(R.string.emoji_names_say, all.take(8).joinToString(", ") + if (all.size > 8) ", …" else ""),
                    icon = { Text(info.emoji, fontSize = 24.sp) },
                    onClick = { showing = info },
                    disabled = disabled,
                    compact = true
                ) { }
            }
            if (results.size > MAX_RESULTS) Tip(stringResource(R.string.emoji_names_more, MAX_RESULTS))
        }
        SettingItem(title = stringResource(R.string.emoji_names_add), onClick = { addingFor = null; editing = -1 }, disabled = disabled, compact = true) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
        if (entries.isNotEmpty()) {
            SettingItem(
                title = stringResource(R.string.emoji_names_reset),
                subtitle = stringResource(R.string.voice_commands_reset_subtitle),
                onClick = { confirmReset = true },
                disabled = disabled,
                compact = true
            ) { }
        }
    }

    if (showingAll) {
        AllBuiltInNamesDialog(
            index = index,
            changes = entries,
            builtIn = builtIn,
            onToggle = { name, emoji, on -> toggle(name, emoji, on) },
            onDismiss = { showingAll = false }
        )
    }
    showing?.let { info ->
        EmojiNamesOfDialog(
            info = info,
            changes = entries,
            builtIn = builtIn,
            onToggle = { name, on -> toggle(name, info.emoji, on) },
            onAdd = { addingFor = info.emoji; showing = null; editing = -1 },
            onDismiss = { showing = null }
        )
    }
    editing?.let { idx ->
        val original = entries.getOrNull(idx) ?: addingFor?.let { EmojiNickname("", it) }
        val otherNames = remember(entries, idx) {
            entries.filterIndexed { i, _ -> i != idx }.mapTo(HashSet()) { VoiceCommands.phraseWords(it.phrase) }
        }
        EmojiNameDialog(
            original = original,
            adding = idx !in entries.indices,
            otherNames = otherNames,
            onSave = { e ->
                save(if (idx !in entries.indices) entries + e else entries.mapIndexed { i, x -> if (i == idx) e else x })
                editing = null
            },
            onDelete = if (idx in entries.indices) { { save(entries.filterIndexed { i, _ -> i != idx }); editing = null } } else null,
            onDismiss = { editing = null }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.emoji_names_reset)) },
            text = { Text(stringResource(R.string.emoji_names_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = { setStored(""); confirmReset = false }) { Text(stringResource(R.string.voice_commands_reset_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.voice_commands_cancel)) }
            }
        )
    }
}

val EmojiMenu = phraseListMenu(
    R.string.smart_corrections_emoji, R.string.smart_corrections_emoji_subtitle,
    "emojiNames", SMART_CORRECTIONS_EMOJI, EmojiNameList
)
