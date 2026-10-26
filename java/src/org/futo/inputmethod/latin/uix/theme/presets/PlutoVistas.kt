package org.futo.inputmethod.latin.uix.theme.presets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.extendedDarkColorScheme
import org.futo.inputmethod.latin.uix.extendedLightColorScheme
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.selector.ThemePreview

// Pluto Classic: the Pluto Night sky with the Pluto Dusk peach accent.
private val plutoClassicScheme = extendedDarkColorScheme(
    primary=Color(0xFFE8B096),
    onPrimary=Color(0xFF3A1A14),
    primaryContainer=Color(0xFF4A3550),
    onPrimaryContainer=Color(0xFFF6E8D6),
    secondary=Color(0xFF9475FC),
    onSecondary=Color(0xFF0A0C2C),
    secondaryContainer=Color(0xFF2E2D72),
    onSecondaryContainer=Color(0xFFF6F1FF),
    tertiary=Color(0xFF7BA4FD),
    onTertiary=Color(0xFF07082A),
    tertiaryContainer=Color(0xFF1E2A78),
    onTertiaryContainer=Color(0xFFD6DEFF),
    error=Color(0xFFFF8C80),
    onError=Color(0xFF4D2B2B),
    errorContainer=Color(0xFF803B3B),
    onErrorContainer=Color(0xFFFFDFDB),
    outline=Color(0xFFB0B6F5),
    outlineVariant=Color(0xFF2C3166),
    surface=Color(0xFF0A0C2C),
    onSurface=Color(0xFFF6F1FF),
    onSurfaceVariant=Color(0xFFB0B6F5),
    surfaceContainerHighest=Color(0xFF252A5C),
    shadow=Color(0xFF000000).copy(alpha = 0.7f),
    keyboardSurface=Color(0xFF10153C),
    keyboardContainer=Color(0xFFD6DEFF).copy(alpha = 0.14f),
    keyboardContainerVariant=Color(0xFFD6DEFF).copy(alpha = 0.07f),
    onKeyboardContainer=Color(0xFFF6F1FF),
    keyboardPress=Color(0xFFE8B096).copy(alpha = 0.5f),
    keyboardFade0=Color(0xFF10153C),
    keyboardFade1=Color(0xFF10153C),
    primaryTransparent=Color(0xFFE8B096).copy(alpha = 0.3f),
    onSurfaceTransparent=Color(0xFFF6F1FF).copy(alpha = 0.1f),
    hintColor=Color(0xFFE8B096),

    keyboardBackgroundGradient = Brush.verticalGradient(
        0.0f to Color(0xFF141A48),
        1.0f to Color(0xFF0A0C2C)
    ),
    navigationBarColor = Color(0xFF0A0C2C),
    navigationBarColorForTransparency = Color(0xFF000000),
)

// Pluto Gilded Horizon: black sky over mustard-gold plains and white ice fields.
private val plutoGildedHorizonScheme = extendedDarkColorScheme(
    primary=Color(0xFFD9B44A),
    onPrimary=Color(0xFF2A2006),
    primaryContainer=Color(0xFF4A3D14),
    onPrimaryContainer=Color(0xFFF7EBC4),
    secondary=Color(0xFFE8E4D8),
    onSecondary=Color(0xFF1E1C16),
    secondaryContainer=Color(0xFF3A372C),
    onSecondaryContainer=Color(0xFFF4F1E8),
    tertiary=Color(0xFFA8B0A0),
    onTertiary=Color(0xFF1A1D16),
    tertiaryContainer=Color(0xFF3C4236),
    onTertiaryContainer=Color(0xFFE6EBDF),
    error=Color(0xFFFF8C80),
    onError=Color(0xFF4D2B2B),
    errorContainer=Color(0xFF803B3B),
    onErrorContainer=Color(0xFFFFDFDB),
    outline=Color(0xFF9C9478),
    outlineVariant=Color(0xFF3A3626),
    surface=Color(0xFF0B0B0A),
    onSurface=Color(0xFFF4F1E8),
    onSurfaceVariant=Color(0xFFCFC8B0),
    surfaceContainerHighest=Color(0xFF2A2820),
    shadow=Color(0xFF000000).copy(alpha = 0.8f),
    keyboardSurface=Color(0xFF121210),
    keyboardContainer=Color(0xFFFFF6E0).copy(alpha = 0.1f),
    keyboardContainerVariant=Color(0xFFFFF6E0).copy(alpha = 0.06f),
    onKeyboardContainer=Color(0xFFF7F4EC),
    keyboardPress=Color(0xFFD9B44A).copy(alpha = 0.45f),
    keyboardFade0=Color(0xFF121210),
    keyboardFade1=Color(0xFF121210),
    primaryTransparent=Color(0xFFD9B44A).copy(alpha = 0.3f),
    onSurfaceTransparent=Color(0xFFF4F1E8).copy(alpha = 0.1f),
    hintColor=Color(0xFFD9B44A),

    keyboardBackgroundGradient = Brush.verticalGradient(
        0.0f to Color(0xFF0A0A0A),
        0.5f to Color(0xFF151410),
        1.0f to Color(0xFF3A3216)
    ),
    navigationBarColor = Color(0xFF3A3216),
    navigationBarColorForTransparency = Color(0xFF000000),
)

