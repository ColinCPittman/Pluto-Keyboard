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
 * model file's download link in the browser and then the model import file picker. The voice step
 * offers each voice model with what it is like to use, and the punctuation step says when the
 * chosen voice model doesn't need it.
 */

const val VoiceSetupGuideRoute = "voiceSetupGuide"

/** Whether a voice model of Pluto's own is installed (Moonshine, Nemotron or Parakeet; Whisper comes built in). */
fun hasVoiceModel(context: Context) =
    VoiceInputEngines.installedMoonshineModels(context).isNotEmpty() ||
        VoiceInputEngines.installedTranscribeModels(context).isNotEmpty()

/** Voice Input page (top), shown while no voice model of Pluto's own is installed. */
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
    val missing = remember(changes) { !hasVoiceModel(context) }
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

/**
 * One model to get. [downloadUrl] is null when the model is picked from [choices] (each with its own
 * Download button), and [note] (under "Installed", in the same color) says when the step isn't needed.
 */
@Composable
private fun SetupStep(
    title: String,
    body: String,
    installed: Boolean,
    downloadUrl: String?,
    note: String? = null,
    choices: (@Composable () -> Unit)? = null,
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
            listOfNotNull(if (installed) stringResource(R.string.pluto_voice_setup_installed) else null, note).forEach {
                Text(it, style = Typography.Body.RegularMl, color = MaterialTheme.colorScheme.primary)
            }
            Text(body, style = Typography.Body.RegularMl, color = MaterialTheme.colorScheme.onSurfaceVariant)
            choices?.invoke()
            extra?.invoke()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (downloadUrl != null) {
                    Button(onClick = { context.openURI(downloadUrl, true) }) {
                        Text(stringResource(R.string.pluto_voice_setup_download))
                    }
                }
                OutlinedButton(onClick = { openModelPackageImporter(context) }) {
                    Text(stringResource(R.string.pluto_voice_setup_import))
                }
            }
        }
    }
}

/** One voice model to choose in the voice step: its name and size, what it is like to use, and its download. */
@Composable
private fun VoiceModelChoice(title: Int, body: Int, downloadUrl: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = Typography.Body.MediumMl, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(body), style = Typography.SmallMl, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { context.openURI(downloadUrl, true) }) {
            Text(stringResource(R.string.pluto_voice_setup_download))
        }
    }
}

@Composable
fun VoiceSetupGuideScreen(navController: NavHostController) {
    val context = LocalContext.current
    val changes by ModelPackages.changes.collectAsState()
    val voiceInstalled = remember(changes) { hasVoiceModel(context) }
    // a voice model that punctuates itself (not Whisper, which is only there while none is installed) has no use for step 2
    val voiceModel = currentVoiceModel()
    val punctuationNotNeeded = !voiceModel.isWhisper && voiceModel.profile.punctuatesItself
    val punctuationInstalled = remember(changes) { PunctuationModels.installed(context).isNotEmpty() }
    val contextInstalled = remember(changes) { ContextModels.installed(context).isNotEmpty() }

    ScrollableList {
        ScreenTitle(stringResource(R.string.pluto_voice_setup_title), showBack = true, navController)
        Tip(stringResource(R.string.pluto_voice_setup_intro))

        SetupStep(
            title = stringResource(R.string.pluto_voice_setup_step_voice_title),
            body = stringResource(R.string.pluto_voice_setup_step_voice_body),
            installed = voiceInstalled,
            downloadUrl = null,
            choices = {
                VoiceModelChoice(R.string.pluto_voice_setup_choice_parakeet_title, R.string.pluto_voice_setup_choice_parakeet_body, PlutoLinks.MODEL_VOICE_PARAKEET_EN)
                VoiceModelChoice(R.string.pluto_voice_setup_choice_moonshine_title, R.string.pluto_voice_setup_choice_moonshine_body, PlutoLinks.MODEL_VOICE_MOONSHINE_SMALL_EN)
                VoiceModelChoice(R.string.pluto_voice_setup_choice_nemotron_title, R.string.pluto_voice_setup_choice_nemotron_body, PlutoLinks.MODEL_VOICE_NEMOTRON_EN)
            },
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
            downloadUrl = PlutoLinks.MODEL_PUNCTUATION_EN,
            note = if (punctuationNotNeeded) stringResource(R.string.pluto_voice_setup_punctuation_not_needed, voiceModel.name) else null
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
