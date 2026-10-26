package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.useSharedPrefsBool
import org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting
import org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting

@Preview
@Composable
fun AdvancedParametersScreen(navController: NavHostController = rememberNavController()) {
    val resources = LocalResources.current
    ScrollableList {
        ScreenTitle(stringResource(R.string.prediction_settings_transformer_advanced_params), showBack = true, navController)

        Tip(stringResource(R.string.prediction_settings_transformer_advanced_params_experimental_notice))

        SettingSlider(
            title = stringResource(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength),
            subtitle = stringResource(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength_subtitle, "${BinaryDictTransformerWeightSetting.default}"),
            setting = BinaryDictTransformerWeightSetting,
            range = 0.0f .. 100.0f,
            transform = {
                if(it > 99.9f) {
                    Float.POSITIVE_INFINITY
                } else if(it < 0.0001f) {
                    Float.NEGATIVE_INFINITY
                } else {
                    it
                }
            },
            indicator = {
                when {
                    it == Float.POSITIVE_INFINITY -> {
                        resources.getString(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength_always_transformer)
                    }
                    it == Float.NEGATIVE_INFINITY -> {
                        resources.getString(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength_always_binarydictionary)
                    }
                    (it > 0.1f) -> {
                        "a = ${String.format("%.1f", it)}"
                    }
                    else -> {
                        "a = $it"
                    }
                }
            },
            power = 2.5f
        )

        // "Always use middle suggestion" skips the confidence check, so the threshold has no effect then
        val autocorrectOn = useSharedPrefsBool(Settings.PREF_AUTO_CORRECTION, true).value
        val middleSuggestionOn = useSharedPrefsBool(Settings.PREF_ALWAYS_USE_MIDDLE_SUGGESTION, false).value
        val thresholdUnused = autocorrectOn && middleSuggestionOn
        Box(
            if (!thresholdUnused) Modifier else Modifier
                .alpha(0.38f)
                .pointerInput(Unit) {
                    // swallow every touch before the slider sees it
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
        ) {
        SettingSlider(
            title = stringResource(R.string.prediction_settings_transformer_advanced_params_autocorrect_threshold),
            subtitle = stringResource(
                R.string.prediction_settings_transformer_advanced_params_autocorrect_threshold_subtitle,
                AutocorrectThresholdSetting.default
            ),
            setting = AutocorrectThresholdSetting,
            range = 0.0f .. 25.0f,
            hardRange = 0.0f .. 25.0f,
            transform = {
                it
            },
            indicator = {
                "T = ${String.format("%.1f", it)}"
            },
            power = 2.5f
        )
        }
        if (thresholdUnused) {
            Tip(stringResource(R.string.autocorrect_threshold_unused_middle_suggestion))
        }

    }
}