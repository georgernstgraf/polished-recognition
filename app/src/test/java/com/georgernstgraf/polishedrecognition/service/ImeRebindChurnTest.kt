package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.view.inputmethod.EditorInfo
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
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
import java.io.File

/**
 * #118 Phase C: a rebind storm must not re-do work it does not need. One
 * rotation delivers repeated bind cycles on the SAME instance; the session
 * survives (FREEZE), but the bar used to be repainted, the foreground service
 * re-asserted, the notification rewritten and the breathing animation
 * restarted on every cycle.
 *
 * The measurement is the existing trace: every `onStartInputView` line carries
 * `churn=render:<r> fgs:<f> notify:<n> breath:<b>` and reports what the
 * interval that just ended really did (the counters reset right after the
 * line), which is the device artifact the owner can read after a rotation.
 */
@RunWith(RobolectricTestRunner::class)
class ImeRebindChurnTest {

    private lateinit var app: PolishedRecognitionApp
    private lateinit var service: PolishedVoiceInputIME

    private val controller: VoiceSessionController
        get() = app.voiceSessionController

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        logFile().delete()
        service = Robolectric.buildService(PolishedVoiceInputIME::class.java).create().get()
    }

    @After
    fun tearDown() {
        // The breath test really starts a session (empty audio under
        // Robolectric); tear it down so its threads cannot outlive the test.
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
    }

    private fun logFile(): File {
        val ctx = RuntimeEnvironment.getApplication()
        return File(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs"), "ime-lifecycle.log")
    }

    private fun bindLines(): List<String> =
        logFile().readLines().filter { it.contains(" onStartInputView ") }

    private fun field(fieldId: Int = 1) = EditorInfo().apply {
        packageName = "com.example.chat"
        this.fieldId = fieldId
    }

    /**
     * Two binds on the same field with nothing changed in between. The line of
     * the FIRST bind reports the fresh view's paint (it must paint once), the
     * line of the SECOND reports a repeat that did nothing at all.
     */
    @Test
    fun `a repeated identical bind reports zero churn in the lifecycle trace`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        service.onStartInputView(field(), false)

        val lines = bindLines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("churn=render:1 fgs:0 notify:0 breath:0")
        assertThat(lines[1]).contains("churn=render:0 fgs:0 notify:0 breath:0")
        assertThat(service.renders).isEqualTo(0)
        assertThat(service.foregroundAssertions).isEqualTo(0)
        assertThat(service.notificationPosts).isEqualTo(0)
        assertThat(service.breathStarts).isEqualTo(0)
    }

    /**
     * The notification and the foreground service are coalesced the same way —
     * and a REAL change still goes through. An FGS assert carries its own
     * notification, so it counts as `fgs`, never as `notify` as well.
     */
    @Test
    fun `an identical notification and foreground assert are skipped, a real change is not`() {
        val processing = VoiceSessionController.Event.StateChanged(
            VoiceSessionController.State.PROCESSING
        )
        service.handleEvent(processing)
        service.handleEvent(processing)

        assertThat(service.notificationPosts).isEqualTo(1)
        assertThat(service.foregroundAssertions).isEqualTo(0)

        val recording = VoiceSessionController.Event.StateChanged(
            VoiceSessionController.State.RECORDING
        )
        service.handleEvent(recording)

        assertThat(service.foregroundAssertions).isEqualTo(1)
        assertThat(service.notificationPosts).isEqualTo(1)

        service.handleEvent(recording)

        assertThat(service.foregroundAssertions).isEqualTo(1)
        assertThat(service.notificationPosts).isEqualTo(1)

        // #118 Phase C must not suppress a real change: the text differs again.
        service.handleEvent(processing)
        assertThat(service.notificationPosts).isEqualTo(2)
        service.handleEvent(processing)
        assertThat(service.notificationPosts).isEqualTo(2)
    }

    /**
     * The most visible churn: `startBreathing()` cancels and recreates the
     * `ValueAnimator`, so a restart jumps the breath phase back to its start.
     * Two consecutive binds while RECORDING must not start it a second time.
     *
     * The RECORDING state is real here — the auto-start runs through the
     * production path (`onInputViewDrawn` → `runAutoStart` → `controller.start`),
     * exactly like `ImeAutoStartGateTest`.
     */
    @Test
    fun `two rebinds while recording do not restart the breathing animation`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        service.onInputViewDrawn()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        // Exactly one start: the state transition into RECORDING.
        assertThat(service.breathStarts).isEqualTo(1)

        service.onStartInputView(field(), false)
        service.onStartInputView(field(), false)

        assertThat(bindLines().last()).contains("breath:0")
        assertThat(service.breathStarts).isEqualTo(0)
        assertThat(service.renders).isEqualTo(0)
    }
}
