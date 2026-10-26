package org.futo.inputmethod.latin.uix.settings.pages.pdict

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.dictionarytools.LearnedWord
import org.futo.inputmethod.latin.dictionarytools.LearnedWords
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.theme.Typography

const val CopyLearnedWordsRoute = "copyLearnedWords"
const val HiddenLearnedWordsRoute = "hiddenLearnedWords"

private fun LearnedWord.key() = locale + "\u0000" + word

/**
 * Personal dictionary → Copy learned words to dictionary lets you review the words the keyboard learned
 * (that the dictionaries don't know yet) and copy the chosen ones to Android's personal dictionary.
 * Nothing is written until the user taps "Copy N words".
 */
@Composable
fun CopyLearnedWordsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hiddenStore = remember { LearnedWords.hiddenWords(context) }

    var loading by remember { mutableStateOf(true) }
    var copying by remember { mutableStateOf(false) }
    val sensitive = remember { mutableStateListOf<LearnedWord>() }
    val normal = remember { mutableStateListOf<LearnedWord>() }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var hiddenCount by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (candidates, hidden) = withContext(Dispatchers.IO) {
            val c = try { LearnedWords.copyCandidates(context) } catch (e: Exception) { null }
            c to hiddenStore.all().size
        }
        sensitive.clear(); normal.clear(); checked.clear()
        candidates?.let {
            sensitive.addAll(it.sensitive)
            normal.addAll(it.normal)
            it.sensitive.forEach { w -> checked[w.key()] = false }
            it.normal.forEach { w -> checked[w.key()] = true }
        }
        hiddenCount = hidden
        failed = candidates == null
        loading = false
    }

    val showLanguages = remember(sensitive.size, normal.size) {
        (sensitive + normal).map { it.locale }.distinct().size > 1
    }
    val selected = (sensitive + normal).filter { checked[it.key()] == true }

    fun hide(w: LearnedWord) {
        scope.launch {
            withContext(Dispatchers.IO) { hiddenStore.add(w.word) }
            sensitive.remove(w); normal.remove(w); checked.remove(w.key())
            hiddenCount = withContext(Dispatchers.IO) { hiddenStore.all().size }
        }
    }

    fun forget(w: LearnedWord) {
        scope.launch {
            withContext(Dispatchers.IO) { LearnedWords.forget(context, w) }
            sensitive.remove(w); normal.remove(w); checked.remove(w.key())
            Toast.makeText(context, context.getString(R.string.pluto_copy_learned_words_forgotten, w.word), Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1.0f)) {
            item {
                ScreenTitle(
                    pluralStringResource(R.plurals.pluto_copy_learned_words_title, selected.size, selected.size),
                    showBack = true,
                    navController = navController
                )
                Tip(stringResource(R.string.pluto_copy_learned_words_explanation))
            }

            if (loading) {
                item { BodyText(stringResource(R.string.pluto_copy_learned_words_loading)) }
            } else if (failed) {
                item { BodyText(stringResource(R.string.pluto_copy_learned_words_failed)) }
            } else if (sensitive.isEmpty() && normal.isEmpty()) {
                item { BodyText(stringResource(R.string.pluto_copy_learned_words_none)) }
            }

            if (sensitive.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.pluto_copy_learned_words_sensitive_header), MaterialTheme.colorScheme.error)
                    BodyText(stringResource(R.string.pluto_copy_learned_words_sensitive_explanation))
                }
                items(sensitive, key = { "s" + it.key() }) { w ->
                    WordRow(
                        word = w,
                        showLanguage = showLanguages,
                        checked = checked[w.key()] == true,
                        onCheckedChange = { checked[w.key()] = it }
                    ) {
                        TextButton(onClick = { forget(w) }) { Text(stringResource(R.string.pluto_copy_learned_words_forget)) }
                        TextButton(onClick = { hide(w) }) { Text(stringResource(R.string.pluto_copy_learned_words_never)) }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }

            if (normal.isNotEmpty()) {
                if (sensitive.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.pluto_copy_learned_words_words_header), MaterialTheme.colorScheme.primary) }
                }
                items(normal, key = { "n" + it.key() }) { w ->
                    WordRow(
                        word = w,
                        showLanguage = showLanguages,
                        checked = checked[w.key()] == true,
                        onCheckedChange = { checked[w.key()] = it }
                    ) {
                        TextButton(onClick = { hide(w) }) { Text(stringResource(R.string.pluto_copy_learned_words_never)) }
                    }
                }
            }

            if (!loading) {
                item {
                    NavigationItem(
                        title = stringResource(R.string.pluto_copy_learned_words_hidden_link, hiddenCount),
                        style = NavigationItemStyle.Misc,
                        navigate = { navController.navigate(HiddenLearnedWordsRoute) }
                    )
                }
            }
        }

        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { navController.navigateUp() }) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = selected.isNotEmpty() && !loading && !copying,
                onClick = {
                    copying = true
                    val words = selected.toList()
                    scope.launch {
                        val added = withContext(Dispatchers.IO) {
                            try { LearnedWords.copyToPersonalDictionary(context, words) } catch (e: Exception) { null }
                        }
                        copying = false
                        if (added == null) {
                            Toast.makeText(context, context.getString(R.string.pluto_copy_learned_words_failed), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(R.plurals.pluto_copy_learned_words_done, added, added),
                                Toast.LENGTH_SHORT
                            ).show()
                            navController.navigateUp()
                        }
                    }
                }
            ) {
                Text(pluralStringResource(R.plurals.pluto_copy_learned_words_copy_button, selected.size, selected.size))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = Typography.Body.RegularMl,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun WordRow(
    word: LearnedWord,
    showLanguage: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    actions: @Composable () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.weight(1.0f)) {
            Text(word.word, style = Typography.Body.RegularMl, color = MaterialTheme.colorScheme.onSurface)
            if (showLanguage) {
                Text(
                    localeFromString(word.locale).getDisplayName(LocalConfiguration.current.locale),
                    style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) { actions() }
    }
}

/** Copy learned words → Hidden words lists words the user chose to never offer for copying. */
@Composable
fun HiddenLearnedWordsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LearnedWords.hiddenWords(context) }
    val words = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val all = withContext(Dispatchers.IO) { store.all() }
        words.clear(); words.addAll(all.sortedBy { it.lowercase() })
        loaded = true
    }

    ScrollableList {
        ScreenTitle(stringResource(R.string.pluto_hidden_learned_words_title), showBack = true, navController = navController)
        Tip(stringResource(R.string.pluto_hidden_learned_words_explanation))
        if (loaded && words.isEmpty()) {
            BodyText(stringResource(R.string.pluto_hidden_learned_words_none))
        }
        words.toList().forEach { w ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(w, style = Typography.Body.RegularMl, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.0f))
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.remove(w) }
                            words.remove(w)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors()
                ) { Text(stringResource(R.string.pluto_hidden_learned_words_unhide)) }
            }
        }
    }
}
