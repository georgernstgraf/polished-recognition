package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.view.inputmethod.EditorInfo
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
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
 * #165: the IME must not start a capture before its window has been drawn —
 * a microphone foreground service started while the window is still
 * invisible loses its while-in-use type and records silence
 * (`Foreground service started from background … New WIU:DENIED`).
 *
 * The draw callback is driven through the same entry point the
 * `ViewTreeObserver.OnDrawListener` uses (`onInputViewDrawn`); the listener
 * itself only posts it.
 */
@RunWith(RobolectricTestRunner::class)
class ImeAutoStartGateTest {

    private lateinit var service: PolishedVoiceInputIME

    private val controller: VoiceSessionController
        get() = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp)
            .voiceSessionController

    @Before
    fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication() as Application)
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        // #198: the app's global crash handler kills the fork — capture instead.
        CaptureThreadGuard.install()
        service = Robolectric.buildService(PolishedVoiceInputIME::class.java).create().get()
    }

    @After
    fun tearDown() {
        // The tests really start a session (empty audio under Robolectric);
        // tear it down so its worker threads cannot outlive the test and
        // post into another test's looper.
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
        // #198: restore the app's handler; fails the test if it saw a throwable.
        CaptureThreadGuard.restoreAndFail()
    }

    private fun field(fieldId: Int = 1) = EditorInfo().apply {
        packageName = "com.example.chat"
        this.fieldId = fieldId
    }

    @Test
    fun `a cold bind waits for the first draw instead of starting`() {
        service.onStartInputView(field(), false)

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(shadowOf(service).lastForegroundNotification).isNull()
    }

    @Test
    fun `the pending start runs once the window is drawn`() {
        service.onStartInputView(field(), false)
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)

        service.onInputViewDrawn()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        assertThat(shadowOf(service).lastForegroundNotification).isNotNull()
    }

    @Test
    fun `a window that hides before its first draw drops the start`() {
        service.onStartInputView(field(), false)

        service.onWindowHidden()
        service.onInputViewDrawn()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(shadowOf(service).lastForegroundNotification).isNull()
    }

    @Test
    fun `the first draw itself never starts anything without a pending action`() {
        service.onInputViewDrawn()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(shadowOf(service).lastForegroundNotification).isNull()
    }
}
