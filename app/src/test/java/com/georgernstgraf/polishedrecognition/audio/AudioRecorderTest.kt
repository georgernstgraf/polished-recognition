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
    fun `rms of constant signal equals its magnitude`() {
        val buf = pcmBuffer(1000, -1000, 1000, -1000)
        assertThat(AudioRecorder.computePcmRms(buf, buf.size)).isWithin(0.01f).of(1000f)
    }

    @Test
    fun `rms is finite for deep negative samples beyond Int-square overflow range`() {
        val buf = pcmBuffer(100, -30000, 200, -32768, 300)
        val rms = AudioRecorder.computePcmRms(buf, buf.size)
        assertThat(rms).isFinite()
        assertThat(rms).isGreaterThan(0f)
    }

    @Test
    fun `rms of loud negative-only samples matches signed expectation`() {
        val buf = pcmBuffer(-20000, -20000, -20000, -20000)
        assertThat(AudioRecorder.computePcmRms(buf, buf.size)).isWithin(0.01f).of(20000f)
    }

    @Test
    fun `rms of silence is zero`() {
        val buf = pcmBuffer(0, 0, 0, 0)
        assertThat(AudioRecorder.computePcmRms(buf, buf.size)).isEqualTo(0f)
    }

    @Test
    fun `rms is symmetric for positive and negative samples`() {
        val pos = pcmBuffer(12345, 12345)
        val neg = pcmBuffer(-12345, -12345)
        assertThat(AudioRecorder.computePcmRms(pos, pos.size))
            .isWithin(0.01f).of(AudioRecorder.computePcmRms(neg, neg.size))
    }

    @Test
    fun `rms ignores trailing odd byte`() {
        val buf = pcmBuffer(1000, 1000)
        val withOddByte = buf + byteOf(0x7F)
        assertThat(AudioRecorder.computePcmRms(withOddByte, withOddByte.size))
            .isWithin(0.01f).of(1000f)
    }

    private fun byteOf(v: Int): Byte = v.toByte()
}
