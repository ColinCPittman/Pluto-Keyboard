package org.futo.inputmethod.latin.uix

import android.content.Context
import java.util.Locale

/** A tuning hook that does nothing in public builds (nothing is kept). */
@Suppress("UNUSED_PARAMETER")
object DictationRecorder {
    fun isEnabled(context: Context): Boolean = false

    fun note(context: Context, key: String, value: Long) {}

    fun record(
        context: Context, locale: Locale, raw: String, segments: List<String>,
        stages: List<Pair<String, String>>, stageMs: List<Pair<String, Long>> = emptyList(),
    ) {}
}
