package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AudioRecorderTest {

    private fun newRecorder() = AudioRecorder(
        File(RuntimeEnvironment.getApplication().cacheDir, "test_recording_${System.nanoTime()}.pcm")
    )

    @Test
    fun `stop returns a non-empty WAV file`() {
        val recorder = newRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop().readBytes()
        assertThat(wav).isNotEmpty()
    }

    @Test
    fun `WAV is at least 44 bytes`() {
        val recorder = newRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop().readBytes()
        assertThat(wav.size).isAtLeast(44)
    }

    @Test
    fun `first 4 bytes are RIFF header`() {
        val recorder = newRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop().readBytes()
        val riff = String(wav.sliceArray(0..3))
        assertThat(riff).isEqualTo("RIFF")
    }

    @Test
    fun `bytes 8-11 are WAVE marker`() {
        val recorder = newRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop().readBytes()
        val wave = String(wav.sliceArray(8..11))
        assertThat(wave).isEqualTo("WAVE")
    }

    @Test
    fun `bytes 36-39 are data chunk`() {
        val recorder = newRecorder()
        recorder.start()
        Thread.sleep(10)
        val wav = recorder.stop().readBytes()
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
        val recorder = newRecorder()
        recorder.restorePcm(pcmBuffer(1000, -1000))
        recorder.stop()
        assertThat(recorder.snapshotPcm()).isEmpty()
    }

    @Test
    fun `stopSessionWavFile keeps PCM for retry after pipeline failure`() {
        val recorder = newRecorder()
        recorder.restorePcm(pcmBuffer(1000, -1000, 1000, -1000))

        val wav = recorder.stopSessionWavFile()

        assertThat(wav.length()).isGreaterThan(44)
        assertThat(recorder.snapshotPcm()).isNotEmpty()
        recorder.cancel()
    }

    @Test
    fun `flushBuffer clears restored PCM`() {
        val recorder = newRecorder()
        recorder.restorePcm(pcmBuffer(1000, -1000, 1000, -1000))

        recorder.flushBuffer()

        assertThat(recorder.snapshotPcm()).isEmpty()
    }

    @Test
    fun `flushBuffer on empty buffer is a safe no-op`() {
        val recorder = newRecorder()

        recorder.flushBuffer()

        assertThat(recorder.snapshotPcm()).isEmpty()
    }

    // ---- #135/#136: spooled buffer — ranged reads never touch the whole session ----

    /** A deterministic buffer larger than two spool blocks (block size 512 KB). */
    private fun bigPcm(bytes: Int): ByteArray = ByteArray(bytes) { i -> (i % 251).toByte() }

    @Test
    fun `copyPcmRange returns the exact range across block boundaries (#135)`() {
        val recorder = newRecorder()
        val pcm = bigPcm(2 * 524_288 + 100_003)
        recorder.restorePcm(pcm)
        assertThat(recorder.pcmSize()).isEqualTo(pcm.size.toLong())

        // a fragment-sized slice spanning the whole spool
        val start = 524_288 - 1000L
        val end = 2 * 524_288L + 50_000L
        assertThat(recorder.copyPcmRange(start, end))
            .isEqualTo(pcm.copyOfRange(start.toInt(), end.toInt()))
    }

    @Test
    fun `copyPcmRange full-buffer round-trips through snapshot (#135)`() {
        val recorder = newRecorder()
        val pcm = bigPcm(524_288 + 123_457)
        recorder.restorePcm(pcm)

        assertThat(recorder.copyPcmRange(0, pcm.size.toLong())).isEqualTo(pcm)
        assertThat(recorder.snapshotPcm()).isEqualTo(pcm)
    }

    @Test
    fun `stopSessionWavFile assembles the exact WAV without stacked copies (#136)`() {
        val recorder = newRecorder()
        val pcm = bigPcm(524_288 + 999)

        recorder.restorePcm(pcm)
        val wav = recorder.stopSessionWavFile()

        assertThat(wav.length()).isEqualTo(44 + pcm.size)
        assertThat(wav.readBytes().copyOfRange(0, 44))
            .isEqualTo(WavWriter.write(pcm, 16000).copyOfRange(0, 44))
        assertThat(wav.readBytes().copyOfRange(44, wav.length().toInt())).isEqualTo(pcm)
        recorder.cancel()
    }

    @Test
    fun `copyPcmRange rejects out-of-bounds ranges (#135)`() {
        val recorder = newRecorder()
        recorder.restorePcm(pcmBuffer(1000, -1000))

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            recorder.copyPcmRange(0, 5)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            recorder.copyPcmRange(3, 2)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            recorder.copyPcmRange(-1, 1)
        }
    }

    @Test
    fun `restore after use replaces the whole buffer (#135)`() {
        val recorder = newRecorder()
        recorder.restorePcm(bigPcm(600_000))
        recorder.restorePcm(pcmBuffer(1000, -1000))

        assertThat(recorder.pcmSize()).isEqualTo(4L)
        assertThat(recorder.snapshotPcm()).isEqualTo(pcmBuffer(1000, -1000))
    }

    @Test
    fun `snapshotToFile and restoreFromFile round-trip without RAM (#136)`() {
        val recorder = newRecorder()
        val pcm = bigPcm(700_001)
        recorder.restorePcm(pcm)

        val snapshot = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "test_snapshot_${System.nanoTime()}.pcm"
        )
        recorder.snapshotToFile(snapshot)
        assertThat(snapshot.length()).isEqualTo(pcm.size.toLong())

        recorder.flushBuffer()
        assertThat(recorder.snapshotPcm()).isEmpty()

        recorder.restoreFromFile(snapshot)
        assertThat(recorder.pcmSize()).isEqualTo(pcm.size.toLong())
        assertThat(recorder.copyPcmRange(0, pcm.size.toLong())).isEqualTo(pcm)
        // the snapshot file is CONSUMED (renamed into place)
        assertThat(snapshot.exists()).isFalse()
    }
}
