package org.futo.inputmethod.latin.uix.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Pluto: a settings page on a background of its own. Pages switch without an animation, but
 * Android still draws the old page and the new one together until the new one has drawn its first
 * frame. With see-through pages, both showed on top of each other for as long as that took (a
 * moment on a slow phone). On its own background, the new page always covers the old one.
 */
@Composable
internal fun OpaquePage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        content()
    }
}

/** Like [composable], with the page on its own background ([OpaquePage]). */
internal fun NavGraphBuilder.settingsPage(route: String, content: @Composable (NavBackStackEntry) -> Unit) =
    composable(route) { OpaquePage { content(it) } }

/** Like [composable] for a typed route, with the page on its own background ([OpaquePage]). */
internal inline fun <reified T : Any> NavGraphBuilder.settingsPage(noinline content: @Composable (NavBackStackEntry) -> Unit) =
    composable<T> { OpaquePage { content(it) } }
