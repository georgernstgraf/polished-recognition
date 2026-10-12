package com.georgernstgraf.polishedrecognition.audio


import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
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

    /**
     * #126 Nyquist-square PCM (sample k alternates ±[amp]): zero-mean (the
     * metric's 80-Hz high-pass passes it at ~0.985·amp, so frame levels are
     * fully controllable), every full frame the SAME RMS, deterministic.
     */
    private fun nyquist(bytes: Int, amp: Int): ByteArray {
        val out = ByteArray(bytes)
        var sample = 0
        for (i in 0 until bytes step 2) {
            val v = if (sample % 2 == 0) amp else -amp
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            sample++
        }
        return out
    }

    /**
     * #126: [total] bytes of ±3000 "speech" except the frame-aligned [pauses]
     * (byte ranges, exclusive end) which are floor (zeros).
     */
    private fun speechWithZeros(total: Int, vararg pauses: IntRange): ByteArray {
        var pcm = ByteArray(0)
        val frame = SilenceQuality.FRAME_BYTES
        for (f in 0 until total / frame) {
            val a = f * frame
            val zero = pauses.any { it.first <= a && a + frame <= it.last + 1 }
            pcm += nyquist(frame, if (zero) 0 else 3000)
        }
        return pcm + nyquist(total - pcm.size, 3000) // remainder — never scanned
    }

    /** PCM payload of a wavMode fragment file (canonical 44-byte header). */
    private fun payload(file: File): ByteArray = file.readBytes().copyOfRange(44, file.readBytes().size)

    private fun fragmentFiles(dir: File = sessionDir) =
        dir.listFiles().orEmpty().filter { it.name.startsWith("frag_") }.sortedBy { it.name }

    @Test
    fun `silence-quality cut lands inside the pause of the late-half search span`() {
        val data = noisy(1200) + ByteArray(1800) // speech, then a long pause
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val fragments = fragmentFiles()
        // the measured span is the LATE half [2000, 3000) — all pause: one
        // 3-frame below-axis candidate, cut at its midpoint frame's centre
        // (2000 + 480 = 2480); the tail takes the remainder whole
        assertThat(fragments).hasSize(2)
        assertThat(payload(fragments[0]).size).isEqualTo(2480)
        assertThat(payload(fragments[0]).copyOfRange(2420, 2480)).isEqualTo(ByteArray(60))
        assertThat(payload(fragments[1]).size).isEqualTo(520)
    }

    @Test
    fun `continuous speech cuts at the quietest frame of the late half (forced)`() {
        // #126: no frame of the late-half span falls below the axis — the
        // best-effort path cuts at the span's quietest frame (the one-pole's
        // start overshoot makes frame 1 the quietest full frame), tagged
        // FORCED so FORCED_ONLY still applies pre-roll + prompt
        val data = nyquist(3500, 3000)
        val p = FakePcm(data)
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )

        preparer.encodeAvailableFragments() // window complete, no candidate → forced cut at 2480
        preparer.prepareTailSync()          // window incomplete → remainder whole

        val payloads = fragmentFiles().map { payload(it) }
        assertThat(payloads).hasSize(2)
        assertThat(payloads.map { it.size }).containsExactly(2480, 1020).inOrder()
        assertThat(preparer.committedFragments().map { it.seamForced })
            .containsExactly(false, true).inOrder()
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
            axisK = 0.65, axisC = 100.0, marginKMs = 150,
            chunkMaxSeconds = 0.0625 // cap = 0.0625 s × 32000 B/s = 2000 PCM bytes
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()
        // fragments: 2480 B (silence-quality cut), 2520 B (tail)

        val chunks = preparer.assembleChunks()
        // 2480 alone (the first fragment always joins a chunk); adding the
        // 2520 B tail would exceed 2000 → own chunk
        assertThat(chunks.map { it.name }).containsExactly(
            "recording_1.wav", "recording_2.wav"
        ).inOrder()
        val fragments = fragmentFiles()
        assertThat(chunks[0].readBytes()).isEqualTo(fragments[0].readBytes())
        assertThat(chunks[1].readBytes()).isEqualTo(fragments[1].readBytes())
    }

    // ---- #117 round 2: acoustic pre-roll ------------------------------------

    private fun preRollFiles(dir: File = sessionDir) =
        dir.listFiles().orEmpty().filter { it.name.startsWith("preroll_") }.sortedBy { it.name }

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
        preparer.onFragmentCommitted = { index, file, preRoll, _, _ ->
            committed.add(Triple(index, file.name, preRoll?.name))
        }

        preparer.encodeAvailableFragments()

        assertThat(committed).containsExactly(
            Triple(0, "frag_000000.ogg", null),
            Triple(1, "frag_000001.ogg", "preroll_000001.ogg")
        ).inOrder()
        assertThat(preparer.committedFragments().map { it.preRoll?.name })
            .containsExactly(null, "preroll_000001.ogg")
            .inOrder()
        // #128: default ALL arm — every seam ≥ 1 is stitched
        assertThat(preparer.committedFragments().map { it.stitched })
            .containsExactly(false, true).inOrder()
    }

    @Test
    fun `pre-roll transcode failure degrades to null without the wav fallback`() {
        val preparer = newPreparer(fragmentBytes = 1000, preRollBytes = 320)
        pcm.append(2500)
        val hookPreRolls = mutableListOf<String?>()
        preparer.onFragmentCommitted = { _, _, preRoll, _, _ -> hookPreRolls.add(preRoll?.name) }
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
        assertThat(preparer.committedFragments().map { it.preRoll }).containsExactly(null, null)
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
        assertThat(reborn.committedFragments().map { it.preRoll?.name })
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

    // ---- #122: seam policy + true silence tagging --------------------------

    @Test
    fun `tail fragment below the live nominal size is kept whole (no floor)`() {
        // 100 000 B ≈ 3.1 s; the 10-s nominal floor must NEVER apply to the
        // stop-tap tail — the remainder is taken whole, never dropped/padded.
        val p = FakePcm(noisy(100_000))
        val preparer = FragmentPreparer(
            pcm = p, transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 320_000, wavMode = true
        )
        preparer.encodeAvailableFragments()
        assertThat(fragmentFiles()).isEmpty()
        preparer.prepareTailSync()
        assertThat(fragmentFiles().map { payload(it).size }).containsExactly(100_000)
    }

    @Test
    fun `forced-only keeps the pre-roll at forced seams and drops it at a candidate seam`() {
        // frag = search = 1 s → the measured span is the late half
        // [nominal + 0.5 s, nominal + 1 s]. Floor pauses sit exactly at the
        // spans of fragments 0 ([48000, 64000)) and 2 ([152320, 168320));
        // everything else is continuous ±3000 speech. Cuts (mirrored):
        // frag0 candidate-aligned at 56160, frag1 forced at 104640 (the
        // quietest late-half frame), frag2 aligned at 160480, frag3 forced
        // at 208960, tail whole → seamForced = (F, F, T, F, T).
        val data = speechWithZeros(260_000, 48_000 until 64_000, 152_320 until 168_320)
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 32_000, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.FORCED_ONLY,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        // the seams before fragments 2 and 4 were forced → their pre-rolls
        // survive; the candidate seams (1, 3) get none
        assertThat(preRollFiles().map { it.name }).containsExactly(
            "preroll_000002.ogg", "preroll_000004.ogg"
        ).inOrder()
        assertThat(preparer.committedFragments().map { it.seamForced })
            .containsExactly(false, false, true, false, true).inOrder()
        // sanity: the aligned cuts landed inside their pauses
        val sizes = fragmentFiles().map { payload(it).size }
        assertThat(sizes.take(3).sum()).isEqualTo(160_480) // frag2's end ∈ its pause
        assertThat(sizes[0]).isGreaterThan(48_000)          // frag0's end ∈ its pause
        assertThat(sizes[0]).isAtMost(64_000)
    }

    @Test
    fun `all policy writes the pre-roll at every seam`() {
        val data = speechWithZeros(260_000, 48_000 until 64_000, 152_320 until 168_320)
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 32_000, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.ALL,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        assertThat(preRollFiles().map { it.name }).containsExactly(
            "preroll_000001.ogg", "preroll_000002.ogg", "preroll_000003.ogg", "preroll_000004.ogg"
        ).inOrder()
    }

    // ---- #124: adaptive search + quietest-frame energy fallback -------------

    /** Full-amplitude 16-bit speech-like noise (sample RMS ≈ 43 605). */
    private fun loud(size: Int) =
        ByteArray(size) { i -> if (i % 2 == 0) 0x55 else 0xAA.toByte() }

    /**
     * Constant-byte frame: every sample = 257·[level] — above the silence
     * threshold (RMS > 500) for level ≥ 2 but far below [loud].
     */
    private fun dip(size: Int, level: Int) = ByteArray(size) { level.toByte() }

    @Test
    fun `the search span is the late half - an earlier quieter dip is invisible`() {
        // early half [1000, 2000): a dip at amp 300 (the QUIETER one); late
        // half [2000, 3000): 2 speech frames + a dip at amp 500. Only the
        // late half is measured — the cut lands at the LATE dip's frame
        // centre (2800), and the single-frame below-axis candidate counts
        // as found (the interim silenceAligned mapping; its low Q is what
        // #128's qualityMin gate will judge)
        val data = nyquist(1000, 3000) + nyquist(320, 300) + nyquist(680, 3000) +
            nyquist(640, 3000) + nyquist(320, 500) + nyquist(40, 3000)
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        val payloads = fragmentFiles().map { payload(it) }
        assertThat(payloads.map { it.size }).containsExactly(2800, 200).inOrder()
        assertThat(preparer.committedFragments().map { it.seamForced })
            .containsExactly(false, false).inOrder()
    }

    @Test
    fun `a recording below 2S without pauses collapses to a single fragment`() {
        // search = S (320 000 B) → fragment spans [S, 2S] = [10, 20] s; a
        // 12.5-s recording never fills the first search window, so nothing
        // commits live and the stop-tap tail takes the whole stream.
        val preparer = FragmentPreparer(
            pcm = FakePcm(loud(400_000)), transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 320_000, wavMode = true, searchBytes = 320_000
        )

        preparer.encodeAvailableFragments()
        assertThat(fragmentFiles()).isEmpty()

        preparer.prepareTailSync()
        assertThat(fragmentFiles().map { payload(it).size }).containsExactly(400_000)
        assertThat(preparer.committedFragments().map { it.seamForced }).containsExactly(false)
    }

    // ---- #126: silence-quality metric in the seam path --------------------

    @Test
    fun `a whisper-quiet span takes the forced path - the pre-roll arms stitching`() {
        // uniform ±400 whisper (a tight band over the axis — no frame below
        // it): fragment 0's cut is FORCED, so under FORCED_ONLY the next
        // seam carries the pre-roll — the #128 stitching precondition is
        // armed exactly when quality is absent
        val data = nyquist(96_000, 400)
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.FORCED_ONLY,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        assertThat(preparer.committedFragments().map { it.seamForced })
            .containsExactly(false, true).inOrder()
        assertThat(preRollFiles().map { it.name }).containsExactly("preroll_000001.wav")
    }

    @Test
    fun `late-half default ignores an early-half pause - the full-window arm uses it`() {
        // the pause [32000, 48000) is the EARLY half of fragment 0's window
        // [32000, 64000); the late half [48000, 64000) is continuous speech
        val data = speechWithZeros(96_000, 32_000 until 48_000)

        // default (LATE_HALF): no candidate in the measured span → forced
        val lateDir = tmp.newFolder("late")
        val late = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = lateDir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        late.encodeAvailableFragments()
        late.prepareTailSync()
        assertThat(late.committedFragments().map { it.seamForced })
            .containsExactly(false, true).inOrder()

        // FULL_WINDOW (the #129 A/B arm): the early pause IS the measured
        // span → a 50-frame floor candidate → aligned cut at 40160, inside
        // the pause
        val fullDir = tmp.newFolder("full")
        val full = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = fullDir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000,
            searchSpan = SearchSpan.FULL_WINDOW,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        full.encodeAvailableFragments()
        full.prepareTailSync()
        assertThat(full.committedFragments().map { it.seamForced })
            .containsExactly(false, false).inOrder()
        assertThat(payload(fragmentFiles(fullDir).first()).size).isEqualTo(40_160)
    }

    @Test
    fun `prepare json carries the silence-quality contract block`() {
        val logged = mutableListOf<String>()
        val logger = mockk<RotatingJsonLogger>()
        every { logger.log("prepare", any()) } answers { logged.add(secondArg()) }
        val data = noisy(1200) + ByteArray(1800)
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 2000,
            seamPolicy = SeamPolicy.Q_GATE,
            axisK = 0.65, axisC = 100.0, marginKMs = 150,
            logger = logger
        )

        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        assertThat(logged).hasSize(2)
        val gson = Gson()
        val frag0 = gson.fromJson(logged[0], JsonObject::class.java)
        // cadence fields unchanged
        assertThat(frag0.get("fragment").asInt).isEqualTo(0)
        assertThat(frag0.get("bytes").asInt).isEqualTo(2480)
        assertThat(frag0.get("pcmEnd").asLong).isEqualTo(2480)
        assertThat(frag0.get("encodeMs").asInt).isAtLeast(0)
        assertThat(frag0.get("mode").asString).isEqualTo("wav")
        // the old flag is gone; the contract block is in (all-zero span:
        // M = 0, X = c = 100, a 3-frame candidate at depth 1)
        assertThat(frag0.has("silenceAligned")).isFalse()
        assertThat(frag0.get("M").asDouble).isEqualTo(0.0)
        assertThat(frag0.get("min").asDouble).isEqualTo(0.0)
        assertThat(frag0.get("max").asDouble).isEqualTo(0.0)
        assertThat(frag0.get("X").asDouble).isEqualTo(100.0)
        assertThat(frag0.get("Q").asDouble).isEqualTo(3.0)
        assertThat(frag0.get("centroidMs").asDouble).isEqualTo(77.5) // cut 2480 B / 32 B-per-ms
        // #128: stitched is REAL — the gate decision for the seam this
        // record describes (Q = 3.0 < qualityMin = 10 → the NEXT fragment
        // is stitched)
        assertThat(frag0.get("stitched").asBoolean).isTrue()
        // the stop-tap tail is not a searched cut — cadence only, no block
        val tail = gson.fromJson(logged[1], JsonObject::class.java)
        assertThat(tail.get("fragment").asInt).isEqualTo(1)
        assertThat(tail.has("Q")).isFalse()
        assertThat(tail.has("stitched")).isFalse()
        assertThat(tail.has("silenceAligned")).isFalse()
    }

    // ---- #128: the Q-gated stitching decision --------------------------------

    /**
     * The #126 whisper-THREADING regime meets the #128 gate: fragment 0's
     * late-half span ([48000, 64000) — frames 150..199 of a 5-frame
     * 120/240 alternation) threads the axis, the best candidate is one
     * shallow quiet block, Q ≈ 2.26 < qualityMin (10) → the seam to the
     * next fragment STITCHES under Q_GATE (pre-roll composed, stitched=true
     * in prepare.json) — while the frozen FORCED_ONLY arm leaves the SAME
     * seam bare (the cut was silence-aligned, Q > 0). Mirror-verified:
     * Q = 2.2572, span-relative cut 800 → absolute 48800.
     */
    @Test
    fun `q-gate stitches a low-quality whisper seam - forced-only leaves the same seam bare`() {
        var data = ByteArray(0)
        repeat(45) { blk ->
            data += nyquist(5 * SilenceQuality.FRAME_BYTES, if (blk % 2 == 0) 120 else 240)
        }
        assertThat(data.size).isEqualTo(72_000)
        val logged = mutableListOf<String>()
        val logger = mockk<RotatingJsonLogger>()
        every { logger.log("prepare", any()) } answers { logged.add(secondArg()) }

        val gateDir = tmp.newFolder("gate")
        val gate = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = gateDir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.Q_GATE,
            axisK = 0.65, axisC = 100.0, marginKMs = 150,
            logger = logger
        )
        gate.encodeAvailableFragments()
        gate.prepareTailSync()

        // the seam before the tail fragment stitched: pre-roll composed +
        // the decision visible in committedFragments and prepare.json
        assertThat(preRollFiles(gateDir).map { it.name }).containsExactly("preroll_000001.wav")
        assertThat(gate.committedFragments().map { it.stitched })
            .containsExactly(false, true).inOrder()
        val gson = Gson()
        val frag0 = gson.fromJson(logged[0], JsonObject::class.java)
        assertThat(frag0.get("Q").asDouble).isGreaterThan(1.0) // a real (shallow) candidate
        assertThat(frag0.get("Q").asDouble).isLessThan(10.0)   // below the gate → stitch
        assertThat(frag0.get("stitched").asBoolean).isTrue()

        // the frozen FORCED_ONLY A/B arm: the same cut was silence-aligned
        // (Q > 0) → NO pre-roll, stitched=false
        val forcedDir = tmp.newFolder("forced-only-arm")
        val forcedOnly = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = forcedDir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.FORCED_ONLY,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        forcedOnly.encodeAvailableFragments()
        forcedOnly.prepareTailSync()
        assertThat(preRollFiles(forcedDir)).isEmpty()
        assertThat(forcedOnly.committedFragments().map { it.stitched })
            .containsExactly(false, false).inOrder()

        // manifest round-trip: the seam Q survives recover() — a reborn
        // preparer re-derives the SAME stitch decision
        val reborn = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = gateDir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.Q_GATE,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        assertThat(reborn.committedFragments().map { it.stitched })
            .containsExactly(false, true).inOrder()
    }

    /**
     * A solid pause (the span's full 50 zero frames at depth 1 → Q = 50
     * EXACTLY, mirror-verified; cut 56160) is GOOD ENOUGH: Q ≥ qualityMin
     * → the seam stays BARE under Q_GATE. Boundary semantics: the
     * comparison is STRICT — Q == qualityMin (50.0 vs 50.0) is bare; only
     * Q < qualityMin stitches (qualityMin = 60 flips the same seam).
     */
    @Test
    fun `q-gate boundary - Q equal to qualityMin stays bare, only below stitches`() {
        val data = speechWithZeros(96_000, 48_000 until 64_000)
        val logged = mutableListOf<String>()
        val logger = mockk<RotatingJsonLogger>()
        every { logger.log("prepare", any()) } answers { logged.add(secondArg()) }

        fun qGate(name: String, qualityMin: Double, log: Boolean = false): Pair<File, FragmentPreparer> {
            val dir = tmp.newFolder(name)
            val preparer = FragmentPreparer(
                pcm = FakePcm(data), transcoder = transcoder, sessionDir = dir,
                fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000, preRollBytes = 320,
                seamPolicy = SeamPolicy.Q_GATE, qualityMin = qualityMin,
                axisK = 0.65, axisC = 100.0, marginKMs = 150,
                logger = if (log) logger else null
            )
            return dir to preparer
        }

        // default threshold (10.0): Q = 50 ≥ 10 → bare
        val (defaultDir, defaultGate) = qGate("at-default", FragmentPreparer.DEFAULT_QUALITY_MIN)
        defaultGate.encodeAvailableFragments()
        defaultGate.prepareTailSync()
        assertThat(preRollFiles(defaultDir)).isEmpty()
        assertThat(defaultGate.committedFragments().map { it.stitched })
            .containsExactly(false, false).inOrder()

        // boundary: Q == qualityMin → STILL bare (strict less-than)
        val (atMinDir, atMin) = qGate("at-min", 50.0, log = true)
        atMin.encodeAvailableFragments()
        atMin.prepareTailSync()
        assertThat(preRollFiles(atMinDir)).isEmpty()
        assertThat(atMin.committedFragments().map { it.stitched })
            .containsExactly(false, false).inOrder()
        val gson = Gson()
        val frag0 = gson.fromJson(logged[0], JsonObject::class.java)
        assertThat(frag0.get("Q").asDouble).isEqualTo(50.0) // the exact boundary
        assertThat(frag0.get("stitched").asBoolean).isFalse()

        // just below the gate's idea of good enough: Q = 50 < 60 → stitch
        val (aboveDir, aboveMin) = qGate("above-min", 60.0)
        aboveMin.encodeAvailableFragments()
        aboveMin.prepareTailSync()
        assertThat(preRollFiles(aboveDir).map { it.name }).containsExactly("preroll_000001.wav")
        assertThat(aboveMin.committedFragments().map { it.stitched })
            .containsExactly(false, true).inOrder()
    }

    /**
     * The forced path (no candidate → Q = 0) ALWAYS stitches under Q_GATE —
     * 0 is below any positive qualityMin: uniform speech leaves the
     * late-half span without a below-axis frame, the cut is the quietest
     * frame (Q = 0) and the seam arms the full safety net.
     */
    @Test
    fun `q-gate stitches the forced path - a Q of zero is always below the gate`() {
        val data = nyquist(96_000, 3000)
        val dir = tmp.newFolder("forced-q")
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = dir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.Q_GATE,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        assertThat(preRollFiles(dir).map { it.name }).containsExactly("preroll_000001.wav")
        assertThat(preparer.committedFragments().map { it.stitched })
            .containsExactly(false, true).inOrder()
        assertThat(preparer.committedFragments().map { it.seamForced })
            .containsExactly(false, true).inOrder()
    }

    /** The frozen OFF A/B arm: never a pre-roll, never a stitched seam. */
    @Test
    fun `off policy never writes a pre-roll`() {
        val data = speechWithZeros(260_000, 48_000 until 64_000, 152_320 until 168_320)
        val dir = tmp.newFolder("off-arm")
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = dir,
            fragmentBytes = 32_000, wavMode = true, searchBytes = 32_000, preRollBytes = 320,
            seamPolicy = SeamPolicy.OFF,
            axisK = 0.65, axisC = 100.0, marginKMs = 150
        )
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()

        assertThat(preRollFiles(dir)).isEmpty()
        // the 260 kB fixture commits 4 searched fragments + the tail
        assertThat(preparer.committedFragments().map { it.stitched })
            .containsExactly(false, false, false, false, false).inOrder()
    }

    /**
     * Legacy manifests (no `seamQ` — pre-#128, or the unmeasured
     * fixed-cut path) keep parsing, and the gate treats a null Q as
     * STITCH — the conservative direction (an unmeasured seam is not
     * trusted to be clean): the recovered seam arms the pre-roll.
     */
    @Test
    fun `legacy manifest without seamQ parses and its seam stitches under the q-gate`() {
        val frag = File(sessionDir, "frag_000000.wav")
        frag.writeBytes(WavWriter.write(ByteArray(1000), sampleRate = 16_000))
        File(sessionDir, "manifest.json").writeText(
            """{"fallbackToWav":true,"fragments":[""" +
                """{"name":"frag_000000.wav","bytes":1044,"end":1000,"silenceAligned":false}]}"""
        )
        val data = noisy(2000)
        val preparer = FragmentPreparer(
            pcm = FakePcm(data), transcoder = transcoder, sessionDir = sessionDir,
            fragmentBytes = 1000, wavMode = true, searchBytes = 0, preRollBytes = 320,
            seamPolicy = SeamPolicy.Q_GATE
        )

        preparer.prepareTailSync()

        // recovered fragment 0 reused (not re-encoded), fragment 1 = the rest
        assertThat(fragmentFiles().map { it.name }).containsExactly(
            "frag_000000.wav", "frag_000001.wav"
        )
        // the null-Q seam stitched: pre-roll composed for fragment 1
        assertThat(preRollFiles().map { it.name }).containsExactly("preroll_000001.wav")
        assertThat(payload(preRollFiles().single())).isEqualTo(data.copyOfRange(680, 1000))
        assertThat(preparer.committedFragments().map { it.stitched })
            .containsExactly(false, true).inOrder()
    }
}
