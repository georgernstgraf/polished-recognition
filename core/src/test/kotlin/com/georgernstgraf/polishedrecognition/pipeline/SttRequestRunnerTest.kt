package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.ResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Call
import retrofit2.Response
import java.io.File
import java.io.IOException

/**
 * #116: single-request STT execution with retry ×3 (transient-only) and
 * per-attempt completion records in `stt-latency.json`.
 */
class SttRequestRunnerTest {

    private val sttApi = mockk<OpenAiSttApiService>(relaxed = true)

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var logDir: File
    private val delays = mutableListOf<Long>()

    private val config = SttProviderConfig(
        displayName = "LAN",
        baseUrl = "http://10.8.0.16:11437/v1/",
        apiToken = "token",
        model = "large-v3"
    )

    @Before
    fun setUp() {
        logDir = tmp.newFolder("logs")
        delays.clear()
    }

    private fun runner(): SttRequestRunner =
        SttRequestRunner(
            getSttApi = { sttApi },
            logger = RotatingJsonLogger(logDir),
            backoffMs = listOf(1L, 2L),
            sleep = { delays.add(it) }
        )

    private fun mockStt(response: Response<SttResponse>) {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returns response
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns call
    }

    private fun mockSttMany(vararg responses: Response<SttResponse>) {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returnsMany responses.toList()
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns call
    }

    private fun success(text: String = "hallo welt") =
        Response.success(SttResponse(text = text, language = "de"))

    private fun httpError(code: Int) =
        @Suppress("DEPRECATION")
        Response.error<SttResponse>(code, ResponseBody.create(null, "error"))

    /** 16-bit mono @ 16 kHz: 16000 data bytes = 500 ms (WavWriter layout). */
    private fun wavFile(name: String = "fragment.wav"): File =
        tmp.newFile(name).apply { writeBytes(WavWriter.write(ByteArray(16_000), sampleRate = 16_000)) }

    private fun readLog(baseName: String): Map<*, *> =
        com.google.gson.Gson().fromJson(File(logDir, "$baseName.json").readText(), Map::class.java)

    @Test
    fun `success on first attempt logs upload and completion with duration`() = kotlinx.coroutines.runBlocking {
        mockStt(success())
        val wav = wavFile()

        val result = runner().run(wav, config, chunk = 1, chunkCount = 1)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().text).isEqualTo("hallo welt")

        val upload = readLog("stt-upload")
        assertThat(upload["chunk"]).isEqualTo(1.0)
        assertThat(upload["file"]).isEqualTo("fragment.wav")
        assertThat(upload["mediaType"]).isEqualTo("audio/wav")
        assertThat(upload["model"]).isEqualTo("large-v3")
        assertThat(upload["baseUrl"]).isEqualTo("http://10.8.0.16:11437/v1/")
        // legacy upload entries carry no completion fields
        assertThat(upload.containsKey("durationMs")).isFalse()
        assertThat(upload.containsKey("httpCode")).isFalse()