// Pluto Frost: silver ice under a hazy horizon glow, deep blue sky ink.
private val plutoFrostScheme = extendedLightColorScheme(
    primary=Color(0xFF34557F),
    onPrimary=Color(0xFFFFFFFF),
    primaryContainer=Color(0xFFD3DEEC),
    onPrimaryContainer=Color(0xFF102038),
    secondary=Color(0xFF5B6B80),
    onSecondary=Color(0xFFFFFFFF),
    secondaryContainer=Color(0xFFE1E6EC),
    onSecondaryContainer=Color(0xFF1B2A44),
    tertiary=Color(0xFF9A7B45),
    onTertiary=Color(0xFFFFFFFF),
    tertiaryContainer=Color(0xFFF1E6CF),
    onTertiaryContainer=Color(0xFF3A2C10),
    error=Color(0xFF8C2A2D),
    onError=Color(0xFFF2BFC1),
    errorContainer=Color(0xFFF2C2C5),
    onErrorContainer=Color(0xFF80262C),
    outline=Color(0xFF66748A),
    outlineVariant=Color(0xFFC3CBD6),
    surface=Color(0xFFF2F4F6),
    onSurface=Color(0xFF1B2A44),
    onSurfaceVariant=Color(0xFF3C4A60),
    surfaceContainerHighest=Color(0xFFD8DEE6),
    shadow=Color(0xFF0E1A2E).copy(alpha = 0.5f),
    keyboardSurface=Color(0xFFE4E8EC),
    keyboardSurfaceDim=Color(0xFFD9DEE4),
    keyboardContainer=Color(0xFFFFFFFF).copy(alpha = 1.0f),
    keyboardContainerVariant=Color(0xFFFFFFFF).copy(alpha = 0.5f),
    onKeyboardContainer=Color(0xFF16233A),
    keyboardPress=Color(0xFFC5D3E6),
    keyboardFade0=Color(0xFFE4E8EC),
    keyboardFade1=Color(0xFFE4E8EC),
    primaryTransparent=Color(0xFF34557F).copy(alpha = 0.3f),
    onSurfaceTransparent=Color(0xFF1B2A44).copy(alpha = 0.1f),
    hintColor=Color(0xFF3F5F8C),

    keyboardBackgroundGradient = Brush.verticalGradient(
        0.0f to Color(0xFFCDD7E4),
        0.5f to Color(0xFFDDE4EC),
        1.0f to Color(0xFFE8ECF1)
    ),
    navigationBarColor = Color(0xFFE8ECF1),
)

// Pluto Ember Ridge: graphite sky, rust-red rock and fresh snow.
private val plutoEmberRidgeScheme = extendedDarkColorScheme(
    primary=Color(0xFFE0796A),
    onPrimary=Color(0xFF2A0E08),
    primaryContainer=Color(0xFF5E2A22),
    onPrimaryContainer=Color(0xFFFFDDD5),
    secondary=Color(0xFFE6E6E8),
    onSecondary=Color(0xFF1E1E20),
    secondaryContainer=Color(0xFF3C3C40),
    onSecondaryContainer=Color(0xFFF2F2F4),
    tertiary=Color(0xFFB8A9A6),
    onTertiary=Color(0xFF2A2221),
    tertiaryContainer=Color(0xFF4A3F3D),
    onTertiaryContainer=Color(0xFFF2E6E4),
    error=Color(0xFFFF8C80),
    onError=Color(0xFF4D2B2B),
    errorContainer=Color(0xFF803B3B),
    onErrorContainer=Color(0xFFFFDFDB),
    outline=Color(0xFF9A9296),
    outlineVariant=Color(0xFF45403F),
    surface=Color(0xFF1C1C1E),
    onSurface=Color(0xFFF2F2F4),
    onSurfaceVariant=Color(0xFFC8C4C6),
    surfaceContainerHighest=Color(0xFF3A3A3D),
    shadow=Color(0xFF000000).copy(alpha = 0.7f),
    keyboardSurface=Color(0xFF2A2A2D),
    keyboardContainer=Color(0xFFFFFFFF).copy(alpha = 0.09f),
    keyboardContainerVariant=Color(0xFFFFFFFF).copy(alpha = 0.06f),
    onKeyboardContainer=Color(0xFFF7F7F8),
    keyboardPress=Color(0xFFE0796A).copy(alpha = 0.45f),
    keyboardFade0=Color(0xFF2A2A2D),
    keyboardFade1=Color(0xFF2A2A2D),
    primaryTransparent=Color(0xFFE0796A).copy(alpha = 0.3f),
    onSurfaceTransparent=Color(0xFFF2F2F4).copy(alpha = 0.1f),
    hintColor=Color(0xFFF0A090),

    keyboardBackgroundGradient = Brush.verticalGradient(
        0.0f to Color(0xFF2C2C30),
        0.45f to Color(0xFF2E2A2B),
        1.0f to Color(0xFF4A2A24)
    ),
    navigationBarColor = Color(0xFF4A2A24),
    navigationBarColorForTransparency = Color(0xFF000000),
)

