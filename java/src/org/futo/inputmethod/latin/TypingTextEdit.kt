package org.futo.inputmethod.latin

import android.view.inputmethod.InputConnection

/**
 * Replacing a range of the text somewhere away from the selection, straight through the editor's
 * InputConnection, with the selection put back where it was (Smart corrections for typing: a fix,
 * its undo, or its underline put back). It's kept apart from [RichInputConnection] so it can be
 * tested with a fake editor.
 */
object TypingTextEdit {
    /** It was replaced and reads back as expected. */
    const val DONE = 0
    /** The editor refused to select the range, so nothing changed. */
    const val REFUSED = 1
    /** The editor said yes to selecting it but selected something else, so nothing changed. */
    const val NOT_SELECTED = 2
    /** It was replaced, but the text there doesn't read back as expected afterwards. */
    const val WRONG_AFTER = 3
    /** The expected text isn't at the range, counted from where the selection really is, so nothing was touched. */
    const val NOT_THERE = 4

    /**
     * Replaces [start]..[end] of [ic]'s text with [text], where the selection is [selStart]..[selEnd]
     * and the range doesn't overlap it. When [expected] is given, it must first be there as read
     * from the editor's own selection (if the app moved its cursor or changed its text and the
     * keyboard hasn't heard yet, the range is somewhere else, and nothing is touched, the selection
     * included). The range is then read back once selected and must be exactly that, or nothing is
     * written (an editor that ignores the selection would get the text at its own cursor). The
     * selection is put back, moved by the change in length when the range is before it. Returns
     * one of [DONE], [REFUSED], [NOT_SELECTED], [WRONG_AFTER] or [NOT_THERE].
     */
    fun replace(ic: InputConnection, start: Int, end: Int, expected: String?, text: CharSequence, selStart: Int, selEnd: Int): Int {
        val before = end <= selStart
        val delta = text.length - (end - start)
        val newStart = if (before) selStart + delta else selStart
        val newEnd = if (before) selEnd + delta else selEnd
        if (expected != null && !readsBack(ic, start, expected, selStart, selEnd)) return NOT_THERE
        ic.beginBatchEdit()
        try {
            if (!ic.setSelection(start, end)) return REFUSED
            if (expected != null && ic.getSelectedText(0)?.toString() != expected) {
                ic.setSelection(selStart, selEnd)
                return NOT_SELECTED
            }
            ic.commitText(text, 1)
            ic.setSelection(newStart, newEnd)
        } finally {
            ic.endBatchEdit()
        }
        return if (readsBack(ic, start, text.toString(), newStart, newEnd)) DONE else WRONG_AFTER
    }

    /** Whether [text] is at [start] of [ic]'s text, read from the side of the selection it's on. */
    fun readsBack(ic: InputConnection, start: Int, text: String, selStart: Int, selEnd: Int): Boolean {
        if (text.isEmpty()) return true
        return if (start + text.length <= selStart) {
            val got = ic.getTextBeforeCursor(selStart - start, 0)?.toString() ?: return true
            // (an app may give less than asked, and then it can't be told)
            got.length < selStart - start || got.startsWith(text)
        } else if (start >= selEnd) {
            val n = start - selEnd + text.length
            val got = ic.getTextAfterCursor(n, 0)?.toString() ?: return true
            got.length < n || got.endsWith(text)
        } else true
    }
}
