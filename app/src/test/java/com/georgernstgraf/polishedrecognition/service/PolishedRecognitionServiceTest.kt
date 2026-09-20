package com.georgernstgraf.polishedrecognition.service

import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PolishedRecognitionServiceTest {

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
