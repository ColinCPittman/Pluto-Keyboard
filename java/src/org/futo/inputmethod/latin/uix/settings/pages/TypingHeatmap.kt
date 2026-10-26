package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.heatmap.AdaptiveSwipe
import org.futo.inputmethod.latin.heatmap.KeyboardSchematic
import org.futo.inputmethod.latin.heatmap.SchematicKey
import org.futo.inputmethod.latin.heatmap.TypingHeatmap
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingToggleSharedPrefs
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.useSharedPrefsBool
import kotlin.math.sqrt

/** Schematic QWERTY used when no keyboard geometry has been recorded for a profile. */
private val fallbackSchematic: KeyboardSchematic by lazy {
    val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
    val keys = ArrayList<SchematicKey>()
    rows.forEachIndexed { r, (letters, indent) ->
        letters.forEachIndexed { i, c ->
            keys.add(SchematicKey(c.code, c.toString(), (indent + i) / 10f + 0.005f,
                r / 3f + 0.01f, 0.09f, 1f / 3f - 0.02f))
        }
    }
    KeyboardSchematic(10f / 4.5f, keys)
}

@Composable
private fun profileLabel(id: String): String {
    val parts = id.split("|")
    val orientation = if (parts.contains("landscape")) {
        stringResource(R.string.typing_heatmap_landscape)
    } else {
        stringResource(R.string.typing_heatmap_portrait)
    }
    val split = if (parts.contains("split")) " (" + stringResource(R.string.typing_heatmap_split) + ")" else ""
    val swipe = if (AdaptiveSwipe.isSwipeProfile(id)) " · " + stringResource(R.string.typing_heatmap_swipe) else ""
    return "${parts.firstOrNull() ?: id} · $orientation$split$swipe"
}

@Composable
private fun HeatmapCanvas(snapshot: TypingHeatmap.ProfileSnapshot, modifier: Modifier = Modifier) {
    val schematic = snapshot.profile.schematic ?: fallbackSchematic
    val textMeasurer = rememberTextMeasurer()
    val outlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val dotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val markerColor = MaterialTheme.colorScheme.error
    val spreadColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(schematic.aspect.coerceIn(1f, 6f))) {
        val canvasW = size.width
        val canvasH = size.height
        val stroke = Stroke(width = 1.dp.toPx())
        val dotRadius = 2.dp.toPx()

        for (key in schematic.keys) {
            val left = key.x * canvasW
            val top = key.y * canvasH
            val w = key.w * canvasW
            val h = key.h * canvasH
            drawRoundRect(
                color = outlineColor, topLeft = Offset(left, top), size = Size(w, h),
                cornerRadius = CornerRadius(4.dp.toPx()), style = stroke
            )
            if (key.label.isNotEmpty()) {
                drawText(textMeasurer, key.label, topLeft = Offset(left + 3.dp.toPx(), top), style = labelStyle)
            }

            val code = Character.toLowerCase(key.code)
            val stats = snapshot.profile.keys[code] ?: continue
            val cx = left + w / 2f
            val cy = top + h / 2f
            val px = stats.orderedPointsX()
            val py = stats.orderedPointsY()
            for (i in px.indices) {
                drawCircle(dotColor, radius = dotRadius, center = Offset(cx + px[i] * w, cy + py[i] * h))
            }

            val eff = snapshot.effective[code] ?: continue
            val ax = cx + eff.shiftX * w
            val ay = cy + eff.shiftY * h
            // One standard deviation of the learned tap spread
            val sx = sqrt(stats.varX) * w
            val sy = sqrt(stats.varY) * h
            if (sx > 0f && sy > 0f) {
                drawOval(
                    color = spreadColor, topLeft = Offset(cx + stats.meanX * w - sx, cy + stats.meanY * h - sy),
                    size = Size(2 * sx, 2 * sy), style = stroke
                )
            }
            drawLine(markerColor, Offset(cx, cy), Offset(ax, ay), strokeWidth = 1.5.dp.toPx())
            drawCircle(markerColor, radius = 3.dp.toPx(), center = Offset(ax, ay))
        }
    }
}

@Composable
fun TypingHeatmapScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    var refreshCounter by remember { mutableIntStateOf(0) }
    var snapshots by remember { mutableStateOf<List<TypingHeatmap.ProfileSnapshot>?>(null) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    val enabled = useSharedPrefsBool(Settings.PREF_USE_TYPING_HEATMAP, true).value

    LaunchedEffect(refreshCounter) {
        snapshots = withContext(Dispatchers.IO) {
            TypingHeatmap.ensureLoadedBlocking(context)
            TypingHeatmap.snapshot()
        }
    }

    ScrollableList {
        ScreenTitle(stringResource(R.string.typing_heatmap_screen_title), showBack = true, navController)

        SettingToggleSharedPrefs(
            title = stringResource(R.string.typing_heatmap_toggle_title),
            subtitle = stringResource(R.string.typing_heatmap_toggle_subtitle),
            key = Settings.PREF_USE_TYPING_HEATMAP,
            default = true
        )

        if (!enabled) Tip(stringResource(R.string.typing_heatmap_disabled))
        Tip(stringResource(R.string.typing_heatmap_explanation))

        val profiles = snapshots
        if (profiles != null && profiles.isEmpty()) {
            Tip(stringResource(R.string.typing_heatmap_no_data))
        } else if (profiles != null) {
            val current = profiles.firstOrNull { it.id == selectedProfile } ?: profiles.first()

            if (profiles.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { p ->
                        FilterChip(
                            selected = p.id == current.id,
                            onClick = { selectedProfile = p.id },
                            label = { Text(profileLabel(p.id)) }
                        )
                    }
                }
            } else {
                Text(
                    profileLabel(current.id),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HeatmapCanvas(current, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            Text(
                stringResource(
                    if (AdaptiveSwipe.isSwipeProfile(current.id)) R.string.typing_heatmap_total_swipe_points
                    else R.string.typing_heatmap_total_taps,
                    current.profile.totalSamples()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                stringResource(R.string.typing_heatmap_taps_per_key),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                current.profile.keys.entries
                    .sortedBy { it.key }
                    .joinToString("   ") { (code, stats) ->
                        String(Character.toChars(code)) + " " + stats.totalCount
                    },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Button(
            onClick = { showResetDialog = true },
            enabled = profiles?.isNotEmpty() == true,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.padding(16.dp)
        ) {
            Text(stringResource(R.string.typing_heatmap_reset))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            title = { Text(stringResource(R.string.typing_heatmap_reset_confirm_title)) },
            text = { Text(stringResource(R.string.typing_heatmap_reset_confirm_body)) },
            onDismissRequest = { showResetDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    TypingHeatmap.reset()
                    showResetDialog = false
                    selectedProfile = null
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
}
