package org.futo.inputmethod.latin.uix

import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.animation.core.tween
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration
import org.futo.inputmethod.latin.DisplayTop4Setting
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo.KIND_EMOJI_SUGGESTION
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo.KIND_TYPED
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewListener
import org.futo.inputmethod.latin.uix.actions.FavoriteActions
import org.futo.inputmethod.latin.uix.actions.MoreActionsAction
import org.futo.inputmethod.latin.uix.actions.PinnedActions
import org.futo.inputmethod.latin.uix.actions.HoldToTalk
import org.futo.inputmethod.latin.uix.actions.VoiceInputAction
import org.futo.inputmethod.latin.uix.actions.toActionList
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.LocalCompatEmojiFamily
import org.futo.inputmethod.latin.uix.theme.LocalCompatEmojiTypeface
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.latin.uix.theme.UixThemeWrapper
import org.futo.inputmethod.latin.uix.theme.emojiNeedsCompat
import org.futo.inputmethod.latin.uix.theme.presets.DefaultDarkScheme
import org.futo.inputmethod.latin.uix.theme.presets.DynamicDarkTheme
import org.futo.inputmethod.latin.uix.theme.presets.DynamicLightTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The UIX Action Bar is intended to replace the previous top bar of the AOSP keyboard.
 * Its goal is to function similar to the old top bar by showing predictions, but also modernize
 * it with actions and new features.
 *
 * Example bar:
 * [>] word1 | word2 | word3 [mic]
 *
 * The [>] button expands the action bar, replacing word predictions with actions the user can take.
 * Actions have little icons which perform an action. Some examples:
 * - Microphone: opens the voice input menu
 * - Undo/Redo
 * - Text editing: switches to the text editing menu
 * - Settings: opens the keyboard settings menu
 * - Report problem: opens the report menu
 *
 * Generally there are a few kinds of actions:
 * - Take an action on the text being typed (undo/redo)
 * - Switch from the keyboard UI to something else (voice input, text editing)
 * - Open an app (settings, report)
 *
 * The UIX effort is to modernize the AOSP Keyboard by replacing and extending
 * parts of it with UI written in Android Compose, while keeping most of the
 * battle-tested original keyboard code the same
 *
 * TODO: Will need to make RTL languages work
 */

val ActionBarHeight = 40.dp

val ActionBarScrollIndexSetting = SettingsKey(
    intPreferencesKey("action_bar_scroll_index"),
    0
)

val ActionBarScrollOffsetSetting = SettingsKey(
    intPreferencesKey("action_bar_scroll_offset"),
    0
)

val ActionBarExpanded = SettingsKey(
    booleanPreferencesKey("actionExpanded"),
    false
)

val OldStyleActionsBar = SettingsKey(
    booleanPreferencesKey("oldActionBar"),
    false
)


interface ImportantNotice {
    @Composable fun getText(): String
    fun onDismiss(context: Context, auto: Boolean = false)
    fun onOpen(context: Context)

    /**
     * Pluto: a notice with a separate action button. When this returns a label, the notice shows
     * [getHeadline] over [getText] with the button (which calls [onOpen]) and a close icon,
     * instead of one tappable line.
     */
    @Composable fun getActionLabel(): String? = null

    /** First line of a notice with an action button (for example the word it is about). */
    @Composable fun getHeadline(): String? = null
}


val suggestionStylePrimary = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    lineHeight = 26.sp,
    letterSpacing = 0.5.sp,
    //textAlign = TextAlign.Center
)

val suggestionStyleAlternative = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 18.sp,
    lineHeight = 26.sp,
    letterSpacing = 0.5.sp,
    //textAlign = TextAlign.Center
)

val suggestionStyleCandidateDescription = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 8.sp,
    lineHeight = 8.sp,
    letterSpacing = 0.5.sp,
    //textAlign = TextAlign.Center
)


@Composable
fun actionBarColor(): Color =
    if(LocalInspectionMode.current) {
        LocalKeyboardScheme.current.keyboardSurface
    } else if(LocalKeyboardScheme.current.extended.advancedThemeOptions.backgroundImage != null) {
        LocalKeyboardScheme.current.keyboardSurface.copy(alpha = LocalKeyboardScheme.current.extended.advancedThemeOptions.actionBarOpacity)
    } else if(LocalKeyboardScheme.current.keyboardBackgroundGradient != null) {
        Color.Transparent
    } else {
        LocalThemeProvider.current.actionBarColor
    }


// Automatically try to fit the given text to the available space in one line.
// If text is too long, the text gets scaled horizontally to fit.
// TODO: Could also put ellipsis in the middle
@OptIn(ExperimentalTextApi::class)
@Composable
fun AutoFitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr
) {
    val measurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxSize(), contentDescription = text) {
        val measurement = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(
                maxWidth = Int.MAX_VALUE,
                maxHeight = ceil(this.size.height).roundToInt()
            ),
            layoutDirection = layoutDirection,
            density = this
        )

        val scale = (size.width / measurement.size.width).coerceAtMost(1.0f)

        translate(left = (scale * (size.width - measurement.size.width)) / 2.0f, top = size.height / 2 - measurement.size.height / 2) {
            scale(scaleX = scale, scaleY = 1.0f) {
                drawText(
                    measurement
                )
            }
        }
    }
}

