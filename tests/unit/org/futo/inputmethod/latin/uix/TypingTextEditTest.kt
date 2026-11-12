package org.futo.inputmethod.latin.uix

import android.view.inputmethod.InputConnection
import org.futo.inputmethod.latin.TypingTextEdit
import org.futo.voiceinput.shared.context.ContextLm
import org.futo.voiceinput.shared.context.TypingConfusions
import org.futo.voiceinput.shared.context.TypingCorrectionConfig
import org.futo.voiceinput.shared.context.TypingCorrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * A text box behind an InputConnection, for the edits a typing fix makes through it. [ignoresSelection]:
 * it says yes to setSelection but doesn't move (some editors do). [dropsCommits]: it says yes to
 * commitText but writes nothing.
 */
private class FakeEditor(text: String, cursor: Int, val ignoresSelection: Boolean = false, val dropsCommits: Boolean = false) {
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
                if (!dropsCommits) {
                    this.text.replace(selStart, selEnd, s)
                    selStart += s.length
                    selEnd = selStart
                }
                true
            }
            "getTextBeforeCursor" -> this.text.substring(maxOf(0, selStart - args[0] as Int), selStart)
            "getTextAfterCursor" -> this.text.substring(selEnd, minOf(this.text.length, selEnd + args[0] as Int))
            "beginBatchEdit", "endBatchEdit" -> true
            else -> if (m.returnType == Boolean::class.javaPrimitiveType) false else null
        }
    } as InputConnection
}

/** A word-level fake LM where log P(word) depends on the previous word only (bigram table, else -3). */
private class PairLm(private val bigrams: Map<Pair<String, String>, Float>) : ContextLm {
    private val ids = LinkedHashMap<String, Int>()
    private val pieces = ArrayList<String>()
    private fun id(p: String) = ids.getOrPut(p) { pieces.add(p); pieces.size - 1 }
    override fun tokenize(text: String, addSpecial: Boolean): IntArray {
        if (addSpecial) return intArrayOf(id("<prompt>"))
        return Regex("\\s*[A-Za-z']+|\\s*[^\\sA-Za-z']").findAll(text).map { id(it.value) }.toList().toIntArray()
    }
    override fun pieceBytes(token: Int): ByteArray = pieces[token].toByteArray()
    override fun ensureCapacity(nTokens: Int) {}
    private fun word(t: Int) = pieces[t].trim().lowercase()
    private fun lp(tokens: IntArray, i: Int, token: Int): Float = bigrams[(if (i > 0) word(tokens[i - 1]) else "") to word(token)] ?: -3f
    override fun evalBase(tokens: IntArray, keep: Int, needFrom: Int, wantOff: IntArray, wantIds: IntArray): ContextLm.BaseEval {
        val first = maxOf(1, needFrom)
        val out = ArrayList<Float>()
        for (i in first until tokens.size) {
            out.add(lp(tokens, i, tokens[i]))
            for (q in wantOff[i] until wantOff[i + 1]) out.add(lp(tokens, i, wantIds[q]))
        }
        return ContextLm.BaseEval(first, out.toFloatArray())
    }
    override fun evalVariants(variants: List<IntArray>, starts: IntArray): List<FloatArray> =
        variants.mapIndexed { v, t -> FloatArray(t.size - starts[v]) { lp(t, starts[v] + it, t[starts[v] + it]) } }
    override fun close() {}
}

/**
 * A typing fix written through the editor's InputConnection ([TypingTextEdit], which
 * RichInputConnection.replaceText uses; RichInputConnection itself needs Android classes the
 * unit tests don't have), and what an undo leaves behind.
 */
