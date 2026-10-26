package org.futo.inputmethod.latin.uix.settings.pages

import org.futo.inputmethod.latin.uix.settings.SetupTour
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.FeatureTips
import org.futo.inputmethod.latin.uix.PlutoLinks
import org.futo.inputmethod.latin.uix.SHOW_FEATURE_TIPS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.updates.openURI

internal fun Context.copyToClipboard(text: CharSequence, label: String = "Copied Text") {
    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText(label, text)
    clipboardManager.setPrimaryClip(clipData)
}

val HelpMenu = UserSettingsMenu(
    title = R.string.help_menu_title,
    navPath = "help", registerNavPath = true,
    settings = listOf(
        userSettingToggleDataStore(
            title = R.string.feature_tips_setting,
            subtitle = R.string.feature_tips_setting_subtitle,
            setting = SHOW_FEATURE_TIPS,
        ),
        UserSetting(name = R.string.feature_tips_reset, subtitle = R.string.feature_tips_reset_subtitle) {
            val context = LocalContext.current
            NavigationItem(
                title = stringResource(R.string.feature_tips_reset),
                subtitle = stringResource(R.string.feature_tips_reset_subtitle),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = {
                    FeatureTips.reset(context)
                    Toast.makeText(context, context.getString(R.string.feature_tips_reset_done), Toast.LENGTH_SHORT).show()
                }
            )
        },
        UserSetting(name = R.string.tour_restart, subtitle = R.string.tour_restart_subtitle) {
            val context = LocalContext.current
            NavigationItem(
                title = stringResource(R.string.tour_restart),
                subtitle = stringResource(R.string.tour_restart_subtitle),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = { SetupTour.restart(context) }
            )
        },
        UserSetting(
            name = R.string.help_menu_version_name
        ) {
            val context = LocalContext.current
            NavigationItem(
                title = stringResource(R.string.help_menu_version_name, BuildConfig.VERSION_NAME),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = {
                    context.copyToClipboard(BuildConfig.VERSION_NAME)
                }
            )
        },
        UserSetting(
            name = R.string.help_menu_version_code,
            searchTags = R.string.dev_settings_title
        ) {
            val context = LocalContext.current
            val numPresses = remember { mutableIntStateOf(0) }
            var lastToast: MutableState<Toast?> = remember { mutableStateOf(null) }
            NavigationItem(
                title = stringResource(R.string.help_menu_version_code, BuildConfig.VERSION_CODE),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = {
                    val makeToast: (String) -> Unit = { text ->
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).let {
                            lastToast.value?.cancel()
                            lastToast.value = it
                            it.show()
                        }
                    }
                    if(runBlocking { context.getSetting(IS_DEVELOPER) }) {
                        makeToast("You're already a developer")
                    } else {
                        numPresses.intValue += 1
                        if (numPresses.intValue > 3) {
                            val pressesUntilDeveloper = 8 - numPresses.intValue
                            if (pressesUntilDeveloper <= 0) {
                                makeToast("You're now a developer")
                                runBlocking { context.setSetting(IS_DEVELOPER, true) }
                            } else {
                                makeToast("You are $pressesUntilDeveloper steps until becoming a developer")
                            }
                        }
                    }
                }
            )
        },
        userSettingDecorationOnly {
            Tip(stringResource(
                    R.string.help_menu_version_tip,
                    BuildConfig.VERSION_NAME
                ))
        },

        // Pluto Keyboard sends feedback and bug reports to the Pluto Keyboard project, not to FUTO
        // (FUTO's website, Discord, Zulip chat, issue tracker and email support the official app).
        userSettingNavigationItem(
            title = R.string.help_menu_pluto_report_issue,
            subtitle = R.string.help_menu_pluto_report_issue_subtitle,
            style = NavigationItemStyle.Misc,
            navigate = { nav ->
                nav.context.openURI(PlutoLinks.ISSUES)
            }
        ).copy(searchTags = R.string.help_menu_pluto_report_issue_tags),
        userSettingNavigationItem(
            title = R.string.help_menu_pluto_project_page,
            subtitle = R.string.help_menu_pluto_project_page_subtitle,
            style = NavigationItemStyle.Misc,
            navigate = { nav ->
                nav.context.openURI(PlutoLinks.PROJECT_PAGE)
            }
        ),
        userSettingNavigationItem(
            title = R.string.help_menu_pluto_upstream_docs,
            subtitle = R.string.help_menu_pluto_upstream_docs_subtitle,
            style = NavigationItemStyle.Misc,
            navigate = { nav ->
                nav.context.openURI(PlutoLinks.FUTO_KEYBOARD_DOCS)
            }
        ),
    )
)