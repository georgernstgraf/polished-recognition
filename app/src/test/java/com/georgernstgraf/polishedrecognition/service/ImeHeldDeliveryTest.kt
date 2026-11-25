package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.service.RotationGate.FieldId
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
 * #207: a send whose field was unreadable (`outcome=UNREADABLE`) parks with
 * its result HELD — the device defect (2026-11-25) was two such sends whose
 * resume taps cleared the park with no evidence the text ever arrived
 * anywhere. The lifecycle pinned here:
 *
 * - the hold never drops the text silently — a readable moment delivers it
 *   (the resume tap, the next bind of the ORIGIN field) or salvages it to
 *   the clipboard with a toast naming where the text is;
 * - a bind of a DIFFERENT field salvages instead of blind-inserting (#192:
 *   a field the user has repurposed is not auto-written);
 * - an unreadable bind keeps the hold, and the teardown salvages it.
 */
@RunWith(RobolectricTestRunner::class)
class ImeHeldDeliveryTest {

    /**
     * Readability is the seam: a Robolectric service never goes through
     * `doStartInput`, so `fieldReadable()` is overridden instead of relying
     * on a shadow `InputConnection`.
     */
    private class HeldIme : PolishedVoiceInputIME() {
        val inserted = mutableListOf<String>()
        var readable = false
        var startCalls = 0

        /** Robolectric: `super.onDestroy()` may run exactly once (SoftInputWindow). */
        var destroyed = false

        override fun onDestroy() {
            if (destroyed) return
            destroyed = true
            super.onDestroy()
        }

        override fun fieldReadable(): Boolean = readable

        override fun insertResultText(text: String) {
            inserted += text
        }

        override fun startIfPermitted() {
            startCalls++
        }
    }

    private lateinit var app: PolishedRecognitionApp
    private lateinit var service: HeldIme

    private val controller: VoiceSessionController
        get() = app.voiceSessionController

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        CaptureThreadGuard.install()
        service = Robolectric.buildService(HeldIme::class.java).create().get()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
        CaptureThreadGuard.restoreAndFail()
    }

    private fun completed(text: String) = VoiceSessionController.Event.Completed(
        result = Result.success(text)
    )

    private fun field(fieldId: Int) = EditorInfo().apply {
        packageName = "com.example.chat"
        this.fieldId = fieldId
    }

    private fun clipboardText(): String? {
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        return clip.getItemAt(0).text?.toString()
    }

    /**
     * The device case: the take ran with the field unreadable (the stream
     * never got to write), so the send ends `outcome=UNREADABLE` with no
     * connection — the exact path where `commitWithSpacing` used to no-op
     * silently. The result is held and the user is TOLD.
     */
    private fun holdViaUnreadableSend() {
        app.imeSessionField = FieldId("com.example.chat", 1)
        service.readable = false
        controller.livePreview.begin(true)
        service.handleCompletedSuccess("Hallo.", completed("Hallo."))
    }

    @Test
    fun `an unreadable send holds the result and says so`() {
        holdViaUnreadableSend()

        assertThat(service.inserted).isEmpty()
        assertThat(clipboardText()).isNull()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_delivery_pending))
    }

    @Test
    fun `a held result is delivered when the user taps resume on the parked bar`() {
        holdViaUnreadableSend()
        val view = service.onCreateInputView()
        service.onStartInputView(field(1), false)

        service.readable = true
        view.findViewById<ImageButton>(R.id.ime_pause_resume_button).performClick()

        assertThat(service.inserted).containsExactly("Hallo.")
        // The fresh take still starts — the delivery does not eat the tap.
        assertThat(service.startCalls).isEqualTo(1)
    }

    @Test
    fun `a held result is delivered at the next bind of the same field`() {
        holdViaUnreadableSend()

        service.readable = true
        service.onStartInputView(field(1), false)

        assertThat(service.inserted).containsExactly("Hallo.")
        assertThat(clipboardText()).isNull()
    }

    /**
     * #192: the user has since bound a different field — the hold must not
     * write into it. The clipboard salvage names where the text is.
     */
    @Test
    fun `a held result is salvaged at a bind of a different field`() {
        holdViaUnreadableSend()
        ShadowToast.reset()

        service.readable = true
        service.onStartInputView(field(2), false)

        assertThat(service.inserted).isEmpty()
        assertThat(clipboardText()).isEqualTo("Hallo.")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_delivery_salvaged))
    }

    @Test
    fun `a held result is salvaged when the instance is torn down`() {
        holdViaUnreadableSend()
        ShadowToast.reset()

        service.onDestroy()

        assertThat(service.inserted).isEmpty()
        assertThat(clipboardText()).isEqualTo("Hallo.")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_delivery_salvaged))
    }

    /** A second unreadable send never orphans the first hold out of order. */
    @Test
    fun `a second unreadable send salvages the older hold first`() {
        holdViaUnreadableSend()
        // A real second send re-begins the preview session (beginRecording);
        // the first send's terminal step ended it.
        controller.livePreview.begin(true)
        service.readable = false
        service.handleCompletedSuccess("Zweiter.", completed("Zweiter."))

        assertThat(clipboardText()).isEqualTo("Hallo.")
        // Three toasts: hold 1's pending line, then the superseded salvage
        // ("Hallo. is in the clipboard"), then hold 2's pending line last.
        assertThat(ShadowToast.shownToastCount()).isEqualTo(3)
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.ime_delivery_pending))
    }

    /**
     * The unpark paths stay intact: a readable send (the regression #174
     * pins) still inserts immediately, and the hold does not exist.
     */
    @Test
    fun `a readable send inserts immediately and holds nothing`() {
        app.imeSessionField = FieldId("com.example.chat", 1)
        service.readable = true
        controller.livePreview.begin(true)
        service.handleCompletedSuccess("Hallo.", completed("Hallo."))

        assertThat(service.inserted).containsExactly("Hallo.")
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }
}
