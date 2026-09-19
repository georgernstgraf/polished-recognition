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

    private fun pcmBuffer(vararg samples: Int): ByteArray {
        val buf = ByteArray(samples.size * 2)
        samples.forEachIndexed { idx, s ->
            buf[idx * 2] = (s and 0xFF).toByte()
            buf[idx * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return buf
    }

    @Test
    fun `stop clears the buffer`() {
        val recorder = AudioRecorder()
        recorder.restorePcm(pcmBuffer(1000, -1000))
        recorder.stop()
        assertThat(recorder.snapshotPcm()).isEmpty()
    }

    @Test
    fun `stopPreservingBuffer keeps PCM for retry after pipeline failure`() {
        val recorder = AudioRecorder()
        recorder.restorePcm(pcmBuffer(1000, -1000, 1000, -1000))

        val wav = recorder.stopPreservingBuffer()

        assertThat(wav.size).isGreaterThan(44)
        assertThat(recorder.snapshotPcm()).isNotEmpty()
        recorder.cancel()
    }

    @Test
    fun `flushBuffer clears restored PCM`() {
        val recorder = AudioRecorder()
        recorder.restorePcm(pcmBuffer(1000, -1000, 1000, -1000))

        recorder.flushBuffer()

        assertThat(recorder.snapshotPcm()).isEmpty()
    }

    @Test
    fun `flushBuffer on empty buffer is a safe no-op`() {
        val recorder = AudioRecorder()

        recorder.flushBuffer()

        assertThat(recorder.snapshotPcm()).isEmpty()
    }
}
