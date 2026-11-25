package com.georgernstgraf.polishedrecognition.service

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * #205: the editor seam. The live preview needs `setComposingText`,
 * `finishComposingText`, `getTextBeforeCursor`, `commitText` and the selection
 * — operations that sat unreachable above the `insertResultText` override seam
 * (there is no fake `InputConnection` in the repo, and building one would
 * test the shadow, not the policy).
 *
 * Every call is a REQUEST the client may honour differently (owner
 * vocabulary); the policy above this port is pure and unit-tested, the
 * adapter below is a one-to-one mapping.
 */
internal interface EditorPort {

    /** `getTextBeforeCursor(length)` — null when the client cannot answer. */
    fun textBefore(length: Int): CharSequence?

    /** `getTextAfterCursor(length)` — null when the client cannot answer. */
    fun textAfter(length: Int): CharSequence?

    /** A final, committed insertion (the terminal suffix append). */
    fun commit(text: CharSequence)

    /**
     * #207: deletes [length] chars left of the cursor — the verified span of
     * OUR streamed text, so the polished delivery can replace it. A REQUEST
     * the client may refuse; the caller verifies the result before writing.
     */
    fun deleteBefore(length: Int)

    /** `setComposingText(text)` — REPLACES the current composing region. */
    fun setComposing(text: CharSequence)

    /** Commits the composition as it stands (the selection restore's second half). */
    fun finishComposing()

    /** The current selection's text plus its absolute range, or null. */
    fun selection(): Selection?

    /** Absolute selection range (the restore's last step). */
    fun setSelection(start: Int, end: Int)

    /**
     * #205 D6: the selection snapshot taken at session start. [start]/[end]
     * are absolute offsets in the client's text buffer.
     */
    data class Selection(val text: CharSequence, val start: Int, val end: Int)

    companion object {
        /** The production port over the connection bound right now, or null. */
        fun of(connection: InputConnection?): EditorPort? =
            connection?.let { InputConnectionEditorPort(it) }
    }
}

/** The production adapter — Android in, Android out, no policy. */
internal class InputConnectionEditorPort(
    private val connection: InputConnection
) : EditorPort {

    override fun textBefore(length: Int): CharSequence? = connection.getTextBeforeCursor(length, 0)

    override fun textAfter(length: Int): CharSequence? = connection.getTextAfterCursor(length, 0)

    override fun commit(text: CharSequence) {
        connection.commitText(text, 1)
    }

    override fun deleteBefore(length: Int) {
        connection.deleteSurroundingText(length, 0)
    }

    override fun setComposing(text: CharSequence) {
        connection.setComposingText(text, 1)
    }

    override fun finishComposing() {
        connection.finishComposingText()
    }

    /**
     * #205 D6: the selection is read as text + range — `getSelectedText`
     * carries the styles, `getExtractedText` the offsets. A client that
     * answers neither (a password field, an editor without extracted text)
     * yields null, which the caller records as `selectionUnreadable`.
     */
    override fun selection(): EditorPort.Selection? {
        val text = connection.getSelectedText(InputConnection.GET_TEXT_WITH_STYLES) ?: return null
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        if (start < 0 || end < 0) return null
        return EditorPort.Selection(text, start, end)
    }

    override fun setSelection(start: Int, end: Int) {
        connection.setSelection(start, end)
    }
}
