package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.service.AutoStartPolicy.EntryAction
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
import java.io.File

/**
 * #192: a paused take continues in the app it belongs to — and nowhere else.
 *
 * The device finding: a take paused by the X (keyboard switch) auto-resumed
 * 3.6 minutes later and its audio was merged into the next send (measured
 * `INSERTION length=1125` on the OnePlus). The owner's rule, in three steps:
 *
 * 1. a return into the SAME target app continues the take, always — the pause
 *    button, the X, a nav-bar close, a hidden keyboard, and a take restored
 *    after a process death or a reboot behave identically;
 * 2. another app ends it through the existing #113 cancel (clipboard + Toast
 *    for fragments already transcribed) and starts a fresh empty take — but
 *    only when the cancel can hand the audio over: a take with buffered audio
 *    and nothing transcribed yet is KEPT (a cancel there would delete the only
 *    copy and say nothing, #192 follow-up / #152);
 * 3. hiding the keyboard while RECORDING pauses the take; the post-send park
 *    ends with the keyboard; a rotation storm is neither a pause nor a
 *    dismissal; nothing is written for a paused take (no Workbench entry, no
 *    journal session, no state beyond the existing snapshot).
 *
 * Two Robolectric constraints shape these tests:
 *
 * 1. A live take is poisonous: the shadow `AudioRecord` returns full buffers
 *    with no I/O wait, so the capture thread fills the disk within a second
 *    (PITFALLS). Tests that need RECORDING therefore cancel within a few
 *    statements, and the PAUSED state is produced through the controller's own
 *    process-death snapshot ([VoiceSessionController.restore]).
 * 2. Executing a resume re-opens that capture, so the resume ROWS are pinned
 *    through [PolishedVoiceInputIME.lastEntryAction] (the value the lifecycle
 *    trace prints); the resume body is the unchanged #65
 *    `startMicForeground()` + `controller.resume()` pair.
 */
@RunWith(RobolectricTestRunner::class)
class ImePauseEntryTest {

    /**
     * The start request is counted instead of executed (`ImeCompletedHandoverTest`
     * pattern): the decision is visible without a running recorder.
     */
    private class RecordingIme : PolishedVoiceInputIME() {
        var startCalls = 0
        val inserted = mutableListOf<String>()

        /** #217: the released session ids the out-of-band kick received. */
        val kickedRecovery = mutableListOf<String>()

        override fun insertResultText(text: String) {
            inserted += text
        }

        override fun startIfPermitted() {
            startCalls++
        }

        override fun kickRecovery(sessionId: String) {
            kickedRecovery += sessionId
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
        // Hermetic: a rotation mark, a field or a snapshot from another test
        // class in the same sandbox would silently change every decision.
        app.imeConfigChangeMs = 0L
        app.imeLastField = null
        sessionFile("session.pcm").delete()
        sessionFile("session.meta").delete()
        // #198: the app's global crash handler turns one uncaught background
        // throwable (here: the live capture thread and the fragment worker
        // that read its spool) into `exit value 2` and no trace. The guard
        // makes it a failing test with the throwable instead.
        CaptureThreadGuard.install()
        service = Robolectric.buildService(RecordingIme::class.java).create().get()
    }

    @After
    fun tearDown() {
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
        sessionFile("session.pcm").delete()
        sessionFile("session.meta").delete()
        // #198: restore the app's handler; fails the test if it saw a throwable.
        CaptureThreadGuard.restoreAndFail()
    }

    private fun sessionFile(name: String) = File(app.cacheDir, name)

    /** The IME's adb-readable lifecycle trace (same file [ImeLifecycleTrace] appends to). */
    private fun traceFile(): File {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
        return File(dir, "ime-lifecycle.log")
    }

    private fun field(fieldId: Int = 1, pkg: String = "com.example.chat") = EditorInfo().apply {
        packageName = pkg
        this.fieldId = fieldId
    }

    /**
     * Puts the controller into PAUSED with a target, without a live capture: a
     * hand-crafted process-death snapshot restored through the production path
     * (the same one a killed IME uses on the device).
     */
    private fun pauseViaSnapshot(pkg: String?, durationMs: Long = 4200) {
        sessionFile("session.pcm").writeBytes(ByteArray(320))
        sessionFile("session.meta").writeText(
            buildString {
                append("{\"durationMs\":$durationMs,\"sessionId\":\"s1\"")
                pkg?.let { append(",\"pausePackage\":\"$it\"") }
                append("}")
            }
        )

        assertThat(controller.restore()).isTrue()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.pausedPackage()).isEqualTo(pkg)
    }

    // --- same app: always continue ------------------------------------------

