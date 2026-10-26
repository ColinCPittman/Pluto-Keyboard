package org.futo.inputmethod.latin.uix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.voiceinput.shared.context.TypingPairEdits

/**
 * The strip's chip for Smart corrections for typing changes with what's going on. One word selected
 * ("Add correction"), a fix just undone ("Stop pour → pout"), else the newest fix.
 */
@Composable
fun RowScope.TypingStripChip(fixes: List<TypingFix>, onOpenList: () -> Unit, onAddCorrection: () -> Unit) {
    val selected = TypingFixes.selectedWord.value
    val stop = TypingFixes.stopOffer.value
    when {
        selected != null -> SmallChip(
            label = { Text(stringResource(R.string.typing_add_correction_chip), style = Typography.Small, maxLines = 1) },
            icon = R.drawable.plus_circle,
            description = stringResource(R.string.typing_add_correction_description, selected),
            onClick = onAddCorrection,
        )
        stop != null -> {
            val context = LocalContext.current
            SmallChip(
                label = {
                    Text(
                        buildAnnotatedString {
                            append(stringResource(R.string.typing_fix_stop_chip))
                            append(" ")
                            append(stop.original)
                            append("→")
                            append(stop.replacement)
                        },
                        style = Typography.Small, maxLines = 1
                    )
                },
                icon = null,
                description = stringResource(R.string.typing_fix_stop_description, stop.original, stop.replacement),
                onClick = { TypingFixes.stopFix(context, stop) },
            )
        }
        fixes.isNotEmpty() -> TypingFixChip(fixes, onOpenList)
    }
}

@Composable
private fun SmallChip(label: @Composable () -> Unit, icon: Int?, description: String, onClick: () -> Unit) {
    val manager = LocalManager.current
    val view = LocalView.current
    Row(Modifier.fillMaxHeight().padding(vertical = 6.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = LocalKeyboardScheme.current.keyboardContainer, shape = RoundedCornerShape(8.dp)) {
            CompositionLocalProvider(LocalContentColor provides LocalKeyboardScheme.current.onKeyboardContainer) {
                Row(
                    Modifier
                        .clickable {
                            manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                            onClick()
                        }
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = description
                            onClick(action = { onClick(); true })
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp))
                    label()
                }
            }
        }
    }
}

/**
 * "Add correction" for the selected word. Type (or pick) the word you meant, and it's saved as your
 * typing pair and put in place of the selection.
 */
val AddCorrectionAction = Action(
    icon = R.drawable.plus_circle,
    name = R.string.typing_add_correction_title,
    canShowKeyboard = true,
    shownInEditor = false,
    simplePressImpl = null,
    windowImpl = { manager, _ ->
        val typed = TypingFixes.selectedWord.value?.lowercase()
        val meant = mutableStateOf("")
        object : ActionWindow() {
            override val onlyShowAboveKeyboard: Boolean get() = true
            override val positionIsUserManagable: Boolean get() = false

            @Composable
            override fun windowName(): String = stringResource(R.string.typing_add_correction_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val context = LocalContext.current
                if (typed == null) {
                    LaunchedEffect(Unit) { manager.closeActionWindow() }
                    return
                }
                val word = TypingPairEdits.word(meant.value)?.takeIf { it != typed }
                val save = {
                    if (word != null) {
                        manager.closeActionWindow()
                        TypingFixes.addCorrection(context, typed, word)
                    }
                }
                // words it may be fixed into now (the pairs in use)
                val ideas = remember { ContextModels.typingPairs(context).candidates(typed) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.typing_add_correction_prompt, typed), style = Typography.Body.Regular)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = LocalKeyboardScheme.current.surface,
                            border = BorderStroke(1.dp, SolidColor(LocalKeyboardScheme.current.outline)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 8.dp)) {
                                ActionTextEditor(
                                    meant, autocorrect = false, onEnter = { save() },
                                    placeholder = stringResource(R.string.typing_add_correction_hint),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { save() }, enabled = word != null, shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.typing_add_correction_save))
                        }
                    }
                    if (ideas.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ideas.forEach { w ->
                                SmallChip(label = { Text(w, style = Typography.Small) }, icon = null, description = w, onClick = { meant.value = w })
                            }
                        }
                    }
                }
            }
        }
    }
)