// Pluto Horizon: black starry sky meeting the tan glow of Pluto's plains.
private val plutoHorizonScheme = extendedDarkColorScheme(
    primary=Color(0xFFE6BC8E),
    onPrimary=Color(0xFF3A2410),
    primaryContainer=Color(0xFF5A4232),
    onPrimaryContainer=Color(0xFFFBEBDA),
    secondary=Color(0xFFB8BED0),
    onSecondary=Color(0xFF1C2030),
    secondaryContainer=Color(0xFF363A4C),
    onSecondaryContainer=Color(0xFFE6E9F2),
    tertiary=Color(0xFFC98F6A),
    onTertiary=Color(0xFF3A1E0E),
    tertiaryContainer=Color(0xFF5E3A26),
    onTertiaryContainer=Color(0xFFF3DCC8),
    error=Color(0xFFFF8C80),
    onError=Color(0xFF4D2B2B),
    errorContainer=Color(0xFF803B3B),
    onErrorContainer=Color(0xFFFFDFDB),
    outline=Color(0xFF9A96A0),
    outlineVariant=Color(0xFF3A3A44),
    surface=Color(0xFF0B0C12),
    onSurface=Color(0xFFF4EEE6),
    onSurfaceVariant=Color(0xFFC9C4C0),
    surfaceContainerHighest=Color(0xFF2A2C36),
    shadow=Color(0xFF000000).copy(alpha = 0.7f),
    keyboardSurface=Color(0xFF14151E),
    keyboardContainer=Color(0xFFFFF4E8).copy(alpha = 0.09f),
    keyboardContainerVariant=Color(0xFFFFF4E8).copy(alpha = 0.05f),
    onKeyboardContainer=Color(0xFFFBF3EA),
    keyboardPress=Color(0xFFE6BC8E).copy(alpha = 0.45f),
    keyboardFade0=Color(0xFF14151E),
    keyboardFade1=Color(0xFF14151E),
    primaryTransparent=Color(0xFFE6BC8E).copy(alpha = 0.3f),
    onSurfaceTransparent=Color(0xFFF4EEE6).copy(alpha = 0.1f),
    hintColor=Color(0xFFE6BC8E),

    keyboardBackgroundGradient = Brush.verticalGradient(
        0.0f to Color(0xFF0A0B12),
        0.5f to Color(0xFF1A1A22),
        1.0f to Color(0xFF5C4232)
    ),
    navigationBarColor = Color(0xFF5C4232),
    navigationBarColorForTransparency = Color(0xFF000000),
)

val PlutoClassic = ThemeOption(
    dynamic = false,
    key = "PlutoClassic",
    name = R.string.theme_pluto_classic,
    available = { true }
) {
    plutoClassicScheme
}

val PlutoGildedHorizon = ThemeOption(
    dynamic = false,
    key = "PlutoGildedHorizon",
    name = R.string.theme_pluto_gilded_horizon,
    available = { true }
) {
    plutoGildedHorizonScheme
}

val PlutoFrost = ThemeOption(
    dynamic = false,
    key = "PlutoFrost",
    name = R.string.theme_pluto_frost,
    available = { true }
) {
    plutoFrostScheme
}

val PlutoEmberRidge = ThemeOption(
    dynamic = false,
    key = "PlutoEmberRidge",
    name = R.string.theme_pluto_ember_ridge,
    available = { true }
) {
    plutoEmberRidgeScheme
}

val PlutoHorizon = ThemeOption(
    dynamic = false,
    key = "PlutoHorizon",
    name = R.string.theme_pluto_horizon,
    available = { true }
) {
    plutoHorizonScheme
}

@Composable
@Preview
private fun PreviewPlutoClassic() {
    ThemePreview(PlutoClassic)
}

@Composable
@Preview
private fun PreviewPlutoGildedHorizon() {
    ThemePreview(PlutoGildedHorizon)
}

@Composable
@Preview
private fun PreviewPlutoFrost() {
    ThemePreview(PlutoFrost)
}

@Composable
@Preview
private fun PreviewPlutoEmberRidge() {
    ThemePreview(PlutoEmberRidge)
}

@Composable
@Preview
private fun PreviewPlutoHorizon() {
    ThemePreview(PlutoHorizon)
}
