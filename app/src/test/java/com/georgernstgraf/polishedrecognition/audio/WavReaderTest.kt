package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class WavReaderTest {

    private fun buildWav(sampleRate: Int, channels: Short, bitsPerSample: Short, data: ByteArray): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val wav = ByteArray(44 + data.size)
        fun putIntLE(offset: Int, value: Int) {
            wav[offset] = (value and 0xFF).toByte()
            wav[offset + 1] = ((value shr 8) and 0xFF).toByte()
            wav[offset + 2] = ((value shr 16) and 0xFF).toByte()
            wav[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShortLE(offset: Int, value: Short) {
            wav[offset] = (value.toInt() and 0xFF).toByte()
            wav[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        "RIFF".forEachIndexed { i, c -> wav[i] = c.code.toByte() }
        putIntLE(4, 44 + data.size - 8)
        "WAVE".forEachIndexed { i, c -> wav[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> wav[12 + i] = c.code.toByte() }
        putIntLE(16, 16)
        putShortLE(20, 1)
        putShortLE(22, channels)
        putIntLE(24, sampleRate)
        putIntLE(28, byteRate)
        putShortLE(32, blockAlign)
        putShortLE(34, bitsPerSample)
        "data".forEachIndexed { i, c -> wav[36 + i] = c.code.toByte() }
        putIntLE(40, data.size)
        System.arraycopy(data, 0, wav, 44, data.size)
        return wav
    }

    @Test
    fun `reads back pcm data and sample rate`() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)
        val pcm = WavReader.read(buildWav(16000, 1, 16, data))
        assertThat(pcm.sampleRate).isEqualTo(16000)
        assertThat(pcm.data).isEqualTo(data)
    }

    @Test
    fun `rejects garbage input`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavReader.read(byteArrayOf(0, 1, 2))
        }
    }

    @Test
    fun `rejects zero data size`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavReader.read(buildWav(16000, 1, 16, ByteArray(0)))
        }
    }

    @Test
    fun `rejects truncated data chunk`() {
        val wav = buildWav(16000, 1, 16, byteArrayOf(1, 2, 3, 4))
        // lie about the data size: header claims 4 bytes, only 2 present
        wav[40] = 4
        val truncated = wav.copyOfRange(0, 46)
        assertThrows(IllegalArgumentException::class.java) {
            WavReader.read(truncated)
        }
    }

    @Test
    fun `rejects stereo`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavReader.read(buildWav(16000, 2, 16, byteArrayOf(1, 2, 3, 4)))
        }
    }

    @Test
    fun `rejects non-16-bit pcm`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavReader.read(buildWav(16000, 1, 8, byteArrayOf(1, 2)))
        }
    }
}