        val completion = readLog("stt-latency")
        assertThat(completion["event"]).isEqualTo("complete")
        assertThat((completion["durationMs"] as Double).toLong()).isEqualTo(500L)
        assertThat((completion["httpCode"] as Double).toInt()).isEqualTo(200)
        assertThat((completion["attempt"] as Double).toInt()).isEqualTo(1)
        assertThat(completion["elapsedMs"] as Double).isAtLeast(0.0)
        assertThat(completion["error"]).isNull()
    }

    @Test
    fun `ogg duration is parsed from the last page granule`() = kotlinx.coroutines.runBlocking {
        mockStt(success())
        val ogg = tmp.newFile("fragment.ogg")
        ogg.writeBytes(singlePageOgg(granulePosition = 336_000)) // 7 s

        val result = runner().run(ogg, config)

        assertThat(result.isSuccess).isTrue()
        assertThat((readLog("stt-latency")["durationMs"] as Double).toLong()).isEqualTo(7_000L)
    }

    @Test
    fun `transient HTTP 500 retries and succeeds`() = kotlinx.coroutines.runBlocking {
        mockSttMany(httpError(500), success("second try"))

        val result = runner().run(wavFile(), config)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().text).isEqualTo("second try")
        assertThat(delays).containsExactly(1L).inOrder()
        // attempt 1 (500) rotated into _1, attempt 2 (200) is current
        assertThat((readLog("stt-latency")["attempt"] as Double).toInt()).isEqualTo(2)
        assertThat((readLog("stt-latency")["httpCode"] as Double).toInt()).isEqualTo(200)
        assertThat((readLog("stt-latency_1")["httpCode"] as Double).toInt()).isEqualTo(500)
    }

    @Test
    fun `HTTP 429 is transient and retries`() = kotlinx.coroutines.runBlocking {
        mockSttMany(httpError(429), success())

        val result = runner().run(wavFile(), config)

        assertThat(result.isSuccess).isTrue()
        assertThat(delays).containsExactly(1L).inOrder()
    }

    @Test
    fun `HTTP 400 fails immediately without retry`() = kotlinx.coroutines.runBlocking {
        mockStt(httpError(400))

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("HTTP 400")
        assertThat((result.exceptionOrNull() as SttRequestRunner.SttRequestException).httpCode).isEqualTo(400)
        assertThat(delays).isEmpty()
        assertThat((readLog("stt-latency")["attempt"] as Double).toInt()).isEqualTo(1)
    }

    @Test
    fun `persistent HTTP 500 fails after three attempts with backoff`() = kotlinx.coroutines.runBlocking {
        mockStt(httpError(500))

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("HTTP 500")
        assertThat(delays).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `IOException is transient and fails after three attempts`() = kotlinx.coroutines.runBlocking {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } throws IOException("read timeout")
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns call

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("read timeout")
        assertThat(delays).containsExactly(1L, 2L).inOrder()
        val completion = readLog("stt-latency")
        assertThat(completion["httpCode"]).isNull()
        assertThat(completion["error"]).isEqualTo("IOException")
    }

    /** Minimal structurally valid Ogg page (see AudioDurationTest). */    private fun singlePageOgg(granulePosition: Long): ByteArray {
        val page = ByteArray(28)
        page[0] = 'O'.code.toByte()
        page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte()
        page[3] = 'S'.code.toByte()
        page[4] = 0
        for (i in 0 until 8) page[6 + i] = ((granulePosition shr (8 * i)) and 0xFF).toByte()
        page[26] = 1
        page[27] = 0
        return page
    }

    // ---- #117: prompt carry-over + evidence suppression --------------------

    private fun stubWithPromptSlot(): io.mockk.CapturingSlot<okhttp3.RequestBody?> {
        val promptSlot = slot<okhttp3.RequestBody?>()
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returns success()
        every {
            sttApi.transcribeAudioSync(any(), any(), any(), any(), captureNullable(promptSlot), any())
        } returns call
        return promptSlot
    }

    @Test
    fun `prompt is forwarded as the prompt multipart part`() = kotlinx.coroutines.runBlocking {
        val promptSlot = stubWithPromptSlot()

        val result = runner().run(wavFile(), config, chunk = 1, prompt = "context tail")

        assertThat(result.isSuccess).isTrue()
        val buffer = okio.Buffer()
        promptSlot.captured!!.writeTo(buffer)
        assertThat(buffer.readUtf8()).isEqualTo("context tail")
        // prompt marker in the completion record (#117) keeps the Phase 2
        // t(S) fit unpolluted by prompt-induced latency
        assertThat((readLog("stt-latency")["promptChars"] as Double).toInt()).isEqualTo(12)
    }

    @Test
    fun `no prompt means the prompt part is omitted`() = kotlinx.coroutines.runBlocking {
        val promptSlot = stubWithPromptSlot()

        val result = runner().run(wavFile(), config, chunk = 1)

        assertThat(result.isSuccess).isTrue()
        assertThat(promptSlot.captured).isNull()
        assertThat(readLog("stt-latency").containsKey("promptChars")).isFalse()
    }

    // ---- #184: idempotent fragment uploads --------------------------------

    /** Captures the `@PartMap` of every attempt, in order. */
    private fun captureIdempotencyFields(vararg responses: Response<SttResponse>): MutableList<Map<String, okhttp3.RequestBody>> {
        val captured = mutableListOf<Map<String, okhttp3.RequestBody>>()
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returnsMany responses.toList()
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } answers {
            captured += arg<Map<String, okhttp3.RequestBody>>(5)
            call
        }
        return captured
    }

    private fun okhttp3.RequestBody.text(): String {
        val buffer = okio.Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `a fragment request carries session id, fragment index and attempt`() = kotlinx.coroutines.runBlocking {
        val fields = captureIdempotencyFields(success())

        val result = runner().run(
            wavFile(),
            config,
            chunk = 8,
            sessionId = "20260101-1200-aaaaaaaa",
            fragmentIndex = 7
        )

        assertThat(result.isSuccess).isTrue()
        val sent = fields.single()
        assertThat(sent.keys).containsExactly("session_id", "fragment_index", "attempt")
        assertThat(sent.getValue("session_id").text()).isEqualTo("20260101-1200-aaaaaaaa")
        assertThat(sent.getValue("fragment_index").text()).isEqualTo("7")
        assertThat(sent.getValue("attempt").text()).isEqualTo("1")
    }

    @Test
    fun `every retry repeats the same identity and bumps only the attempt`() = kotlinx.coroutines.runBlocking {
        val fields = captureIdempotencyFields(httpError(500), httpError(429), success())

        runner().run(
            wavFile(),
            config,
            sessionId = "20260101-1200-bbbbbbbb",
            fragmentIndex = 2
        )

        assertThat(fields).hasSize(3)
        assertThat(fields.map { it.getValue("session_id").text() })
            .containsExactly("20260101-1200-bbbbbbbb", "20260101-1200-bbbbbbbb", "20260101-1200-bbbbbbbb")
        assertThat(fields.map { it.getValue("fragment_index").text() })
            .containsExactly("2", "2", "2")
        assertThat(fields.map { it.getValue("attempt").text() }).containsExactly("1", "2", "3").inOrder()
    }

    @Test
    fun `without a session identity the wire format is unchanged`() = kotlinx.coroutines.runBlocking {
        val fields = captureIdempotencyFields(success())

        val result = runner().run(wavFile(), config, chunk = 1, chunkCount = 2)

        assertThat(result.isSuccess).isTrue()
        assertThat(fields.single()).isEmpty()
        // the pre-send record stays byte-shape-identical (Gson omits nulls)
        assertThat(readLog("stt-upload").containsKey("sessionId")).isFalse()
    }

    // ---- #183: failure classification -------------------------------------

    /** An HTTP failure WITH headers, so `Retry-After` can be exercised. */
    private fun httpError(code: Int, retryAfter: String? = null): Response<SttResponse> {
        @Suppress("DEPRECATION")
        val body = ResponseBody.create(null, "error")
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://localhost/v1/audio/transcriptions").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(body)
            .build()
        @Suppress("DEPRECATION")
        return Response.error(body, raw)
    }

    private fun runnerWithCaps(
        totalWaitMs: Long,
        retryWaitMs: Long = 10_000L,
        backoff: List<Long> = listOf(1L, 2L)
    ): SttRequestRunner =
        SttRequestRunner(
            getSttApi = { sttApi },
            logger = RotatingJsonLogger(logDir),
            backoffMs = backoff,
            sleep = { delays.add(it) },
            maxRetryWaitMs = retryWaitMs,
            maxTotalWaitMs = totalWaitMs
        )

    private fun failureOf(result: Result<SttResponse>): SttRequestRunner.SttRequestException =
        result.exceptionOrNull() as SttRequestRunner.SttRequestException

    /** A 2xx body as a real server sends it: the transcript key is absent. */
    private fun envelopeWithoutText(): SttResponse =
        com.google.gson.Gson().fromJson("""{"error":"bad gateway"}""", SttResponse::class.java)

    @Test
    fun `401 fails immediately with the auth class`() = kotlinx.coroutines.runBlocking {
        mockStt(httpError(401))

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(failureOf(result).httpCode).isEqualTo(401)
        assertThat(failureOf(result).failure).isEqualTo(SttFailure.AUTH)
        assertThat(delays).isEmpty()
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `403 is auth too`() = kotlinx.coroutines.runBlocking {
        mockStt(httpError(403))

        val result = runner().run(wavFile(), config)

        assertThat(failureOf(result).failure).isEqualTo(SttFailure.AUTH)
        assertThat(delays).isEmpty()
    }

    @Test
    fun `retry-after is honoured over the configured backoff`() = kotlinx.coroutines.runBlocking {
        mockSttMany(httpError(429, retryAfter = "3"), success())

        val result = runner().run(wavFile(), config)

        assertThat(result.isSuccess).isTrue()
        assertThat(delays).containsExactly(3_000L).inOrder()
        assertThat((readLog("stt-latency_1")["retryAfterMs"] as Double).toLong()).isEqualTo(3_000L)
    }

    @Test
    fun `a retry-after above the per-wait cap stops instead of waiting less`() =
        kotlinx.coroutines.runBlocking {
            mockSttMany(httpError(429, retryAfter = "60"), success())

            // cap 2 s: the server asks for 60 s. Retrying after 2 s would be a
            // violation of the very advice we asked for, so the request fails.
            val result = runnerWithCaps(totalWaitMs = 20_000L, retryWaitMs = 2_000L)
                .run(wavFile(), config)

            assertThat(result.isFailure).isTrue()
            assertThat(failureOf(result).message).contains("Retry-After 60s")
            assertThat(delays).isEmpty()
            verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a wait that exceeds the total budget stops instead of stalling`() =
        kotlinx.coroutines.runBlocking {
            mockSttMany(httpError(500), success())

            // the production 1 s backoff no longer fits the request's budget
            val result = runnerWithCaps(totalWaitMs = 500L, backoff = listOf(1_000L, 2_000L))
                .run(wavFile(), config)

            assertThat(result.isFailure).isTrue()
            assertThat(delays).isEmpty()
            verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a 2xx without a transcript is a protocol failure retried exactly once`() =
        kotlinx.coroutines.runBlocking {
            mockSttMany(Response.success(envelopeWithoutText()), success("after the retry"))

            val result = runner().run(wavFile(), config)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().text).isEqualTo("after the retry")
            assertThat(delays).containsExactly(1L).inOrder()
            assertThat((readLog("stt-latency_1")["error"] as String)).isEqualTo("unusableEnvelope")
        }

    @Test
    fun `a persistent protocol failure gives up after one retry`() = kotlinx.coroutines.runBlocking {
        mockSttMany(Response.success(envelopeWithoutText()), Response.success(envelopeWithoutText()))

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(failureOf(result).failure).isEqualTo(SttFailure.PROTOCOL)
        // exactly ONE repeat — a third attempt would just break the same way
        verify(exactly = 2) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty transcript is a legitimate silent fragment, not a protocol failure`() =
        kotlinx.coroutines.runBlocking {
            mockStt(success(""))

            val result = runner().run(wavFile(), config)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().text).isEmpty()
            verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `an undecodable body is protocol and gets its one retry`() = kotlinx.coroutines.runBlocking {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } throws com.google.gson.JsonSyntaxException("Expected BEGIN_OBJECT")
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns call

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(failureOf(result).failure).isEqualTo(SttFailure.PROTOCOL)
        verify(exactly = 2) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
    }
}
