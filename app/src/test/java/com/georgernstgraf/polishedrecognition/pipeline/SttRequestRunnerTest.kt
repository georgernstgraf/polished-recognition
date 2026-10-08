package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
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
        every { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns call
    }

    private fun mockSttMany(vararg responses: Response<SttResponse>) {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returnsMany responses.toList()
        every { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns call
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
        every { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns call

        val result = runner().run(wavFile(), config)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("read timeout")
        assertThat(delays).containsExactly(1L, 2L).inOrder()
        val completion = readLog("stt-latency")
        assertThat(completion["httpCode"]).isNull()
        assertThat(completion["error"]).isEqualTo("IOException")
    }

    /** Minimal structurally valid Ogg page (see AudioDurationTest). */
    private fun singlePageOgg(granulePosition: Long): ByteArray {
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
}