class TypingTextEditTest {
    @Test
    fun fixBeforeTheCursorMovesItAndUndoPutsItBack() {
        val start = "I want to to go home. See you"
        val ed = FakeEditor(start, start.length)
        val at = start.indexOf("to to")
        val later = TypingFix(2, start.indexOf("See"), "Se", "See", "", "")
        assertEquals(TypingTextEdit.DONE, TypingTextEdit.replace(ed.ic, at, at + 5, "to to", "to", ed.selStart, ed.selEnd))
        assertEquals("I want to go home. See you", ed.text.toString())
        assertEquals(start.length - 3, ed.selStart)
        assertEquals(ed.selStart, ed.selEnd)
        // the fixes after it moved by the same amount
        val moved = shiftFixes(listOf(TypingFix(1, at, "to to", "to", "I want ", " go home."), later), at, -3)
        assertEquals(at, moved[0].start)
        assertEquals(ed.text.indexOf("See"), moved[1].start)
        // undo
        assertEquals(TypingTextEdit.DONE, TypingTextEdit.replace(ed.ic, at, at + 2, "to", "to to", ed.selStart, ed.selEnd))
        assertEquals(start, ed.text.toString())
        assertEquals(start.length, ed.selStart)
    }

    @Test
    fun fixAfterTheCursorLeavesItWhereItIs() {
        val start = "Ok. So would go there tomorrow."
        val ed = FakeEditor(start, 3)
        val at = start.indexOf("would")
        assertEquals(TypingTextEdit.DONE, TypingTextEdit.replace(ed.ic, at, at + 5, "would", "I would", 3, 3))
        assertEquals("Ok. So I would go there tomorrow.", ed.text.toString())
        assertEquals(3 to 3, ed.selStart to ed.selEnd)
        assertEquals(TypingTextEdit.DONE, TypingTextEdit.replace(ed.ic, at, at + 7, "I would", "would", 3, 3))
        assertEquals(start, ed.text.toString())
        assertEquals(3 to 3, ed.selStart to ed.selEnd)
    }

    @Test
    fun aSelectionBeforeTheFixStaysAndOneAfterItMoves() {
        val start = "Pick this then salt an pepper."
        val ed = FakeEditor(start, 0)
        ed.selStart = 5; ed.selEnd = 9 // "this" selected
        val at = start.indexOf("an ")
        assertEquals(TypingTextEdit.DONE, TypingTextEdit.replace(ed.ic, at, at + 2, "an", "and", 5, 9))
        assertEquals(5 to 9, ed.selStart to ed.selEnd)
        val ed2 = FakeEditor("salt an pepper and this", 0)
        ed2.selStart = 19; ed2.selEnd = 23
        assertEquals(TypingTextEdit.DONE, TypingTextEdit.replace(ed2.ic, 5, 7, "an", "and", 19, 23))
        assertEquals("this", ed2.text.substring(ed2.selStart, ed2.selEnd))
    }

    @Test
    fun undoWithTheCursorInsideTheFixGoesToItsEndFirst() {
        // what TypingCorrections.revert does when the cursor is inside the fixed words
        val text = "We ran out of milk."
        val ed = FakeEditor(text, text.indexOf("of") + 1)
        val at = text.indexOf("out of")
        ed.ic.setSelection(at + 6, at + 6)
        assertEquals(TypingTextEdit.DONE, TypingTextEdit.replace(ed.ic, at, at + 6, "out of", "out", ed.selStart, ed.selEnd))
        assertEquals("We ran out milk.", ed.text.toString())
        assertEquals(at + 3, ed.selStart)
    }

    @Test
    fun anEditorThatIgnoresTheSelectionIsLeftUnchanged() {
        val start = "Ok. So would go there."
        val ed = FakeEditor(start, 3, ignoresSelection = true)
        val at = start.indexOf("would")
        assertEquals(TypingTextEdit.NOT_SELECTED, TypingTextEdit.replace(ed.ic, at, at + 5, "would", "I would", 3, 3))
        assertEquals(start, ed.text.toString())
        assertEquals(3 to 3, ed.selStart to ed.selEnd)
        // before the cursor too
        val ed2 = FakeEditor("salt an pepper", 14, ignoresSelection = true)
        assertEquals(TypingTextEdit.NOT_SELECTED, TypingTextEdit.replace(ed2.ic, 5, 7, "an", "and", 14, 14))
        assertEquals("salt an pepper", ed2.text.toString())
    }

