package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.heatmap.AdaptiveSwipe
import org.futo.inputmethod.latin.heatmap.SwipeConfig
import org.futo.inputmethod.latin.heatmap.TypingHeatmap
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.SettingSliderSharedPrefsInt
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useSharedPrefsBool
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleSharedPrefs

/** Swipe typing → Adaptive swipe learning: the switch, its strength, and a reset. */
val AdaptiveSwipeMenu = UserSettingsMenu(
    title = R.string.adaptive_swipe_title,
    navPath = "adaptiveSwipe", registerNavPath = true,
    settings = listOf(
        userSettingToggleSharedPrefs(
            title = R.string.adaptive_swipe_toggle_title,
            subtitle = R.string.adaptive_swipe_toggle_subtitle,
            key = Settings.PREF_ADAPTIVE_SWIPE,
            default = { true },
        ),

        UserSetting(name = R.string.adaptive_swipe_strength, subtitle = R.string.adaptive_swipe_strength_subtitle) {
            val labels = listOf(
                R.string.adaptive_swipe_strength_gentlest, R.string.adaptive_swipe_strength_gentle,
                R.string.adaptive_swipe_strength_default, R.string.adaptive_swipe_strength_strong,
                R.string.adaptive_swipe_strength_strongest,
            ).map { stringResource(it) }
            SettingSliderSharedPrefsInt(
                title = stringResource(R.string.adaptive_swipe_strength),
                key = Settings.PREF_ADAPTIVE_SWIPE_STRENGTH,
                default = SwipeConfig.DEFAULT_STRENGTH,
                range = SwipeConfig.MIN_STRENGTH.toFloat()..SwipeConfig.MAX_STRENGTH.toFloat(),
                transform = { kotlin.math.round(it).toInt() },
                indicator = { labels[(it - SwipeConfig.MIN_STRENGTH).coerceIn(0, labels.size - 1)] },
                steps = SwipeConfig.MAX_STRENGTH - SwipeConfig.MIN_STRENGTH - 1,
                subtitle = stringResource(R.string.adaptive_swipe_strength_subtitle),
            )
        },

        userSettingDecorationOnly {
            val context = LocalContext.current
            val enabled = useSharedPrefsBool(Settings.PREF_ADAPTIVE_SWIPE, true).value
            var refreshCounter by remember { mutableIntStateOf(0) }
            var learned by remember { mutableStateOf<Long?>(null) }
            var showResetDialog by remember { mutableStateOf(false) }

            LaunchedEffect(refreshCounter) {
                learned = withContext(Dispatchers.IO) {
                    TypingHeatmap.ensureLoadedBlocking(context)
                    AdaptiveSwipe.learnedSamples()
                }
            }

            if (!enabled) Tip(stringResource(R.string.adaptive_swipe_disabled))
            Tip(stringResource(R.string.adaptive_swipe_explanation))
            learned?.let {
                Text(
                    stringResource(R.string.adaptive_swipe_learned, it),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            Button(
                onClick = { showResetDialog = true },
                enabled = (learned ?: 0L) > 0L,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(stringResource(R.string.adaptive_swipe_reset))
            }

            if (showResetDialog) {
                AlertDialog(
                    title = { Text(stringResource(R.string.adaptive_swipe_reset_confirm_title)) },
                    text = { Text(stringResource(R.string.adaptive_swipe_reset_confirm_body)) },
                    onDismissRequest = { showResetDialog = false },
                    confirmButton = {
                        TextButton(onClick = {
                            AdaptiveSwipe.reset()
                            showResetDialog = false
                            refreshCounter++
                        }) {
                            Text(stringResource(R.string.typing_heatmap_reset_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
        },

        userSettingNavigationItem(
            title = R.string.adaptive_swipe_view_heatmap,
            subtitle = R.string.adaptive_swipe_view_heatmap_subtitle,
            navigateTo = "typingHeatmap",
            style = NavigationItemStyle.Misc
        ).copy(appearsInSearch = false),
    )
)
