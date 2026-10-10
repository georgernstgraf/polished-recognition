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
        chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
        wavMode: Boolean = false,
        searchBytes: Int = 0, // legacy fixed-offset cuts unless a test opts in (#117)
        preRollBytes: Int = 0 // pre-roll off unless a test opts in (#117 round 2)
    ): FragmentPreparer = FragmentPreparer(
        pcm = pcm,
        transcoder = transcoder,
        sessionDir = sessionDir,
        fragmentBytes = fragmentBytes,
        wavMode = wavMode,
        searchBytes = searchBytes,
        preRollBytes = preRollBytes,
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
        // #119: the assembled WAV is REBUILT into one header, not
        // byte-concatenated — a decoder must see ALL fragments (2500 B PCM),
        // not only the first one.
        val assembled = WavReader.read(chunk.readBytes())
        assertThat(assembled.data.size).isEqualTo(2500)
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

    // ---- #117: silence-aligned boundaries ----------------------------------

    /** High-energy PCM (speech-like), deterministic. */
    private fun noisy(size: Int, seed: Int = 1) =
        ByteArray(size) { i -> ((i * 31 + seed * 7) % 251).toByte() }

    /** PCM payload of a wavMode fragment file (canonical 44-byte header). */
    private fun payload(file: File): ByteArray = file.readBytes().copyOfRange(44, file.readBytes().size)

    private fun fragmentFiles() =
        sessionDir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }.sortedBy { it.name }

    @Test
    fun `silence-aligned cut lands inside the silence run`() {
        val data = noisy(1200) + ByteArray(1800) // speech, then a long pause
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000
        )

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val fragments = fragmentFiles()
        // fragment 0 ends at the center of the zero region (window [1000, 3000):
        // silent frames 1..5 → cut at byte 1000 + 3·320 = 1960), the tail takes
        // the remainder whole (its end is the stream end — no seam follows)
        assertThat(fragments).hasSize(2)
        assertThat(payload(fragments[0]).size).isEqualTo(1960)
        assertThat(payload(fragments[0]).copyOfRange(1900, 1960)).isEqualTo(ByteArray(60))
        assertThat(payload(fragments[1]).size).isEqualTo(1040)
    }

    @Test
    fun `continuous speech falls back to the hard cut at the nominal boundary`() {
        val data = noisy(3500)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000
        )

        preparer.encodeAvailableFragments() // window complete, no silence → cut at 1000
        preparer.prepareTailSync()          // window incomplete → remainder whole

        val payloads = fragmentFiles().map { payload(it) }
        assertThat(payloads).hasSize(2)
        assertThat(payloads[0].size).isEqualTo(1000)
        assertThat(payloads[1].size).isEqualTo(2500)
    }

    @Test
    fun `fragments stay gapless — concatenating them reproduces the pcm byte-for-byte`() {
        val data = noisy(1200) + ByteArray(1800) + noisy(2000, seed = 2)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000
        )

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val joined = fragmentFiles().flatMap { payload(it).toList() }.toByteArray()
        assertThat(joined).isEqualTo(data)
    }

    @Test
    fun `incomplete search window without silence waits instead of splitting a word`() {
        val p = FakePcm(noisy(1500))
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 64_000
        )

        // nominal boundary reached, but the 2-s search window is not — and
        // no silence has appeared yet: nothing is committed
        preparer.encodeAvailableFragments()
        assertThat(fragmentFiles()).isEmpty()

        // at stop the remainder is taken whole (its end is the stream end)
        preparer.prepareTailSync()
        assertThat(fragmentFiles().map { payload(it).size }).containsExactly(1500)
    }

    @Test
    fun `manifest end offsets let a fresh preparer resume at the exact last cut`() {
        val data = noisy(1200) + ByteArray(1800) + noisy(2000, seed = 2)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()
        val firstRun = fragmentFiles().map { payload(it) }

        // process death + restore: fresh preparer over the same dir, more audio
        val reborn = FragmentPreparer(
            pcm = FakePcm(data + noisy(1000, seed = 3)), transcoder = transcoder,
            sessionDir = sessionDir, fragmentBytes = 1000, wavMode = true, searchBytes = 2000
        )
        reborn.prepareTailSync()

        val payloads = fragmentFiles().map { payload(it) }
        // the first-run fragments were NOT re-encoded (identical payloads)
        payloads.take(firstRun.size).forEachIndexed { i, bytes ->
            assertThat(bytes).isEqualTo(firstRun[i])
        }
        // and the whole stream is still covered gaplessly
        assertThat(payloads.flatMap { it.toList() }.toByteArray())
            .isEqualTo(data + noisy(1000, seed = 3))
    }

    @Test
    fun `pre-#117 manifest without end offsets is discarded and the prefix re-encoded`() {
        val stale = File(sessionDir, "frag_000000.ogg")
        stale.writeBytes(byteArrayOf(1, 2, 3, 4))
        File(sessionDir, "manifest.json").writeText(
            """{"fallbackToWav":false,"fragments":[{"name":"frag_000000.ogg","bytes":4}]}"""
        )
        pcm.append(2500)

        val preparer = newPreparer() // searchBytes = 0 → fixed cuts
        preparer.encodeAvailableFragments()

        // the stale fragment was overwritten by a fresh encode of the real range
        assertThat(stale.length()).isNotEqualTo(4)
        assertThat(fragmentFiles()).hasSize(2)
    }

    @Test
    fun `chunk assembly never exceeds the default 5-minute cap`() {
        // 320 s of PCM at 32 000 B/s: the 300-s cap (#120) must force a split.
        val preparer = FragmentPreparer(
            pcm = FakePcm(ByteArray(320 * 32_000)), transcoder = transcoder,
            sessionDir = sessionDir, fragmentBytes = FragmentPreparer.DEFAULT_FRAGMENT_BYTES,
            wavMode = true, searchBytes = 0
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val chunks = preparer.assembleChunks()
        assertThat(chunks.size).isAtLeast(2)
        chunks.forEach { chunk ->
            val seconds = WavReader.read(chunk.readBytes()).data.size / 32_000.0
            assertThat(seconds).isAtMost(300.0)
        }
    }

    @Test
    fun `chunk assembly respects the byte cap with variable fragment sizes`() {
        val data = noisy(1200) + ByteArray(1800) + noisy(2000, seed = 2)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000,
            chunkMaxSeconds = 0.0625 // cap = 0.0625 s × 32000 B/s = 2000 PCM bytes
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()
        // fragments: 1960 B (silence cut), 1000 B (hard cut), 2040 B (tail)

        val chunks = preparer.assembleChunks()
        // 1960 alone fits; adding 1000 would exceed 2000 → own chunk, etc.
        assertThat(chunks.map { it.name }).containsExactly(
            "recording_1.wav", "recording_2.wav", "recording_3.wav"
        ).inOrder()
        val fragments = fragmentFiles()
        assertThat(chunks[0].readBytes()).isEqualTo(fragments[0].readBytes())
        assertThat(chunks[1].readBytes()).isEqualTo(fragments[1].readBytes())
        assertThat(chunks[2].readBytes()).isEqualTo(fragments[2].readBytes())
    }

    // ---- #117 round 2: acoustic pre-roll ------------------------------------

    private fun preRollFiles() =
        sessionDir.listFiles().orEmpty().filter { it.name.startsWith("preroll_") }.sortedBy { it.name }

    @Test
    fun `pre-roll covers the pcm immediately before the fragment start`() {
        val data = noisy(2500)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 0, preRollBytes = 320
        )

        preparer.encodeAvailableFragments()

        // fragment 0 has no predecessor; fragment 1 starts at 1000 → [680, 1000)
        val preRolls = preRollFiles()
        assertThat(preRolls.map { it.name }).containsExactly("preroll_000001.wav")
        assertThat(payload(preRolls[0])).isEqualTo(data.copyOfRange(680, 1000))
    }

    @Test
    fun `pre-roll clamps at the stream start`() {
        val data = noisy(2500)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 0, preRollBytes = 1500
        )

        preparer.encodeAvailableFragments()

        // fragment 1 starts at 1000, but the pre-roll wants 1500 → clamped to [0, 1000)
        assertThat(payload(preRollFiles().single()))
            .isEqualTo(data.copyOfRange(0, 1000))
    }

    @Test
    fun `commit hook and committedFragments carry the pre-roll side file`() {
        val preparer = newPreparer(fragmentBytes = 1000, preRollBytes = 320)
        pcm.append(2500)
        val committed = mutableListOf<Triple<Int, String, String?>>()
        preparer.onFragmentCommitted = { index, file, preRoll ->
            committed.add(Triple(index, file.name, preRoll?.name))
        }

        preparer.encodeAvailableFragments()

        assertThat(committed).containsExactly(
            Triple(0, "frag_000000.ogg", null),
            Triple(1, "frag_000001.ogg", "preroll_000001.ogg")
        ).inOrder()
        assertThat(preparer.committedFragments().map { it.third?.name })
            .containsExactly(null, "preroll_000001.ogg")
            .inOrder()
    }

    @Test
    fun `pre-roll transcode failure degrades to null without the wav fallback`() {
        val preparer = newPreparer(fragmentBytes = 1000, preRollBytes = 320)
        pcm.append(2500)
        val hookPreRolls = mutableListOf<String?>()
        preparer.onFragmentCommitted = { _, _, preRoll -> hookPreRolls.add(preRoll?.name) }
        every { transcoder.transcode(any(), any()) } answers {
            val target = secondArg<File>()
            if (target.name.startsWith("preroll_")) throw IOException("pre-roll encode failed")
            target.writeBytes(firstArg())
            target
        }

        preparer.encodeAvailableFragments()

        // the session stays in OGG mode — a pre-roll failure is NOT a
        // transcoder failure of the fragment path
        val fragments = fragmentFiles()
        assertThat(fragments.map { it.name }).containsExactly(
            "frag_000000.ogg", "frag_000001.ogg"
        )
        assertThat(fragments.all { it.extension == "ogg" }).isTrue()
        assertThat(preRollFiles()).isEmpty()
        assertThat(hookPreRolls).containsExactly(null, null).inOrder()
        assertThat(preparer.committedFragments().map { it.third }).containsExactly(null, null)
    }

    @Test
    fun `recover keeps the kept fragments' pre-rolls and deletes orphans`() {
        val preparer = newPreparer(fragmentBytes = 1000, preRollBytes = 320)
        pcm.append(2500)
        preparer.encodeAvailableFragments() // fragments 0+1, pre-roll 1
        assertThat(preRollFiles()).hasSize(1)

        // crash residue: a pre-roll beyond the manifest prefix
        File(sessionDir, "preroll_000009.ogg").writeBytes(byteArrayOf(1))

        val reborn = newPreparer(fragmentBytes = 1000, preRollBytes = 320) // recover() ran
        assertThat(File(sessionDir, "preroll_000001.ogg").isFile).isTrue()
        assertThat(File(sessionDir, "preroll_000009.ogg").exists()).isFalse()
        assertThat(reborn.committedFragments().map { it.third?.name })
            .containsExactly(null, "preroll_000001.ogg")
            .inOrder()
    }

    @Test
    fun `wav fallback rebuild re-creates the pre-rolls`() {
        val data = noisy(3500)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, searchBytes = 0, preRollBytes = 320
        )
        // fragment 2's OGG encode dies → whole session rebuilds as WAV
        every { transcoder.transcode(any(), any()) } answers {
            val target = secondArg<File>()
            if (target.name == "frag_000002.ogg") throw IOException("encoder died")
            target.writeBytes(firstArg())
            target
        }

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        assertThat(fragmentFiles().map { it.name }).containsExactly(
            "frag_000000.wav", "frag_000001.wav", "frag_000002.wav", "frag_000003.wav"
        ).inOrder()
        // rebuilt pre-rolls, byte-exact from the original PCM ranges
        assertThat(preRollFiles().map { it.name }).containsExactly(
            "preroll_000001.wav", "preroll_000002.wav", "preroll_000003.wav"
        ).inOrder()
        assertThat(payload(File(sessionDir, "preroll_000001.wav")))
            .isEqualTo(data.copyOfRange(680, 1000))
        assertThat(payload(File(sessionDir, "preroll_000002.wav")))
            .isEqualTo(data.copyOfRange(1680, 2000))
        assertThat(payload(File(sessionDir, "preroll_000003.wav")))
            .isEqualTo(data.copyOfRange(2680, 3000))
    }
}
