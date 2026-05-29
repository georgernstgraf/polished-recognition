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
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

        val mp3Resource = javaClass.classLoader.getResource("lincoln.mp3")
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

    private fun mockSttSuccess() {
        coEvery { sttApi.transcribeAudio(any(), any(), any(), any()) } returns
            Response.success(SttResponse(text = lincolnGermanText, language = "de"))
    }

    private fun mockChatSuccess(content: String = "Cleaned text") {
        coEvery { chatApi.chat(any(), any<ChatRequest>()) } returns
            Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", content)))))
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
    fun `STT success with LLM mode returns processed text`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        mockChatSuccess("Cleaned text")

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("Cleaned text")
    }

    @Test
    fun `source_language resolved from Whisper language field`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        coEvery { chatApi.chat(any(), capture(requestSlot)) } returns
            Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", "ok")))))

        pipeline.transcribe(lincolnFile)

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).contains("German")
    }

    @Test
    fun `translate_prompt injected when targetLanguage is set`() = runBlocking {
        settingsStore.rawMode = false
        settingsStore.targetLanguage = "English"
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        coEvery { chatApi.chat(any(), capture(requestSlot)) } returns
            Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", "ok")))))

        pipeline.transcribe(lincolnFile)

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).contains("Please produce the output")
    }

    @Test
    fun `translate_prompt not injected when targetLanguage is null`() = runBlocking {
        settingsStore.rawMode = false
        settingsStore.targetLanguage = null
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        coEvery { chatApi.chat(any(), capture(requestSlot)) } returns
            Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", "ok")))))

        pipeline.transcribe(lincolnFile)

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).doesNotContain("Please produce the output")
        assertThat(userMessage).contains("German")
    }

    @Test
    fun `text replaced with Whisper output`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        coEvery { chatApi.chat(any(), capture(requestSlot)) } returns
            Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", "ok")))))

        pipeline.transcribe(lincolnFile)

        val userMessage = requestSlot.captured.messages.find { it.role == "user" }?.content ?: ""
        assertThat(userMessage).contains("Bürgerkrieg")
        assertThat(userMessage).contains("Emanzipationsproklamation")
    }

    @Test
    fun `system prompt passed as system message to chat API`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()

        val requestSlot = slot<ChatRequest>()
        coEvery { chatApi.chat(any(), capture(requestSlot)) } returns
            Response.success(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", "ok")))))

        pipeline.transcribe(lincolnFile)

        val systemMessage = requestSlot.captured.messages.find { it.role == "system" }?.content ?: ""
        assertThat(systemMessage).contains("transcription post-processor")
        assertThat(systemMessage).contains("Return only the requested output text")
    }

    @Test
    fun `STT HTTP error returns failure`() = runBlocking {
        coEvery { sttApi.transcribeAudio(any(), any(), any(), any()) } returns
            Response.error(500, ResponseBody.create(null, "Server Error"))

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("HTTP 500")
    }

    @Test
    fun `LLM HTTP error returns failure`() = runBlocking {
        settingsStore.rawMode = false
        mockSttSuccess()
        coEvery { chatApi.chat(any(), any<ChatRequest>()) } returns
            Response.error(503, ResponseBody.create(null, "Unavailable"))

        val result = pipeline.transcribe(lincolnFile)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("HTTP 503")
    }
}
