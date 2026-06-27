package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
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
    private val settingsStore: SettingsStore,
    private val promptLogger: PromptLogger? = null
) {

    data class SttResult(
        val text: String,
        val language: String? = null
    )

    sealed class TranscriptionStage {
        object RequestingStt : TranscriptionStage()
        data class RequestingLlm(val wordCount: Int) : TranscriptionStage()
    }

    suspend fun transcribe(
        wavFile: File,
        onStageChange: ((TranscriptionStage) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val sttConfig = settingsStore.sttProvider
            ?: return@withContext Result.failure(Exception("STT provider not configured"))

        val rawMode = settingsStore.rawMode
        val targetLanguage = settingsStore.targetLanguage

        onStageChange?.invoke(TranscriptionStage.RequestingStt)
        val sttResult = runStt(wavFile, sttConfig)
        if (sttResult.isFailure) return@withContext Result.failure(
            Exception("STT transcription failed: ${sttResult.exceptionOrNull()?.message}")
        )

        val raw = sttResult.getOrThrow()
        val whisper = raw.copy(text = raw.text.trim())

        val targetLanguageClause = if (targetLanguage != null) {
            promptStore.targetLanguageClauseTemplate.replace("{{target_language}}", targetLanguage)
        } else {
            ""
        }

        val sourceLanguageClause = if (isLanguageUnknown(whisper.language)) {
            ""
        } else {
            "The STT service transcribed audio spoken in ${capitalizeWords(whisper.language)}."
        }

        val systemPrompt = promptStore.systemPrompt
            .replace("{{source_language_clause}}", sourceLanguageClause)
            .replace("{{target_language_clause}}", targetLanguageClause)
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        val userPrompt = promptStore.userPromptTemplate
            .replace("{{text}}", whisper.text)

        promptLogger?.log(systemPrompt, userPrompt)

        if (rawMode) return@withContext Result.success(whisper.text)

        val llmConfig = settingsStore.llmProvider
            ?: return@withContext Result.failure(Exception("LLM provider not configured"))

        onStageChange?.invoke(
            TranscriptionStage.RequestingLlm(
                whisper.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            )
        )

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
        return Result.success(SttResult(text = body.text, language = body.language))
    }

    companion object {
        private fun isLanguageUnknown(raw: String?): Boolean {
            val v = raw?.trim()
            return v.isNullOrBlank() || v.equals("unknown", ignoreCase = true)
        }

        private fun capitalizeWords(input: String?): String {
            if (input == null) return "unknown"
            return input.trim()
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }
        }
    }
}