@Composable
fun TextStyle.withCustomFont(orDefault: FontFamily? = null): TextStyle {
    val typeface = LocalKeyboardScheme.current.extended.advancedThemeOptions.font
    if(typeface != null) {
        val family = FontFamily(typeface)
        return this.copy(fontFamily = family)
    } else if (orDefault != null) {
        return this.copy(fontFamily = orDefault)
    } else {
        return this
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScope.SuggestionItem(words: SuggestedWords, idx: Int, isPrimary: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, forcePrimary: Boolean, isEmoji: Boolean) {
    val wordInfo = words.getInfoOrNull(idx)
    val isVerbatim = wordInfo?.kind == KIND_TYPED
    val word = wordInfo?.mWord

    val isAutocorrect = forcePrimary || (isPrimary && words.mWillAutoCorrect)

    val color = when(isAutocorrect) {
        true -> LocalKeyboardScheme.current.onSurface
        else -> LocalKeyboardScheme.current.onSurfaceVariant
    }

    val iconToUse = when {
        wordInfo?.mOriginatesFromTransformerLM == true -> painterResource(id = R.drawable.transformer_suggestion)
        wordInfo?.mOriginatesFromSwipeModel    == true -> painterResource(id = R.drawable.swipemodel_indicator)
        else -> null
    }
    val textButtonModifier = if(iconToUse != null) {
        Modifier.drawBehind {
            with(iconToUse) {
                val iconSize = iconToUse.intrinsicSize
                translate(
                    left = (size.width - iconSize.width) / 2.0f,
                    top = size.height - iconSize.height * (if(wordInfo?.mOriginatesFromSwipeModel == true) 1.3f else 2.0f)
                ) {
                    draw(
                        iconToUse.intrinsicSize,
                        colorFilter = ColorFilter.tint(color = color)
                    )
                }
            }
        }
    } else {
        Modifier
    }

    val compatTypeface = LocalCompatEmojiTypeface.current
    val compatFamily = LocalCompatEmojiFamily.current
    val potentiallyCompatEmojiFont = remember(word, isEmoji) {
        if(word != null && isEmoji && emojiNeedsCompat(word, compatTypeface)) {
            compatFamily
        } else {
            null
        }
    }

    val textStyle = when(isAutocorrect) {
        true -> suggestionStylePrimary
        false -> suggestionStyleAlternative
    }.copy(color = color).withCustomFont(potentiallyCompatEmojiFont)

    Box(
        modifier = textButtonModifier
            .weight(1.0f)
            .fillMaxHeight()
            .combinedClickable(
                enabled = word != null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("SuggestionItem"),
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            if (word != null) {
                val modifier = Modifier
                    .align(Center)
                    .padding(2.dp)
                    .testTag("SuggestionItemText")
                if(isVerbatim) {
                    AutoFitText('"' + word + '"', style = textStyle.copy(fontStyle = FontStyle.Italic), modifier = modifier)
                } else {
                    AutoFitText(word, style = textStyle, modifier = modifier)
                }
            }
        }
    }
}

@Composable fun RowScope.SuggestionSeparator() {
    Box(
        modifier = Modifier
            .fillMaxHeight(0.66f)
            .align(CenterVertically)
            .background(color = LocalKeyboardScheme.current.outline)
            .width(1.dp)
    )
}


data class SuggestionLayout(
    /** Set to the word to be autocorrected to */
    val autocorrectMatch: SuggestedWordInfo?,

    /** Other words, sorted by likelihood */
    val sortedMatches: List<SuggestedWordInfo>,

    /** Emoji suggestions if they are to be shown */
    val emojiMatches: List<SuggestedWordInfo>,

    /** The exact word the user typed */
    val verbatimWord: SuggestedWordInfo?,

    /** Set to true if the best match is so unlikely that we should show verbatim instead */
    val areSuggestionsClueless: Boolean,

    /** Set to true if this is a gesture update, and we should only show one suggestion */
    val isGestureBatch: Boolean,

    /** The primary tail batch element, if present it should be highlighted */
    val swipePrimaryElement: SuggestedWordInfo?,

    /**
     * Set in "always use middle suggestion" mode when there is no autocorrection. The typed word
     * is what gets committed, so it is shown (highlighted) in the middle slot.
     */
    val verbatimInMiddle: SuggestedWordInfo?,

    val presentableSuggestions: List<SuggestedWordInfo>
)

fun SuggestedWords.getInfoOrNull(idx: Int): SuggestedWordInfo? = try {
    getInfo(idx)
} catch(e: IndexOutOfBoundsException) {
    null
}

fun makeSuggestionLayout(words: SuggestedWords, blacklist: SuggestionBlacklist?, swipeTailRemovePrimarySuggestion: Boolean): SuggestionLayout {
    val isGestureBatch = words.mInputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH
    val isSwipeTail = words.mInputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH

    val typedWord = words.getInfoOrNull(SuggestedWords.INDEX_OF_TYPED_WORD)?.let {
        if(it.kind == KIND_TYPED) { it } else { null }
    }?.let {
        if(blacklist?.isSuggestedWordOk(it) != false) {
            it
        } else {
            null
        }
    }

    val autocorrectMatch = words.getInfoOrNull(SuggestedWords.INDEX_OF_AUTO_CORRECTION)?.let {
        if(words.mWillAutoCorrect) { it } else { null }
    }

    // We actually have to avoid sorting these because they are provided sorted in an important order

    val emojiMatches = words.mSuggestedWordInfoList.filter {
        it.kind == KIND_EMOJI_SUGGESTION
    }

    val sortedMatches = words.mSuggestedWordInfoList.filter {
        it != typedWord && it.kind != KIND_TYPED && it != autocorrectMatch && !emojiMatches.contains(it)
            // Do not include the verbatim word when autocorrecting to avoid such duplicate word situation:
            // [ hid | **his** | "hid" ]
            // Same when the verbatim word is shown in the middle slot (always use middle suggestion mode)
            && (isGestureBatch || (autocorrectMatch == null && !words.mAlwaysUseMiddleSuggestion) || typedWord == null || it.mWord != typedWord.mWord)
    }.toMutableList()

    var swipePrimaryElement: SuggestedWordInfo? = null
    if(isSwipeTail && sortedMatches.size > 1) {
        if(swipeTailRemovePrimarySuggestion) {
            sortedMatches.removeAt(0)
        } else {
            swipePrimaryElement = sortedMatches[0]
        }
    }

    val areSuggestionsClueless = (autocorrectMatch ?: sortedMatches.getOrNull(0))?.let {
        it.mOriginatesFromTransformerLM && it.mScore < -50
    } ?: false

    val verbatimInMiddle = if(
        words.mAlwaysUseMiddleSuggestion && !isGestureBatch && !isSwipeTail && autocorrectMatch == null
    ) { typedWord } else { null }

    val presentableSuggestions = (
            listOf(
                typedWord,
                autocorrectMatch,
            ) + sortedMatches
    ).filterNotNull()

    return SuggestionLayout(
        autocorrectMatch = autocorrectMatch,
        sortedMatches = sortedMatches,
        emojiMatches = emojiMatches,
        verbatimWord = typedWord,
        areSuggestionsClueless = areSuggestionsClueless,
        isGestureBatch = isGestureBatch,
        presentableSuggestions = presentableSuggestions,
        swipePrimaryElement = swipePrimaryElement,
        verbatimInMiddle = verbatimInMiddle
    )
}

@Composable
fun RowScope.SuggestionItems(words: SuggestedWords, onClick: (i: Int) -> Unit, onLongClick: (i: Int) -> Unit) {
    val layout = makeSuggestionLayout(
        words = words,
        blacklist = null,
        swipeTailRemovePrimarySuggestion = useDataStoreValue(DisplayTop4Setting)
    )

    val suggestionItem = @Composable { suggestion: SuggestedWordInfo? ->
        if(suggestion != null) {
            val idx = words.indexOf(suggestion)
            SuggestionItem(
                words,
                idx,
                isPrimary = idx == SuggestedWords.INDEX_OF_AUTO_CORRECTION,
                forcePrimary = suggestion == layout.swipePrimaryElement || suggestion == layout.verbatimInMiddle,
                onClick = { onClick(idx) },
                onLongClick = { onLongClick(idx) },
                isEmoji = suggestion in layout.emojiMatches
            )
        } else {
            Spacer(Modifier.weight(1.0f))
        }
    }

    when {
        layout.isGestureBatch ||
                (layout.emojiMatches.isEmpty() && layout.presentableSuggestions.size <= 1) ->
            suggestionItem(layout.presentableSuggestions.firstOrNull())

        // In "Always use middle suggestion" mode the middle slot is always what will be committed.
        // The verbatim word is preferably placed on the left, so it can be tapped to keep it.
        layout.autocorrectMatch != null && words.mAlwaysUseMiddleSuggestion -> {
            val verbatimDiffers = layout.verbatimWord != null
                    && layout.verbatimWord.mWord != layout.autocorrectMatch.mWord
            var supplementalSuggestionIndex = 0
            if(layout.emojiMatches.isNotEmpty()) {
                suggestionItem(layout.emojiMatches[0])
            } else if(verbatimDiffers) {
                suggestionItem(layout.verbatimWord)
            } else {
                suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex++))
            }
            SuggestionSeparator()
            suggestionItem(layout.autocorrectMatch)
            SuggestionSeparator()

            if(layout.emojiMatches.isNotEmpty() && verbatimDiffers) {
                suggestionItem(layout.verbatimWord)
            } else {
                suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex))
            }
        }

        layout.verbatimInMiddle != null -> {
            var supplementalSuggestionIndex = 0
            if(layout.emojiMatches.isEmpty()) {
                suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex++))
            } else {
                suggestionItem(layout.emojiMatches[0])
            }
            SuggestionSeparator()
            suggestionItem(layout.verbatimInMiddle)
            SuggestionSeparator()
            suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex))
        }

        layout.autocorrectMatch != null -> {
            var supplementalSuggestionIndex = 0
            if(layout.emojiMatches.isEmpty()) {
                suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex++))
            } else {
                suggestionItem(layout.emojiMatches[0])
            }
            SuggestionSeparator()
            suggestionItem(layout.autocorrectMatch)
            SuggestionSeparator()

            if(layout.verbatimWord != null && layout.verbatimWord.mWord != layout.autocorrectMatch.mWord) {
                suggestionItem(layout.verbatimWord)
            } else {
                suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex))
            }
        }

        else -> {
            var supplementalSuggestionIndex = 1
            if(layout.emojiMatches.isEmpty()) {
                suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex++))
            } else {
                suggestionItem(layout.emojiMatches[0])
            }
            SuggestionSeparator()
            suggestionItem(layout.sortedMatches.getOrNull(0))
            SuggestionSeparator()
            suggestionItem(layout.sortedMatches.getOrNull(supplementalSuggestionIndex))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyItemScope.ActionItem(idx: Int, action: Action, onSelect: (Action) -> Unit, onLongSelect: (Action) -> Unit) {
    val width = 56.dp

    val modifier = Modifier
        .width(width)
        .fillMaxHeight()

    val contentCol = LocalKeyboardScheme.current.onBackground

    Box(modifier = modifier
        .clip(CircleShape)
        .combinedClickable(
            onLongClick = action.altPressImpl?.let { { onLongSelect(action) } },
            onClick = { onSelect(action) }), contentAlignment = Center) {
        Icon(
            painter = painterResource(id = action.icon),
            contentDescription = stringResource(action.name),
            tint = contentCol,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Pluto: letting go of the mic sooner than this is a tap (it opens the voice window). */
private const val HOLD_TO_TALK_TAP_MS = 250L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionItemSmall(action: Action, onSelect: (Action) -> Unit, onLongSelect: (Action) -> Unit, onHold: ((Action) -> Boolean)? = null) {
    val bgCol = LocalKeyboardScheme.current.keyboardContainer
    val fgCol = LocalKeyboardScheme.current.onKeyboardContainer

    val circleRadius = with(LocalDensity.current) {
        16.dp.toPx()
    }

    // Pluto: the mic button can be held to talk (see HoldToTalk)
    val holdable = onHold != null && action == VoiceInputAction
    val holdPhase = if (holdable) HoldToTalk.phase.value else HoldToTalk.Phase.Idle
    val ringCol = LocalKeyboardScheme.current.primary
    val view = LocalView.current

    val pressModifier = if (holdable) {
        Modifier.onGloballyPositioned { HoldToTalk.micCenter.value = it.boundsInRoot().center }.semantics { onClick { onSelect(action); true } }.pointerInput(action) {
            awaitEachGesture {
                awaitFirstDown()
                // still finishing the last one, so this press does nothing
                if (HoldToTalk.phase.value != HoldToTalk.Phase.Idle) {
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                // listening starts the moment the finger is down, and a quick tap drops it again
                val started = onHold?.invoke(action) == true
                var cancelled = false
                val up = withTimeoutOrNull(HOLD_TO_TALK_TAP_MS) {
                    waitForUpOrCancellation().also { if (it == null) cancelled = true }
                }
                when {
                    !started -> {
                        // it couldn't start (no permission or no model), so the voice window explains why
                        val released = up != null || (!cancelled && waitForUpOrCancellation() != null)
                        if (released) onSelect(action)
                    }
                    up != null || cancelled -> {
                        HoldToTalk.drop(windowOpens = up != null)
                        if (up != null) onSelect(action)
                    }
                    else -> {
                        HoldToTalk.confirm()
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        try {
                            // let go anywhere (the finger may drift off the button while talking)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            HoldToTalk.release()
                        }
                    }
                }
            }
        }
    } else {
        Modifier.combinedClickable(onLongClick = action.altPressImpl?.let { { onLongSelect(action) } }) {
            onSelect(
                action
            )
        }
    }

    Box(modifier = Modifier
        .width(42.dp)
        .fillMaxHeight()
        .drawBehind {
            drawCircle(
                color = bgCol,
                radius = circleRadius,
                style = Fill
            )
        }
        .clip(CircleShape)
        .then(pressModifier),
        contentAlignment = Center
    ) {
        if (holdPhase == HoldToTalk.Phase.Finishing) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = ringCol,
                strokeWidth = 2.5.dp
            )
        }
        Icon(
            painter = painterResource(id = action.icon),
            contentDescription = stringResource(action.name),
            tint = fgCol,
            modifier = Modifier.size(16.dp)
        )
    }
}


@Composable
fun ActionItems(onSelect: (Action) -> Unit, onLongSelect: (Action) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val actions = if(!LocalInspectionMode.current) {
        useDataStoreValue(FavoriteActions)
    } else {
        FavoriteActions.default
    }

    val scrollItemIndex = if(LocalInspectionMode.current) { 0 } else {
        remember {
            context.getSettingBlocking(ActionBarScrollIndexSetting)
        }
    }

    val scrollItemOffset = if(LocalInspectionMode.current) { 0 } else {
        remember {
            context.getSettingBlocking(ActionBarScrollOffsetSetting)
        }
    }

    val actionItems = remember(actions) {
        actions.toActionList().toSet().toList()
    }

    val lazyListState = rememberLazyListState(scrollItemIndex, scrollItemOffset)

    DisposableEffect(Unit) {
        onDispose {
            lifecycle.deferSetSetting(context, ActionBarScrollIndexSetting, lazyListState.firstVisibleItemIndex)
            lifecycle.deferSetSetting(context, ActionBarScrollOffsetSetting, lazyListState.firstVisibleItemScrollOffset)
        }
    }

    val gradientColor = LocalKeyboardScheme.current.keyboardSurfaceDim

    val drawLeftGradient = remember { derivedStateOf { lazyListState.firstVisibleItemIndex > 0 } }
    val drawRightGradient = remember(actionItems) { derivedStateOf {
        lazyListState.layoutInfo.visibleItemsInfo.isNotEmpty()
                && actionItems.isNotEmpty()
                && (lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.key != actionItems.lastOrNull()?.name)
    } }

    Box(Modifier.safeKeyboardPadding()) {
        LazyRow(state = lazyListState) {
            item {
                ActionItemSmall(action = MoreActionsAction, onSelect = {
                    onSelect(MoreActionsAction)
                }, onLongSelect = { })

            }
            items(actionItems.size, key = { actionItems[it].name }) {
                ActionItem(it, actionItems[it], onSelect, onLongSelect)
            }
        }


        if(drawLeftGradient.value) {
            Canvas(modifier = Modifier
                .fillMaxHeight()
                .width(72.dp)
                .align(Alignment.CenterStart)) {
                for(i in 0 until 1) {
                    drawRect(
                        Brush.linearGradient(
                            0.0f to gradientColor,
                            1.0f to Color.Transparent,
                            start = Offset.Zero,
                            end = Offset(Float.POSITIVE_INFINITY, 0.0f)
                        )
                    )
                }
            }
        }

        if(drawRightGradient.value) {
            Canvas(modifier = Modifier
                .fillMaxHeight()
                .width(72.dp)
                .align(Alignment.CenterEnd)) {
                for(i in 0 until 1) {
                    drawRect(
                        Brush.linearGradient(
                            0.0f to Color.Transparent,
                            1.0f to gradientColor,
                            start = Offset.Zero,
                            end = Offset(Float.POSITIVE_INFINITY, 0.0f)
                        )
                    )
                }
            }
        }
    }
}


@Composable
fun ExpandActionsButton(isActionsOpen: Boolean, onClick: () -> Unit) {
    val bgCol = LocalKeyboardScheme.current.keyboardContainer
    val fgCol = LocalKeyboardScheme.current.onKeyboardContainer

    val circleRadius = with(LocalDensity.current) {
        16.dp.toPx()
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .width(42.dp)
            .rotate(
                if (isActionsOpen) {
                    -90.0f
                } else {
                    0.0f
                }
            )
            .fillMaxHeight()
            .drawBehind {
                drawCircle(
                    color = bgCol,
                    radius = circleRadius,
                    style = Fill
                )
            },

        colors = IconButtonDefaults.iconButtonColors(contentColor = fgCol)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.chevron_right),
            contentDescription = stringResource(
                if (!isActionsOpen) R.string.keyboard_actionbar_expand_actions_talkback
                else R.string.keyboard_actionbar_collapse_actions_talkback
            ),
            Modifier.size(20.dp)
        )
    }
}

