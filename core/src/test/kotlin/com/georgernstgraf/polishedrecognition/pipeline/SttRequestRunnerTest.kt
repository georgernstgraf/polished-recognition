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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
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
    fun `a 2xx without a transcript is a protocol failure with exactly one request`() =
        kotlinx.coroutines.runBlocking {
            // #75: PROTOCOL dropped from two attempts to ONE — the class has
            // never occurred in the device logs, and the manual Retry STT
            // covers a one-off glitch.
            mockSttMany(Response.success(envelopeWithoutText()), success("should not be asked"))

            val result = runner().run(wavFile(), config)

            assertThat(result.isFailure).isTrue()
            assertThat(failureOf(result).failure).isEqualTo(SttFailure.PROTOCOL)
            assertThat(failureOf(result).message).isEqualTo("HTTP 200 (unusable response body)")
            assertThat(delays).isEmpty()
            assertThat((readLog("stt-latency")["error"] as String)).isEqualTo("unusableEnvelope")
            verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
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
    fun `an undecodable body is protocol with exactly one request`() = kotlinx.coroutines.runBlocking {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } throws com.google.gson.JsonSyntaxException("Expected BEGIN_OBJECT")
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns call

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(failureOf(result).failure).isEqualTo(SttFailure.PROTOCOL)
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
    }

    // ---- #75: the provider's own refusal ----------------------------------

    /** The body the device actually produced (LiteLLM spend cap, OPO logs). */
    private val budgetBody =
        """{"error":{"message":"ExceededBudget: User=grafg over budget. Spend=5.00060467499998, Budget=5.0","type":"budget_exceeded","code":"429"}}"""

    private val rateLimitBody =
        """{"error":{"message":"Rate limit reached for requests","type":"rate_limit_exceeded","code":"429"}}"""

    /**
     * An HTTP failure with a real body (and optional headers) to parse. Named
     * apart from [httpError] on purpose: a 2-arg call would otherwise resolve
     * to the `retryAfter` overload and silently skip the body.
     */
    private fun errorWithBody(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Response<SttResponse> {
        val responseBody = body.toResponseBody("application/json".toMediaTypeOrNull())
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://localhost/v1/audio/transcriptions").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(responseBody)
            .build()
        @Suppress("DEPRECATION")
        return Response.error(responseBody, raw)
    }

    @Test
    fun `a budget refusal makes exactly one request and carries the provider's sentence`() =
        kotlinx.coroutines.runBlocking {
            mockStt(errorWithBody(429, budgetBody))

            val result = runner().run(wavFile(), config)

            assertThat(result.isFailure).isTrue()
            val failure = failureOf(result)
            // the #183 matrix alone would call a 429 TRANSIENT and spend three
            // attempts against a wall that cannot clear
            assertThat(failure.failure).isEqualTo(SttFailure.BUDGET)
            assertThat(failure.httpCode).isEqualTo(429)
            // the user sees the provider's own line, after what the app did
            assertThat(failure.message).isEqualTo(
                "HTTP 429 — ExceededBudget: User=grafg over budget. " +
                    "Spend=5.00060467499998, Budget=5.0"
            )
            assertThat(failure.providerError!!.type).isEqualTo("budget_exceeded")
            assertThat(delays).isEmpty()
            verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a rate_limit_exceeded body keeps the transient retries`() = kotlinx.coroutines.runBlocking {
        mockSttMany(errorWithBody(429, rateLimitBody), success("second try"))

        val result = runner().run(wavFile(), config)

        // one minute later the same request may work — this is not a spend cap
        assertThat(result.isSuccess).isTrue()
        assertThat(delays).containsExactly(1L).inOrder()
        verify(exactly = 2) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unparseable 429 body keeps the transient retries`() = kotlinx.coroutines.runBlocking {
        // an nginx HTML page says nothing about a budget
        mockSttMany(errorWithBody(429, "<html><body>429 Too Many Requests</body></html>"), success())
        assertThat(runner().run(wavFile(), config).isSuccess).isTrue()

        delays.clear()
        // ... and neither does an empty body
        mockSttMany(errorWithBody(429, ""), success())
        assertThat(runner().run(wavFile("empty-body.wav"), config).isSuccess).isTrue()
        assertThat(delays).isNotEmpty()
    }

    @Test
    fun `attempts per failure class are the #75 contract`() {
        val runner = runner()

        assertThat(runner.attemptsFor(SttFailure.BUDGET)).isEqualTo(1)
        assertThat(runner.attemptsFor(SttFailure.PROTOCOL)).isEqualTo(1)
        assertThat(runner.attemptsFor(SttFailure.AUTH)).isEqualTo(1)
        assertThat(runner.attemptsFor(SttFailure.TERMINAL)).isEqualTo(1)
        assertThat(runner.attemptsFor(SttFailure.TRANSIENT))
            .isEqualTo(SttRequestRunner.MAX_ATTEMPTS)
    }

    @Test
    fun `a transient failure still uses all three attempts`() = kotlinx.coroutines.runBlocking {
        mockStt(errorWithBody(503, "upstream unavailable"))

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(failureOf(result).failure).isEqualTo(SttFailure.TRANSIENT)
        verify(exactly = 3) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the completion record carries the limits block and the parsed refusal`() =
        kotlinx.coroutines.runBlocking {
            mockStt(
                errorWithBody(
                    429,
                    budgetBody,
                    mapOf(
                        "x-ratelimit-remaining-requests" to "0",
                        "Retry-After" to "30",
                        "X-Request-Id" to "not-a-limit"
                    )
                )
            )

            runner().run(wavFile(), config)

            val record = readLog("stt-latency")
            val headers = record["headers"] as Map<*, *>
            // limits only, names lowercased — an unrelated header is no evidence
            assertThat(headers.keys).containsExactly("x-ratelimit-remaining-requests", "retry-after")
            assertThat(headers["x-ratelimit-remaining-requests"]).isEqualTo("0")
            assertThat(headers["retry-after"]).isEqualTo("30")

            val providerError = record["providerError"] as Map<*, *>
            assertThat(providerError["type"]).isEqualTo("budget_exceeded")
            assertThat(providerError["message"] as String).startsWith("ExceededBudget:")
        }

    @Test
    fun `a record without limits omits both fields`() = kotlinx.coroutines.runBlocking {
        mockStt(success())

        runner().run(wavFile(), config)

        val record = readLog("stt-latency")
        // pre-#75 records keep their exact shape (Gson omits nulls)
        assertThat(record.containsKey("headers")).isFalse()
        assertThat(record.containsKey("providerError")).isFalse()
    }

    @Test
    fun `the success path logs the limits too`() = kotlinx.coroutines.runBlocking {
        // a 200 still carries the gateway's budget state — that is what makes
        // a "how much was left at 14:12" question answerable afterwards
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://localhost/v1/audio/transcriptions").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("x-ratelimit-remaining-tokens", "42")
            .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
            .build()
        mockStt(Response.success(success().body()!!, raw.headers))

        val result = runner().run(wavFile(), config)

        assertThat(result.isSuccess).isTrue()
        val headers = readLog("stt-latency")["headers"] as Map<*, *>
        assertThat(headers["x-ratelimit-remaining-tokens"]).isEqualTo("42")
    }

    @Test
    fun `the failure carries the limits block for the bundle`() = kotlinx.coroutines.runBlocking {
        mockStt(errorWithBody(429, budgetBody, mapOf("x-ratelimit-remaining-requests" to "0")))

        val block = failureOf(runner().run(wavFile(), config)).rateLimitBlock()!!

        assertThat(block["httpCode"]).isEqualTo(429)
        assertThat(block["type"]).isEqualTo("budget_exceeded")
        assertThat(block["message"] as String).startsWith("ExceededBudget:")
        assertThat((block["headers"] as Map<*, *>)["x-ratelimit-remaining-requests"]).isEqualTo("0")

        // a transport failure has neither body nor headers — nothing to record
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } throws IOException("read timeout")
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns call
        assertThat(failureOf(runner().run(wavFile("timeout.wav"), config)).rateLimitBlock()).isNull()
    }
}
