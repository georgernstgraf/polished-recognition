package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.SeamPolicy
import com.georgernstgraf.polishedrecognition.audio.WavReader
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * #116: the live fragment transcription worker — ordered accumulation,
 * per-fragment failure isolation, drain semantics, cheap retries.
 */
class FragmentTranscriberTest {

    private val sttRunner: SttRequestRunner = mockk(relaxed = true)

    @get:Rule
    val tmp = TemporaryFolder()

    private val config = SttProviderConfig(
        displayName = "LAN",
        baseUrl = "http://10.8.0.16:11437/v1/",
        apiToken = "token",
        model = "large-v3"
    )

    private val progresses = mutableListOf<Int>()
    private lateinit var transcriber: FragmentTranscriber

    @Before
    fun setUp() {
        progresses.clear()
        transcriber = FragmentTranscriber(
            sttRunner = sttRunner,
            configProvider = { config },
            scope = CoroutineScope(Dispatchers.Unconfined),
            onProgress = { progresses.add(it) }
        )
    }

    private fun fragment(
        index: Int,
        seconds: Int = 7,
        preRollSeconds: Int = 0, // pre-roll off unless a test opts in (#117 round 2)
        seamForced: Boolean = true // #122: boundary cut type
    ): FragmentTranscriber.CommittedFragment {
        // 16-bit mono @ 16 kHz = 32_000 bytes/s → one second of PCM per 32 kB
        val file = File(tmp.root, "frag_%06d.wav".format(index))
        file.writeBytes(WavWriter.write(ByteArray(seconds * 32_000), sampleRate = 16_000))
        val preRoll = if (preRollSeconds == 0) null else
            File(tmp.root, "preroll_%06d.wav".format(index)).apply {
                writeBytes(WavWriter.write(ByteArray(preRollSeconds * 32_000), sampleRate = 16_000))
            }
        return FragmentTranscriber.CommittedFragment(index, file, preRoll, seamForced)
    }

    private fun ok(text: String, language: String? = null): Result<SttResponse> =
        Result.success(SttResponse(text = text, language = language))

