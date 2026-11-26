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
import retrofit2.Response
import java.io.File

class TranscriptionPipeline(
    private val getSttApi: (String) -> OpenAiSttApiService,
    private val getChatApi: (String) -> OpenAiChatApiService,
    private val promptStore: PromptStore,
    private val settingsStore: SettingsStore,
    private val logger: RotatingJsonLogger? = null,
    private val sttRunner: SttRequestRunner = SttRequestRunner(getSttApi, logger),
    /**
     * #186: the same network gate the STT runner uses. The polish call is a
     * network request too, so a closed gate must not spend a failed round-trip
     * on it: the RAW text is stored (the half that cost the recording) and the
     * failure is [SttFailure.OFFLINE] — "saved", not "broken". Default `true`
     * keeps every existing construction unchanged.
     */
    private val isOnline: () -> Boolean = { true }
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
        /**
         * #116 (owner decision 2026-11-12): the AUDIO LENGTH of the fragment
         * currently being transcribed — not the backlog. The stage line reads
         * "STT · 16 s Audio" and always describes the running request.
         */
        data class RequestingSttFragment(val audioSeconds: Int) : TranscriptionStage()
        data class RequestingLlm(val wordCount: Int) : TranscriptionStage()
    }

    /** The shared per-request STT engine (#116) — the live fragment worker runs on it too. */
    val sttRequestRunner: SttRequestRunner get() = sttRunner

    suspend fun transcribe(
        audioFiles: List<File>,
        callerPackage: String? = null,
        onStageChange: ((TranscriptionStage) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val sttConfig = settingsStore.sttProvider
            ?: return@withContext Result.failure(Exception("STT provider not configured"))

        onStageChange?.invoke(TranscriptionStage.RequestingStt)
        finishTranscription(runStt(audioFiles, sttConfig), callerPackage, onStageChange)
    }

    /**
     * #142 forensic hook: set ONCE by the controller (which owns the live
     * session bundle) — invoked at the END of every [finishTranscription]
     * with the outcome (raw text + the LLM pass). The closure reads the
     * CURRENT session bundle, so per-session wiring is unnecessary. The
     * theoretical multi-controller race (a study controller + the IME
     * controller on the
     * SAME pipeline instance) cannot happen in practice: only one
     * PROCESSING session at a time.
     */
    /**
     * #142: what the pipeline DID with the session's raw input — the raw
     * transcript, whether polish ran, and the full LLM pass (prompts +
     * response, or the error). The forensic record consumes it.
     * #146: extracted to its own top-level file in :core so [SessionBundle]
     * can reference it without dragging :app's pipeline wiring along;
     * same package, type unchanged.
     */
    var onOutcome: ((PipelineOutcome) -> Unit)? = null

    /**
     * #168 (epic #166 D7): a **session-scoped** raw override the controller
     * sets for the duration of one run — the IME forces raw while dictating
     * into the app's own Workbench. Never persisted, never written to
     * `SettingsStore.rawMode`; the controller resets it when the run ends.
     */
    @Volatile
    var forceRawMode: Boolean = false

    /**
     * The post-STT half of [transcribe], exposed for the live fragment
     * worker path (#116): the worker has already transcribed the committed
     * fragments DURING dictation and the controller hands in the ordered
     * joined [SttResult] — what remains here is the stt-text evidence, the
     * language clauses, the raw-mode branch and the single full-context LLM
     * pass (owner decision: the polish always sees the whole transcript in
     * one piece).
     */
    suspend fun finishTranscription(
        sttResult: Result<SttResult>,
        callerPackage: String? = null,
        onStageChange: ((TranscriptionStage) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (sttResult.isFailure) return@withContext Result.failure(
            Exception("STT transcription failed: ${sttResult.exceptionOrNull()?.message}")
        )

        val raw = sttResult.getOrThrow()
        val rawMode = settingsStore.rawMode || forceRawMode
        val targetLanguage = settingsStore.targetLanguage
        val wrapWidth = settingsStore.wrapWidthFor(callerPackage)
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

        val targetLanguageClause = targetLanguageClause(targetLanguage)

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

        if (rawMode) {
            onOutcome?.invoke(PipelineOutcome(rawText = whisper.text, rawMode = true))
            return@withContext Result.success(LineWrapPolicy.wrap(whisper.text, wrapWidth))
        }

        val llmConfig = settingsStore.llmProvider
            ?: return@withContext Result.failure(Exception("LLM provider not configured"))

        onStageChange?.invoke(
            TranscriptionStage.RequestingLlm(
                whisper.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            )
        )

        // #186: no request without a validated network. The raw text is the
        // outcome (it is stored by the caller), so the session loses nothing —
        // the polish is the part that waits.
        if (!isOnline()) {
            val failure = offlineFailure()
            onOutcome?.invoke(
                PipelineOutcome(
                    rawText = whisper.text,
                    rawMode = false,
                    llmError = failure.message
                )
            )
            return@withContext Result.failure(failure)
        }

        val request = ChatRequest(
            model = llmConfig.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            ),
            reasoningEffort = llmConfig.reasoningEffort
        )

        logger?.log("llm-prompt", GsonBuilder().setPrettyPrinting().create().toJson(request))

        val response = getChatApi(llmConfig.baseUrl).chatSync(
            authorization = "Bearer ${llmConfig.apiToken}",
            request = request
        ).execute()

        if (!response.isSuccessful || response.body() == null) {
            // #75: the polish call speaks the same language as the STT call —
            // the provider's own error body is parsed, its class travels (a
            // budget wall is a BUDGET here too) and its sentence rides the
            // message. The RAW text is still stored (rawText below), unchanged.
            val failure = llmFailure(response)
            onOutcome?.invoke(
                PipelineOutcome(
                    rawText = whisper.text,
                    rawMode = false,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    llmError = failure.message
                )
            )
            return@withContext Result.failure(failure)
        }

        val polished = response.body()!!.getContent().trim()
        onOutcome?.invoke(
            PipelineOutcome(
                rawText = whisper.text,
                rawMode = false,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                llmResponse = polished
            )
        )
        Result.success(LineWrapPolicy.wrap(polished, wrapWidth))
    }

    /**
     * #171 (epic #166 D9): the Workbench's manual **re-polish** — one explicit
     * raw text through the SAME prompt assembly and chat call as
     * [finishTranscription], with a per-dictation target language.
     *
     * Deliberately independent of `raw_mode` (the Workbench asks for a polish
     * explicitly) and it never writes `targetLanguage` or any preference: the
     * spinner's choice lives for this dictation only. Returns the trimmed
     * model answer; the caller owns the polished slot and the clipboard.
     */
    suspend fun polish(rawText: String, targetLanguage: String?): Result<String> =
        withContext(Dispatchers.IO) {
            val text = rawText.trim()
            if (text.isEmpty()) return@withContext Result.failure(
                Exception("nothing to polish")
            )
            val llmConfig = settingsStore.llmProvider ?: return@withContext Result.failure(
                Exception("LLM provider not configured")
            )
            // #186: the manual re-polish is a request as well — offline it
            // fails as OFFLINE instead of pretending the provider refused.
            if (!isOnline()) return@withContext Result.failure(offlineFailure())
            // No STT result exists for a re-polish, so the source-language
            // clause is dropped (the prompt template treats "" as "omit").
            val systemPrompt = promptStore.systemPrompt
                .replace("{{source_language_clause}}", "")
                .replace("{{target_language_clause}}", targetLanguageClause(targetLanguage))
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            val userPrompt = promptStore.userPromptTemplate.replace("{{text}}", text)
            val request = ChatRequest(
                model = llmConfig.model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt)
                ),
                reasoningEffort = llmConfig.reasoningEffort
            )
            logger?.log("llm-prompt", GsonBuilder().setPrettyPrinting().create().toJson(request))
            val response = getChatApi(llmConfig.baseUrl).chatSync(
                authorization = "Bearer ${llmConfig.apiToken}",
                request = request
            ).execute()
            if (!response.isSuccessful || response.body() == null) {
                return@withContext Result.failure(llmFailure(response))
            }
            Result.success(response.body()!!.getContent().trim())
        }

    /**
     * #75: a refused chat call as the same carrier the STT path uses
     * ([SttRequestRunner.SttRequestException]) — `LLM post-processing failed:
     * HTTP 429 — <provider sentence>`, with the provider's error body parsed by
     * [ProviderError] so a budget/quota refusal is classified `BUDGET` on this
     * path too (both endpoints sit behind the same gateway and the same spend
     * cap). Falls back to the plain status line when the body says nothing.
     */
    private fun llmFailure(response: Response<*>): SttRequestRunner.SttRequestException {
        val providerError = ProviderError.parse(ProviderError.bodyOf(response))
        val base = "LLM post-processing failed: HTTP ${response.code()}"
        return SttRequestRunner.SttRequestException(
            providerError?.appendTo(base) ?: base,
            httpCode = response.code(),
            failure = SttFailureClassifier.classify(response.code(), providerError = providerError),
            providerError = providerError
        )
    }

    /**
     * #186: the offline refusal of the polish path — the same carrier an STT
     * failure uses, with the class the ring and the IME act on. No HTTP code:
     * nothing was asked.
     */
    private fun offlineFailure(): SttRequestRunner.SttRequestException =
        SttRequestRunner.SttRequestException(
            SttRequestRunner.OFFLINE_MESSAGE,
            httpCode = null,
            failure = SttFailure.OFFLINE
        )

    private fun targetLanguageClause(targetLanguage: String?): String =
        if (targetLanguage != null) {
            promptStore.targetLanguageClauseTemplate.replace("{{target_language}}", targetLanguage)
        } else {
            ""
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