@Composable
fun ImportantNoticeView(
    importantNotice: ImportantNotice
) {
    val context = LocalContext.current

    val actionLabel = importantNotice.getActionLabel()
    if (actionLabel != null) {
        ActionNoticeView(importantNotice, actionLabel)
        return
    }

    Row {
        TextButton(
            onClick = { importantNotice.onOpen(context) },
            modifier = Modifier
                .weight(1.0f)
                .fillMaxHeight(),
            shape = RectangleShape,
            colors = ButtonDefaults.textButtonColors(contentColor = LocalKeyboardScheme.current.onBackground),
            enabled = true
        ) {
            AutoFitText(importantNotice.getText(), style = suggestionStylePrimary.copy(color = LocalKeyboardScheme.current.onBackground).withCustomFont())
        }

        val color = LocalKeyboardScheme.current.primary
        IconButton(
            onClick = { importantNotice.onDismiss(context) },
            modifier = Modifier
                .width(42.dp)
                .fillMaxHeight()
                .drawBehind {
                    drawCircle(color = color, radius = size.width / 3.0f + 1.0f)
                },

            colors = IconButtonDefaults.iconButtonColors(contentColor = LocalKeyboardScheme.current.onPrimary)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.close),
                contentDescription = stringResource(R.string.keyboard_actionbar_dismiss_important_notice_talkback)
            )
        }
    }
}