    @Test
    fun `transcripts accumulate in fragment order regardless of offer order`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            val index = firstArg<File>().name.substringAfter("frag_").takeWhile { it.isDigit() }.toInt()
            Result.success(SttResponse(text = "f$index"))
        }

        transcriber.start()
        transcriber.offer(fragment(2))
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1))
        val drained = transcriber.drain()

        assertThat(drained.text).isEqualTo("f0 f1 f2")
        assertThat(drained.chunkCount).isEqualTo(3)
        assertThat(drained.failedIndex).isNull()
    }

    @Test
    fun `language comes from the first fragment reporting one`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(ok("eins"), ok("zwei", language = "de", ), ok("drei", language = "fr"))

        transcriber.start()
        (0..2).forEach { transcriber.offer(fragment(it)) }
        val drained = transcriber.drain()

        assertThat(drained.language).isEqualTo("de")
        assertThat(drained.text).isEqualTo("eins zwei drei")
    }

    @Test
    fun `failed fragment is isolated and reported at the lowest failed index`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(
                ok("eins"),
                Result.failure(SttRequestRunner.SttRequestException("HTTP 429", httpCode = 429)),
                Result.failure(SttRequestRunner.SttRequestException("HTTP 500", httpCode = 500)),
                ok("vier")
            )

        transcriber.start()
        (0..3).forEach { transcriber.offer(fragment(it)) }
        val drained = transcriber.drain()

        // fragments 1 and 2 failed, 0 and 3 still transcribed in order
        assertThat(drained.text).isEqualTo("eins vier")
        assertThat(drained.failedIndex).isEqualTo(1)
        assertThat(drained.failure!!.message).isEqualTo("HTTP 429")
        assertThat(drained.chunkCount).isEqualTo(4)
    }

    @Test
    fun `offer is idempotent per index`() = runBlocking {
        var calls = 0
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            calls++
            ok("once")
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(0))
        val drained = transcriber.drain()

        assertThat(calls).isEqualTo(1)
        assertThat(drained.text).isEqualTo("once")
        assertThat(drained.chunkCount).isEqualTo(1)
    }

    @Test
    fun `progress reports pending audio seconds while draining`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            Result.success(SttResponse(text = "x"))
        }

        // offer BOTH fragments before the worker starts (drain() starts it):
        // pending is deterministically 14 s when processing begins
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1))
        transcriber.drain()

        // pending ticks down as the serial worker completes fragments
        assertThat(progresses.first()).isEqualTo(7)
        assertThat(progresses.last()).isEqualTo(0)
    }

    @Test
    fun `drain after cancel returns an empty snapshot`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } returns ok("eins")

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.drain()
        transcriber.cancel()

        val drained = transcriber.drain()
        assertThat(drained.chunkCount).isEqualTo(0)
        assertThat(drained.text).isEmpty()
    }

    @Test
    fun `unconfigured provider records a failure instead of crashing`() = runBlocking {
        val unconfigured = FragmentTranscriber(
            sttRunner = sttRunner,
            configProvider = { null },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        unconfigured.start()
        unconfigured.offer(fragment(0))
        val drained = unconfigured.drain()

        assertThat(drained.failedIndex).isEqualTo(0)
        assertThat(drained.failure!!.message).isEqualTo("STT provider not configured")
    }

    @Test
    fun `unexpected worker exception is recorded as fragment failure`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        transcriber.start()
        transcriber.offer(fragment(0))
        val drained = transcriber.drain()

        assertThat(drained.failedIndex).isEqualTo(0)
        assertThat(drained.failure!!.message).isEqualTo("boom")
    }

    @Test
    fun `resetFailures re-queues only the failed fragments`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(
                ok("eins"),
                Result.failure(SttRequestRunner.SttRequestException("read timeout")),
                ok("drei")
            )

        transcriber.start()
        (0..2).forEach { transcriber.offer(fragment(it)) }
        val first = transcriber.drain()
        assertThat(first.failedIndex).isEqualTo(1)

        // retry: fragment 1 gets a fresh attempt, 0 and 2 stay cached
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } returns ok("zwei")
        transcriber.resetFailures()
        val second = transcriber.drain()

        assertThat(second.text).isEqualTo("eins zwei drei")
        assertThat(second.failedIndex).isNull()
    }

    // ---- #117: prompt carry-over across fragment seams ---------------------

    /** The prompt argument (arg 4) of every runner call, in call order. */
    private val prompts = mutableListOf<String?>()

    /** Records every runner call's prompt and answers `t<fragmentIndex>`. */
    private fun stubPromptRecordingRunner(answer: (String?, Int) -> Result<SttResponse>) {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            val prompt = invocation.args[4] as String?
            prompts.add(prompt)
            val index = firstArg<File>().name.substringAfter("frag_").takeWhile { it.isDigit() }.toInt()
            answer(prompt, index)
        }
    }

    @Test
    fun `previous fragment transcript is passed as the prompt`() = runBlocking {
        stubPromptRecordingRunner { _, index -> Result.success(SttResponse(text = "t$index")) }

        transcriber.start()
        (0..2).forEach { transcriber.offer(fragment(it)) }
        val drained = transcriber.drain()

        assertThat(drained.text).isEqualTo("t0 t1 t2")
        // the first fragment is contextless; every later one is conditioned
        // on the previous transcript (which is NOT echoed into the output)
        assertThat(prompts).containsExactly(null, "t0", "t1").inOrder()
    }

    @Test
    fun `prompt carries a word-snapped seam-local tail of the previous transcript`() = runBlocking {
        val long = (1..500).joinToString(" ") { "wort$it" } // far beyond the window
        stubPromptRecordingRunner { _, _ -> Result.success(SttResponse(text = long)) }

        transcriber.start()
        (0..1).forEach { transcriber.offer(fragment(it)) }
        transcriber.drain()

        val prompt = prompts[1]!!
        // #122: seam-local (≤ the window) AND starting on a word boundary.
        assertThat(prompt.length).isAtMost(FragmentTranscriber.DEFAULT_PROMPT_MAX_CHARS)
        assertThat(long.endsWith(prompt)).isTrue()
        val start = long.length - prompt.length
        assertThat(long[start - 1]).isEqualTo(' ')
    }

    @Test
    fun `HTTP 400 on a prompted request retries without prompt and disables prompts`() = runBlocking {
        stubPromptRecordingRunner { prompt, index ->
            if (prompt != null) {
                Result.failure(SttRequestRunner.SttRequestException("HTTP 400", httpCode = 400))
            } else {
                Result.success(SttResponse(text = "t$index"))
            }
        }

        transcriber.start()
        (0..3).forEach { transcriber.offer(fragment(it)) }
        val drained = transcriber.drain()

        // fragment 0 (no prompt) succeeds; fragment 1 fails WITH the prompt
        // and retries without it; fragments 2..3 skip the prompt directly
        assertThat(prompts).containsExactly(null, "t0", null, null, null).inOrder()
        assertThat(drained.text).isEqualTo("t0 t1 t2 t3")
        assertThat(drained.failedIndex).isNull()
    }

    // ---- #117 round 2: acoustic pre-roll composition + echo trim -----------

    @Test
    fun `pre-roll is prepended to the upload and the composed file is deleted`() = runBlocking {
        val uploaded = mutableListOf<Pair<String, ByteArray>>() // name to bytes AT CALL TIME
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            val file = firstArg<File>()
            uploaded.add(file.name to file.readBytes())
            ok("t${thirdArg<Int>() - 1}")
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1, preRollSeconds = 1))
        val drained = transcriber.drain()

        // fragment 0 uploads bare (no pre-roll); fragment 1 uploads the
        // pre-roll and the fragment combined (#119: a WAV is REBUILT into one
        // canonical header — a byte-concat would cap the decoded duration at
        // the 1-s pre-roll and drop the fragment).
        assertThat(uploaded[0].first).isEqualTo("frag_000000.wav")
        assertThat(uploaded[1].first).isEqualTo("upload_000001.wav")
        val composed = WavReader.read(uploaded[1].second)
        assertThat(composed.sampleRate).isEqualTo(16_000)
        assertThat(composed.data.size).isEqualTo((1 + 7) * 32_000)
        // the composed upload is single-use — deleted after the attempt
        assertThat(File(tmp.root, "upload_000001.wav").exists()).isFalse()
        // the preparer-owned sources survive
        assertThat(File(tmp.root, "frag_000001.wav").exists()).isTrue()
        assertThat(File(tmp.root, "preroll_000001.wav").exists()).isTrue()
        assertThat(drained.text).isEqualTo("t0 t1")
    }

    @Test
    fun `missing pre-roll file uploads the bare fragment`() = runBlocking {
        val uploaded = mutableListOf<File>()
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            uploaded.add(firstArg())
            ok("t${thirdArg<Int>() - 1}")
        }

        val f1 = fragment(1, preRollSeconds = 1)
        f1.preRoll!!.delete() // e.g. a failed pre-roll encode on-device
        transcriber.start()
        transcriber.offer(f1)
        val drained = transcriber.drain()

        assertThat(uploaded.single().name).isEqualTo("frag_000001.wav")
        assertThat(drained.text).isEqualTo("t1")
    }

    @Test
    fun `echoed pre-roll text is trimmed against the previous transcript tail`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            when (thirdArg<Int>() - 1) {
                0 -> ok("Der Patient klagt über Schmerzen")
                else -> ok("Der Patient klagt über Schmerzen und Fieber")
            }
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1, preRollSeconds = 1))
        val drained = transcriber.drain()

        // the echoed 5 head words match the previous tail → trimmed;
        // only the fresh words are added
        assertThat(drained.text).isEqualTo("Der Patient klagt über Schmerzen und Fieber")
    }

    @Test
    fun `echo trim matches a single tail token`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            when (thirdArg<Int>() - 1) {
                0 -> ok("Der Patient klagt über Schmerzen")
                else -> ok("Schmerzen und Fieber")
            }
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1, preRollSeconds = 1))
        val drained = transcriber.drain()

        // only the LAST tail token repeats at the head → k=1 match → trimmed
        assertThat(drained.text).isEqualTo("Der Patient klagt über Schmerzen und Fieber")
    }

    @Test
    fun `echo trim keeps everything when no match exists`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            when (thirdArg<Int>() - 1) {
                0 -> ok("Der Patient klagt über Schmerzen")
                else -> ok("Völlig neuer Gedanke beginnt hier")
            }
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1, preRollSeconds = 1))
        val drained = transcriber.drain()

        // conservative: no match → keep everything (duplication beats loss)
        assertThat(drained.text)
            .isEqualTo("Der Patient klagt über Schmerzen Völlig neuer Gedanke beginnt hier")
    }

    @Test
    fun `a fully echoed fragment trims to empty`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            ok("Der Patient klagt über Schmerzen")
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1, preRollSeconds = 1))
        val drained = transcriber.drain()

        // every token of fragment 1 matches the previous tail → trimmed empty;
        // blank transcripts join as nothing
        assertThat(drained.text).isEqualTo("Der Patient klagt über Schmerzen")
    }

    @Test
    fun `trim runs only when the upload carried a pre-roll`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            when (thirdArg<Int>() - 1) {
                0 -> ok("Der Patient klagt über Schmerzen")
                else -> ok("Der Patient klagt über Schmerzen und Fieber")
            }
        }

        transcriber.start()
        // NO pre-roll: the identical-looking repeat is real speech, not echo
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1))
        val drained = transcriber.drain()

        assertThat(drained.text).isEqualTo(
            "Der Patient klagt über Schmerzen Der Patient klagt über Schmerzen und Fieber"
        )
    }

    @Test
    fun `HTTP 400 retry uploads the same composed file`() = runBlocking {
        val calls = mutableListOf<Pair<String, String?>>() // upload name to prompt
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            val prompt = invocation.args[4] as String?
            calls.add(firstArg<File>().name to prompt)
            if (prompt != null) {
                Result.failure(SttRequestRunner.SttRequestException("HTTP 400", httpCode = 400))
            } else {
                ok("t${thirdArg<Int>() - 1}")
            }
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1, preRollSeconds = 1))
        val drained = transcriber.drain()

        // fragment 1: attempt 1 with prompt → 400 → attempt 2 WITHOUT the
        // prompt on the SAME composed upload (the pre-roll must survive a
        // prompt fallback — the echo is acoustic)
        assertThat(calls).containsExactly(
            "frag_000000.wav" to null,
            "upload_000001.wav" to "t0",
            "upload_000001.wav" to null
        ).inOrder()
        assertThat(drained.text).isEqualTo("t0 t1")
        assertThat(File(tmp.root, "upload_000001.wav").exists()).isFalse()
    }

    // ---- #122: seam policy -------------------------------------------------

    private fun transcriberWith(policy: SeamPolicy): FragmentTranscriber =
        FragmentTranscriber(
            sttRunner = sttRunner,
            configProvider = { config },
            scope = CoroutineScope(Dispatchers.Unconfined),
            onProgress = { progresses.add(it) },
            seamPolicy = policy
        )

    @Test
    fun `forced-only skips the prompt at a silence-aligned seam`() = runBlocking {
        transcriber = transcriberWith(SeamPolicy.FORCED_ONLY)
        stubPromptRecordingRunner { _, index -> Result.success(SttResponse(text = "t$index")) }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1, seamForced = true))
        transcriber.offer(fragment(2, seamForced = false)) // silence-aligned boundary
        transcriber.drain()

        // f0 contextless; f1 forced → prompted; f2 silence → no prompt
        assertThat(prompts).containsExactly(null, "t0", null).inOrder()
    }

    @Test
    fun `all policy keeps the prompt at every seam`() = runBlocking {
        transcriber = transcriberWith(SeamPolicy.ALL)
        stubPromptRecordingRunner { _, index -> Result.success(SttResponse(text = "t$index")) }

        transcriber.start()
        (0..2).forEach { transcriber.offer(fragment(it, seamForced = false)) }
        transcriber.drain()

        assertThat(prompts).containsExactly(null, "t0", "t1").inOrder()
    }

    @Test
    fun `off policy sends no prompt at all`() = runBlocking {
        transcriber = transcriberWith(SeamPolicy.OFF)
        stubPromptRecordingRunner { _, index -> Result.success(SttResponse(text = "t$index")) }

        transcriber.start()
        (0..2).forEach { transcriber.offer(fragment(it)) }
        transcriber.drain()

        assertThat(prompts).containsExactly(null, null, null).inOrder()
    }

    @Test
    fun `prompt window is configurable — the long variant stays reachable`() = runBlocking {
        val long = (1..400).joinToString(" ") { "wort$it" }
        transcriber = FragmentTranscriber(
            sttRunner = sttRunner,
            configProvider = { config },
            scope = CoroutineScope(Dispatchers.Unconfined),
            promptMaxChars = 800
        )
        stubPromptRecordingRunner { _, _ -> Result.success(SttResponse(text = long)) }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1))
        transcriber.drain()

        val prompt = prompts[1]!!
        assertThat(prompt.length).isAtMost(800)
        assertThat(long.endsWith(prompt)).isTrue()
    }

    @Test
    fun `seam evidence is one accumulating array when enabled`() = runBlocking {
        val logDir = tmp.newFolder("logs")
        transcriber = FragmentTranscriber(
            sttRunner = sttRunner,
            configProvider = { config },
            scope = CoroutineScope(Dispatchers.Unconfined),
            logger = RotatingJsonLogger(logDir),
            seamEvidence = true
        )
        coEvery { sttRunner.run(any(), any(), any(), any(), any(), any()) } answers {
            when (thirdArg<Int>() - 1) {
                0 -> ok("Der Patient klagt über Schmerzen und Fieber")
                else -> ok("und Fieber am Abend stärker")
            }
        }

        transcriber.start()
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1))
        transcriber.drain()

        val json = File(logDir, "stt-fragment.json").readText()
        // both fragments in ONE array (rotation would otherwise keep only the
        // last 9), with the seam-overlap metric computed across the seam
        assertThat(json).contains("\"fragment\":0")
        assertThat(json).contains("\"fragment\":1")
        assertThat(json).contains("\"seamOverlapTokens\":2")
        assertThat(json).contains("\"words\":5")
    }
}
