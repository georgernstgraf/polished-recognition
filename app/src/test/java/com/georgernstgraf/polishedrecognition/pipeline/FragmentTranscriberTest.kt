package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
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

    private fun fragment(index: Int, seconds: Int = 7): FragmentTranscriber.CommittedFragment {
        // 16-bit mono @ 16 kHz = 32_000 bytes/s → one second of PCM per 32 kB
        val file = File(tmp.root, "frag_%06d.wav".format(index))
        file.writeBytes(WavWriter.write(ByteArray(seconds * 32_000), sampleRate = 16_000))
        return FragmentTranscriber.CommittedFragment(index, file)
    }

    private fun ok(text: String, language: String? = null): Result<SttResponse> =
        Result.success(SttResponse(text = text, language = language))

    @Test
    fun `transcripts accumulate in fragment order regardless of offer order`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any()) } answers {
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
        coEvery { sttRunner.run(any(), any(), any(), any()) } returnsMany
            listOf(ok("eins"), ok("zwei", language = "de", ), ok("drei", language = "fr"))

        transcriber.start()
        (0..2).forEach { transcriber.offer(fragment(it)) }
        val drained = transcriber.drain()

        assertThat(drained.language).isEqualTo("de")
        assertThat(drained.text).isEqualTo("eins zwei drei")
    }

    @Test
    fun `failed fragment is isolated and reported at the lowest failed index`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any()) } returnsMany
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
        coEvery { sttRunner.run(any(), any(), any(), any()) } answers {
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
        coEvery { sttRunner.run(any(), any(), any(), any()) } answers {
            Result.success(SttResponse(text = "x"))
        }

        transcriber.start()
        // each fragment = 7 s of audio; offers stack up to 14 s pending
        transcriber.offer(fragment(0))
        transcriber.offer(fragment(1))
        transcriber.drain()

        // pending ticks down as the serial worker completes fragments
        assertThat(progresses.first()).isEqualTo(7)
        assertThat(progresses.last()).isEqualTo(0)
    }

    @Test
    fun `drain after cancel returns an empty snapshot`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any()) } returns ok("eins")

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
        coEvery { sttRunner.run(any(), any(), any(), any()) } throws RuntimeException("boom")

        transcriber.start()
        transcriber.offer(fragment(0))
        val drained = transcriber.drain()

        assertThat(drained.failedIndex).isEqualTo(0)
        assertThat(drained.failure!!.message).isEqualTo("boom")
    }

    @Test
    fun `resetFailures re-queues only the failed fragments`() = runBlocking {
        coEvery { sttRunner.run(any(), any(), any(), any()) } returnsMany
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
        coEvery { sttRunner.run(any(), any(), any(), any()) } returns ok("zwei")
        transcriber.resetFailures()
        val second = transcriber.drain()

        assertThat(second.text).isEqualTo("eins zwei drei")
        assertThat(second.failedIndex).isNull()
    }
}
