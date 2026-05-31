package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioRecorderTest {

    @Test
    fun `stop returns non-empty ByteArray`() {
        val recorder = AudioRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop()
        assertThat(wav).isNotEmpty()
    }

    @Test
    fun `WAV is at least 44 bytes`() {
        val recorder = AudioRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop()
        assertThat(wav.size).isAtLeast(44)
    }

    @Test
    fun `first 4 bytes are RIFF header`() {
        val recorder = AudioRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop()
        val riff = String(wav.sliceArray(0..3))
        assertThat(riff).isEqualTo("RIFF")
    }

    @Test
    fun `bytes 8-11 are WAVE marker`() {
        val recorder = AudioRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop()
        val wave = String(wav.sliceArray(8..11))
        assertThat(wave).isEqualTo("WAVE")
    }

    @Test
    fun `bytes 36-39 are data chunk`() {
        val recorder = AudioRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop()
        val dataChunk = String(wav.sliceArray(36..39))
        assertThat(dataChunk).isEqualTo("data")
    }
}
