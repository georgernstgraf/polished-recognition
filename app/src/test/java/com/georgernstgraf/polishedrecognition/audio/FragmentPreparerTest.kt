package com.georgernstgraf.polishedrecognition.audio


import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Pure-JVM tests for the streaming fragment preparer (#115): a fake
 * append-only [PcmSource] stands in for the recorder buffer, the transcoder
 * is mocked (Robolectric cannot run MediaCodec). The worker loop is never
 * started here — the test drives `encodeAvailableFragments` /
 * `prepareTailSync` / `assembleChunks` directly, deterministically.
 */
class FragmentPreparerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Append-only fake of the recorder buffer. */
    private class FakePcm(var data: ByteArray = ByteArray(0)) : PcmSource {
        override fun pcmSize(): Long = data.size.toLong()
        override fun copyPcmRange(start: Long, end: Long): ByteArray {
            require(start >= 0 && end >= start && end <= data.size) {
                "invalid PCM range [$start, $end) beyond ${data.size}B"
            }
            return data.copyOfRange(start.toInt(), end.toInt())
        }

        fun append(bytes: Int) {
            data = data + ByteArray(bytes) { i -> ((data.size + i) % 251).toByte() }
        }
    }

    private val pcm = FakePcm()
    private val transcoder: AudioTranscoder = mockk(relaxed = true)
    private lateinit var sessionDir: File

    private fun newPreparer(
        fragmentBytes: Int = 1000,
        chunkMaxSeconds: Double = 600.0,
        wavMode: Boolean = false
    ): FragmentPreparer = FragmentPreparer(
        pcm = pcm,
        transcoder = transcoder,
        sessionDir = sessionDir,
        fragmentBytes = fragmentBytes,
        wavMode = wavMode,
        chunkMaxSeconds = chunkMaxSeconds
    )

    @Before
    fun setUp() {
        sessionDir = tmp.newFolder("session")
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) } // echo the WAV payload
        }
    }

    @Test
    fun `encodes one fragment per complete fragment window`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(2500)

        preparer.encodeAvailableFragments()

        assertThat(preparer.assembleChunks().single().name).isEqualTo("recording_1.ogg")
        val fragments = sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }
        assertThat(fragments.map { it.name }).containsExactly("frag_000000.ogg", "frag_000001.ogg")
    }

    /**
     * WAV mode (#116): the fragment worker runs regardless of `compress_audio`
     * — in wavMode the transcoder is never touched, fragments are plain WAV
     * (canonical 44-byte header) and chunk assembly produces .wav files.
     */
    @Test
    fun `wav mode writes plain wav fragments without transcoding`() {
        val preparer = newPreparer(fragmentBytes = 1000, wavMode = true)
        pcm.append(2500)

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync() // 500 B remainder

        verify(exactly = 0) { transcoder.transcode(any(), any()) }
        val fragments = sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }
        assertThat(fragments.map { it.name }).containsExactly(
            "frag_000000.wav", "frag_000001.wav", "frag_000002.wav"
        )
        // canonical WAV headers, not raw PCM
        fragments.forEach { assertThat(it.readBytes().decodeToString(0, 4)).isEqualTo("RIFF") }
        val chunk = preparer.assembleChunks().single()
        assertThat(chunk.name).isEqualTo("recording_1.wav")
    }

    @Test
    fun `tail fragment covers the remainder below a full window`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(1500)

        preparer.encodeAvailableFragments() // 1 full fragment
        preparer.prepareTailSync()          // 500 B remainder

        val fragments = sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }
        assertThat(fragments).hasSize(2)
        assertThat(fragments.map { it.length() }).containsExactly(1044L, 544L)
    }

    @Test
    fun `chunks are groups of chunkFragments fragments with concatenated bytes`() {
        // chunkMaxSeconds such that a chunk holds exactly 2 fragments:
        // chunkFragments = floor(0.0625 s × 32000 B/s / 1000 B) = 2
        val preparer = newPreparer(fragmentBytes = 1000, chunkMaxSeconds = 0.0625)
        pcm.append(5000)

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val chunks = preparer.assembleChunks()
        assertThat(chunks.map { it.name }).containsExactly(
            "recording_1.ogg", "recording_2.ogg", "recording_3.ogg"
        ).inOrder()
        // chunk 1 = fragments 0+1 concatenated byte-for-byte (chained OGG)
        val expected = File(sessionDir, "frag_000000.ogg").readBytes() +
            File(sessionDir, "frag_000001.ogg").readBytes()
        assertThat(chunks[0].readBytes()).isEqualTo(expected)
    }

    @Test
    fun `new preparer instance resumes from the committed manifest`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(2500)
        preparer.encodeAvailableFragments()
        val encodedAfterFirstRun = sessionDir.listFiles().orEmpty()
            .filter { it.name.startsWith("frag_") }.map { it.name to it.length() }.sortedBy { it.first }

        // simulate process death + restore: fresh preparer over the same dir
        val reborn = newPreparer(fragmentBytes = 1000)
        pcm.append(500)
        reborn.prepareTailSync()

        val fragmentsAfterResume = sessionDir.listFiles().orEmpty()
            .filter { it.name.startsWith("frag_") }.map { it.name to it.length() }.sortedBy { it.first }
        assertThat(fragmentsAfterResume.size).isEqualTo(encodedAfterFirstRun.size + 1)
        // the first two fragments were NOT re-encoded (bytes unchanged)
        assertThat(fragmentsAfterResume.take(2)).isEqualTo(encodedAfterFirstRun)
        verifyTranscodeCount(3)
    }

    @Test
    fun `invalid manifest prefix is truncated and orphans removed`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(2500)
        preparer.encodeAvailableFragments()
        // corrupt: delete the second fragment on disk
        File(sessionDir, "frag_000001.ogg").delete()

        val reborn = newPreparer(fragmentBytes = 1000)
        reborn.prepareTailSync() // PCM still 2500 → re-encode fragment 1, then the 500 B tail

        val fragments = sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }
        assertThat(fragments.map { it.name }).containsExactly(
            "frag_000000.ogg", "frag_000001.ogg", "frag_000002.ogg"
        )
    }

    @Test
    fun `transcoder failure switches the whole session to wav fragments`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(2500)
        every { transcoder.transcode(any(), any()) } throws IOException("no encoder")

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val fragments = sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }
        assertThat(fragments).hasSize(3)
        assertThat(fragments.all { it.extension == "wav" }).isTrue()
        val chunks = preparer.assembleChunks()
        assertThat(chunks.single().name).isEqualTo("recording_1.wav")
    }

    @Test
    fun `fallback rebuilds earlier ogg fragments as wav from the pcm prefix`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(1500)
        // first fragment encodes fine, second (the partial tail) fails
        var call = 0
        every { transcoder.transcode(any(), any()) } answers {
            call++
            if (call == 2) throw IOException("encoder died")
            secondArg<File>().writeBytes(byteArrayOf(1, 2, 3, 4))
            secondArg<File>()
        }

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val fragments = sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }
        // fragments 0 and 1 rebuilt as wav (no .ogg left)
        assertThat(fragments.map { it.name }).containsExactly("frag_000000.wav", "frag_000001.wav")
        assertThat(preparer.assembleChunks().single().extension).isEqualTo("wav")
    }

    @Test
    fun `prune discards fragments and resets the resume point`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(1500)
        preparer.encodeAvailableFragments()

        preparer.prune()

        assertThat(sessionDir.listFiles().orEmpty().filter { it.name != "manifest.json" }).isEmpty()
        // a fresh preparation starts from scratch
        preparer.encodeAvailableFragments()
        assertThat(sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }).hasSize(1)
    }

    @Test
    fun `empty buffer assembles nothing`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        assertThat(preparer.assembleChunks()).isEmpty()
    }

    @Test
    fun `rejects out-of-range pcm copies`() {
        val preparer = newPreparer(fragmentBytes = 1000)
        pcm.append(100)
        assertThrows(IllegalArgumentException::class.java) {
            pcm.copyPcmRange(0, 101)
        }
    }

    private fun verifyTranscodeCount(count: Int) {
        io.mockk.verify(exactly = count) { transcoder.transcode(any(), any()) }
    }
}
