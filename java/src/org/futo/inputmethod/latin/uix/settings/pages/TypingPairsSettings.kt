package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.TYPING_PAIRS_BUILT_IN
import org.futo.inputmethod.latin.uix.TYPING_PAIRS_LIST
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.voiceinput.shared.context.OwnTypingPair
import org.futo.voiceinput.shared.context.TypingConfusions
import org.futo.voiceinput.shared.context.TypingPairEdits

/*
 * Smart corrections for typing → Typing corrections list shows the pairs "typed word -> word you may
 * have meant" the typing check tries, searched by typed word, with your own pairs and the words
 * that are never changed.
 */

/** How many search results are shown at once. */
private const val MAX_RESULTS = 30

private fun arrow(typed: String, intended: List<String>, max: Int = 6) =
    "$typed → " + intended.take(max).joinToString(", ") + if (intended.size > max) ", …" else ""

/** Add / edit dialog for one of your own pairs, and [original] is null when adding. */
@Composable
private fun OwnPairDialog(
    original: OwnTypingPair?,
    edits: TypingPairEdits,
    index: Int?,
    onSave: (OwnTypingPair) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(original?.typed ?: "") }
    var intended by remember { mutableStateOf(original?.intended?.joinToString(", ") ?: "") }
    val typedWord = TypingPairEdits.word(typed)
    val words = TypingPairEdits.words(intended).filter { it != typedWord }
    // the same pair twice is not saved, because one of your other pairs has every word already
    val duplicate = typedWord != null && words.isNotEmpty() && words.all { edits.hasOwn(typedWord, it, except = index) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (original == null) R.string.typing_pairs_add else R.string.typing_pairs_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.typing_pairs_typed_label)) },
                    singleLine = true,
                    isError = typed.isNotBlank() && typedWord == null,
                    supportingText = { Text(stringResource(R.string.typing_pairs_typed_hint)) }
                )
                OutlinedTextField(
                    value = intended,
                    onValueChange = { intended = it },
                    label = { Text(stringResource(R.string.typing_pairs_intended_label)) },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = {
                        Text(
                            if (duplicate) stringResource(R.string.typing_pairs_duplicate, typedWord!!, words.joinToString(", "))
                            else stringResource(R.string.typing_pairs_intended_hint)
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = typedWord != null && words.isNotEmpty() && !duplicate,
                onClick = { onSave(OwnTypingPair(typedWord!!, words, original?.enabled ?: true)) }
            ) { Text(stringResource(R.string.voice_commands_save)) }
        },
        dismissButton = {
            if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.voice_commands_delete)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_commands_cancel)) }
        }
    )
}

/** Add dialog for a word that is never changed. */
@Composable
private fun NeverWordDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val word = TypingPairEdits.word(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.typing_pairs_never_add)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.typing_pairs_typed_label)) },
                singleLine = true,
                isError = text.isNotBlank() && word == null,
                supportingText = { Text(stringResource(R.string.typing_pairs_never_hint)) }
            )
        },
        confirmButton = {
            TextButton(enabled = word != null, onClick = { onSave(word!!) }) { Text(stringResource(R.string.voice_commands_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_commands_cancel)) } }
    )
}

/** The built-in pairs of one typed word, each with a switch (greyed when the built-in pairs are off). */
@Composable
private fun BuiltInPairsDialog(
    typed: String,
    intended: List<String>,
    off: Set<String>,
    builtIn: Boolean,
    never: Boolean,
    own: List<Pair<Int, OwnTypingPair>>,
    onToggle: (String, Boolean) -> Unit,
    onToggleOwn: (Int, Boolean) -> Unit,
    onEditOwn: (Int) -> Unit,
    onNever: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.typing_pairs_pairs_of, typed)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (!builtIn) Text(
                    stringResource(R.string.typing_pairs_builtin_off_note), style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                intended.forEach { w ->
                    val active = builtIn && !never
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "$typed → $w", modifier = Modifier.weight(1f),
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Switch(checked = active && w !in off, enabled = active, onCheckedChange = { onToggle(w, it) })
                    }
                }
                own.forEach { (i, o) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.typing_pairs_row_yours_pair, arrow(o.typed, o.intended)),
                            color = if (o.enabled && !never) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            modifier = Modifier.weight(1f).clickable { onEditOwn(i) }
                        )
                        Switch(checked = o.enabled, onCheckedChange = { onToggleOwn(i, it) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.typing_pairs_never_row, typed), modifier = Modifier.weight(1f))
                    Switch(checked = never, onCheckedChange = onNever)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.emoji_names_close)) } }
    )
}

