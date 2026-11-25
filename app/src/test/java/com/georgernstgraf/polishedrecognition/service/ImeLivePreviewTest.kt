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
        /** #207: the lengths of the delete-before requests the client honoured. */
        val deletions = mutableListOf<Int>()

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

        override fun deleteBefore(length: Int) {
            val start = maxOf(0, selEnd - length)
            buffer.delete(start, selEnd)
            selStart = start
            selEnd = start
            deletions += length
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

        /** #207: apply the terminal insert to the fake editor for field assertions. */
        var applyInsert = false

        override fun editorPort(): EditorPort? = editor

        override fun insertResultText(text: String) {
            inserted += text
            if (applyInsert) editor?.commit(text)
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

    /**
     * Device finding 2026-11-25: the reconciliation probe read a constant 64
     * chars, so a composition outgrowing it aborted every tick after the
     * first — the preview froze after fragment one until Send delivered.
     */
    @Test
    fun `a composition outgrowing the old constant window keeps advancing`() {
        val first = "wort ".repeat(24) // 120 chars
        stream(first)
        assertThat(editor.buffer.toString()).isEqualTo(first)

        val second = "$first und weiter"
        controller.livePreview.onFragments(second, 0)
        service.handleEvent(VoiceSessionController.Event.DisplayChanged(second, 0))

        assertThat(editor.buffer.toString()).isEqualTo(second)
        assertThat(controller.livePreview.aborted).isFalse()
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
    fun `an identical delivery keeps the committed raw text and copies it`() {
        stream("hallo")
        // The platform commits the composition when the field finishes input.
        editor.clientCommitsComposition()

        // Raw mode: the delivery equals what the field holds — the clipboard
        // behaviour of #205 stays (Q == field content).
        service.handleCompletedSuccess("hallo", completed("hallo"))

        assertThat(service.inserted).isEmpty()
        assertThat(editor.buffer.toString()).isEqualTo("hallo")
        assertThat(editor.deletions).isEmpty()
        assertThat(clipboardText()).isEqualTo("hallo")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_raw_kept))
    }

    /**
     * The #207 device incident (OnePlus, Markor, 2026-11-25): a mid-take
     * rebind left the streamed provisional COMMITTED in the field
     * (`committed=934 composed=0`, no selection), and Send classified it
     * "already delivered" — the polished text went ONLY to the clipboard
     * while the field kept the raw text. Send must leave exactly the
     * polished text in the field.
     */
    @Test
    fun `send after the platform committed the stream replaces the raw text with the polished one`() {
        stream("hello world")
        editor.clientCommitsComposition()
        // The rebind finds everything committed and restores the composition
        // (#207 workstream 3): the underline is back, no duplicate.
        service.onStartInput(field(), false)
        assertThat(controller.livePreview.reconciled).isTrue()
        assertThat(controller.livePreview.sent).isEqualTo("hello world")
        assertThat(controller.livePreview.committedPrefix).isEmpty()
        assertThat(editor.buffer.toString()).isEqualTo("hello world")
        assertThat(editor.deletions).containsExactly(11)
        // The incident record had no readable selection either.
        assertThat(controller.livePreview.selectionUnreadable).isTrue()

        service.applyInsert = true
        service.handleCompletedSuccess("Hallo Welt.", completed("Hallo Welt."))

        // Exactly the polished text, no duplicate, no leftover of the stream.
        assertThat(editor.buffer.toString()).isEqualTo("Hallo Welt.")
        assertThat(service.inserted).containsExactly("Hallo Welt.")
        assertThat(editor.deletions).containsExactly(11)
        assertThat(clipboardText()).isNull()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    /**
     * The raw-mode twin of the incident. Since #207 workstream 3 the bind
     * re-composes the committed stream, so the field holds OUR composition
     * again at Send: the terminal clears it and inserts Q through today's
     * path — the field ends with exactly Q, and the #205 clipboard copy of
     * an identical delivery is not needed any more (the text IS in the field).
     */
    @Test
    fun `send after the platform committed the stream keeps an identical delivery`() {
        stream("hello world")
        editor.clientCommitsComposition()
        service.onStartInput(field(), false)

        service.applyInsert = true
        service.handleCompletedSuccess("hello world", completed("hello world"))

        assertThat(service.inserted).containsExactly("hello world")
        assertThat(editor.buffer.toString()).isEqualTo("hello world")
        assertThat(editor.deletions).containsExactly(11)
        assertThat(clipboardText()).isNull()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `a verified prefix gets the raw suffix appended and an identical delivery copied`() {
        editor.buffer.append("vor hal")
        editor.select(7, 7)
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo welt", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "vor "
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        service.onStartInputView(field(), false)

        service.handleCompletedSuccess("hallo welt", completed("hallo welt"))

        assertThat(editor.commits).containsExactly("lo welt")
        assertThat(editor.buffer.toString()).isEqualTo("vor hallo welt")
        assertThat(service.inserted).isEmpty()
        assertThat(clipboardText()).isEqualTo("hallo welt")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_polished_copied))
    }

    /** #207: the incident's own classification — a differing Q replaces the prefix. */
    @Test
    fun `send with a differing polished result replaces the verified prefix instead of appending`() {
        editor.buffer.append("vor hal")
        editor.select(7, 7)
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo welt", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "vor "
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        service.onStartInputView(field(), false)

        service.applyInsert = true
        service.handleCompletedSuccess("Hallo Welt.", completed("Hallo Welt."))

        assertThat(editor.buffer.toString()).isEqualTo("vor Hallo Welt.")
        assertThat(service.inserted).containsExactly("Hallo Welt.")
        assertThat(editor.deletions).containsExactly(3)
        assertThat(editor.commits).containsExactly("Hallo Welt.")
        assertThat(clipboardText()).isNull()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
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

        service.handleCompletedSuccess("hallo welt", completed("hallo welt"))

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

    /**
     * #207 workstream 3: the rebind over a fully committed stream no longer
     * leaves the provisional un-underlined — the guarded restore deletes the
     * verified span and re-composes it, WITHOUT duplicating anything (the
     * field content is byte-identical, only the composing region is back).
     */
    @Test
    fun `a rebind over a fully committed stream restores the composition without duplicating`() {
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
        assertThat(controller.livePreview.reconciled).isTrue()
        assertThat(controller.livePreview.committedPrefix).isEmpty()
        assertThat(controller.livePreview.sent).isEqualTo("hallo")
        assertThat(editor.deletions).containsExactly(5)
        assertThat(editor.commits).isEmpty()
    }

    /**
     * #207 workstream 3: the restored region keeps streaming — the next
     * fragment replaces it through the normal tick path.
     */
    @Test
    fun `a restored composition keeps growing with the next fragment`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.sent = "hallo"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abchallo")
        editor.select(8, 8)
        service.onStartInput(field(), false)
        assertThat(controller.livePreview.sent).isEqualTo("hallo")

        controller.livePreview.onFragments("hallo welt", 0)
        service.handleEvent(VoiceSessionController.Event.DisplayChanged("hallo welt", 0))

        assertThat(editor.buffer.toString()).isEqualTo("abchallo welt")
        assertThat(controller.livePreview.aborted).isFalse()
    }

    /**
     * #207 workstream 3: the delete is a REQUEST the client may refuse. The
     * read-back then still shows exactly B + P — today's behaviour
     * (committed text, no compose) is the fallback, and no write happens.
     */
    @Test
    fun `a rebind whose restore delete is refused keeps the committed text`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.sent = "hallo"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abchallo")
        editor.select(8, 8)
        service.editor = object : EditorPort by editor {
            override fun deleteBefore(length: Int) = Unit
        }

        service.onStartInput(field(), false)

        assertThat(editor.buffer.toString()).isEqualTo("abchallo")
        assertThat(controller.livePreview.reconciled).isTrue()
        assertThat(controller.livePreview.committedPrefix).isEqualTo("hallo")
        assertThat(controller.livePreview.sent).isEmpty()
        assertThat(editor.composingWrites).isEqualTo(0)
    }

    /**
     * #207 workstream 3: the client honoured the delete but the read-back is
     * unreadable — abort BEFORE the compose write; the field holds B only
     * and the terminal classifies it at Send.
     */
    @Test
    fun `a rebind restore aborts without composing when the read-back is unreadable`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.sent = "hallo"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abchallo")
        editor.select(8, 8)
        service.editor = object : EditorPort by editor {
            var deleted = false
            override fun deleteBefore(length: Int) {
                deleted = true
                editor.deleteBefore(length)
            }

            override fun textBefore(length: Int): CharSequence? =
                if (deleted) null else editor.textBefore(length)
        }

        service.onStartInput(field(), false)

        assertThat(editor.buffer.toString()).isEqualTo("abc")
        assertThat(controller.livePreview.aborted).isTrue()
        assertThat(editor.composingWrites).isEqualTo(0)
        assertThat(controller.livePreview.reconciled).isFalse()
    }

    /**
     * #207 workstream 3: the client deleted LESS than the verified span —
     * the read-back mismatch aborts BEFORE any write (the spec's hard rule:
     * a mismatch cannot mangle the field).
     */
    @Test
    fun `a rebind restore aborts when the client deleted less than the span`() {
        controller.livePreview.begin(true)
        controller.livePreview.onFragments("hallo", 0)
        controller.livePreview.streamed = true
        controller.livePreview.before = "abc"
        controller.livePreview.sent = "hallo"
        controller.livePreview.field = RotationGate.FieldId("com.example.chat", 1)
        editor.buffer.append("abchallo")
        editor.select(8, 8)
        service.editor = object : EditorPort by editor {
            override fun deleteBefore(length: Int) = editor.deleteBefore(length / 2)
        }

        service.onStartInput(field(), false)

        // "hallo" minus its tail — the client kept "hal" committed.
        assertThat(editor.buffer.toString()).isEqualTo("abchal")
        assertThat(controller.livePreview.aborted).isTrue()
        assertThat(editor.composingWrites).isEqualTo(0)
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
        second.applyInsert = true
        second.handleCompletedSuccess("Hallo.", completed("Hallo."))

        // #207: classified, not blind-inserted — the verified streamed span
        // was replaced by exactly the polished text.
        assertThat(second.inserted).containsExactly("Hallo.")
        assertThat(editor.buffer.toString()).isEqualTo("Hallo.")
        assertThat(editor.deletions).containsExactly(5)
        assertThat(clipboardText()).isNull()
    }

    /** The raw-mode twin: an identical delivery keeps the committed text. */
    @Test
    fun `a new IME instance with an identical delivery keeps the committed text`() {
        stream("hallo")
        editor.clientCommitsComposition()

        val second = Robolectric.buildService(RecordingIme::class.java).create().get()
        second.editor = editor
        second.onStartInputView(field(), false)
        second.handleCompletedSuccess("hallo", completed("hallo"))

        assertThat(second.inserted).isEmpty()
        assertThat(editor.buffer.toString()).isEqualTo("hallo")
        assertThat(editor.deletions).isEmpty()
        assertThat(clipboardText()).isEqualTo("hallo")
    }

    /**
     * #207: the delete is a REQUEST the client may refuse — inserting the
     * polished text over a surviving span would be the forbidden duplicate
     * (I5). The verified fallback: Q to the clipboard, the field untouched.
     */
    @Test
    fun `a refused delete falls back to the clipboard instead of duplicating`() {
        stream("hallo")
        editor.clientCommitsComposition()

        service.editor = object : EditorPort by editor {
            override fun deleteBefore(length: Int) = Unit
        }

        service.handleCompletedSuccess("Hallo.", completed("Hallo."))

        assertThat(service.inserted).isEmpty()
        assertThat(editor.buffer.toString()).isEqualTo("hallo")
        assertThat(clipboardText()).isEqualTo("Hallo.")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_stream_incomplete))
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
