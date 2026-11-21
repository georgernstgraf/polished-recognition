package com.georgernstgraf.polishedrecognition.recovery

import com.georgernstgraf.polishedrecognition.audio.WavReader
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #185: sharing a leftover's audio. A WAV session is tiled into ONE stream with
 * the pre-rolls cut off (the analyzer's reconstruct rule, without ffmpeg); an
 * OGG session is shared piece by piece because the app cannot decode it.
 */
class RecoveryAudioTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    private val sampleRate = 16_000
    private val bytesPerSecond = 32_000

    @Before
    fun setUp() {
        dir = tmp.newFolder("session")
    }

    private fun pcm(seconds: Int, value: Byte): ByteArray =
        ByteArray(seconds * bytesPerSecond) { value }

    private fun composed(name: String, preRoll: Byte?, body: Byte, bodySeconds: Int = 2): File {
        val pcm = (preRoll?.let { pcm(1, it) } ?: ByteArray(0)) + pcm(bodySeconds, body)
        return File(dir, name).apply { writeBytes(WavWriter.write(pcm, sampleRate)) }
    }

    private fun record(index: Int, file: String, preRollMs: Long?) =
        FragmentJournal.FragmentRecord(index = index, uploadFile = file, preRollMs = preRollMs)

    @Test
    fun `wav pieces are tiled with their pre-rolls cut off`() {
        composed("upload_000000.wav", preRoll = null, body = 0x11.toByte())
        composed("upload_000001.wav", preRoll = 0x22.toByte(), body = 0x33.toByte())
        // a parsed WAV carries ONE pre-roll chunk; a null preRollMs means the
        // piece never had one, so nothing may be cut (the #149 rule).
        // fragment 0 has no pre-roll: its 2 s of body form the first half
        val boundary = 2 * bytesPerSecond

        val wav = RecoveryAudio.reconstructWav(
            dir,
            listOf(
                record(0, "upload_000000.wav", preRollMs = null),
                record(1, "upload_000001.wav", preRollMs = 1_000L)
            )
        )!!

        val read = WavReader.read(wav)
        assertThat(read.sampleRate).isEqualTo(sampleRate)
        assertThat(read.data.size).isEqualTo(4 * bytesPerSecond)
        assertThat(read.data[0]).isEqualTo(0x11.toByte())
        assertThat(read.data[boundary - 1]).isEqualTo(0x11.toByte())
        assertThat(read.data[boundary]).isEqualTo(0x33.toByte())
        assertThat(read.data[read.data.size - 1]).isEqualTo(0x33.toByte())
    }

    @Test
    fun `a piece with a lying pre-roll length is cut at its data end, not past it`() {
        composed("upload_000000.wav", preRoll = 0x44.toByte(), body = 0x55.toByte(), bodySeconds = 1)

        val wav = RecoveryAudio.reconstructWav(
            dir,
            listOf(record(0, "upload_000000.wav", preRollMs = 5_000L))
        )

        // 1 s of audio, 5 s of pre-roll claimed → the skip is clamped and the
        // stream is empty: null, never a truncated file with garbage audio
        assertThat(wav).isNull()
    }

    @Test
    fun `a session with an OGG piece cannot be reconstructed`() {
        composed("upload_000000.wav", preRoll = null, body = 0x11.toByte())
        File(dir, "upload_000001.ogg").writeBytes(ByteArray(64))

        assertThat(
            RecoveryAudio.canReconstructWav(
                listOf(
                    record(0, "upload_000000.wav", null),
                    record(1, "upload_000001.ogg", null)
                )
            )
        ).isFalse()
        assertThat(
            RecoveryAudio.reconstructWav(
                dir,
                listOf(
                    record(0, "upload_000000.wav", null),
                    record(1, "upload_000001.ogg", null)
                )
            )
        ).isNull()
    }

    @Test
    fun `a missing piece yields no stream instead of a truncated one`() {
        composed("upload_000000.wav", preRoll = null, body = 0x11.toByte())

        val wav = RecoveryAudio.reconstructWav(
            dir,
            listOf(record(0, "upload_000000.wav", null), record(1, "upload_000001.wav", null))
        )

        assertThat(wav).isNull()
    }

    @Test
    fun `piece files are the fallback payload, in index order`() {
        composed("upload_000000.wav", preRoll = null, body = 0x11.toByte())
        File(dir, "upload_000001.ogg").writeBytes(ByteArray(64))

        val files = RecoveryAudio.pieceFiles(
            dir,
            listOf(
                record(1, "upload_000001.ogg", null),
                record(0, "upload_000000.wav", null),
                record(2, "upload_000002.ogg", null)
            )
        )

        assertThat(files.map { it.name }).containsExactly("upload_000001.ogg", "upload_000000.wav")
    }
}
