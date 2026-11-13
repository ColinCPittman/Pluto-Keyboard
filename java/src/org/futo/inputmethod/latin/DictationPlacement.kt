package org.futo.inputmethod.latin

import android.view.inputmethod.InputConnection

/**
 * Writing a dictation's final text (after punctuation and Smart corrections) over the text that
 * was streamed in while the user spoke, when the user moved the cursor while the final pass ran.
 * The final text goes where the dictation happened and the user's cursor stays where they put it.
 * If the streamed text isn't there exactly any more (the user edited it), nothing is written, so
 * the dictation is never typed twice. Kept apart from the transaction so it can be tested with a
 * fake editor, like [TypingTextEdit], whose result codes it returns.
 */
object DictationPlacement {
    /**
     * Replaces [shown] at [start] of [ic]'s text with [text], where the selection is
     * [selStart]..[selEnd]. The selection may be before, after or inside the streamed text. Returns
     * one of the [TypingTextEdit] results ([TypingTextEdit.DONE] when nothing needed changing).
     */
    fun place(ic: InputConnection, start: Int, shown: String, text: String, selStart: Int, selEnd: Int): Int {
        if (start < 0 || selStart < 0 || selEnd < selStart) return TypingTextEdit.NOT_THERE
        val end = start + shown.length
        if (end <= selStart || start >= selEnd) {
            if (text == shown) return if (TypingTextEdit.readsBack(ic, start, shown, selStart, selEnd)) TypingTextEdit.DONE else TypingTextEdit.NOT_THERE
            return TypingTextEdit.replace(ic, start, end, shown, text, selStart, selEnd)
        }
        // the cursor or selection is inside the streamed text (the user is fixing a word in it)
        if (readAround(ic, start, shown.length, selStart, selEnd) != shown) return TypingTextEdit.NOT_THERE
        if (text == shown) return TypingTextEdit.DONE
        val newStart = mapPosition(shown, text, selStart - start) + start
        val newEnd = if (selEnd == selStart) newStart else mapPosition(shown, text, selEnd - start) + start
        ic.beginBatchEdit()
        try {
            if (!ic.setSelection(start, end)) return TypingTextEdit.REFUSED
            if (ic.getSelectedText(0)?.toString() != shown) {
                ic.setSelection(selStart, selEnd)
                return TypingTextEdit.NOT_SELECTED
            }
            ic.commitText(text, 1)
            ic.setSelection(newStart, newEnd)
        } finally {
            ic.endBatchEdit()
        }
        return if (readAround(ic, start, text.length, newStart, newEnd) == text) TypingTextEdit.DONE else TypingTextEdit.WRONG_AFTER
    }

    /**
     * The [length] characters at [start], read from the editor's own selection [selStart]..[selEnd],
     * which overlaps them. Null when the editor gives less than asked (it can't be checked then).
     */
    private fun readAround(ic: InputConnection, start: Int, length: Int, selStart: Int, selEnd: Int): String? {
        val from = minOf(start, selStart)
        val to = maxOf(start + length, selEnd)
        val before = if (selStart > from) ic.getTextBeforeCursor(selStart - from, 0)?.toString() ?: return null else ""
        val selected = if (selEnd > selStart) ic.getSelectedText(0)?.toString() ?: return null else ""
        val after = if (to > selEnd) ic.getTextAfterCursor(to - selEnd, 0)?.toString() ?: return null else ""
        if (before.length != selStart - from || selected.length != selEnd - selStart || after.length != to - selEnd) return null
        val window = before + selected + after
        return window.substring(start - from, start - from + length)
    }

    /**
     * Where position [pos] of [old] ends up in [new] (both the same passage, before and after the
     * final pass): characters are lined up with the fewest edits, a change of case counting as none,
     * so a cursor after a word stays after that word when punctuation or capitals were added. A
     * position inside text that was replaced goes to the end of the replacement.
     */
    fun mapPosition(old: String, new: String, pos: Int): Int {
        if (pos <= 0) return pos
        if (pos >= old.length) return pos + new.length - old.length
        val n = old.length
        val m = new.length
        if (n.toLong() * m > 4_000_000L) {
            // too long to line up: keep it if it's in the unchanged start, else at the same distance from the end
            var p = 0
            while (p < n && p < m && old[p] == new[p]) p++
            return if (pos <= p) pos else maxOf(p, pos + m - n).coerceAtMost(m)
        }
        // edit distance, then walk back for the alignment
        val w = m + 1
        val d = IntArray((n + 1) * w)
        for (j in 0..m) d[j] = j
        for (i in 1..n) {
            d[i * w] = i
            for (j in 1..m) {
                val same = old[i - 1].equals(new[j - 1], ignoreCase = true)
                d[i * w + j] = minOf(
                    d[(i - 1) * w + j - 1] + if (same) 0 else 1,
                    d[(i - 1) * w + j] + 1,
                    d[i * w + j - 1] + 1
                )
            }
        }
        // walk back for the alignment: 'M' same (or only the case changed), 'S' replaced, 'D' only in old, 'I' only in new
        val ops = StringBuilder()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val here = d[i * w + j]
            val same = i > 0 && j > 0 && old[i - 1].equals(new[j - 1], ignoreCase = true)
            if (i > 0 && j > 0 && here == d[(i - 1) * w + j - 1] + (if (same) 0 else 1)) {
                ops.append(if (same) 'M' else 'S'); i--; j--
            } else if (i > 0 && here == d[(i - 1) * w + j] + 1) {
                ops.append('D'); i--
            } else {
                ops.append('I'); j--
            }
        }
        ops.reverse()
        // forward: the first point where pos characters of old are behind, unless pos is inside replaced text
        i = 0
        j = 0
        var k = 0
        while (i < pos) {
            when (ops[k]) { 'M', 'S' -> { i++; j++ }; 'D' -> i++; else -> j++ }
            k++
        }
        val lastWasChanged = ops[k - 1] != 'M'
        var nextOld = k
        while (nextOld < ops.length && ops[nextOld] == 'I') nextOld++
        if (lastWasChanged && nextOld < ops.length && ops[nextOld] != 'M') {
            // inside a replacement: to its end in new
            while (k < ops.length && ops[k] != 'M') { if (ops[k] != 'D') j++; k++ }
        }
        return j
    }
}