    @Test
    fun `a take paused by the nav-bar close continues in the same app`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false) // learns the field
        pauseViaSnapshot("com.example.chat")

        // Back into the same app — no time limit, no ↺ requirement.
        service.onStartInputView(field(), false)

        assertThat(service.lastEntryAction).isEqualTo(EntryAction.RESUME)
        assertThat(service.startCalls).isEqualTo(0)
    }

    /**
     * "Always continue, no exceptions": a snapshot that came back from disk
     * (process death, reboot) is not a special case — the same app continues it
     * on the FIRST bind, without an ↺ tap.
     */
    @Test
    fun `a take restored after process death continues in the same app`() {
        // Craft the snapshot BEFORE the service exists, so onCreate's
        // controller.restore() consumes it (the device path after a kill).
        sessionFile("session.pcm").writeBytes(ByteArray(320))
        sessionFile("session.meta").writeText(
            "{\"durationMs\":4200,\"sessionId\":\"s1\",\"pausePackage\":\"com.example.chat\"}"
        )
        service = Robolectric.buildService(RecordingIme::class.java).create().get()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)

        service.onCreateInputView()
        service.onStartInputView(field(), false)

        assertThat(service.lastEntryAction).isEqualTo(EntryAction.RESUME)
        assertThat(service.startCalls).isEqualTo(0)
    }

    @Test
    fun `a take paused by the pause button continues in the same app`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        pauseViaSnapshot("com.example.chat")

        service.onStartInputView(field(), false)

        assertThat(service.lastEntryAction).isEqualTo(EntryAction.RESUME)
    }

    // --- another app: salvage, then a fresh take ----------------------------

    /**
     * #217: a foreign app RELEASES the take instead of stalling — the #152
     * kept-guard left the keyboard stuck in the pause look (>1 min, measured).
     * A take restored from disk has buffered audio and nothing transcribed;
     * the release hands the audio to the journal (the recovery contract), a
     * fresh take starts IMMEDIATELY, the out-of-band transcription is kicked
     * for the released session, and the trace says why.
     */
    @Test
    fun `a take paused in one app is released to the recovery when another app opens`() {
        traceFile().delete()
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        pauseViaSnapshot("com.example.chat")

        service.onStartInputView(field(pkg = "com.example.mail"), false)
        assertThat(service.lastEntryAction).isEqualTo(EntryAction.FRESH_START)
        service.onInputViewDrawn()

        // the take is over — and the fresh recording started by itself.
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(service.startCalls).isEqualTo(1)

        // the audio survived in the journal session (the recovery contract).
        val sid = service.kickedRecovery.single()
        val journal = app.fragmentJournal
        val record = journal.readRecord(sid, 0)!!
        assertThat(record.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(File(journal.sessionDir(sid), record.uploadFile!!).length())
            .isGreaterThan(0L)

        val released = traceFile().readLines()
            .filter { it.contains("released-to-recovery") }
        assertThat(released).hasSize(1)
        assertThat(released.single()).contains("pausedMs=4200 session=$sid")

        journal.sessionDir(sid).deleteRecursively()
    }

    /**
     * The other half of the guard: a paused take that holds no audio has
     * nothing to lose, so the foreign app ends it and starts fresh, exactly as
     * before the guard.
     *
     * (The third case — buffered audio WITH transcripts, so the cancel really
     * salvages — cannot be built at IME level: the app's controller uses the
     * real STT endpoint and the transcriber has no test seam. The decision
     * matrix is pinned by `ImeLeftoverPolicyTest`; the release mechanics are
     * pinned at the controller level in `VoiceSessionControllerTest`.)
     */
    @Test
    fun `a paused take with no audio is ended when another app opens`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        pauseViaSnapshot("com.example.chat", durationMs = 0)

        service.onStartInputView(field(pkg = "com.example.mail"), false)
        service.onInputViewDrawn()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(controller.pausedPackage()).isNull()
        assertThat(service.startCalls).isEqualTo(1)
        assertThat(service.kickedRecovery).isEmpty()
    }

    /**
     * A different FIELD of the same app is still "my own dictation" — the rule
     * is keyed on the package (#192 supersedes #134's field comparison).
     */
    @Test
    fun `a take continues on another field of the same app`() {
        service.onCreateInputView()
        service.onStartInputView(field(1), false)
        pauseViaSnapshot("com.example.chat")

        service.onStartInputView(field(2), false)

        assertThat(service.lastEntryAction).isEqualTo(EntryAction.RESUME)
        assertThat(service.startCalls).isEqualTo(0)
    }

    // --- the pause paths remember the target --------------------------------

    /**
     * The X click pauses and records the app the take belongs to, and the
     * pause/resume button keeps the old #65 semantics.
     */
    @Test
    fun `X pauses with the target app and the pause button resumes`() {
        val view = service.onCreateInputView()
        val resume = view.findViewById<ImageButton>(R.id.ime_pause_resume_button)
        val switch = view.findViewById<ImageButton>(R.id.ime_switch_button)
        service.onStartInputView(field(), false)

        try {
            controller.start { service.handleEvent(it) }
        } catch (_: Throwable) {
        }
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)

        switch.performClick()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.pausedPackage()).isEqualTo("com.example.chat")

        resume.performClick()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        assertThat(controller.pausedPackage()).isNull()

        // The recorder runs at CPU speed under Robolectric — stop it now.
        controller.cancel()
    }

    // --- rotation storm: no dismissal, no pause ------------------------------

    /**
     * The storm's `onFinishInputView` carries `finishing=true` and the instance
     * has seen `onConfigurationChanged`; the rebind that follows sees a fresh
     * rotation gate. None of them may pause the take or end the post-send park
     * — pausing or cancelling a rotation destroyed dictations (#83/#113).
     */
    @Test
    fun `a rotation storm never pauses the take`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        try {
            controller.start { service.handleEvent(it) }
        } catch (_: Throwable) {
        }

        service.onConfigurationChanged(Configuration())
        service.onFinishInputView(true)
        service.onWindowHidden()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)

        // Next cycle: the rebind lands first, the mark is still fresh — FREEZE.
        service.onStartInputView(field(), false)
        assertThat(service.lastEntryAction).isEqualTo(EntryAction.NONE)
        service.onFinishInputView(true)

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        assertThat(service.startCalls).isEqualTo(0)
        controller.cancel()
    }

    /** A plain hide while RECORDING pauses the take (the mic must not run unseen). */
    @Test
    fun `hiding the keyboard while recording pauses the take`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        try {
            controller.start { service.handleEvent(it) }
        } catch (_: Throwable) {
        }

        service.onWindowHidden()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.pausedPackage()).isEqualTo("com.example.chat")
        controller.cancel()
    }

    // --- the post-send park ends with the keyboard --------------------------

    @Test
    fun `closing the keyboard after a send starts a new take on the next entry`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        service.handleCompletedSuccess(
            "Hallo Welt",
            VoiceSessionController.Event.Completed(result = Result.success("Hallo Welt"))
        )

        // Nav-bar close: the park must not survive the keyboard.
        service.onWindowHidden()

        // Tap to edit again: the same field now starts a plain fresh take.
        service.onStartInputView(field(), false)
        service.onInputViewDrawn()

        assertThat(service.startCalls).isEqualTo(1)
        assertThat(service.lastEntryAction).isEqualTo(EntryAction.FRESH_START)
    }

    @Test
    fun `without a dismissal the same field still comes back parked (#177)`() {
        service.onCreateInputView()
        service.onStartInputView(field(), false)
        service.handleCompletedSuccess(
            "Hallo Welt",
            VoiceSessionController.Event.Completed(result = Result.success("Hallo Welt"))
        )

        service.onStartInputView(field(), false)
        service.onInputViewDrawn()

        assertThat(service.startCalls).isEqualTo(0)
    }

    // --- the parked bar is not a pause --------------------------------------

    /**
     * The no-take bar must not read as "a pause that is too deep". #192: it is
     * the parked-0:00 shape — send disabled (nothing to send), ↺ live as "New
     * take", the timer visible and pinned to `0:00`. #201/#204 (owner): the bar
     * carries no stage line, exactly like a real pause. A real pause therefore
     * differs only where it matters: send is LIVE, ↺ says "Resume", and the
     * timer shows the BUFFERED time.
     */
    @Test
    fun `the parked bar differs from a paused one in state only`() {
        val view = service.onCreateInputView()
        val resume = view.findViewById<ImageButton>(R.id.ime_pause_resume_button)
        val send = view.findViewById<ImageButton>(R.id.ime_send_button)
        val stage = view.findViewById<TextView>(R.id.ime_stage_text)
        val timer = view.findViewById<TextView>(R.id.ime_rec_timer)

        service.onStartInputView(field(), false)
        service.handleCompletedSuccess(
            "Hallo Welt",
            VoiceSessionController.Event.Completed(result = Result.success("Hallo Welt"))
        )

        assertThat(send.isEnabled).isFalse()
        assertThat(resume.contentDescription)
            .isEqualTo(service.getString(R.string.ime_new_take_desc))
        assertThat(timer.visibility).isEqualTo(View.VISIBLE)
        assertThat(timer.text.toString()).isEqualTo("0:00")
        assertThat(stage.visibility).isEqualTo(View.GONE)

        // A real pause: send enabled, buffered time visible, "Resume" wording.
        pauseViaSnapshot("com.example.chat")

        assertThat(send.isEnabled).isTrue()
        assertThat(resume.contentDescription)
            .isEqualTo(service.getString(R.string.ime_resume_desc))
        assertThat(timer.visibility).isEqualTo(View.VISIBLE)
        assertThat(timer.text.toString()).isEqualTo("0:04")
        assertThat(stage.visibility).isEqualTo(View.GONE)
    }
}
