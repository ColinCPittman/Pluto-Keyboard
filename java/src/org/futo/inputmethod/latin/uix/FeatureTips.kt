package org.futo.inputmethod.latin.uix

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.theme.Typography

/** Show a short tip the first time you meet a feature (Help settings). */
val SHOW_FEATURE_TIPS = SettingsKey(
    key = booleanPreferencesKey("show_feature_tips"),
    default = true
)

/** The tips already shown ([FeatureTip] names). */
val FEATURE_TIPS_SEEN = SettingsKey(
    key = stringSetPreferencesKey("feature_tips_seen"),
    default = emptySet()
)

/**
 * A tip shown once, when you first meet a feature. Its text may take arguments (the words of a
 * fix, ...). Tips are offered by the feature itself, so only features you use show theirs.
 */
enum class FeatureTip(val title: Int, val text: Int) {
    /** Smart corrections for typing fixed a typo that is a real word. */
    TYPING_FIX(R.string.tip_typing_fix_title, R.string.tip_typing_fix_text),

    /** ... or a missed typo, a word no dictionary knows. */
    MISSED_TYPO_FIX(R.string.tip_missed_typo_fix_title, R.string.tip_missed_typo_fix_text),

    /** A typing fix was undone. */
    TYPING_FIX_UNDONE(R.string.tip_typing_fix_undone_title, R.string.tip_typing_fix_undone_text),

    /** You fixed by hand a typo that it left (a word with a pair). */
    TYPING_MISS_LEARNED(R.string.tip_typing_miss_learned_title, R.string.tip_typing_miss_learned_text),

    /** One word selected shows the "Add correction" chip. */
    ADD_CORRECTION(R.string.tip_add_correction_title, R.string.tip_add_correction_text),

    /** Smart corrections changed a dictation. */
    VOICE_SMART_CORRECTIONS(R.string.tip_voice_smart_corrections_title, R.string.tip_voice_smart_corrections_text),

    /** The first swiped word, while adaptive swipe learning is on. */
    ADAPTIVE_SWIPE(R.string.tip_adaptive_swipe_title, R.string.tip_adaptive_swipe_text),
}

/** The tip on screen, with the arguments of its text. */
data class ShownTip(val tip: FeatureTip, val args: List<String>)

object FeatureTips {
    /** The tip the keyboard shows now, if any (one at a time). */
    val current = mutableStateOf<ShownTip?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Shows [tip] unless tips are off, it was shown before, or another tip is showing. */
    fun offer(context: Context, tip: FeatureTip, vararg args: String) {
        val seen = context.getSetting(FEATURE_TIPS_SEEN)
        if (current.value != null) return
        if (!context.getSetting(SHOW_FEATURE_TIPS) || tip.name in seen) return
        current.value = ShownTip(tip, args.toList())
    }

    /** Takes [tip] off the screen without counting it as seen (it no longer applies). */
    fun withdraw(tip: FeatureTip) {
        if (current.value?.tip == tip) current.value = null
    }

    /** Closes the tip on screen (it is not shown again), and [turnOff] stops all tips. */
    fun dismiss(context: Context, turnOff: Boolean = false) {
        val shown = current.value ?: return
        current.value = null
        val app = context.applicationContext
        scope.launch {
            app.setSetting(FEATURE_TIPS_SEEN, app.getSetting(FEATURE_TIPS_SEEN) + shown.tip.name)
            if (turnOff) app.setSetting(SHOW_FEATURE_TIPS, false)
        }
    }

    /** Every tip may be shown again. */
    fun reset(context: Context) {
        val app = context.applicationContext
        scope.launch {
            app.setSetting(FEATURE_TIPS_SEEN, emptySet())
            app.setSetting(SHOW_FEATURE_TIPS, true)
        }
    }
}

/** The tip on screen as a card over the top of the keyboard, below the suggestion bar it is often about. */
@Composable
fun FeatureTipCard(modifier: Modifier = Modifier) {
    val shown = FeatureTips.current.value ?: return
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(shown.tip.title), style = Typography.Body.MediumMl, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(shown.tip.text, *shown.args.toTypedArray()),
                style = Typography.SmallMl,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { FeatureTips.dismiss(context, turnOff = true) }) {
                    Text(stringResource(R.string.tip_turn_off))
                }
                Button(onClick = { FeatureTips.dismiss(context) }, modifier = Modifier.padding(start = 4.dp)) {
                    Text(stringResource(R.string.tip_got_it))
                }
            }
        }
    }
}
