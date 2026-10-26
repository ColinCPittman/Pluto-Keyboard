package org.futo.inputmethod.latin.uix

import androidx.compose.runtime.Composable

/**
 * Hooks for the developer's private tuning tools. Public builds include this folder, where every
 * hook does nothing (no logs, no extra settings).
 */
object Tuning {
    @Suppress("UNUSED_PARAMETER")
    fun learningLog(context: android.content.Context, tag: String, message: () -> String) {}

    @Composable
    fun DeveloperRows() {}
}
