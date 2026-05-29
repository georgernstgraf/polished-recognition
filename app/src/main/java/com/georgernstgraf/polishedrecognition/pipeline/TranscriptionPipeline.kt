package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import com.georgernstgraf.polishedrecognition.config.LanguageMapper
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class TranscriptionPipeline(
    private val getSttApi: (String) -> OpenAiSttApiService,
    private val getChatApi: (String) -> OpenAiChatApiService,
    private val promptStore: PromptStore,
    private val settingsStore: SettingsStore
) {

    data class SttResult(
        val text: String,
        val languageCode: String? = null
    )

    suspend fun transcribe(wavFile: File): Result<String> = withContext(Dispatchers.IO) {
        val sttConfig = settingsStore.sttProvider
            ?: return@withContext Result.failure(Exception("STT provider not configured"))

        val rawMode = settingsStore.rawMode
        val targetLanguage = settingsStore.targetLanguage

        val sttResult = runStt(wavFile, sttConfig)
        if (sttResult.isFailure) return@withContext Result.failure(
            Exception("STT transcription failed: ${sttResult.exceptionOrNull()?.message}")
        )

        val whisper = sttResult.getOrThrow()
        val sourceLanguageName = LanguageMapper.mapCodeToName(whisper.languageCode)

        if (rawMode) return@withContext Result.success(whisper.text)

        val llmConfig = settingsStore.llmProvider
            ?: return@withContext Result.failure(Exception("LLM provider not configured"))

        val systemPrompt = promptStore.systemPrompt
        val userTemplate = promptStore.userPromptTemplate

        val translatePrompt = if (targetLanguage != null) {
            promptStore.translatePromptTemplate.replace("{{target_language}}", LanguageMapper.mapCodeToName(targetLanguage))
        } else {
            ""
        }

        val userPrompt = userTemplate
            .replace("{{source_language}}", sourceLanguageName)
            .replace("{{translate_prompt}}", translatePrompt)
            .replace("{{text}}", whisper.text)

        val request = ChatRequest(
            model = llmConfig.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            )
        )

        val response = getChatApi(llmConfig.baseUrl).chatSync(
            authorization = "Bearer ${llmConfig.apiToken}",
            request = request
        ).execute()

        if (!response.isSuccessful || response.body() == null) {
            return@withContext Result.failure(Exception("LLM post-processing failed: HTTP ${response.code()}"))
        }

        Result.success(response.body()!!.getContent().trim())
    }

    private suspend fun runStt(wavFile: File, config: SttProviderConfig): Result<SttResult> {
        val requestFile = wavFile.asRequestBody("audio/wav".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", "audio.wav", requestFile)
        val modelPart = config.model.toRequestBody("text/plain".toMediaTypeOrNull())
        val responseFormatPart = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull())

        val response = getSttApi(config.baseUrl).transcribeAudioSync(
            authorization = "Bearer ${config.apiToken}",
            file = filePart,
            model = modelPart,
            responseFormat = responseFormatPart
        ).execute()

        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("HTTP ${response.code()}"))
        }

        val body = response.body()!!
        return Result.success(SttResult(text = body.text, languageCode = body.language))
    }
}