/**
 * Pluto: [ImportantNotice] with a headline, a second line and an action button. It has no close
 * icon because these notices go away by themselves after a few seconds or when typing goes on.
 */
@Composable
private fun ActionNoticeView(importantNotice: ImportantNotice, actionLabel: String) {
    val context = LocalContext.current
    val scheme = LocalKeyboardScheme.current
    Row(
        modifier = Modifier.fillMaxHeight().padding(start = 12.dp),
        verticalAlignment = CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.0f), verticalArrangement = Arrangement.Center) {
            importantNotice.getHeadline()?.let {
                Text(
                    it,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                importantNotice.getText(),
                style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp),
                color = scheme.onBackground.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = { importantNotice.onOpen(context) },
            colors = ButtonDefaults.textButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.padding(start = 8.dp, end = 8.dp).height(34.dp)
        ) {
            Text(actionLabel, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

@Composable
fun RowScope.PinnedActionItems(onSelect: (Action) -> Unit, onLongSelect: (Action) -> Unit, onHold: ((Action) -> Boolean)? = null) {
    val actions = if(!LocalInspectionMode.current) {
        useDataStoreValue(PinnedActions)
    } else {
        PinnedActions.default
    }

    val actionItems = remember(actions) {
        actions.toActionList()
    }

    actionItems.forEach {
        ActionItemSmall(it, onSelect, onLongSelect, onHold)
    }
}

@Composable
fun ActionSep(isExtra: Boolean = false) {
    if(isExtra && (LocalInspectionMode.current || LocalThemeProvider.current.keyBorders)) return

    val sepCol = LocalKeyboardScheme.current.keyboardContainer

    Box(modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(sepCol)) {}
}

/**
 * Pluto: hold-to-talk's bubble, drawn over the whole keyboard and the screen above it (a thumb
 * covers the mic button). It is centred on the mic button and grows with the mic level, like the
 * circle in the voice window.
 */
@Composable
fun HoldToTalkBubble() {
    val phase = HoldToTalk.phase.value
    val active = phase == HoldToTalk.Phase.Starting || phase == HoldToTalk.Phase.Listening
    val center = HoldToTalk.micCenter.value ?: return

    // The mic level comes in steps every 0.1 s and jumps close to full as soon as you speak, so the
    // bubble eases toward it every frame instead of snapping to each step. This is done by hand,
    // not with Compose animations, because those snap straight to the end when the phone's
    // animations are turned off (Remove animations, or the developer animation scales at 0).
    val level = remember { mutableFloatStateOf(0f) }
    val shown = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        var last = 0L
        while (active || shown.floatValue > 0f) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val target = if (HoldToTalk.phase.value == HoldToTalk.Phase.Listening) HoldToTalk.magnitude.coerceIn(0f, 1f) else 0f
                level.floatValue += (target - level.floatValue) * (1f - exp(-dt / 0.09f))
                val step = dt / (if (active) 0.15f else 0.25f)
                shown.floatValue = if (active) (shown.floatValue + step).coerceAtMost(1f) else (shown.floatValue - step).coerceAtLeast(0f)
            }
        }
    }

    val color = LocalKeyboardScheme.current.primary
    val density = LocalDensity.current
    val base = with(density) { 40.dp.toPx() }
    val reach = with(density) { 90.dp.toPx() }
    // the states are only read while drawing, so a change redraws the circle and nothing else
    Spacer(Modifier.fillMaxSize().drawBehind {
        val visible = shown.floatValue
        if (visible <= 0f) return@drawBehind
        val radius = (base + level.floatValue * reach) * (0.7f + 0.3f * visible)
        drawCircle(color = color.copy(alpha = 0.12f * visible), radius = radius * 1.3f, center = center)
        drawCircle(color = color.copy(alpha = 0.22f * visible), radius = radius, center = center)
    })
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ActionBar(
    words: SuggestedWords?,
    suggestionStripListener: SuggestionStripViewListener,
    onActionActivated: (Action) -> Unit,
    onActionAltActivated: (Action) -> Unit,
    inlineSuggestions: List<MutableState<View?>>,
    onActionHold: ((Action) -> Boolean)? = null,
    isActionsExpanded: Boolean,
    toggleActionsExpanded: () -> Unit,
    importantNotice: ImportantNotice? = null,
    keyboardManagerForAction: KeyboardManagerForAction? = null,
    quickClipState: QuickClipState? = null,
    onQuickClipDismiss: () -> Unit = {},
    needToUseExpandableSuggestionUi: Boolean = false,
    loading: Boolean = false,
    typingFixes: List<TypingFix> = emptyList(),
    onOpenTypingFixes: () -> Unit = {},
    onAddCorrection: () -> Unit = {},
) {
    val view = LocalView.current
    val context = LocalContext.current

    val oldActionBar = useDataStore(OldStyleActionsBar)

    val useDoubleHeight = isActionsExpanded && oldActionBar.value == false

    Column(Modifier
        .height(
            ActionBarHeight * (if (useDoubleHeight) 2 else 1).let {
                if(needToUseExpandableSuggestionUi) {
                    it - 1
                } else {
                    it
                }
            }
        )
        .semantics {
            testTag = "ActionBar"
            testTagsAsResourceId = true
        }) {
        if(isActionsExpanded && !oldActionBar.value) {
            ActionSep()

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f),
                color = LocalKeyboardScheme.current.keyboardSurfaceDim//actionBarColor()
            ) {
                ActionItems(onActionActivated, onActionAltActivated)
            }
        }

        if(needToUseExpandableSuggestionUi) return@Column
        ActionSep()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f), color = actionBarColor()
        ) {
            Row(Modifier.safeKeyboardPadding()) {
                ExpandActionsButton(isActionsExpanded) {
                    toggleActionsExpanded()

                    keyboardManagerForAction?.performHapticAndAudioFeedback(
                        Constants.CODE_TAB,
                        view
                    )
                }

                if(oldActionBar.value && isActionsExpanded) {
                    Box(modifier = Modifier
                        .weight(1.0f)
                        .fillMaxHeight()) {
                        ActionItems(onActionActivated, onActionAltActivated)
                    }
                } else {
                    if (importantNotice != null) {
                        ImportantNoticeView(importantNotice)
                    } else {
                        val typingFixChipOnRight = useDataStore(TYPING_FIX_CHIP_ON_RIGHT).value
                        if (!loading && !typingFixChipOnRight) {
                            TypingStripChip(typingFixes, onOpenTypingFixes, onAddCorrection)
                        }
                        if(loading) {
                            Spacer(Modifier.weight(1.0f))
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp).align(CenterVertically),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.weight(1.0f))
                        } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && inlineSuggestions.isNotEmpty()
                        ) {
                            InlineSuggestions(inlineSuggestions)
                        } else if(quickClipState != null) {
                            QuickClipView(quickClipState, onQuickClipDismiss)
                        } else if (words != null) {
                            SuggestionItems(
                                words,
                                onClick = {
                                    suggestionStripListener.pickSuggestionManually(
                                        words.getInfo(it)
                                    )
                                    keyboardManagerForAction?.performHapticAndAudioFeedback(
                                        Constants.CODE_TAB,
                                        view
                                    )
                                },
                                onLongClick = {
                                    suggestionStripListener.requestForgetWord(
                                        words.getInfo(it)
                                    )
                                })
                        } else {
                            Spacer(modifier = Modifier.weight(1.0f))
                        }

                        if (!loading && typingFixChipOnRight) {
                            TypingStripChip(typingFixes, onOpenTypingFixes, onAddCorrection)
                        }

                        if(inlineSuggestions.isEmpty()) {
                            PinnedActionItems(onActionActivated, onActionAltActivated, onActionHold)
                        }
                    }
                }
            }
        }

        ActionSep(true)
    }
}

