package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.testing.CaptureThreadGuard
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * #205: the live preview from the IME's side — the display writes into a
 * recording [EditorPort], the terminal step classifies the field and the
 * app-wide state survives an instance death (the forbidden duplicate).
 */
@RunWith(RobolectricTestRunner::class)
class ImeLivePreviewTest {

    /**
     * A small editable model: a text buffer, a selection and one composing
     * region. It mirrors what `setComposingText` does to a real client — the
     * region is REPLACED, not appended to — because that is the behaviour the
     * reconciliation policy reasons about.
     */
    private class FakeEditor(initial: String = "") : EditorPort {
        val buffer = StringBuilder(initial)
        private var selStart = initial.length
        private var selEnd = initial.length
        private var compStart = -1
        private var compEnd = -1
        var composingWrites = 0
        val commits = mutableListOf<String>()

        fun select(start: Int, end: Int) {
            selStart = start
            selEnd = end
        }

        fun cursor() = selEnd

        fun selectionRange() = selStart to selEnd

        /** The platform committing the composition when the field loses input. */
        fun clientCommitsComposition() {
            compStart = -1
            compEnd = -1
        }

        override fun textBefore(length: Int): CharSequence? {
            val end = selEnd
            return buffer.substring(maxOf(0, end - length), end)
        }

        override fun textAfter(length: Int): CharSequence? =
            buffer.substring(selEnd, minOf(buffer.length, selEnd + length))

        override fun commit(text: CharSequence) {
            compStart = -1
            compEnd = -1
            buffer.insert(selEnd, text)
            selStart = selEnd + text.length
            selEnd = selStart
            commits += text.toString()
        }

        override fun setComposing(text: CharSequence) {
            composingWrites++
            if (compStart >= 0) {
                buffer.delete(compStart, compEnd)
                selStart = compStart
                selEnd = compStart
                compStart = -1
                compEnd = -1
            } else {
                buffer.delete(selStart, selEnd)
                selEnd = selStart
            }
            val at = selEnd
            buffer.insert(at, text)
            compStart = at
            compEnd = at + text.length
            selStart = compEnd
            selEnd = compEnd
        }

        override fun finishComposing() {
            compStart = -1
            compEnd = -1
        }

        override fun selection(): EditorPort.Selection? {
            if (selStart == selEnd) return null
            return EditorPort.Selection(buffer.substring(selStart, selEnd), selStart, selEnd)
        }

        override fun setSelection(start: Int, end: Int) {
            selStart = start
            selEnd = end
        }
    }

    private class RecordingIme : PolishedVoiceInputIME() {
        var editor: EditorPort? = null
        val inserted = mutableListOf<String>()
        var startCalls = 0

        override fun editorPort(): EditorPort? = editor

        override fun insertResultText(text: String) {
            inserted += text
        }

        override fun startIfPermitted() {
            startCalls++
        }
    }

    private lateinit var app: PolishedRecognitionApp
    private lateinit var service: RecordingIme
    private lateinit var editor: FakeEditor

