package org.futo.inputmethod.latin.uix

import org.futo.inputmethod.latin.LatinIME

/** A tuning hook that does nothing in public builds. */
@Suppress("UNUSED_PARAMETER")
object DictationFollowUp {
    fun arm(ime: LatinIME, inserted: String, beforeCursor: CharSequence?, afterCursor: CharSequence?) {}

    fun check() {}
}
