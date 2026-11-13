package org.futo.inputmethod.latin.uix

import android.view.inputmethod.InputConnection
import org.futo.inputmethod.latin.DictationPlacement
import org.futo.inputmethod.latin.TypingTextEdit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** A text box behind an InputConnection (as in TypingTextEditTest). */
private class Box(text: String, cursor: Int, val ignoresSelection: Boolean = false) {
    val text = StringBuilder(text)
    var selStart = cursor
    var selEnd = cursor

    val ic: InputConnection = Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, m, args ->
        when (m.name) {
            "setSelection" -> {
                if (!ignoresSelection) { selStart = args[0] as Int; selEnd = args[1] as Int }
                true
            }
            "getSelectedText" -> if (selEnd > selStart) this.text.substring(selStart, selEnd) else null
            "commitText" -> {
                val s = (args[0] as CharSequence).toString()
                this.text.replace(selStart, selEnd, s)
                selStart += s.length
                selEnd = selStart
                true
            }
            "getTextBeforeCursor" -> this.text.substring(maxOf(0, selStart - args[0] as Int), selStart)
            "getTextAfterCursor" -> this.text.substring(selEnd, minOf(this.text.length, selEnd + args[0] as Int))
            "beginBatchEdit", "endBatchEdit" -> true
            else -> if (m.returnType == Boolean::class.javaPrimitiveType) false else null
        }
    } as InputConnection

    fun moveTo(at: Int) { selStart = at; selEnd = at }
}

/**
 * A dictation's final text written over its streamed text after the user moved the cursor while
 * the final pass ran ([DictationPlacement]): it replaces the streamed text where the dictation
 * happened, the cursor stays, and an edited streamed text is left alone (never typed twice).
 */
class DictationPlacementTest {
    private val before = "Notes: "
    private val shown = "i think we should leave at noon"
    private val final = "I think we should leave at noon."

    private fun dictated(after: String = "") = Box(before + shown + after, before.length + shown.length)

    @Test
    fun cursorMovedBeforeTheDictationStaysThere() {
        val box = dictated()
        box.moveTo(2)
        assertEquals(TypingTextEdit.DONE, DictationPlacement.place(box.ic, before.length, shown, final, 2, 2))
        assertEquals(before + final, box.text.toString())
        assertEquals(2 to 2, box.selStart to box.selEnd)
    }

    @Test
    fun cursorMovedAfterTheDictationMovesWithTheLengthChange() {
        val after = " Then lunch."
        val box = dictated(after)
        val at = box.text.length - 1
        box.moveTo(at)
        assertEquals(TypingTextEdit.DONE, DictationPlacement.place(box.ic, before.length, shown, final, at, at))
        assertEquals(before + final + after, box.text.toString())
        assertEquals(at + 1, box.selStart)
        assertEquals("lunch", box.text.substring(box.selStart - 5, box.selStart))
    }

    @Test
    fun cursorInsideTheDictationStaysAfterTheSameWord() {
        val box = dictated()
        val at = before.length + shown.indexOf("should") + "should".length
        box.moveTo(at)
        assertEquals(TypingTextEdit.DONE, DictationPlacement.place(box.ic, before.length, shown, final, at, at))
        assertEquals(before + final, box.text.toString())
        assertEquals(before + "I think we should", box.text.substring(0, box.selStart))
    }

    @Test
    fun aSelectedWordInsideTheDictationStaysSelected() {
        val box = dictated()
        val at = before.length + shown.indexOf("leave")
        box.selStart = at; box.selEnd = at + 5
        assertEquals(TypingTextEdit.DONE, DictationPlacement.place(box.ic, before.length, shown, final, at, at + 5))
        assertEquals(before + final, box.text.toString())
        assertEquals("leave", box.text.substring(box.selStart, box.selEnd))
    }

    @Test
    fun aCursorInAReplacedWordGoesToTheEndOfTheReplacement() {
        val s = "we met at the libary today"
        val f = "We met at the library today."
        val box = Box(s, s.length)
        val at = s.indexOf("libary") + 3 // "lib|ary"
        box.moveTo(at)
        assertEquals(TypingTextEdit.DONE, DictationPlacement.place(box.ic, 0, s, f, at, at))
        assertEquals(f, box.text.toString())
        // still inside the same word, and never past it
        val word = f.indexOf("library")
        assert(box.selStart in word..word + "library".length)
    }

    @Test
    fun editedStreamedTextIsLeftAloneAndNotWrittenTwice() {
        val box = dictated()
        // the user fixed a word in the streamed text before the final pass ended
        val edited = before + shown.replace("noon", "two")
        box.text.setLength(0); box.text.append(edited)
        val at = edited.length - 3
        box.moveTo(at)
        assertEquals(TypingTextEdit.NOT_THERE, DictationPlacement.place(box.ic, before.length, shown, final, at, at))
        assertEquals(edited, box.text.toString())
        assertEquals(at to at, box.selStart to box.selEnd)

        // and text typed before the dictation (so it's moved) when the cursor is after it
        val box2 = dictated(" ok")
        box2.text.insert(0, "Hi ")
        val end = box2.text.length
        box2.moveTo(end)
        assertEquals(TypingTextEdit.NOT_THERE, DictationPlacement.place(box2.ic, before.length, shown, final, end, end))
        assertEquals("Hi " + before + shown + " ok", box2.text.toString())
    }

    @Test
    fun anEditorThatIgnoresTheSelectionGetsNothingTyped() {
        val box = Box(before + shown, 0, ignoresSelection = true)
        assertEquals(TypingTextEdit.NOT_SELECTED, DictationPlacement.place(box.ic, before.length, shown, final, 0, 0))
        assertEquals(before + shown, box.text.toString())
        val inside = Box(before + shown, before.length + 3, ignoresSelection = true)
        assertEquals(TypingTextEdit.NOT_SELECTED, DictationPlacement.place(inside.ic, before.length, shown, final, before.length + 3, before.length + 3))
        assertEquals(before + shown, inside.text.toString())
    }

    @Test
    fun removingTheDictationWhereItIs() {
        // the Cancel button (an empty final text) after the cursor moved
        val box = dictated(" end")
        box.moveTo(box.text.length)
        assertEquals(TypingTextEdit.DONE, DictationPlacement.place(box.ic, before.length, shown, "", box.selStart, box.selEnd))
        assertEquals(before + " end", box.text.toString())
        assertEquals(box.text.length, box.selStart)
    }

    @Test
    fun theSameTextChangesNothing() {
        val box = dictated()
        box.moveTo(0)
        assertEquals(TypingTextEdit.DONE, DictationPlacement.place(box.ic, before.length, shown, shown, 0, 0))
        assertEquals(before + shown, box.text.toString())
        assertEquals(0, box.selStart)
    }

    @Test
    fun positionsLineUpAcrossPunctuationAndCapitals() {
        val old = "i went to the store and bought milk"
        val new = "I went to the store, and bought milk."
        assertEquals(0, DictationPlacement.mapPosition(old, new, 0))
        assertEquals(new.indexOf("went") + 4, DictationPlacement.mapPosition(old, new, old.indexOf("went") + 4))
        assertEquals(new.indexOf("store") + 5, DictationPlacement.mapPosition(old, new, old.indexOf("store") + 5))
        assertEquals(new.indexOf("bought"), DictationPlacement.mapPosition(old, new, old.indexOf("bought")))
        assertEquals(new.length, DictationPlacement.mapPosition(old, new, old.length))
    }
}
