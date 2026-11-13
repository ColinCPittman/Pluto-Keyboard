package org.futo.inputmethod.engine.general

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.DictationPlacement
import org.futo.inputmethod.latin.InputConnectionUtil
import org.futo.inputmethod.latin.InputConnectionInternalComposingWrapper
import org.futo.inputmethod.latin.SupportsNonComposing
import org.futo.inputmethod.latin.TypingTextEdit
import org.futo.inputmethod.latin.VoiceInputAlternativeIC
import org.futo.inputmethod.latin.VoiceInputAlternativeICComposing
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.uix.ActionInputTransaction
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.utils.TextContext
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2

private const val TAG = "ActionInputTransaction"

class ActionInputTransactionIME(val helper: IMEHelper) : IMEInterface, ActionInputTransaction {
    val useComposingMode = run {
        val inputType = helper.getCurrentEditorInfo()?.inputType ?: 0
        val inputClass = inputType and EditorInfo.TYPE_MASK_CLASS
        inputClass == EditorInfo.TYPE_CLASS_TEXT
    }

    val ic = if(helper.context.getSetting(VoiceInputAlternativeIC) && SupportsNonComposing && useComposingMode) {
        InputConnectionInternalComposingWrapper(
            helper.context.getSetting(VoiceInputAlternativeICComposing),
            true,
            helper.getCurrentInputConnection())
    } else {
        helper.getCurrentInputConnection()
    }

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun onDeviceUnlocked() {}
    override fun onStartInput() {}
    override fun onOrientationChanged() {}
    override fun onFinishInput() {}
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        composingSpanStart: Int,
        composingSpanEnd: Int
    ) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (ic !is InputConnectionInternalComposingWrapper && composingSpanStart >= 0 && partialText.isNotEmpty()) {
            partialStart = minOf(composingSpanStart, composingSpanEnd)
        }
        if(ic is InputConnectionInternalComposingWrapper) {
            if(!ic.mightBeBelated(oldSelStart, oldSelEnd, newSelStart, newSelEnd)) {
                ic.cursorUpdated(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
            }
        }
    }

    override fun isGestureHandlingAvailable(): Boolean = false
    override fun onEvent(event: Event) {}
    override fun onStartBatchInput() {}
    override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
    override fun onEndBatchInput(batchPointers: InputPointers?) {}
    override fun onCancelBatchInput() {}
    override fun onCancelInput() {}
    override fun onFinishSlidingInput() {}
    override fun onCustomRequest(requestCode: Int): Boolean = false
    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {}
    override fun onMoveDeletePointer(steps: Int) {}
    override fun onUpWithDeletePointerActive() {}
    override fun onUpWithPointerActive() {}
    override fun onSwipeLanguage(direction: Int) {}
    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {}
    override fun clearUserHistoryDictionaries() {}
    override fun requestSuggestionRefresh() {}
    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) { }

    override val textContext: TextContext = TextContext(
        beforeCursor = ic?.getTextBeforeCursor(Constants.VOICE_INPUT_CONTEXT_SIZE, 0),
        afterCursor = ic?.getTextAfterCursor(Constants.VOICE_INPUT_CONTEXT_SIZE, 0)
    )

    private var isFinished = false
    private var partialText = ""
    /** Where the streamed text starts in the editor's text, -1 while unknown. */
    private var partialStart = -1
    private var selStart = -1
    private var selEnd = -1
    override fun updatePartial(text: String) {
        if (isFinished || !useComposingMode) return
        helper.requestCursorUpdate()
        // where the streamed text starts (it replaces the selection, if any)
        if (partialText.isEmpty() && selStart >= 0) partialStart = minOf(selStart, selEnd)
        partialText = text
        ic?.setComposingText(
            partialText,
            1
        )
        (ic as? InputConnectionInternalComposingWrapper)?.composingStart?.let { if (it >= 0) partialStart = it }

        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    override fun commit(text: String) {
        // Pluto: dictation wrote text (for the log when an app drops a typing fix's underline)
        org.futo.inputmethod.latin.uix.TypingFixes.lastInput = "voice"
        if (isFinished) return
        helper.requestCursorUpdate()
        isFinished = true
        if (!placeOverStreamedText(text)) {
            ic?.commitText(
                text,
                1
            )
        }
        helper.endInputTransaction(this)
        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    override fun cancel() {
        helper.requestCursorUpdate()
        commit(partialText)
        (ic as? InputConnectionInternalComposingWrapper)?.send()
    }

    /**
     * Pluto: when the user moved the cursor after the streamed text was shown (while punctuation and
     * Smart corrections ran after the dictation ended), the final text replaces the streamed text
     * where it is and the cursor stays where the user put it. Committing as usual would write it at
     * the new cursor, so the dictation would be in the text twice. If the streamed text isn't there
     * exactly any more, it's left as it is. Returns false when the cursor didn't move (or where the
     * streamed text is isn't known), so the text is committed as usual.
     */
    private fun placeOverStreamedText(text: String): Boolean {
        val shown = partialText
        val start = partialStart
        if (shown.isEmpty() || start < 0) return false
        val wrapper = ic as? InputConnectionInternalComposingWrapper
        // the editor itself (the wrapper types at its own cursor)
        val target: InputConnection = (if (wrapper != null) wrapper.mTarget else ic) ?: return false
        val extracted = try { InputConnectionUtil.extractSelection(target) } catch (e: Exception) { -1 to -1 }
        val (s, e) = if (extracted.first >= 0 && extracted.second >= 0) extracted else selStart to selEnd
        if (s < 0 || e < 0) return false
        val from = minOf(s, e)
        val to = maxOf(s, e)
        if (from == to && from == start + shown.length) return false
        // (the cursor is still right after the streamed text, wherever the editor says it starts)
        if (from == to && target.getTextBeforeCursor(shown.length, 0)?.toString() == shown) return false

        // the streamed text stays in place as plain text, and nothing is composing any more
        if (wrapper != null) wrapper.finishComposingText() else target.finishComposingText()
        val result = DictationPlacement.place(target, start, shown, text, from, to)
        Log.i(TAG, "Final text placed over the streamed text away from the cursor: result $result")
        if (result != TypingTextEdit.DONE && result != TypingTextEdit.WRONG_AFTER) {
            Log.w(TAG, "The streamed text changed or couldn't be selected, so it is left as it is")
        }
        return true
    }

    fun ensureFinished() {
        isFinished = true
    }
}