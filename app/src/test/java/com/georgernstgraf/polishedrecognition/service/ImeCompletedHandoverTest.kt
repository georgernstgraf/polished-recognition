package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.SttFailure
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
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
 * #174: a successful send inserts the text and NOTHING else — the voice
 * keyboard stays visible and stays the default IME, like a typing keyboard
 * that keeps its place until the platform gesture dismisses it.
 *
 * The regression this pins down: the #133 hand-back switched the DEFAULT IME
 * to the typing keyboard (`switchInputMethod`) and hid the voice keyboard
 * (`requestHideSelf`) after every send.
 *
 * #177 adds the other half of "nothing else": the staying keyboard shows the
 * parked bar and never auto-starts a new recording on its own.
 */
@RunWith(RobolectricTestRunner::class)
class ImeCompletedHandoverTest {

    /**
     * Records what the service would do to the keyboard and the editor. The
     * start path is counted instead of executed: under Robolectric the shadow
     * `AudioRecord` fills at CPU speed (PITFALLS — a live take must not
     * outlive its assert), and this class only needs to know whether a start
     * was requested. `ImeAutoStartGateTest` proves a counted request really
     * becomes a recording.
     */
    private class RecordingIme : PolishedVoiceInputIME() {
        val switchedTo = mutableListOf<String?>()
        var hideCalls = 0
        var startCalls = 0
        val inserted = mutableListOf<String>()

        override fun switchInputMethod(id: String?) {
            switchedTo += id
        }

        override fun requestHideSelf(flags: Int) {
            hideCalls++
        }

        override fun insertResultText(text: String) {
            inserted += text
        }

        override fun startIfPermitted() {
            startCalls++
        }
    }

    private lateinit var app: PolishedRecognitionApp
    private lateinit var service: RecordingIme

    private val controller: VoiceSessionController
        get() = app.voiceSessionController

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
        // #177: without the mic permission there is no auto-start at all, and
        // the park tests would pass for the wrong reason.
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        service = Robolectric.buildService(RecordingIme::class.java).create().get()
    }

    @After
    fun tearDown() {
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
    }

    private fun completed(text: String) = VoiceSessionController.Event.Completed(
        result = Result.success(text)
    )

    /**
     * #186: a raw-mode rescue event — the device case was an offline session
     * whose every fragment failed, so the delivered text was EMPTY.
     */
    private fun rescued(
        text: String,
        failure: SttFailure? = SttFailure.OFFLINE,
        message: String? = "No usable network — saved for later"
    ) = VoiceSessionController.Event.Completed(
        result = Result.success(text),
        partial = VoiceSessionController.Event.PartialInfo(
            failedIndex = 1,
            chunkCount = 1,
            failure = failure,
            message = message
        )
    )

    private fun field(fieldId: Int = 1) = EditorInfo().apply {
        packageName = "com.example.chat"
        this.fieldId = fieldId
    }

    @Test
    fun `a successful send inserts the text without switching the IME`() {
        service.handleCompletedSuccess("Hallo Welt", completed("Hallo Welt"))

        assertThat(service.inserted).containsExactly("Hallo Welt")
        assertThat(service.switchedTo).isEmpty()
    }

    @Test
    fun `a successful send never hides the voice keyboard`() {
        service.handleCompletedSuccess("Hallo Welt", completed("Hallo Welt"))

        assertThat(service.hideCalls).isEqualTo(0)
    }

    @Test
    fun `a redelivered result takes the same path - insert once, no handover`() {
        val event = VoiceSessionController.Event.Completed(
            result = Result.success("nach der Rotation"),
            redelivered = true
        )

        service.handleCompletedSuccess("nach der Rotation", event)

        assertThat(service.inserted).containsExactly("nach der Rotation")
        assertThat(service.switchedTo).isEmpty()
        assertThat(service.hideCalls).isEqualTo(0)
    }

    /**
     * #177: bind on the field, let the send land, then rebind the same field
     * (hide/show) — the keyboard must come back parked and silent instead of
     * starting a new take.
     */
    @Test
    fun `after a send the same field does not auto-start a new take`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)

        service.handleCompletedSuccess("Hallo Welt", completed("Hallo Welt"))
        service.onStartInputView(field(), false)
        service.onInputViewDrawn()

        assertThat(service.startCalls).isEqualTo(0)
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(shadowOf(service).lastForegroundNotification).isNull()
    }

    /**
     * #177: the parked bar shows ↺ (`ic_resume`) with send and flush inert —
     * and a tap on ↺ requests a fresh take.
     */
    @Test
    fun `the parked bar shows resume and a tap on it asks for a fresh take`() {
        val view = service.onCreateInputView()
        service.onStartInputView(field(), false)
        service.handleCompletedSuccess("Hallo Welt", completed("Hallo Welt"))

        val resume = view.findViewById<ImageButton>(R.id.ime_pause_resume_button)
        val send = view.findViewById<ImageButton>(R.id.ime_send_button)
        val flush = view.findViewById<ImageButton>(R.id.ime_flush_button)
        assertThat(resume.isEnabled).isTrue()
        assertThat(send.isEnabled).isFalse()
        assertThat(flush.isEnabled).isFalse()

        resume.performClick()

        assertThat(service.startCalls).isEqualTo(1)
        // Unparked again: the bar is back to the normal idle form.
        assertThat(send.isEnabled).isTrue()
    }

    /** #177: a genuine field change ends the park and the normal auto-start runs. */
    @Test
    fun `a field change ends the park and the normal auto-start runs`() {
        service.onCreateInputView()
        service.onStartInputView(field(1), false)
        service.handleCompletedSuccess("Hallo Welt", completed("Hallo Welt"))

        service.onStartInputView(field(2), false)
        service.onInputViewDrawn()

        assertThat(service.startCalls).isEqualTo(1)
    }

    /**
     * #186 (device defect): a fully failed raw-mode session — airplane mode,
     * every fragment OFFLINE — was delivered as an empty "rescue success". The
     * IME inserted nothing and said nothing, while the controller had already
     * marked the journal complete and deleted it.
     *
     * An empty rescue inserts NOTHING and always tells the user why plus where
     * the recording waits — exactly one Toast, never a generic failure one.
     */
    @Test
    fun `an empty raw-mode rescue inserts nothing and tells the user where the audio is`() {
        ShadowToast.reset()

        service.handleCompletedSuccess("", rescued(""))

        assertThat(service.inserted).isEmpty()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
        val toast = ShadowToast.getTextOfLatestToast()
        assertThat(toast).contains(app.getString(R.string.ime_stt_offline))
        assertThat(toast).contains("No usable network — saved for later")
        assertThat(toast).contains(app.getString(R.string.ime_rescue_saved))
    }

    /**
     * #186: a rescue that DID recover text inserts it — and shows the same one
     * Toast, because the session is still incomplete.
     */
    @Test
    fun `a non-blank raw-mode rescue inserts the recovered text and the same Toast`() {
        ShadowToast.reset()

        service.handleCompletedSuccess("erster Teil", rescued("erster Teil"))

        assertThat(service.inserted).containsExactly("erster Teil")
        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
        assertThat(ShadowToast.getTextOfLatestToast())
            .contains(app.getString(R.string.ime_rescue_saved))
    }
}
