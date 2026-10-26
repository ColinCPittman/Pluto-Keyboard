package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ContextModels
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.PlutoLinks
import org.futo.inputmethod.latin.uix.PunctuationModels
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.VoiceInputEngines
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Route
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.modelpackage.ModelPackages

/*
 * Pluto: "Set up voice typing" guide. The app has no internet permission, so each step opens the
 * model file's download link in the browser and then the model import file picker.
 */

const val VoiceSetupGuideRoute = "voiceSetupGuide"

fun hasMoonshineModel(context: Context) = VoiceInputEngines.installedMoonshineModels(context).isNotEmpty()

/** Voice Input page (top), shown while no Moonshine voice model is installed. */
val VoiceSetupGuideEntry = userSettingNavigationItem(
    title = R.string.pluto_voice_setup_title,
    subtitle = R.string.pluto_voice_setup_subtitle,
    style = NavigationItemStyle.HomePrimary,
    icon = R.drawable.mic_fill,
    navigateTo = VoiceSetupGuideRoute
).copy(visibilityCheck = {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val useSystem = useDataStoreValue(USE_SYSTEM_VOICE_INPUT)
    val missing = remember(changes) { !hasMoonshineModel(context) }
    missing && !useSystem
})

/**
 * Makes a Voice Input setting open the setup guide when tapped while [missing] is true (its
 * switch is greyed out then, with a hint saying a model is needed).
 */
fun UserSetting.opensVoiceSetupGuideWhen(missing: (Context) -> Boolean): UserSetting = copy(component = {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val changes by ModelPackages.changes.collectAsState()
    val isMissing = remember(changes) { missing(context) }
    if (isMissing && navController != null) {
        Box(Modifier.clickable { navController.navigate(VoiceSetupGuideRoute) }) { this@opensVoiceSetupGuideWhen.component() }
    } else {
        this@opensVoiceSetupGuideWhen.component()
    }
})

@Composable
private fun SetupStep(
    title: String,
    body: String,
    installed: Boolean,
    downloadUrl: String,
    extra: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Typography.Heading.RegularMl, color = MaterialTheme.colorScheme.onSurface)
            if (installed) {
                Text(
                    stringResource(R.string.pluto_voice_setup_installed),
                    style = Typography.Body.RegularMl,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(body, style = Typography.Body.RegularMl, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra?.invoke()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { context.openURI(downloadUrl, true) }) {
                    Text(stringResource(R.string.pluto_voice_setup_download))
                }
                OutlinedButton(onClick = { openModelPackageImporter(context) }) {
                    Text(stringResource(R.string.pluto_voice_setup_import))
                }
            }
        }
    }
}

@Composable
fun VoiceSetupGuideScreen(navController: NavHostController) {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val voiceInstalled = remember(changes) { hasMoonshineModel(context) }
    val punctuationInstalled = remember(changes) { PunctuationModels.installed(context).isNotEmpty() }
    val contextInstalled = remember(changes) { ContextModels.installed(context).isNotEmpty() }

    ScrollableList {
        ScreenTitle(stringResource(R.string.pluto_voice_setup_title), showBack = true, navController)
        Tip(stringResource(R.string.pluto_voice_setup_intro))

        SetupStep(
            title = stringResource(R.string.pluto_voice_setup_step_voice_title),
            body = stringResource(R.string.pluto_voice_setup_step_voice_body),
            installed = voiceInstalled,
            downloadUrl = PlutoLinks.MODEL_VOICE_MOONSHINE_SMALL_EN,
            extra = {
                TextButton(onClick = { navController.navigate(Route.VoiceModel("en")) }) {
                    Text(stringResource(R.string.pluto_voice_setup_other_sizes))
                }
            }
        )
        SetupStep(
            title = stringResource(R.string.pluto_voice_setup_step_punctuation_title),
            body = stringResource(R.string.pluto_voice_setup_step_punctuation_body),
            installed = punctuationInstalled,
            downloadUrl = PlutoLinks.MODEL_PUNCTUATION_EN
        )
        SetupStep(
            title = stringResource(R.string.pluto_voice_setup_step_context_title),
            body = stringResource(R.string.pluto_voice_setup_step_context_body),
            installed = contextInstalled,
            downloadUrl = PlutoLinks.MODEL_SMART_CORRECTIONS_EN,
            extra = if (!ContextModels.deviceSupported) {
                {
                    Text(
                        stringResource(R.string.context_models_unsupported_device),
                        style = Typography.Body.RegularMl,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else null
        )
    }
}
