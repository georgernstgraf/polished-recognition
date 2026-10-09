package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.ChatChoice
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import com.georgernstgraf.polishedrecognition.api.dto.ChatResponse
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Call
import retrofit2.Response
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TranscriptionPipelineTest {

    private val sttApi = mockk<OpenAiSttApiService>(relaxed = true)
    private val chatApi = mockk<OpenAiChatApiService>(relaxed = true)

    private val getSttApi: (String) -> OpenAiSttApiService = { sttApi }
    private val getChatApi: (String) -> OpenAiChatApiService = { chatApi }

    private lateinit var settingsStore: SettingsStore
    private lateinit var promptStore: PromptStore
    private lateinit var pipeline: TranscriptionPipeline
    private lateinit var lincolnFile: File

    @get:Rule
    val tmp = TemporaryFolder()

    private val lincolnGermanText =
        "Abraham Lincoln war der 16. Präsident der Vereinigten Staaten von 1861 bis 1865. " +
        "Er führte die Union durch den amerikanischen Bürgerkrieg, erließ die Emanzipationsproklamation, " +
        "die die Abschaffung der Sklaverei einleitete, und hielt die Gettysburg-Rede. " +
        "Er wurde im April 1865 von John Wilkes Booth ermordet."

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("polished_recognition_prompts", 0).edit().clear().commit()
        ctx.getSharedPreferences("known_apps", 0).edit().clear().commit()

        settingsStore = SettingsStore(ctx)
        promptStore = PromptStore(ctx)
        // Fast-backoff runner: the #116 retry policy (1 s / 2 s) must not
        // slow the failure-path tests; production keeps the real default.
        pipeline = TranscriptionPipeline(
            getSttApi, getChatApi, promptStore, settingsStore,
            sttRunner = SttRequestRunner(getSttApi, backoffMs = listOf(0L, 0L))
        )

        val mp3Resource = javaClass.classLoader?.getResource("lincoln.mp3")
        val tempFile = File.createTempFile("lincoln", ".mp3")
        mp3Resource!!.openStream().use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        lincolnFile = tempFile

        settingsStore.sttProvider = SttProviderConfig(
            displayName = "GROQ",
            baseUrl = "https://api.groq.com/openai/v1/",
            apiToken = "gsk_test",
            model = "whisper-large-v3-turbo"
        )
        settingsStore.llmProvider = LlmProviderConfig(
            displayName = "GROQ LLM",
            baseUrl = "https://api.groq.com/openai/v1/",
            apiToken = "gsk_test",
            model = "llama-3.3-70b-versatile"
        )
        // Existing tests assert exact (unwrapped) text — wrapping is covered by
        // the dedicated tests below, so disable it by default here (#81).
        settingsStore.wrapWidth = 0
    }

    @After
    fun tearDown() {
        lincolnFile.delete()
    }

    private fun <T> mockCall(response: Response<T>): Call<T> {
        val call = mockk<Call<T>>()
        every { call.execute() } returns response
        return call
    }

    private fun mockSttSuccess() {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = "german")))
    }

    private fun mockSttSuccessNoLanguage() {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = null)))
    }

    private fun mockSttSuccessWithLanguageProbability() {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = "german", languageProbability = 0.87f)))
    }

    private fun mockSttSuccessIsoCodeWithProbability() {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = "de", languageProbability = 0.99f)))
    }

    private fun mockSttSuccessPadded() {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = "  \n$lincolnGermanText\n  ", language = "german")))
    }

    private fun mockChatSuccess(content: String = "Cleaned text") {
        every { chatApi.chatSync(any(), any<ChatRequest>()) } returns
            mockCall(Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", content))))))
    }

    private fun mockChatSuccessWithCapture(requestSlot: CapturingSlot<ChatRequest>) {
        every { chatApi.chatSync(any(), capture(requestSlot)) } returns
            mockCall(Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", "ok"))))))
    }

    @Test
    fun `STT success with raw mode returns whisper text directly`() = runBlocking {
        settingsStore.rawMode = true
        mockSttSuccess()

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `STT text is trimmed before raw mode return`() = runBlocking {
        settingsStore.rawMode = true
        mockSttSuccessPadded()

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `raw mode writes stt-text log but no llm-prompt log`() = runBlocking {
        settingsStore.rawMode = true
        mockSttSuccess()

        val logDir = tmp.newFolder("rawlogs")
        val loggingPipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore, RotatingJsonLogger(logDir))
        loggingPipeline.transcribe(listOf(lincolnFile))

        val llmFiles = logDir.listFiles().orEmpty().filter { it.name.startsWith("llm-prompt") }
        assertThat(llmFiles).isEmpty()
        val sttLog = File(logDir, "stt-text.json").readText()
        assertThat(sttLog).contains("\"chunkCount\": 1")
        assertThat(sttLog).contains(lincolnGermanText.trim())
    }

    @Test
    fun `stt-text log carries chunk evidence and language`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccessWithLanguageProbability()
        mockChatSuccess()

        val logDir = tmp.newFolder("sttlogs")
        val loggingPipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore, RotatingJsonLogger(logDir))
        loggingPipeline.transcribe(listOf(lincolnFile))

        val sttLog = File(logDir, "stt-text.json").readText()
        assertThat(sttLog).contains("\"chunkCount\": 1")
        assertThat(sttLog).contains("\"language\": \"german\"")
        assertThat(sttLog).contains("0.87")
        assertThat(sttLog).contains(lincolnGermanText.trim())
    }

    @Test
    fun `LLM mode logs the exact ChatRequest sent to the LLM`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        val logDir = tmp.newFolder("llmlogs")
        val loggingPipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore, RotatingJsonLogger(logDir))
        loggingPipeline.transcribe(listOf(lincolnFile))

        val logged = File(logDir, "llm-prompt.json").readText()
        assertThat(logged).isEqualTo(GsonBuilder().setPrettyPrinting().create().toJson(requestSlot.captured))
        assertThat(logged).contains("The STT service transcribed audio spoken in German.")
        assertThat(logged).contains(lincolnGermanText)
    }

    @Test
    fun `STT text is trimmed before substitution into user prompt`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccessPadded()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `STT success with LLM mode returns processed text`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        mockChatSuccess("Cleaned text")

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("Cleaned text")
    }

    @Test
    fun `source_language_clause resolved from Whisper language field`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("The STT service transcribed audio spoken in German.")
        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).doesNotContain("STT service")
    }

    @Test
    fun `source_language_clause omitted when Whisper returns no language`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccessNoLanguage()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).doesNotContain("transcribed audio spoken in")
        assertThat(systemMessage).doesNotContain("{{source_language_clause}}")
    }

    @Test
    fun `source_language_clause maps ISO code to display name with probability`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccessIsoCodeWithProbability()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("The Whisper service detected the recognized language as German with a probability of 99 percent.")
    }

    @Test
    fun `source_language_clause uses probability when provider returns full name`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccessWithLanguageProbability()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("The Whisper service detected the recognized language as German with a probability of 87 percent.")
    }

    @Test
    fun `target_language_clause injected when targetLanguage is set`() = runBlocking {
        settingsStore.rawMode = false
        settingsStore.targetLanguage = "English"
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("IMPORTANT: Write your output in English")
    }

    @Test
    fun `target_language_clause not injected when targetLanguage is null`() = runBlocking {
        settingsStore.rawMode = false
        settingsStore.targetLanguage = null
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).doesNotContain("IMPORTANT: Write your output in")
        assertThat(systemMessage).contains("German")
    }

    @Test
    fun `user message contains only the Whisper output`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `system prompt passed as system message to chat API`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(listOf(lincolnFile))

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("post-process voice dictation")
        assertThat(systemMessage).contains("Return only the cleaned-up transcription")
    }

    @Test
    fun `STT HTTP error returns failure`() = runBlocking {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            @Suppress("DEPRECATION")
            mockCall(Response.error(500, ResponseBody.create(null, "Server Error")))

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("HTTP 500")
    }

    @Test
    fun `ogg file is uploaded as ogg with ogg filename`() = runBlocking {
        settingsStore.rawMode = true
        mockSttSuccess()
        val partSlot = slot<MultipartBody.Part>()
        every { sttApi.transcribeAudioSync(any(), capture(partSlot), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = null)))
        val oggFile = File(tmp.root, "recording.ogg").apply { writeBytes(ByteArray(8)) }

        pipeline.transcribe(listOf(oggFile))

        assertThat(partSlot.captured.body.contentType()).isEqualTo("audio/ogg".toMediaTypeOrNull())
        assertThat(partSlot.captured.headers.toString()).contains("filename=\"audio.ogg\"")
    }

    @Test
    fun `wav file is uploaded as wav with wav filename`() = runBlocking {
        settingsStore.rawMode = true
        mockSttSuccess()
        val partSlot = slot<MultipartBody.Part>()
        every { sttApi.transcribeAudioSync(any(), capture(partSlot), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = null)))
        val wavFile = File(tmp.root, "recording.wav").apply { writeBytes(ByteArray(8)) }

        pipeline.transcribe(listOf(wavFile))

        assertThat(partSlot.captured.body.contentType()).isEqualTo("audio/wav".toMediaTypeOrNull())
        assertThat(partSlot.captured.headers.toString()).contains("filename=\"audio.wav\"")
    }

    @Test
    fun `LLM HTTP error returns failure`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        every { chatApi.chatSync(any(), any<ChatRequest>()) } returns
            @Suppress("DEPRECATION")
            mockCall(Response.error(503, ResponseBody.create(null, "Unavailable")))

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("HTTP 503")
    }

    @Test
    fun `raw mode wraps output at configured width`() = runBlocking {
        settingsStore.rawMode = true
        settingsStore.wrapWidth = 80
        mockSttSuccess()

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(LineWrapPolicy.wrap(lincolnGermanText, 80))
        assertThat(result.getOrNull()).contains("\n")
    }

    @Test
    fun `LLM mode wraps output at configured width`() = runBlocking {
        settingsStore.rawMode = false
        settingsStore.wrapWidth = 90
        mockSttSuccess()
        mockChatSuccess(lincolnGermanText)

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(LineWrapPolicy.wrap(lincolnGermanText, 90))
    }

    @Test
    fun `wrap width zero returns output unwrapped`() = runBlocking {
        settingsStore.rawMode = true
        settingsStore.wrapWidth = 0
        mockSttSuccess()

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `per-app override wins over the global wrap width`() = runBlocking {
        settingsStore.rawMode = true
        settingsStore.wrapWidth = 35
        RuntimeEnvironment.getApplication().getSharedPreferences("known_apps", 0)
            .edit()
            .putString(
                "known_apps",
                """{"com.microsoft.office.outlook":{"label":"Outlook","lastSeenMs":1,"wrapWidth":120}}"""
            )
            .commit()
        mockSttSuccess()

        val result = pipeline.transcribe(listOf(lincolnFile), "com.microsoft.office.outlook")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(LineWrapPolicy.wrap(lincolnGermanText, 120))
    }

    @Test
    fun `null caller package uses the global wrap width`() = runBlocking {
        settingsStore.rawMode = true
        settingsStore.wrapWidth = 35
        mockSttSuccess()

        val result = pipeline.transcribe(listOf(lincolnFile), null)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(LineWrapPolicy.wrap(lincolnGermanText, 35))
    }

    /**
     * Chunked uploads (#115): each chunk gets its own STT call, chunk texts
     * join with a single space, one LLM call runs over the joined text, and
     * the language clause comes from the first chunk reporting a language.
     */
    @Test
    fun `chunked files are transcribed per chunk and joined`() = runBlocking {
        settingsStore.rawMode = false
        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } answers {
            callIndex++
            val text = if (callIndex == 1) "Part one." else "Part two."
            val language = if (callIndex == 1) "german" else null
            mockCall(Response.success(SttResponse(text = text, language = language)))
        }
        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        val file1 = File(tmp.root, "chunk1.mp3").apply { writeBytes(ByteArray(8)) }
        val file2 = File(tmp.root, "chunk2.mp3").apply { writeBytes(ByteArray(8)) }

        val result = pipeline.transcribe(listOf(file1, file2))

        assertThat(result.isSuccess).isTrue()
        verify(exactly = 2) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).isEqualTo("Part one. Part two.")
        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("The STT service transcribed audio spoken in German.")
    }

    @Test
    fun `chunked STT failure names the failing chunk`() = runBlocking {
        settingsStore.rawMode = true
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returnsMany
            listOf(
                mockCall(Response.success(SttResponse(text = "part one", language = null))),
                mockCall(
                    @Suppress("DEPRECATION")
                    Response.error(500, ResponseBody.create(null, "Server Error"))
                )
            )

        val file1 = File(tmp.root, "chunk1.mp3").apply { writeBytes(ByteArray(8)) }
        val file2 = File(tmp.root, "chunk2.mp3").apply { writeBytes(ByteArray(8)) }

        val result = pipeline.transcribe(listOf(file1, file2))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("chunk 2/2")
        assertThat(result.exceptionOrNull()!!.message!!).contains("HTTP 500")
    }

    @Test
    fun `chunked upload logs per-chunk evidence in stt-text`() = runBlocking {
        settingsStore.rawMode = true
        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } answers {
            callIndex++
            val text = if (callIndex == 1) "Part one." else "Part two."
            mockCall(Response.success(SttResponse(text = text, language = null)))
        }

        val logDir = tmp.newFolder("chunklogs")
        val loggingPipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore, RotatingJsonLogger(logDir))
        val file1 = File(tmp.root, "chunk1.mp3").apply { writeBytes(ByteArray(8)) }
        val file2 = File(tmp.root, "chunk2.mp3").apply { writeBytes(ByteArray(8)) }

        loggingPipeline.transcribe(listOf(file1, file2))

        val sttLog = File(logDir, "stt-text.json").readText()
        @Suppress("UNCHECKED_CAST")
        val parsed = com.google.gson.Gson().fromJson(sttLog, Map::class.java) as Map<String, Any>
        assertThat((parsed["chunkCount"] as Double).toInt()).isEqualTo(2)
        assertThat((parsed["chunkLengths"] as List<Double>).map { it.toInt() }).containsExactly(9, 9)
        assertThat(parsed["text"] as String).isEqualTo("Part one. Part two.")
    }

    /**
     * Upload evidence (#115): each chunk upload records file name + size +
     * model + endpoint to `stt-upload.json` (rotated) — on a read-timeout no
     * response ever arrives, so this is the only proof the request was sent.
     */
    @Test
    fun `each chunk upload is logged with its size and endpoint`() = runBlocking {
        settingsStore.rawMode = true
        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } answers {
            callIndex++
            mockCall(Response.success(SttResponse(text = "part $callIndex", language = null)))
        }

        val logDir = tmp.newFolder("uploadlogs")
        val loggingPipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore, RotatingJsonLogger(logDir))
        val file1 = File(tmp.root, "recording_1.ogg").apply { writeBytes(ByteArray(8)) }
        val file2 = File(tmp.root, "recording_2.ogg").apply { writeBytes(ByteArray(8)) }

        loggingPipeline.transcribe(listOf(file1, file2))

        val second = com.google.gson.Gson().fromJson(File(logDir, "stt-upload.json").readText(), Map::class.java)
        val first = com.google.gson.Gson().fromJson(File(logDir, "stt-upload_1.json").readText(), Map::class.java)
        assertThat(second["chunk"]).isEqualTo(2.0)
        assertThat(first["chunk"]).isEqualTo(1.0)
        assertThat(first["file"]).isEqualTo("recording_1.ogg")
        assertThat(first["bytes"]).isEqualTo(8.0)
        assertThat(first["mediaType"]).isEqualTo("audio/ogg")
        assertThat(first["model"]).isEqualTo("whisper-large-v3-turbo")
        assertThat(first["baseUrl"]).isEqualTo("https://api.groq.com/openai/v1/")
    }

    /**
     * #116: a transient HTTP 500 is retried inside the pipeline (1 attempt
     * here thanks to the fast-backoff test runner) and the second call wins.
     */
    @Test
    fun `transient STT failure retries then succeeds through the pipeline`() = runBlocking {
        settingsStore.rawMode = true
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returnsMany
            listOf(
                mockCall(
                    @Suppress("DEPRECATION")
                    Response.error(500, ResponseBody.create(null, "Server Error"))
                ),
                mockCall(Response.success(SttResponse(text = lincolnGermanText, language = null)))
            )

        val result = pipeline.transcribe(listOf(lincolnFile))

        assertThat(result.isSuccess).isTrue()
        verify(exactly = 2) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
    }

    /**
     * #116: per-attempt completion records land in the dedicated
     * `stt-latency.json` stream — `durationMs` from the WAV header, HTTP
     * code and attempt number; the pre-send `stt-upload.json` entry stays
     * unchanged (#115 semantics).
     */
    @Test
    fun `stt latency log records duration and http code per attempt`() = runBlocking {
        settingsStore.rawMode = true
        val wav = File(tmp.root, "chunk.wav")
            .apply { writeBytes(WavWriter.write(ByteArray(16_000), sampleRate = 16_000)) }
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returnsMany
            listOf(
                mockCall(
                    @Suppress("DEPRECATION")
                    Response.error(500, ResponseBody.create(null, "Server Error"))
                ),
                mockCall(Response.success(SttResponse(text = "ok", language = null)))
            )

        val logDir = tmp.newFolder("latencylogs")
        val loggingPipeline = TranscriptionPipeline(
            getSttApi, getChatApi, promptStore, settingsStore,
            RotatingJsonLogger(logDir),
            SttRequestRunner(getSttApi, RotatingJsonLogger(logDir), backoffMs = listOf(0L, 0L))
        )

        val result = loggingPipeline.transcribe(listOf(wav))

        assertThat(result.isSuccess).isTrue()
        val latest = com.google.gson.Gson()
            .fromJson(File(logDir, "stt-latency.json").readText(), Map::class.java)
        assertThat(latest["event"]).isEqualTo("complete")
        assertThat((latest["durationMs"] as Double).toLong()).isEqualTo(500L)
        assertThat((latest["httpCode"] as Double).toInt()).isEqualTo(200)
        assertThat((latest["attempt"] as Double).toInt()).isEqualTo(2)
        val first = com.google.gson.Gson()
            .fromJson(File(logDir, "stt-latency_1.json").readText(), Map::class.java)
        assertThat((first["httpCode"] as Double).toInt()).isEqualTo(500)
        // the upload stream keeps its legacy shape
        assertThat(File(logDir, "stt-upload.json").readText()).contains("chunk")
        assertThat(File(logDir, "stt-upload.json").readText()).doesNotContain("durationMs")
    }
}
