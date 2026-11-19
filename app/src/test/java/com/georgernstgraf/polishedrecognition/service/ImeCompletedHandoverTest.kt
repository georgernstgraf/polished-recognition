package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * #174: a successful send inserts the text and NOTHING else — the voice
 * keyboard stays visible and stays the default IME, like a typing keyboard
 * that keeps its place until the platform gesture dismisses it.
 *
 * The regression this pins down: the #133 hand-back switched the DEFAULT IME
 * to the typing keyboard (`switchInputMethod`) and hid the voice keyboard
 * (`requestHideSelf`) after every send.
 */
@RunWith(RobolectricTestRunner::class)
class ImeCompletedHandoverTest {

    /** Records what the service would do to the keyboard and the editor. */
    private class RecordingIme : PolishedVoiceInputIME() {
        val switchedTo = mutableListOf<String?>()
        var hideCalls = 0
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
    }

    private lateinit var service: RecordingIme

    @Before
    fun setUp() {
        service = Robolectric.buildService(RecordingIme::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    private fun completed(text: String) = VoiceSessionController.Event.Completed(
        result = Result.success(text)
    )

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
}
