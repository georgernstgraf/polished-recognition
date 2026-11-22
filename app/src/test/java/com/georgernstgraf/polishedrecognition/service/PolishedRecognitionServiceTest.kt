package com.georgernstgraf.polishedrecognition.service

import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.georgernstgraf.polishedrecognition.pipeline.SttFailure
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PolishedRecognitionServiceTest {

    @Test
    fun `packageForUid resolves the first package for a uid`() {
        val pm = mockk<PackageManager>()
        every { pm.getPackagesForUid(10123) } returns arrayOf("com.example.chat")

        assertThat(PolishedRecognitionService.packageForUid(pm, 10123))
            .isEqualTo("com.example.chat")
    }

    @Test
    fun `packageForUid returns null for shared or unknown uids`() {
        val pm = mockk<PackageManager>()
        every { pm.getPackagesForUid(1000) } returns null
        every { pm.getPackagesForUid(2000) } throws SecurityException("nope")

        assertThat(PolishedRecognitionService.packageForUid(pm, 1000)).isNull()
        assertThat(PolishedRecognitionService.packageForUid(pm, 2000)).isNull()
    }

    @Test
    fun `network message maps to ERROR_NETWORK`() {
        assertThat(PolishedRecognitionService.mapRecognitionError("network unreachable"))
            .isEqualTo(SpeechRecognizer.ERROR_NETWORK)
    }

    @Test
    fun `timeout message maps to ERROR_NETWORK_TIMEOUT`() {
        assertThat(PolishedRecognitionService.mapRecognitionError("request timeout"))
            .isEqualTo(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
    }

    @Test
    fun `other message maps to ERROR_SERVER`() {
        assertThat(PolishedRecognitionService.mapRecognitionError("HTTP 500"))
            .isEqualTo(SpeechRecognizer.ERROR_SERVER)
    }

    @Test
    fun `result bundle carries text under both recognition keys`() {
        val bundle: Bundle = PolishedRecognitionService.buildResultBundle("hello world")

        assertThat(bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            .containsExactly("hello world")
        assertThat(bundle.getStringArrayList(RecognizerIntent.EXTRA_RESULTS))
            .containsExactly("hello world")
    }

    /**
     * #186: a delegated caller (a system mic) must never be handed an empty
     * "success" for a raw-mode rescue that recovered nothing — the same rule
     * the IME applies. The decision is pure so it is testable here.
     */
    @Test
    fun `a rescue with no recovered text is reported as an error`() {
        val partial = VoiceSessionController.Event.PartialInfo(
            failedIndex = 1,
            chunkCount = 1,
            failure = SttFailure.OFFLINE,
            message = "No usable network — saved for later"
        )

        assertThat(PolishedRecognitionService.isBlankRescue(partial, "")).isTrue()
        assertThat(PolishedRecognitionService.isBlankRescue(partial, "   ")).isTrue()
        // The reason maps to the caller's network error, not ERROR_SERVER.
        assertThat(PolishedRecognitionService.mapRecognitionError(partial.message!!))
            .isEqualTo(SpeechRecognizer.ERROR_NETWORK)
    }

    @Test
    fun `a rescue that recovered text, or a clean result, stays a success`() {
        val partial = VoiceSessionController.Event.PartialInfo(1, 2, SttFailure.TRANSIENT, "HTTP 500")

        assertThat(PolishedRecognitionService.isBlankRescue(partial, "erster Teil")).isFalse()
        assertThat(PolishedRecognitionService.isBlankRescue(null, "")).isFalse()
        assertThat(PolishedRecognitionService.isBlankRescue(null, "hello")).isFalse()
    }
}