@Composable
fun ActionWindowBar(
    windowTitleBar: @Composable RowScope.() -> Unit,
    canExpand: Boolean,
    onBack: () -> Unit,
    onExpand: () -> Unit
) {
    Column(Modifier.height(ActionBarHeight)) {
        ActionSep()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f), color = Color.Transparent
        )
        {
            Row(Modifier.safeKeyboardPadding()) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_left_26),
                        contentDescription = stringResource(R.string.keyboard_actionbar_return_from_action_window_talkback)
                    )
                }

                CompositionLocalProvider(LocalTextStyle provides Typography.Body.MediumMl) {
                    windowTitleBar()
                }

                if (canExpand) {
                    IconButton(onClick = onExpand) {
                        Icon(
                            painter = painterResource(id = R.drawable.arrow_up),
                            contentDescription = stringResource(R.string.keyboard_actionbar_dock_action_window_above_keyboard_talkback)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloseActionWindowButton(onClose: () -> Unit) {
    val color = LocalKeyboardScheme.current.primary
    IconButton(
        onClick = onClose,
        modifier = Modifier
            .width(42.dp)
            .fillMaxHeight()
            .drawBehind {
                drawCircle(color = color, radius = size.width / 3.0f + 1.0f)
            },

        colors = IconButtonDefaults.iconButtonColors(contentColor = LocalKeyboardScheme.current.onPrimary)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.close),
            contentDescription = stringResource(R.string.keyboard_actionbar_close_docked_action_window_talkback)
        )
    }
}

@Composable
fun CollapsibleSuggestionsBar(
    onClose: () -> Unit,
    onCollapse: () -> Unit,
    showClose: Boolean,
    showCollapse: Boolean,
    words: SuggestedWords?,
    suggestionStripListener: SuggestionStripViewListener,
    closeOnRight: Boolean = false,
) {
    Column(Modifier.height(ActionBarHeight)) {
        ActionSep()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f), color = actionBarColor()
        )
        {
            Row(Modifier.safeKeyboardPadding()) {
                if(showClose && !closeOnRight) {
                    CloseActionWindowButton(onClose)
                } else {
                    Spacer(Modifier.width(42.dp))
                }

                if (words != null) {
                    SuggestionItems(
                        words,
                        onClick = {
                            suggestionStripListener.pickSuggestionManually(
                                words.getInfo(it)
                            )
                        },
                        onLongClick = { suggestionStripListener.requestForgetWord(words.getInfo(it)) })
                } else {
                    Spacer(modifier = Modifier.weight(1.0f))
                }

                if(showClose && closeOnRight) {
                    CloseActionWindowButton(onClose)
                } else if(showCollapse) {
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier
                            .width(42.dp)
                            .fillMaxHeight(),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = LocalKeyboardScheme.current.onBackground)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.arrow_down),
                            contentDescription = stringResource(R.string.keyboard_actionbar_undock_action_window_collapse_into_keyboard_talkback)
                        )
                    }
                } else {
                    Spacer(Modifier.width(42.dp))
                }
            }
        }

        ActionSep(true)
    }
}




/* ---- Previews ---- */

class ExampleListener : SuggestionStripViewListener {
    override fun showImportantNoticeContents() {
    }

    override fun pickSuggestionManually(word: SuggestedWordInfo?) {
    }

    override fun requestForgetWord(word: SuggestedWordInfo?) {
    }

    override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateItem(modifier: Modifier, it: SuggestedWordInfo, listener: SuggestionStripViewListener, width: Dp, last: Boolean = false) {
    val word = it.mWord
    val description = it.mCandidateDescription
    val color = LocalKeyboardScheme.current.onSurface
    val textStyle =
        suggestionStylePrimary.copy(color = color).withCustomFont()
    val descTextStyle =
        suggestionStyleCandidateDescription.copy(color = color.copy(alpha = 0.5f)).withCustomFont() // TODO: High contrast for high contrast theme
    Row(
        modifier
            .width(width)
            .combinedClickable(
                enabled = word != null,
                onClick = {
                    listener.pickSuggestionManually(
                        it
                    )
                },
                onLongClick = {
                    listener.requestForgetWord(
                        it
                    )
                }
            )
    ) {
        Spacer(Modifier.weight(1.0f))
        Box(
            modifier = Modifier
                .fillMaxHeight()
                //.widthIn(min = width ?: 48.dp, max = width ?: Dp.Unspecified)
                .testTag("SuggestionItem"),
        ) {
            CompositionLocalProvider(LocalContentColor provides color) {
                if (word != null) {
                    val modifier = Modifier
                        .align(Center)
                        .padding(2.dp)
                        .testTag("SuggestionItemText")
                    Text(
                        word,
                        style = textStyle,
                        modifier = modifier
                    )
                }
            }
        }
        Spacer(Modifier.weight(1.0f))

        if(description != null) {
            val modifier = Modifier
                .align(Alignment.Bottom)
                .padding(0.dp, 0.dp, 0.dp, 4.dp)
                .testTag("SuggestionItemDescription")
            Text(
                description,
                style = descTextStyle,
                modifier = modifier,
                textAlign = TextAlign.Right
            )

            Spacer(Modifier.weight(1.0f))
        }
        if(!last) SuggestionSeparator()
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun <T> LazyFlowRow(
    items: List<T>,
    itemMeasurer: (T) -> Int,
    modifier: Modifier = Modifier,
    itemContent: @Composable (width: Int, T, isLast: Boolean) -> Unit
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.roundToPx() }

        val rows: List<List<Pair<Int, T>>> by remember(items, maxWidthPx) {
            mutableStateOf(
                buildRows(
                    items,
                    itemMeasurer,
                    maxWidthPx
                )
            )
        }

        LazyColumn {
            items(rows) { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { i, it -> itemContent(it.first, it.second, i == row.size-1) }
                }
            }
        }
    }
}

