package com.georgernstgraf.polishedrecognition.service

import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
}
