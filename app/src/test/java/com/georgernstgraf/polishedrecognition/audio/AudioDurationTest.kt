package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

class AudioDurationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `wav duration comes from canonical header`() {
        // 16-bit mono @ 16 kHz: 16000 data bytes = 0.5 s (WavWriter layout).
        val wav = tmp.newFile("recording.wav")
        wav.writeBytes(WavWriter.write(ByteArray(16_000), sampleRate = 16_000))

        assertThat(AudioDuration.estimateMs(wav)).isEqualTo(500L)
    }

    @Test
    fun `ogg duration comes from last page granule position`() {
        val ogg = tmp.newFile("fragment.ogg")
        ogg.writeBytes(singlePageOgg(granulePosition = 336_000)) // 336000 / 48000 = 7 s

        assertThat(AudioDuration.estimateMs(ogg)).isEqualTo(7_000L)
    }

    @Test
    fun `invalid last page falls back to earlier valid page`() {
        // Last page carries granule -1 ("no packet finishes here"); the
        // earlier page holds the real 1 s granule.
        val bytes = ByteArrayOutputStream()
        bytes.write(singlePageOgg(granulePosition = 48_000))
        bytes.write(singlePageOgg(granulePosition = -1L))
        val ogg = tmp.newFile("fragment.ogg")
        ogg.writeBytes(bytes.toByteArray())

        assertThat(AudioDuration.estimateMs(ogg)).isEqualTo(1_000L)
    }

    @Test
    fun `garbage bytes yield null`() {
        val noise = ByteArray(4096)
        SecureRandom().nextBytes(noise)
        val ogg = tmp.newFile("fragment.ogg")
        ogg.writeBytes(noise)

        assertThat(AudioDuration.estimateMs(ogg)).isNull()
    }

    @Test
    fun `truncated ogg tail yields null`() {
        val page = singlePageOgg(granulePosition = 336_000)
        val ogg = tmp.newFile("fragment.ogg")
        ogg.writeBytes(page.copyOfRange(0, page.size - 5))

        assertThat(AudioDuration.estimateMs(ogg)).isNull()
    }

    @Test
    fun `other extensions yield null without reading`() {
        val mp3 = tmp.newFile("recording.mp3")
        mp3.writeBytes(ByteArray(128))

        assertThat(AudioDuration.estimateMs(mp3)).isNull()
    }

    @Test
    fun `empty ogg yields null`() {
        assertThat(AudioDuration.estimateMs(tmp.newFile("fragment.ogg"))).isNull()
    }

    @Test
    fun `missing wav file yields null`() {
        assertThat(AudioDuration.estimateMs(File(tmp.root, "nope.wav"))).isNull()
    }

    /**
     * Builds a minimal but structurally plausible Ogg page: capture pattern,
     * version 0, 8-byte granule position, one segment. Only the fields
     * [AudioDuration] validates are real; the rest is filler.
     */
    private fun singlePageOgg(granulePosition: Long): ByteArray {
        val page = ByteArray(28)
        page[0] = 'O'.code.toByte()
        page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte()
        page[3] = 'S'.code.toByte()
        page[4] = 0 // stream structure version
        page[5] = 0x04 // header type: end-of-stream
        writeLongLE(page, 6, granulePosition)
        page[26] = 1 // one segment in the page
        page[27] = 0 // segment length 0
        return page
    }

    private fun writeLongLE(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buf[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }
}