private fun <T> buildRows(
    source: List<T>,
    itemMeasurer: (T) -> Int,
    maxWidthPx: Int
): List<List<Pair<Int, T>>> {
    if (source.isEmpty() || maxWidthPx <= 0) return emptyList()

    val rows = mutableListOf<List<Pair<Int, T>>>()
    var currentWidth = 0
    var currentRow = mutableListOf<Pair<Int, T>>()

    source.forEach { item ->
        val itemSpace = itemMeasurer(item)

        if (currentWidth + itemSpace > maxWidthPx && currentRow.isNotEmpty()) {
            val widthLeftOver = maxWidthPx - currentWidth
            val extraWidthPerItem = widthLeftOver / currentRow.size
            rows.add(currentRow.map {
                (it.first + extraWidthPerItem) to it.second
            })
            currentRow = mutableListOf()
            currentWidth = 0
        }
        currentRow.add(itemSpace to item)
        currentWidth += itemSpace
    }
    if (currentRow.isNotEmpty()) rows.add(currentRow)
    return rows
}


@Composable
private fun RowScope.InlineCandidates(
    suggestionsExpansion: MutableFloatState,
    keyboardHeight: Int,
    lazyListState: LazyListState,
    isActionsExpanded: Boolean,
    toggleActionsExpanded: () -> Unit,
    closeActionWindow: (() -> Unit)?,
    suggestionStripListener: SuggestionStripViewListener,
    wordList: List<SuggestedWordInfo>,
    widths: CachedCharacterWidthValues,
    extraSpaceForFirstItem: Boolean,
) {
    val view = LocalView.current
    val expandHeight = with(LocalDensity.current) { 120.dp.toPx().coerceAtMost(keyboardHeight / 2.0f) }
    Box(Modifier.weight(1.0f).fillMaxHeight()) {
        LazyRow(
            modifier = Modifier.matchParentSize().pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                    },
                    onDragEnd = {
                        if(suggestionsExpansion.floatValue < 0.5f) {
                            suggestionsExpansion.floatValue = 0.0f
                        } else {
                            suggestionsExpansion.floatValue = 1.0f
                        }
                    },
                    onDragCancel = {
                        if(suggestionsExpansion.floatValue < 0.5f) {
                            suggestionsExpansion.floatValue = 0.0f
                        } else {
                            suggestionsExpansion.floatValue = 1.0f
                        }
                    }
                ) { a, b ->
                    suggestionsExpansion.floatValue =
                        (suggestionsExpansion.floatValue + b / expandHeight).coerceIn(0.0f, 1.0f)
                }
            },
            verticalAlignment = CenterVertically,
            state = lazyListState
        ) {
            item {
                val manager = LocalManager.current
                if(closeActionWindow != null) {
                    CloseActionWindowButton(closeActionWindow)
                } else {
                    ExpandActionsButton(isActionsExpanded) {
                        toggleActionsExpanded()

                        manager.performHapticAndAudioFeedback(
                            Constants.CODE_TAB,
                            view
                        )
                    }
                }
            }
            itemsIndexed(wordList) { i, it ->
                CandidateItem(Modifier.height(ActionBarHeight), it,
                    listener = suggestionStripListener,
                    last = i == wordList.size-1,
                    width = with(LocalDensity.current) {
                        measureWord(this, widths, it).toDp()
                    }.coerceAtLeast(if(i == 0 && extraSpaceForFirstItem) 120.dp else 48.dp)
                )
            }
        }
    }
}

private fun String.countLongestLineLength(): Int {
    var count = 0
    var biggestCount = 0
    for(c in this) {
        if(c == '\n') {
            biggestCount = max(count, biggestCount)
            count = 0
        } else {
            count += 1
        }
    }
    biggestCount = max(count, biggestCount)
    return biggestCount
}

data class CachedCharacterWidthValues(
    val normal: Int,
    val description: Int
)

private fun measureWord(density: Density, widths: CachedCharacterWidthValues, word: SuggestedWordInfo): Int {
    val minWidth = with(density) { 58.dp.toPx() }
    val wordWidth = word.mWord.length * widths.normal * 1.04f //measurer.measure(it.mWord, style = suggestionStylePrimary).size.width
    val extraDescWidth = if(word.mCandidateDescription.isNullOrEmpty()) {
        0
    } else {
        word.mCandidateDescription.countLongestLineLength() * widths.description
    }
    return (wordWidth + with(density) { 10.dp.toPx() } + extraDescWidth).coerceAtLeast(minWidth).toInt()
}

const val START_OF_CANDIDATES = 1 // The 0th element is the show/hide action bar button

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun BoxScope.ActionBarWithExpandableCandidates(
    words: SuggestedWords?,
    suggestionStripListener: SuggestionStripViewListener,
    isActionsExpanded: Boolean,
    toggleActionsExpanded: () -> Unit,
    closeActionWindow: (() -> Unit)?,
    keyboardOffset: MutableIntState? = null,
    keyboardHeight: Int = 1000,
    expandableSuggestionCfg: ExpandableSuggestionBarConfiguration,
) {
    val wordList = remember(words) {
        words?.mSuggestedWordInfoList?.toList()?.filter {
            !it.isKindOf(KIND_TYPED)
        } ?: emptyList()
    }

    val suggestionsExpansion = remember { mutableFloatStateOf(0.0f) }
    val lazyListState = rememberLazyListState()

    LaunchedEffect(wordList, words?.mHighlightedCandidate) {
        if(words?.mHighlightedCandidate != null) {
            lazyListState.scrollToItem(words.mHighlightedCandidate + START_OF_CANDIDATES)
        } else {
            lazyListState.scrollToItem(START_OF_CANDIDATES)
        }
    }

    val offset by animateFloatAsState(keyboardHeight.toFloat() * suggestionsExpansion.floatValue,
        label = "suggest")
    LaunchedEffect(offset) { keyboardOffset?.intValue = offset.roundToInt() }

    val lastVisibleItemIndex by remember(lazyListState) { derivedStateOf {
            lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
                val endOfViewport = lazyListState.layoutInfo.viewportEndOffset
                val visibilityThreshold = it.offset + it.size * 2 / 3
                if(endOfViewport > visibilityThreshold) {
                    it.index
                } else {
                    it.index - 1
                }
            } ?: 0
        }
    }

    val canShowSuggest = suggestionsExpansion.floatValue > 0.001f
    val listToRender = remember(canShowSuggest, wordList) {
        if(canShowSuggest) {
            wordList.subList(
                lastVisibleItemIndex.coerceAtMost(wordList.size),
                wordList.size
            )
        } else {
            null
        }
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // True width measurement is too slow, estimate it by multiplying constant by number of characters
    val styleNormal = suggestionStylePrimary.withCustomFont()
    val styleDescription = suggestionStyleCandidateDescription.withCustomFont()
    val widths = remember(measurer) {
        CachedCharacterWidthValues(
            normal = measurer.measure("あ", style=styleNormal).size.width,
            description = measurer.measure("あ", style=styleDescription).size.width
        )
    }


    if(canShowSuggest) {
        Surface(
            Modifier.fillMaxWidth().padding(0.dp, ActionBarHeight, 0.dp, 0.dp)
                .heightIn(max = with(density) { keyboardOffset?.intValue?.toDp() ?: 0.dp })
                .safeKeyboardPadding()
        ) {
            LazyFlowRow(
                listToRender ?: emptyList(),
                itemMeasurer = { measureWord(density, widths, it) }
            ) { allocatedWidth, item, isLast ->
                CandidateItem(Modifier.height(ActionBarHeight), item, listener = suggestionStripListener, width=with(density) { allocatedWidth.toDp()  }, last=isLast)
            }
        }
    }

    Column(Modifier
            .semantics {
                testTag = "ActionBar"
                testTagsAsResourceId = true
            }
            .height(ActionBarHeight)
            .align(Alignment.TopCenter)
    ) {
        ActionSep()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f), color = actionBarColor()
        ) {
            Row(Modifier.safeKeyboardPadding()) {
                if (wordList.isNotEmpty()) {
                    InlineCandidates(
                        suggestionsExpansion,
                        keyboardHeight,
                        lazyListState,
                        isActionsExpanded,
                        toggleActionsExpanded,
                        closeActionWindow,
                        suggestionStripListener,
                        wordList,
                        widths,
                        expandableSuggestionCfg.addExtraSpaceForFirstEntry
                    )
                }

                IconButton(
                    onClick = {
                        if(suggestionsExpansion.floatValue < 0.5f) {
                            suggestionsExpansion.floatValue = 1.0f
                        } else {
                            suggestionsExpansion.floatValue = 0.0f
                        }
                    },
                    modifier = Modifier.width(40.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.rotate(
                            suggestionsExpansion.floatValue * -180.0f
                        )
                    )
                }
            }
        }

        ActionSep(true)
    }
}