    private val controller: VoiceSessionController
        get() = app.voiceSessionController

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        CaptureThreadGuard.install()
        editor = FakeEditor()
        service = Robolectric.buildService(RecordingIme::class.java).create().get()
        service.editor = editor
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
        CaptureThreadGuard.restoreAndFail()
    }

    private fun field(fieldId: Int = 1) = EditorInfo().apply {
        packageName = "com.example.chat"
        this.fieldId = fieldId
    }

    private fun completed(text: String) = VoiceSessionController.Event.Completed(
        result = Result.success(text)
    )

    private fun clipboardText(): String? {
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        return clip.getItemAt(0).text?.toString()
    }

    /**
     * Starts a preview session in the state and feeds ONE display tick through
     * the IME — the production order (state first, then the event).
     */
    private fun stream(
        text: String,
        markers: Int = 0,
        enabled: Boolean = true,
        bind: Boolean = true
    ) {
        controller.livePreview.begin(enabled)
        controller.livePreview.onFragments(text, markers)
        if (bind) service.onStartInputView(field(), false)
        service.handleEvent(VoiceSessionController.Event.DisplayChanged(text, markers))
    }

    // ------------------------------------------------------------ kill switch

    @Test
    fun `kill switch off writes nothing and inserts once through today's path`() {
        stream("hallo", enabled = false)

        assertThat(editor.composingWrites).isEqualTo(0)
        assertThat(editor.buffer.toString()).isEmpty()

        service.handleCompletedSuccess("Hallo.", completed("Hallo."))

        assertThat(service.inserted).containsExactly("Hallo.")
        assertThat(editor.composingWrites).isEqualTo(0)
    }

    // ------------------------------------------------------------- display D2

    @Test
    fun `every finished fragment advances the composition in the field`() {
        stream("hallo")
        assertThat(editor.buffer.toString()).isEqualTo("hallo")

        controller.livePreview.onFragments("hallo welt", 0)
        service.handleEvent(VoiceSessionController.Event.DisplayChanged("hallo welt", 0))

        assertThat(editor.buffer.toString()).isEqualTo("hallo welt")
    }

    @Test
    fun `the display keeps the text left of the composition intact`() {
        editor.buffer.append("vor ")
        editor.select(4, 4)

        stream("hallo")

        assertThat(editor.buffer.toString()).isEqualTo("vor hallo")
        assertThat(controller.livePreview.before).isEqualTo("vor ")
    }

    @Test
    fun `a display marker reaches the field but never the terminal insert`() {
        stream("hallo <pending> welt", markers = 1)

        assertThat(editor.buffer.toString()).isEqualTo("hallo <pending> welt")

        service.handleCompletedSuccess("QS", completed("QS"))
        assertThat(editor.buffer.toString()).isEmpty()
        assertThat(service.inserted).containsExactly("QS")
    }

    // ------------------------------------------------------------ terminal D4

    @Test
    fun `a cleared field inserts the polished text exactly once`() {
        stream("hallo")

        service.handleCompletedSuccess("Hallo.", completed("Hallo."))

        assertThat(editor.buffer.toString()).isEmpty()
        assertThat(service.inserted).containsExactly("Hallo.")
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `a field holding the whole provisional inserts nothing and copies the polished text`() {
        stream("hallo")
        // The platform commits the composition when the field finishes input.
        editor.clientCommitsComposition()

        service.handleCompletedSuccess("Hallo.", completed("Hallo."))

        assertThat(service.inserted).isEmpty()
        assertThat(editor.buffer.toString()).isEqualTo("hallo")
        assertThat(clipboardText()).isEqualTo("Hallo.")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_raw_kept))
    }

    @Test
    fun `a verified prefix gets the raw suffix appended and the polished text copied`() {
        editor.buffer.append("vor hal")
        editor.select(7, 7)
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo welt", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "vor "
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        service.onStartInputView(field(), false)

        service.handleCompletedSuccess("Hallo Welt.", completed("Hallo Welt."))

        assertThat(editor.commits).containsExactly("lo welt")
        assertThat(editor.buffer.toString()).isEqualTo("vor hallo welt")
        assertThat(service.inserted).isEmpty()
        assertThat(clipboardText()).isEqualTo("Hallo Welt.")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_polished_copied))
    }

    @Test
    fun `the appended raw suffix strips pending markers`() {
        editor.buffer.append("vor hal")
        editor.select(7, 7)
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo <pending> welt", 1)
        controller.livePreview.streamed = true
        controller.livePreview.before = "vor "
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        service.onStartInputView(field(), false)

        service.handleCompletedSuccess("Q", completed("Q"))

        assertThat(editor.commits).containsExactly("lo welt")
        assertThat(editor.buffer.toString()).isEqualTo("vor hallo welt")
    }

    @Test
    fun `an edited field inserts nothing and explains the clipboard`() {
        editor.buffer.append("vor XYZ")
        editor.select(7, 7)
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "vor "
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        service.onStartInputView(field(), false)

        service.handleCompletedSuccess("Q", completed("Q"))

        assertThat(service.inserted).isEmpty()
        assertThat(editor.buffer.toString()).isEqualTo("vor XYZ")
        assertThat(clipboardText()).isEqualTo("Q")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_stream_incomplete))
    }

    // ----------------------------------------------------------- rebind D3

    @Test
    fun `a rebind with a committed prefix composes only the delta`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo welt", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.sent = "hallo"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abchal")
        editor.select(6, 6)

        service.onStartInput(field(), false)

        assertThat(editor.buffer.toString()).isEqualTo("abchallo welt")
        assertThat(controller.livePreview.reconciled).isTrue()
        assertThat(controller.livePreview.committedPrefix).isEqualTo("hal")
    }

    @Test
    fun `a rebind with B only re-composes the whole provisional`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abc")
        editor.select(3, 3)

        service.onStartInput(field(), false)

        assertThat(editor.buffer.toString()).isEqualTo("abchallo")
        assertThat(controller.livePreview.reconciled).isTrue()
    }

    @Test
    fun `a rebind after the platform committed everything writes no text`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.sent = "hallo"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abchallo")
        editor.select(8, 8)

        service.onStartInput(field(), false)

        assertThat(editor.buffer.toString()).isEqualTo("abchallo")
        assertThat(controller.livePreview.committedPrefix).isEqualTo("hallo")
    }

    @Test
    fun `a rebind into another field writes nothing`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abc")
        editor.select(3, 3)

        service.onStartInput(field(fieldId = 2), false)

        assertThat(editor.buffer.toString()).isEqualTo("abc")
        assertThat(controller.livePreview.aborted).isTrue()
    }

    // ------------------------------------------------- app-wide state (D3/I5)

    /**
     * The forbidden duplicate: the first instance displayed the text, the
     * platform committed it, the instance died. A blind insert by the new
     * instance would double it — the app-wide state must classify instead.
     */
    @Test
    fun `a new IME instance classifies from the app-wide state instead of blind inserting`() {
        stream("hallo")
        editor.clientCommitsComposition()

        // The instance dies (rotation); a fresh one binds to the same field.
        val second = Robolectric.buildService(RecordingIme::class.java).create().get()
        second.editor = editor
        second.onStartInputView(field(), false)
        second.handleCompletedSuccess("Hallo.", completed("Hallo."))

        assertThat(second.inserted).isEmpty()
        assertThat(editor.buffer.toString()).isEqualTo("hallo")
        assertThat(clipboardText()).isEqualTo("Hallo.")
    }

    // ----------------------------------------------------- non-delivering I1

    @Test
    fun `a failure clears the composition and restores the selection snapshot`() {
        editor.buffer.append("abcd")
        editor.select(0, 4)
        stream("x")
        assertThat(editor.buffer.toString()).isEqualTo("x")

        service.handleEvent(
            VoiceSessionController.Event.Completed(Result.failure(RuntimeException("boom")))
        )

        assertThat(editor.buffer.toString()).isEqualTo("abcd")
        assertThat(editor.selectionRange()).isEqualTo(0 to 4)
        assertThat(service.inserted).isEmpty()
    }

    @Test
    fun `a failing selection restore copies the snapshot and says so`() {
        editor.buffer.append("abcd")
        editor.select(0, 4)
        stream("x")
        // The field changed under us before the failure arrived.
        editor.clientCommitsComposition()
        editor.buffer.clear()
        editor.buffer.append("other")
        editor.select(5, 5)

        service.handleEvent(
            VoiceSessionController.Event.Completed(Result.failure(RuntimeException("boom")))
        )

        assertThat(clipboardText()).isEqualTo("abcd")
        // The warning rides in the SAME Toast as the failure reason.
        assertThat(ShadowToast.getTextOfLatestToast())
            .contains(app.getString(R.string.ime_selection_restore_failed))
    }

    @Test
    fun `the preview never starts writing into a field it cannot read`() {
        service.editor = object : EditorPort by editor {
            override fun textBefore(length: Int): CharSequence? = null
        }
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        service.onStartInputView(field(), false)
        service.handleEvent(VoiceSessionController.Event.DisplayChanged("hallo", 0))

        assertThat(editor.composingWrites).isEqualTo(0)
        assertThat(controller.livePreview.aborted).isTrue()
    }
}