/**
 * The newest fix ("an → and ↶", a tap reverts it) and, with more than one, a "+N" badge that
 * opens the review list.
 */
@Composable
fun RowScope.TypingFixChip(fixes: List<TypingFix>, onOpenList: () -> Unit) {
    val fix = fixes.lastOrNull() ?: return
    val manager = LocalManager.current
    val view = LocalView.current
    val revertLabel = stringResource(R.string.typing_fix_revert_description, fix.replacement, fix.original)
    Row(Modifier.fillMaxHeight().padding(vertical = 6.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = LocalKeyboardScheme.current.keyboardContainer, shape = RoundedCornerShape(8.dp)) {
            CompositionLocalProvider(LocalContentColor provides LocalKeyboardScheme.current.onKeyboardContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .clickable {
                                manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                                TypingFixes.revert(fix)
                            }
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = revertLabel
                                onClick(action = { TypingFixes.revert(fix); true })
                            }
                            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(fix.original) }
                                append("→")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(fix.replacement) }
                            },
                            style = Typography.Small, maxLines = 1
                        )
                        Icon(painterResource(R.drawable.undo), contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    if (fixes.size > 1) {
                        val more = stringResource(R.string.typing_fix_more_description, fixes.size)
                        Box(
                            Modifier
                                .clickable {
                                    manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                                    onOpenList()
                                }
                                .clearAndSetSemantics {
                                    role = Role.Button
                                    contentDescription = more
                                    onClick(action = { onOpenList(); true })
                                }
                                .padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text("+${fixes.size - 1}", style = Typography.Small.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}

/** The review list shows every fix of this text field in context, each revertable on its own. */
val TypingFixListAction = Action(
    icon = R.drawable.undo,
    name = R.string.typing_fix_list_title,
    canShowKeyboard = true,
    shownInEditor = false,
    simplePressImpl = null,
    windowImpl = { manager, _ ->
        object : ActionWindow() {
            override val onlyShowAboveKeyboard: Boolean get() = true
            override val positionIsUserManagable: Boolean get() = false

            @Composable
            override fun windowName(): String = stringResource(R.string.typing_fix_list_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val fixes = TypingFixes.fixes.value
                LaunchedEffect(Unit) { TypingFixes.refreshStale() }
                LaunchedEffect(fixes.isEmpty()) { if (fixes.isEmpty()) manager.closeActionWindow() }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(fixes.asReversed(), key = { it.id }) { fix -> TypingFixRow(fix) }
                }
            }
        }
    }
)

@Composable
private fun TypingFixRow(fix: TypingFix) {
    val label = if (fix.stale) stringResource(R.string.typing_fix_stale_description, fix.original, fix.replacement)
        else stringResource(R.string.typing_fix_revert_description, fix.replacement, fix.original)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (fix.stale) 0.4f else 1f)
            .clickable(enabled = !fix.stale) { TypingFixes.revert(fix) }
            .clearAndSetSemantics {
                contentDescription = label
                if (!fix.stale) {
                    role = Role.Button
                    onClick(action = { TypingFixes.revert(fix); true })
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildAnnotatedString {
                // an ellipsis only where the context was cut off
                if (fix.before.length >= TypingCorrections.CONTEXT_CHARS) append("…")
                append(fix.before.trimStart())
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(fix.original) }
                append(" → ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(fix.replacement) }
                append(fix.after.trimEnd())
                if (fix.after.length >= TypingCorrections.CONTEXT_CHARS) append("…")
            },
            style = Typography.Body.Regular, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(painterResource(R.drawable.undo), contentDescription = null, modifier = Modifier.padding(start = 12.dp).size(20.dp))
    }
}