val exampleSuggestionsList = arrayListOf(
    SuggestedWordInfo("verylongword123", "", 100, 1, null, 0, 0),
    SuggestedWordInfo("world understanding of patience", "",  99, 1, null, 0, 0),
    SuggestedWordInfo("short", "", 98, 1, null, 0, 0),
    SuggestedWordInfo("extra1", "", 97, 1, null, 0, 0),
    SuggestedWordInfo("extra2", "", 96, 1, null, 0, 0),
    SuggestedWordInfo("extra3", "", 95, 1, null, 0, 0)
)

val japaneseSuggestionsList = arrayListOf(
    SuggestedWordInfo("あ", "", 97, 1, null, 0, 0),
    SuggestedWordInfo("あいう", "", 97, 1, null, 0, 0, 0, "ＬＯＮＧ　ＡＮＤ　ＣＯＭＰＬＥＸ\nＷＩＴＨ　ＭＵＬＴＩＰＬＥ　ＬＩＮＥＳ\nｓｏｍｅ　ｔｅｘｔ　ｈｅｒｅ"),
    SuggestedWordInfo("アイウ", "", 96, 1, null, 0, 0, 0, "[あああ]"),
    SuggestedWordInfo("あいうえお", "", 95, 1, null, 0, 0),
    SuggestedWordInfo("アイウェア", "", 94, 1, null, 0, 0),
    SuggestedWordInfo("アイヴ", "", 93, 1, null, 0, 0),
    SuggestedWordInfo("相打ち", "", 92, 1, null, 0, 0),
    SuggestedWordInfo("愛う", "", 91, 1, null, 0, 0),
    SuggestedWordInfo("会いう", "", 90, 1, null, 0, 0),
    SuggestedWordInfo("アイう", "", 89, 1, null, 0, 0),
    SuggestedWordInfo("合いう", "", 88, 1, null, 0, 0),
    SuggestedWordInfo("藍う", "", 87, 1, null, 0, 0),
    SuggestedWordInfo("遭いう", "", 86, 1, null, 0, 0),
    SuggestedWordInfo("逢いう", "", 85, 1, null, 0, 0),
    SuggestedWordInfo("亜依う", "", 84, 1, null, 0, 0),
    SuggestedWordInfo("亜衣う", "", 83, 1, null, 0, 0),
    SuggestedWordInfo("遇いう", "", 82, 1, null, 0, 0),
    SuggestedWordInfo("愛衣う", "", 81, 1, null, 0, 0),
    SuggestedWordInfo("安威う", "", 80, 1, null, 0, 0),
    SuggestedWordInfo("愛依う", "", 79, 1, null, 0, 0),
    SuggestedWordInfo("會いう", "", 78, 1, null, 0, 0),
    SuggestedWordInfo("吾唯う", "", 77, 1, null, 0, 0),
    SuggestedWordInfo("艾う", "", 76, 1, null, 0, 0),
    SuggestedWordInfo("阿井う", "", 75, 1, null, 0, 0),
    SuggestedWordInfo("亜以う", "", 74, 1, null, 0, 0),
    SuggestedWordInfo("愛良う", "", 73, 1, null, 0, 0),
    SuggestedWordInfo("歩惟う", "", 72, 1, null, 0, 0),
    SuggestedWordInfo("亜伊う", "", 71, 1, null, 0, 0),
    SuggestedWordInfo("アイ", "", 70, 1, null, 0, 0),
    SuggestedWordInfo("あい", "", 69, 1, null, 0, 0),
    SuggestedWordInfo("会い", "", 68, 1, null, 0, 0),
    SuggestedWordInfo("合い", "", 67, 1, null, 0, 0),
    SuggestedWordInfo("亜依", "", 66, 1, null, 0, 0),
    SuggestedWordInfo("遭い", "", 65, 1, null, 0, 0),
    SuggestedWordInfo("亜衣", "", 64, 1, null, 0, 0),
    SuggestedWordInfo("逢い", "", 63, 1, null, 0, 0),
    SuggestedWordInfo("愛衣", "", 62, 1, null, 0, 0),
    SuggestedWordInfo("愛依", "", 61, 1, null, 0, 0),
    SuggestedWordInfo("安威", "", 60, 1, null, 0, 0),
    SuggestedWordInfo("吾唯", "", 59, 1, null, 0, 0),
    SuggestedWordInfo("あ", "", 58, 1, null, 0, 0),
    SuggestedWordInfo("亜以", "", 57, 1, null, 0, 0),
    SuggestedWordInfo("阿井", "", 56, 1, null, 0, 0),
    SuggestedWordInfo("愛良", "", 55, 1, null, 0, 0),
    SuggestedWordInfo("遇い", "", 54, 1, null, 0, 0),
    SuggestedWordInfo("歩惟", "", 53, 1, null, 0, 0),
    SuggestedWordInfo("亜伊", "", 52, 1, null, 0, 0),
    SuggestedWordInfo("杏依", "", 51, 1, null, 0, 0),
    SuggestedWordInfo("歩唯", "", 50, 1, null, 0, 0),
    SuggestedWordInfo("亜唯", "", 49, 1, null, 0, 0),
    SuggestedWordInfo("會い", "", 48, 1, null, 0, 0),
    SuggestedWordInfo("彩衣", "", 47, 1, null, 0, 0),
    SuggestedWordInfo("安居", "", 46, 1, null, 0, 0),
    SuggestedWordInfo("阿惟", "", 45, 1, null, 0, 0),
    SuggestedWordInfo("安衣", "", 44, 1, null, 0, 0),
    SuggestedWordInfo("亜惟", "", 43, 1, null, 0, 0),
    SuggestedWordInfo("阿衣", "", 42, 1, null, 0, 0),
    SuggestedWordInfo("亜", "", 41, 1, null, 0, 0),
    SuggestedWordInfo("空い", "", 40, 1, null, 0, 0),
    SuggestedWordInfo("開い", "", 39, 1, null, 0, 0),
    SuggestedWordInfo("飽い", "", 38, 1, null, 0, 0),
    SuggestedWordInfo("明い", "", 37, 1, null, 0, 0),
    SuggestedWordInfo("在", "", 36, 1, null, 0, 0),
    SuggestedWordInfo("有", "", 35, 1, null, 0, 0),
    SuggestedWordInfo("厭い", "", 34, 1, null, 0, 0),
    SuggestedWordInfo("合", "", 33, 1, null, 0, 0),
    SuggestedWordInfo("娃", "", 32, 1, null, 0, 0),
    SuggestedWordInfo("哀", "", 31, 1, null, 0, 0),
    SuggestedWordInfo("相", "", 30, 1, null, 0, 0),
    SuggestedWordInfo("挨", "", 29, 1, null, 0, 0),
    SuggestedWordInfo("逢", "", 28, 1, null, 0, 0),
    SuggestedWordInfo("愛", "", 27, 1, null, 0, 0),
    SuggestedWordInfo("鮎", "", 26, 1, null, 0, 0),
    SuggestedWordInfo("藍", "", 25, 1, null, 0, 0),
    SuggestedWordInfo("饗", "", 24, 1, null, 0, 0),
    SuggestedWordInfo("姶", "", 23, 1, null, 0, 0),
    SuggestedWordInfo("曖", "", 22, 1, null, 0, 0),
    SuggestedWordInfo("哇", "", 21, 1, null, 0, 0),
    SuggestedWordInfo("噫", "", 20, 1, null, 0, 0),
    SuggestedWordInfo("欸", "", 19, 1, null, 0, 0),
    SuggestedWordInfo("殪", "", 18, 1, null, 0, 0),
    SuggestedWordInfo("瞹", "", 17, 1, null, 0, 0),
    SuggestedWordInfo("藹", "", 16, 1, null, 0, 0),
    SuggestedWordInfo("阨", "", 15, 1, null, 0, 0),
    SuggestedWordInfo("靉", "", 14, 1, null, 0, 0),
    SuggestedWordInfo("鞋", "", 13, 1, null, 0, 0),
    SuggestedWordInfo("乃", "", 12, 1, null, 0, 0),
    SuggestedWordInfo("生", "", 11, 1, null, 0, 0),
    SuggestedWordInfo("会", "", 10, 1, null, 0, 0),
    SuggestedWordInfo("医", "", 9, 1, null, 0, 0),
    SuggestedWordInfo("和", "", 8, 1, null, 0, 0),
    SuggestedWordInfo("秋", "", 7, 1, null, 0, 0),
    SuggestedWordInfo("間", "", 6, 1, null, 0, 0),
    SuggestedWordInfo("綾", "", 5, 1, null, 0, 0),
    SuggestedWordInfo("優", "", 4, 1, null, 0, 0),
    SuggestedWordInfo("會", "", 3, 1, null, 0, 0),
    SuggestedWordInfo("埃", "", 2, 1, null, 0, 0),
    SuggestedWordInfo("隘", "", 1, 1, null, 0, 0),
)