    @Test
    fun aCursorThatMovedUnnoticedLeavesTheTextAndTheCursorAlone() {
        // the app moved its cursor to 20 and the keyboard still takes it to be at 14, so counted
        // from the real cursor the range isn't "an": nothing is selected, written or moved
        val ed = FakeEditor("salt an pepper and more", 20)
        assertEquals(TypingTextEdit.NOT_THERE, TypingTextEdit.replace(ed.ic, 5, 7, "an", "and", 14, 14))
        assertEquals("salt an pepper and more", ed.text.toString())
        assertEquals(20 to 20, ed.selStart to ed.selEnd)
        // after the cursor too
        val ed2 = FakeEditor("salt an pepper and more", 2)
        assertEquals(TypingTextEdit.NOT_THERE, TypingTextEdit.replace(ed2.ic, 5, 7, "an", "and", 0, 0))
        assertEquals(2 to 2, ed2.selStart to ed2.selEnd)
    }

    @Test
    fun anEditTheEditorDidntMakeIsNoticed() {
        val ed = FakeEditor("salt an pepper and more", 0, dropsCommits = true)
        assertEquals(TypingTextEdit.WRONG_AFTER, TypingTextEdit.replace(ed.ic, 5, 7, "an", "and", 0, 0))
    }

    @Test
    fun anAutocorrectRightBeforeTheCursorHoldsBackAFixBeforeIt() {
        // "yuor." was autocorrected to "your." and Backspace would still undo that by its old length
        assertFalse(TypingPlan.mayApplyNow(fixEnd = 10, cursor = 15, revertArmed = true))
        // after the cursor, Backspace doesn't care
        assertTrue(TypingPlan.mayApplyNow(fixEnd = 20, cursor = 15, revertArmed = true))
        // and once the next key has ended it, it goes in
        assertTrue(TypingPlan.mayApplyNow(fixEnd = 10, cursor = 15, revertArmed = false))
    }

    @Test
    fun anUndoneFixOfAnyKindIsNotMadeAgainLater() {
        val bigrams = mapOf(("going" to "to") to -1f, ("to" to "to") to -10f, ("to" to "call") to -1f)
        val config = TypingCorrectionConfig(leftoverWords = true, repeatMargin = 6.0)
        val text = "I was going to to call you."
        val store = HashMap<String, Int>()
        fun memory() = TypingRevertMemory({ store[it] ?: 0 }, { store.putAll(it) })
        fun check(m: TypingRevertMemory) =
            TypingCorrector(PairLm(bigrams), TypingConfusions(emptyMap()), config) { a, b, c, d -> m.caution(a, b, c, d) }.check(text).fix
        val fix = check(memory())
        assertNotNull(fix)
        assertEquals("remove", fix!!.kind)
        // undone, the way TypingCorrections.forget records it (whole strings, spaces and all)
        val (prev, next) = TypingRevertMemory.neighbours(text.substring(0, fix.start), text.substring(fix.end))
        assertEquals("going" to "call", prev to next)
        memory().recordRevert(fix.original.lowercase(), fix.replacement.lowercase(), prev, next)
        memory().recordRefusedResult(TypingPlan.resultHash(text, fix.start, fix.end, fix.replacement))
        assertTrue(store.keys.any { it.startsWith("to to>to") })
        // the field opens again later (a new memory on the same saved counts). The corrector is
        // more careful about that change, and may offer the same result in another form ("to call"
        // -> "call"), which the keyboard doesn't make (TypingCorrections.allowed)
        val m = memory()
        assertTrue(m.caution("to to", "to", "going", "call") >= TypingRevertMemory.PAIR_STEP + TypingRevertMemory.CONTEXT_STEP)
        val again = check(m)
        val remade = again != null && !m.refusesResult(TypingPlan.resultHash(text, again.start, again.end, again.replacement))
        assertFalse(again?.let { "${it.original}>${it.replacement}" } ?: "", remade)
        // a different result in that sentence is still allowed
        assertFalse(m.refusesResult(TypingPlan.resultHash(text, text.indexOf("call"), text.indexOf("call") + 4, "phone")))
    }

    @Test
    fun neighboursStopAtASentenceEnd() {
        assertEquals("" to "pepper", TypingRevertMemory.neighbours("Hi there. ", " pepper and"))
        assertEquals("salt" to "", TypingRevertMemory.neighbours("some salt ", ""))
    }
}
