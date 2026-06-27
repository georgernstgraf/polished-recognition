package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.ChatChoice
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import com.georgernstgraf.polishedrecognition.api.dto.ChatResponse
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
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

        settingsStore = SettingsStore(ctx)
        promptStore = PromptStore(ctx)
        pipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore)

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
        every { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = "german")))
    }

    private fun mockSttSuccessNoLanguage() {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns
            mockCall(Response.success(SttResponse(text = lincolnGermanText, language = null)))
    }

    private fun mockSttSuccessPadded() {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns
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

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `STT text is trimmed before raw mode return`() = runBlocking {
        settingsStore.rawMode = true
        mockSttSuccessPadded()

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `raw mode writes no prompt log`() = runBlocking {
        settingsStore.rawMode = true
        mockSttSuccess()

        val logDir = tmp.newFolder("rawlogs")
        val loggingPipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore, PromptLogger(logDir))
        loggingPipeline.transcribe(lincolnFile)

        val jsonFiles = logDir.listFiles().orEmpty().filter { it.extension == "json" }
        assertThat(jsonFiles).isEmpty()
    }

    @Test
    fun `LLM mode logs the exact ChatRequest sent to the LLM`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        val logDir = tmp.newFolder("llmlogs")
        val loggingPipeline = TranscriptionPipeline(getSttApi, getChatApi, promptStore, settingsStore, PromptLogger(logDir))
        loggingPipeline.transcribe(lincolnFile)

        val logged = File(logDir, "prompt.json").readText()
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

        pipeline.transcribe(lincolnFile)

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `STT success with LLM mode returns processed text`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        mockChatSuccess("Cleaned text")

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("Cleaned text")
    }

    @Test
    fun `source_language_clause resolved from Whisper language field`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(lincolnFile)

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

        pipeline.transcribe(lincolnFile)

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).doesNotContain("transcribed audio spoken in")
        assertThat(systemMessage).doesNotContain("{{source_language_clause}}")
    }

    @Test
    fun `target_language_clause injected when targetLanguage is set`() = runBlocking {
        settingsStore.rawMode = false
        settingsStore.targetLanguage = "English"
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(lincolnFile)

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("Please produce the output")
    }

    @Test
    fun `target_language_clause not injected when targetLanguage is null`() = runBlocking {
        settingsStore.rawMode = false
        settingsStore.targetLanguage = null
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(lincolnFile)

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).doesNotContain("Please produce the output")
        assertThat(systemMessage).contains("German")
    }

    @Test
    fun `user message contains only the Whisper output`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(lincolnFile)

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).isEqualTo(lincolnGermanText)
    }

    @Test
    fun `system prompt passed as system message to chat API`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        mockChatSuccessWithCapture(requestSlot)

        pipeline.transcribe(lincolnFile)

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("transcription post-processor")
        assertThat(systemMessage).contains("Return only the requested output text")
    }

    @Test
    fun `STT HTTP error returns failure`() = runBlocking {
        every { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns
            @Suppress("DEPRECATION")
            mockCall(Response.error(500, ResponseBody.create(null, "Server Error")))

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("HTTP 500")
    }

    @Test
    fun `LLM HTTP error returns failure`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        every { chatApi.chatSync(any(), any<ChatRequest>()) } returns
            @Suppress("DEPRECATION")
            mockCall(Response.error(503, ResponseBody.create(null, "Unavailable")))

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("HTTP 503")
    }
}