@Composable
private fun SmallHeader(text: String) = Text(
    text,
    style = Typography.SmallMl,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
)

private val TypingPairsList = UserSetting(name = R.string.typing_pairs_header, appearsInSearch = false) {
    val (stored, setStored) = useDataStore(TYPING_PAIRS_LIST)
    val (builtInOn, setBuiltIn) = useDataStore(TYPING_PAIRS_BUILT_IN)
    val edits = remember(stored) { TypingPairEdits.parse(stored) }
    fun save(e: TypingPairEdits) = setStored(e.serialize())
    val builtIn = TypingConfusions.builtIn
    val number = NumberFormat.getIntegerInstance()

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }   // index into own, or -1 when adding
    var showing by remember { mutableStateOf<String?>(null) }
    var addingNever by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val q = TypingPairEdits.word(query)
    val results = remember(q, edits, builtInOn) {
        if (q == null) emptyList() else TypingPairEdits.search(builtIn, edits, builtInOn, q, MAX_RESULTS)
    }

    Column {
        Text(
            stringResource(R.string.typing_pairs_header),
            style = Typography.Body.MediumMl,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
        )
        Text(
            stringResource(R.string.typing_pairs_explanation),
            style = Typography.SmallMl,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )
        SettingItem(
            title = stringResource(R.string.typing_pairs_builtin),
            subtitle = stringResource(
                R.string.typing_pairs_builtin_count,
                number.format(builtIn.size), number.format(builtIn.pairs.values.sumOf { it.size })
            ),
            onClick = { setBuiltIn(!builtInOn) },
            compact = true
        ) {
            Switch(checked = builtInOn, onCheckedChange = { setBuiltIn(it) })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.typing_pairs_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (q != null) {
            if (results.isEmpty()) Tip(stringResource(R.string.typing_pairs_no_matches))
            results.take(MAX_RESULTS).forEach { row ->
                val on = row.builtIn.filter { it.second }.map { it.first }
                val yours = row.own.map { edits.own[it] }.filter { it.enabled }.flatMap { it.intended }.distinct()
                SettingItem(
                    title = if (row.never) stringResource(R.string.typing_pairs_never_result, row.typed)
                        else if (on.isEmpty() && yours.isEmpty()) stringResource(R.string.typing_pairs_all_off, row.typed)
                        else if (on.isEmpty()) arrow(row.typed, yours)
                        else arrow(row.typed, on),
                    subtitle = if (on.isNotEmpty() && yours.isNotEmpty()) stringResource(R.string.typing_pairs_row_yours, yours.joinToString(", ")) else null,
                    onClick = { showing = row.typed },
                    compact = true
                ) { }
            }
            if (results.size > MAX_RESULTS) Tip(stringResource(R.string.typing_pairs_more, MAX_RESULTS))
        } else {
            Tip(stringResource(R.string.typing_pairs_search_prompt))
        }

        SmallHeader(stringResource(R.string.typing_pairs_yours))
        if (edits.own.isEmpty()) Tip(stringResource(R.string.typing_pairs_none))
        edits.own.forEachIndexed { i, o ->
            SettingItem(title = arrow(o.typed, o.intended), onClick = { editing = i }, compact = true) {
                Switch(
                    checked = o.enabled,
                    onCheckedChange = { c -> save(edits.copy(own = edits.own.mapIndexed { j, x -> if (j == i) x.copy(enabled = c) else x })) }
                )
            }
        }
        SettingItem(title = stringResource(R.string.typing_pairs_add), onClick = { editing = -1 }, compact = true) {
            Icon(Icons.Default.Add, contentDescription = null)
        }

        SmallHeader(stringResource(R.string.typing_pairs_never_header))
        Text(
            stringResource(R.string.typing_pairs_never_explanation),
            style = Typography.SmallMl,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        edits.never.forEach { w ->
            SettingItem(title = w, onClick = { save(edits.copy(never = edits.never - w)) }, compact = true) {
                Text(stringResource(R.string.typing_pairs_never_remove), style = Typography.SmallMl, color = MaterialTheme.colorScheme.primary)
            }
        }
        SettingItem(title = stringResource(R.string.typing_pairs_never_add), onClick = { addingNever = true }, compact = true) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
        if (!edits.isEmpty || !builtInOn) {
            SettingItem(
                title = stringResource(R.string.typing_pairs_reset),
                subtitle = stringResource(R.string.voice_commands_reset_subtitle),
                onClick = { confirmReset = true },
                compact = true
            ) { }
        }
    }

    showing?.let { typed ->
        BuiltInPairsDialog(
            typed = typed,
            intended = if (builtInOn) builtIn.candidates(typed) else emptyList(),
            off = edits.offBuiltIn.filter { it.first == typed }.mapTo(HashSet()) { it.second },
            builtIn = builtInOn,
            never = typed in edits.never,
            own = edits.own.withIndex().filter { it.value.typed == typed }.map { it.index to it.value },
            onToggle = { w, on ->
                save(edits.copy(offBuiltIn = if (on) edits.offBuiltIn - (typed to w) else edits.offBuiltIn + (typed to w)))
            },
            onToggleOwn = { i, c -> save(edits.copy(own = edits.own.mapIndexed { j, x -> if (j == i) x.copy(enabled = c) else x })) },
            onEditOwn = { i -> showing = null; editing = i },
            onNever = { n -> save(edits.copy(never = if (n) edits.never + typed else edits.never - typed)) },
            onDismiss = { showing = null }
        )
    }
    editing?.let { idx ->
        val original = edits.own.getOrNull(idx)
        OwnPairDialog(
            original = original,
            edits = edits,
            index = idx.takeIf { original != null },
            onSave = { p ->
                save(edits.withOwn(p, replacing = idx.takeIf { original != null }))
                editing = null
            },
            onDelete = original?.let { { save(edits.copy(own = edits.own.filterIndexed { i, _ -> i != idx })); editing = null } },
            onDismiss = { editing = null }
        )
    }
    if (addingNever) {
        NeverWordDialog(
            onSave = { w -> save(edits.copy(never = (edits.never + w).distinct())); addingNever = false },
            onDismiss = { addingNever = false }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.typing_pairs_reset)) },
            text = { Text(stringResource(R.string.typing_pairs_reset_confirm)) },
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

/** The row on the Smart corrections for typing page that opens [TypingPairsMenu]. */
val TypingPairsNavItem = UserSetting(
    name = R.string.typing_pairs_title,
    subtitle = R.string.typing_pairs_nav_subtitle,
    searchTags = R.string.typing_pairs_tags,
) {
    val navController = LocalNavController.current
    SettingItem(
        title = stringResource(R.string.typing_pairs_title),
        subtitle = stringResource(R.string.typing_pairs_nav_subtitle),
        onClick = { navController?.navigate(TypingPairsMenu.navPath) }
    ) {
        Icon(Icons.Default.ArrowForward, contentDescription = null)
    }
}

val TypingPairsMenu = UserSettingsMenu(
    title = R.string.typing_pairs_title,
    navPath = "typingPairs", registerNavPath = true,
    settings = listOf(TypingPairsList)
)
