package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import com.georgernstgraf.polishedrecognition.config.LanguageMapper
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class TranscriptionPipeline(
    private val getSttApi: (String) -> OpenAiSttApiService,
    private val getChatApi: (String) -> OpenAiChatApiService,
    private val promptStore: PromptStore,
    private val settingsStore: SettingsStore,
    private val logger: RotatingJsonLogger? = null,
    private val sttRunner: SttRequestRunner = SttRequestRunner(getSttApi, logger)
) {

    data class SttResult(
        val text: String,
        val language: String? = null,
        val languageProbability: Float? = null,
        val chunkCount: Int = 1,
        val chunkLengths: List<Int> = listOf(text.length)
    )

    sealed class TranscriptionStage {
        object CompressingAudio : TranscriptionStage()
        object RequestingStt : TranscriptionStage()
        data class RequestingLlm(val wordCount: Int) : TranscriptionStage()
    }

    suspend fun transcribe(
        audioFiles: List<File>,
        callerPackage: String? = null,
        onStageChange: ((TranscriptionStage) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val sttConfig = settingsStore.sttProvider
            ?: return@withContext Result.failure(Exception("STT provider not configured"))

        val rawMode = settingsStore.rawMode
        val targetLanguage = settingsStore.targetLanguage
        val wrapWidth = settingsStore.wrapWidthFor(callerPackage)

        onStageChange?.invoke(TranscriptionStage.RequestingStt)
        val sttResult = runStt(audioFiles, sttConfig)
        if (sttResult.isFailure) return@withContext Result.failure(
            Exception("STT transcription failed: ${sttResult.exceptionOrNull()?.message}")
        )

        val raw = sttResult.getOrThrow()
        val whisper = raw.copy(text = raw.text.trim())

        // Persistent STT evidence (#115): chunk count + per-chunk text
        // lengths + detected language land in `stt-text.json` (rotated like
        // `llm-prompt.json`) in BOTH modes — raw mode never reaches the
        // llm-prompt log, so without this a raw-mode long dictation would
        // leave no adb-readable trace of how many uploads happened.
        // `Log.i` is not enough: Oplus suppresses app logcat from the IME
        // process, but the /sdcard JSON logs stay adb-readable.
        logger?.log(
            "stt-text",
            GsonBuilder().setPrettyPrinting().create().toJson(
                SttLog(
                    chunkCount = raw.chunkCount,
                    chunkLengths = raw.chunkLengths,
                    language = raw.language,
                    languageProbability = raw.languageProbability,
                    textLength = whisper.text.length,
                    text = whisper.text
                )
            )
        )

        val targetLanguageClause = if (targetLanguage != null) {
            promptStore.targetLanguageClauseTemplate.replace("{{target_language}}", targetLanguage)
        } else {
            ""
        }

        val sourceLanguageClause = if (isLanguageUnknown(whisper.language)) {
            ""
        } else if (whisper.languageProbability != null) {
            "The Whisper service detected the recognized language as ${LanguageMapper.toDisplayName(whisper.language)} with a probability of ${Math.round(whisper.languageProbability * 100)} percent."
        } else {
            "The STT service transcribed audio spoken in ${LanguageMapper.toDisplayName(whisper.language)}."
        }

        val systemPrompt = promptStore.systemPrompt
            .replace("{{source_language_clause}}", sourceLanguageClause)
            .replace("{{target_language_clause}}", targetLanguageClause)
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        val userPrompt = promptStore.userPromptTemplate
            .replace("{{text}}", whisper.text)

        if (rawMode) return@withContext Result.success(LineWrapPolicy.wrap(whisper.text, wrapWidth))

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

        logger?.log("llm-prompt", GsonBuilder().setPrettyPrinting().create().toJson(request))

        val response = getChatApi(llmConfig.baseUrl).chatSync(
            authorization = "Bearer ${llmConfig.apiToken}",
            request = request
        ).execute()

        if (!response.isSuccessful || response.body() == null) {
            return@withContext Result.failure(Exception("LLM post-processing failed: HTTP ${response.code()}"))
        }

        Result.success(LineWrapPolicy.wrap(response.body()!!.getContent().trim(), wrapWidth))
    }

    /**
     * Transcribes one or more chunk files. Chunks (see `WavChunker`, #115)
     * are uploaded sequentially and their texts joined with a single space —
     * mirroring the sister project aitranscribe's `run_pipeline` in
     * `main.py`. A single LLM pass runs over the joined raw text, and the
     * language clause comes from the first chunk that reported a language.
     *
     * Each upload runs through [SttRequestRunner] (#116): retry ×3 on
     * transient failures only (1 s / 2 s backoff), per-attempt completion
     * records in `stt-latency.json`. The first (lowest-order) failing chunk
     * aborts the run, so the reported failure is always the lowest-order one.
     */
    private suspend fun runStt(audioFiles: List<File>, config: SttProviderConfig): Result<SttResult> {
        val transcripts = mutableListOf<String>()
        var language: String? = null
        var languageProbability: Float? = null
        audioFiles.forEachIndexed { index, audioFile ->
            val result = sttRunner.run(
                audioFile = audioFile,
                config = config,
                chunk = index + 1,
                chunkCount = audioFiles.size
            )
            val body = result.getOrElse { error ->
                val chunkInfo = if (audioFiles.size > 1) "chunk ${index + 1}/${audioFiles.size}: " else ""
                return Result.failure(Exception(chunkInfo + (error.message ?: "unknown error")))
            }
            transcripts.add(body.text)
            if (language == null && !body.language.isNullOrBlank()) {
                language = body.language
                languageProbability = body.languageProbability
            }
        }

        val text = transcripts.filter { it.isNotBlank() }.joinToString(" ").trim()
        return Result.success(
            SttResult(
                text = text,
                language = language,
                languageProbability = languageProbability,
                chunkCount = audioFiles.size,
                chunkLengths = transcripts.map { it.length }
            )
        )
    }

    /** Serializable STT evidence written to `stt-text.json` (#115). */
    private data class SttLog(
        val chunkCount: Int,
        val chunkLengths: List<Int>,
        val language: String?,
        val languageProbability: Float?,
        val textLength: Int,
        val text: String
    )

    companion object {
        private fun isLanguageUnknown(raw: String?): Boolean {
            val v = raw?.trim()
            return v.isNullOrBlank() || v.equals("unknown", ignoreCase = true)
        }
    }
}
