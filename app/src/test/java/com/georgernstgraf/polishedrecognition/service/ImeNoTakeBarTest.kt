package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
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

/**
 * #192 (owner): "fresh idle should advance to a parked at 00:00."
 * #201/#204 (owner): the bar must look like a plain PAUSE at 0:00 — the timer
 * is its only text, no sentence explains the ↺.
 *
 * There is exactly ONE no-take bar: with nothing buffered there is nothing to
 * send, a live ↺ that starts a take, and the timer at `0:00`. The post-send
 * park (#177) is the same bar, identically drawn — the flag now only
 * suppresses the auto-start, it changes no visible pixel.
 *
 * Why one bar and not two: a control may only be live when pressing it has an
 * effect. Before #192, fresh idle had a live-looking plane (disabled) and a
 * pause glyph that did nothing, while the park had ↺ — two looks for the same
 * "nothing buffered" state, one of which read as a deep pause.
 */
@RunWith(RobolectricTestRunner::class)
class ImeNoTakeBarTest {

    /** The start path is counted, not executed (a live take is poisonous under Robolectric). */
    private class RecordingIme : PolishedVoiceInputIME() {
        var startCalls = 0

        override fun startIfPermitted() {
            startCalls++
        }

        override fun insertResultText(text: String) {
        }
    }

    private lateinit var app: PolishedRecognitionApp
    private lateinit var service: RecordingIme

    private val controller: VoiceSessionController
        get() = app.voiceSessionController

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.imeConfigChangeMs = 0L
        app.imeLastField = null
        // #198: the app's global crash handler kills the fork — capture instead.
        CaptureThreadGuard.install()
        service = Robolectric.buildService(RecordingIme::class.java).create().get()
    }

    @After
    fun tearDown() {
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
        // #198: restore the app's handler; fails the test if it saw a throwable.
        CaptureThreadGuard.restoreAndFail()
    }

    private fun field(fieldId: Int = 1) = EditorInfo().apply {
        packageName = "com.example.chat"
        this.fieldId = fieldId
    }

    /**
     * A fresh keyboard: nothing was dictated, nothing was sent — and the bar
     * is already the parked-0:00 shape, not a third "empty" look.
     */
    @Test
    fun `a fresh idle bar looks like a plain pause at 0 00`() {
        val view = service.onCreateInputView()

        val resume = view.findViewById<ImageButton>(R.id.ime_pause_resume_button)
        val send = view.findViewById<ImageButton>(R.id.ime_send_button)
        val flush = view.findViewById<ImageButton>(R.id.ime_flush_button)
        val timer = view.findViewById<TextView>(R.id.ime_rec_timer)
        val stage = view.findViewById<TextView>(R.id.ime_stage_text)

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        // Nothing to send: the plane is disabled, not merely inert-looking.
        assertThat(send.isEnabled).isFalse()
        assertThat(flush.isEnabled).isFalse()
        // ↺ is live and says what it does (a content description, not a line).
        assertThat(resume.isEnabled).isTrue()
        assertThat(resume.contentDescription)
            .isEqualTo(service.getString(R.string.ime_new_take_desc))
        // The buffered time reads 0:00 instead of being hidden.
        assertThat(timer.visibility).isEqualTo(View.VISIBLE)
        assertThat(timer.text.toString()).isEqualTo("0:00")
        // #201/#204: no explaining sentence — a paused take shows no stage
        // line either, and this bar must not look different from one.
        assertThat(stage.visibility).isEqualTo(View.GONE)
    }

    /**
     * A tap on ↺ in fresh idle really starts a take — the whole point of
     * rendering it live (a live control without an effect is what the owner's
     * rule forbids).
     */
    @Test
    fun `a tap on the fresh idle resume starts a take`() {
        val view = service.onCreateInputView()
        service.onStartInputView(field(), false)

        view.findViewById<ImageButton>(R.id.ime_pause_resume_button).performClick()

        assertThat(service.startCalls).isEqualTo(1)
    }

    /**
     * After a send the SAME bar comes back, identically drawn (#201/#204): same
     * enabled/disabled set, same `0:00`, no stage line. The park keeps its
     * effect — it suppresses the auto-start (#177) — but has no look of its
     * own any more.
     */
    @Test
    fun `after a send the same bar comes back unchanged`() {
        val view = service.onCreateInputView()
        service.onStartInputView(field(), false)
        val resume = view.findViewById<ImageButton>(R.id.ime_pause_resume_button)
        val send = view.findViewById<ImageButton>(R.id.ime_send_button)
        val flush = view.findViewById<ImageButton>(R.id.ime_flush_button)
        val timer = view.findViewById<TextView>(R.id.ime_rec_timer)
        val stage = view.findViewById<TextView>(R.id.ime_stage_text)

        assertThat(stage.visibility).isEqualTo(View.GONE)

        service.handleCompletedSuccess(
            "Hallo Welt",
            VoiceSessionController.Event.Completed(result = Result.success("Hallo Welt"))
        )

        assertThat(send.isEnabled).isFalse()
        assertThat(flush.isEnabled).isFalse()
        assertThat(resume.isEnabled).isTrue()
        assertThat(resume.contentDescription)
            .isEqualTo(service.getString(R.string.ime_new_take_desc))
        assertThat(timer.visibility).isEqualTo(View.VISIBLE)
        assertThat(timer.text.toString()).isEqualTo("0:00")
        assertThat(stage.visibility).isEqualTo(View.GONE)
    }
}