val exampleSuggestedWords = SuggestedWords(
    exampleSuggestionsList,
    exampleSuggestionsList,
    exampleSuggestionsList[0],
    true,
    true,
    false,
    0,
    0
)

val japaneseSuggestedWords = SuggestedWords(
    japaneseSuggestionsList,
    japaneseSuggestionsList,
    japaneseSuggestionsList[0],
    true,
    true,
    false,
    0,
    0
)

val exampleSuggestedWordsEmpty = SuggestedWords(
    arrayListOf(),
    arrayListOf(),
    exampleSuggestionsList[0],
    true,
    true,
    false,
    0,
    0
)

@Composable
@Preview
fun PreviewActionBarWithSuggestions(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        ActionBar(
            words = exampleSuggestedWords,
            suggestionStripListener = ExampleListener(),
            onActionActivated = { },
            onActionAltActivated = { },
            inlineSuggestions = listOf(),
            isActionsExpanded = false,
            toggleActionsExpanded = { },
        )
    }
}

@Composable
@Preview
fun PreviewActionBarWithExpandableCandidatesExtraSpace(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        Box(Modifier.fillMaxWidth().height(250.dp)) {
            ActionBarWithExpandableCandidates(
                words = japaneseSuggestedWords,
                suggestionStripListener = ExampleListener(),
                isActionsExpanded = false,
                toggleActionsExpanded = { },
                closeActionWindow = null,
                expandableSuggestionCfg = ExpandableSuggestionBarConfiguration(true, true)
            )
        }
    }
}

@Composable
@Preview
fun PreviewActionBarWithExpandableCandidatesNoExtraSpace(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        Box(Modifier.fillMaxWidth().height(250.dp)) {
            ActionBarWithExpandableCandidates(
                words = japaneseSuggestedWords,
                suggestionStripListener = ExampleListener(),
                isActionsExpanded = false,
                toggleActionsExpanded = { },
                closeActionWindow = null,
                expandableSuggestionCfg = ExpandableSuggestionBarConfiguration(true, false)
            )
        }
    }
}

@Composable
@Preview
fun PreviewActionBarWithQuickClip(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        ActionBar(
            words = exampleSuggestedWords,
            suggestionStripListener = ExampleListener(),
            onActionActivated = { },
            onActionAltActivated = { },
            inlineSuggestions = listOf(),
            isActionsExpanded = false,
            toggleActionsExpanded = { },
            quickClipState = QuickClipState(
                texts = listOf(
                    QuickClipItem(QuickClipKind.EmailAddress, "someone@example.com", 0),
                    QuickClipItem(QuickClipKind.NumericCode, "123456", 0),
                    QuickClipItem(QuickClipKind.FullString, "Hello world, this is a full string.", 0),
                ),
                image = "content://example".toUri(),
                validUntil = Long.MAX_VALUE,
                imageMimeTypes = listOf(),
                isSensitive = true
            ),
        )
    }
}

@Composable
@Preview
fun PreviewActionBarWithNotice(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        ActionBar(
            words = exampleSuggestedWords,
            suggestionStripListener = ExampleListener(),
            onActionActivated = { },
            onActionAltActivated = { },
            inlineSuggestions = listOf(),
            isActionsExpanded = true,
            toggleActionsExpanded = { },
            importantNotice = object : ImportantNotice {
                @Composable
                override fun getText(): String {
                    return "Update available: v1.2.3"
                }

                override fun onDismiss(context: Context, auto: Boolean) { }
                override fun onOpen(context: Context) { }
            },
        )
    }
}

@Composable
@Preview
fun PreviewActionBarWithEmptySuggestions(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        ActionBar(
            words = exampleSuggestedWordsEmpty,
            suggestionStripListener = ExampleListener(),
            onActionActivated = { },
            onActionAltActivated = { },
            inlineSuggestions = listOf(),
            isActionsExpanded = true,
            toggleActionsExpanded = { },
        )
    }
}

@Composable
@Preview
fun PreviewActionBarLoading(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        ActionBar(
            words = exampleSuggestedWordsEmpty,
            suggestionStripListener = ExampleListener(),
            onActionActivated = { },
            onActionAltActivated = { },
            inlineSuggestions = listOf(),
            isActionsExpanded = true,
            toggleActionsExpanded = { },
            loading = true
        )
    }
}

@Composable
@Preview
fun PreviewExpandedActionBar(colorScheme: ThemeOption = DefaultDarkScheme) {
    UixThemeWrapper(colorScheme.obtainColors(LocalContext.current)) {
        ActionBar(
            words = exampleSuggestedWordsEmpty,
            suggestionStripListener = ExampleListener(),
            onActionActivated = { },
            onActionAltActivated = { },
            inlineSuggestions = listOf(),
            isActionsExpanded = true,
            toggleActionsExpanded = { },
        )
    }
}

@Composable
@Preview
fun PreviewCollapsibleBar(colorScheme: ThemeOption = DefaultDarkScheme) {
    CollapsibleSuggestionsBar(
        onCollapse = { },
        onClose = { },
        words = exampleSuggestedWords,
        showClose = true,
        showCollapse = true,
        suggestionStripListener = ExampleListener()
    )
}


@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewActionBarWithSuggestionsDynamicLight() {
    PreviewActionBarWithSuggestions(DynamicLightTheme)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewActionBarWithEmptySuggestionsDynamicLight() {
    PreviewActionBarWithEmptySuggestions(DynamicLightTheme)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewActionBarLoadingDynamicLight() {
    PreviewActionBarLoading(DynamicLightTheme)
}


@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewExpandedActionBarDynamicLight() {
    PreviewExpandedActionBar(DynamicLightTheme)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewActionBarWithSuggestionsDynamicDark() {
    PreviewActionBarWithSuggestions(DynamicDarkTheme)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewActionBarWithEmptySuggestionsDynamicDark() {
    PreviewActionBarWithEmptySuggestions(DynamicDarkTheme)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewActionBarLoadingDynamicDark() {
    PreviewActionBarLoading(DynamicDarkTheme)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
@Preview
fun PreviewExpandedActionBarDynamicDark() {
    PreviewExpandedActionBar(DynamicDarkTheme)
}