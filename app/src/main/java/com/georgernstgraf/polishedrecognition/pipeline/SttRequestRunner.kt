package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.AudioDuration
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.gson.GsonBuilder
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Runs ONE STT request for one audio file with the #116 retry policy: up to
 * [MAX_ATTEMPTS] attempts, retrying only transient failures (IOException /
 * timeouts, HTTP 5xx, HTTP 429) with a 1 s / 2 s backoff; auth/validation
 * 4xx and empty 2xx bodies fail immediately.
 *
 * Writes two evidence streams:
 * - `stt-upload.json` (unchanged #115 semantics): pre-send proof that the
 *   request went out, with size/model/endpoint — on a read-timeout no
 *   response ever arrives, so this is the only immediate proof of the send.
 * - `stt-latency.json` (new, #116): one completion record PER ATTEMPT with
 *   the audio duration (`durationMs`, parsed via [AudioDuration]), HTTP
 *   code, elapsed time and failure class. This is the Phase 2 per-provider
 *   latency-profile input (robust fit of t(S) ≈ a + b·S → auto fragment
 *   size + concurrency) and closes the #64 evidence gap ("&gt;2 min per
 *   600-s chunk" was neither provable nor refutable from the old logs).
 *
 * Completion records go into a dedicated rotation stream instead of extra
 * `stt-upload.json` entries: [RotatingJsonLogger] shifts history per call,
 * so two entries per request would halve the 9-slot depth of both streams.
 */
class SttRequestRunner(
    private val getSttApi: (String) -> OpenAiSttApiService,
    private val logger: RotatingJsonLogger? = null,
    private val backoffMs: List<Long> = listOf(1_000L, 2_000L),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * Successful-completion hook (#116 Phase 2): the per-provider latency
     * profile's sample feed. Invoked on the caller's context for every
     * successful request WITH evidence on (the shadow pass runs with
     * evidence off and must not pollute the profile). `durationMs` is the
     * uploaded audio duration (pre-roll included — the uploaded-size truth),
     * `elapsedMs` this single attempt's round-trip.
     */
    private val onCompletion: ((Completion) -> Unit)? = null
) {

    /** Successful request measurement (#116 Phase 2 profile sample). */
    data class Completion(
        val config: SttProviderConfig,
        val mediaType: String,
        val durationMs: Long?,
        val elapsedMs: Long,
        val attempt: Int
    )

    /** Final failure after all attempts; [httpCode] is set for HTTP failures. */
    class SttRequestException(message: String, val httpCode: Int? = null) : Exception(message)

    /**
     * Executes one upload. Fails fast on non-transient errors; otherwise
     * retries up to [MAX_ATTEMPTS] total attempts with the configured
     * backoff between attempts.
     *
     * [prompt] (#117): optional Whisper conditioning prompt — the tail of
     * the previous fragment's transcript. Conditioned text is NOT echoed
     * into the output, so no join-side dedup is needed.
     *
     * [evidence] = false suppresses the `stt-upload`/`stt-latency` records
     * (used by the #117 shadow comparison, whose requests must not pollute
     * the Phase 2 latency-profile input streams).
     */
    suspend fun run(
        audioFile: File,
        config: SttProviderConfig,
        chunk: Int? = null,
        chunkCount: Int? = null,
        prompt: String? = null,
        evidence: Boolean = true
    ): Result<SttResponse> {
        val mediaType = if (isOgg(audioFile)) "audio/ogg" else "audio/wav"
        val uploadName = if (isOgg(audioFile)) "audio.ogg" else "audio.wav"
        val durationMs = AudioDuration.estimateMs(audioFile)

        // Pre-send evidence (#115 semantics, unchanged shape).
        if (evidence) logger?.log(
            "stt-upload",
            gson(SttRequestLog(
                chunk = chunk,
                chunkCount = chunkCount,
                file = audioFile.name,
                bytes = audioFile.length(),
                mediaType = mediaType,
                model = config.model,
                baseUrl = config.baseUrl
            ))
        )

        var lastFailure: SttRequestException? = null
        repeat(MAX_ATTEMPTS) { zeroBased ->
            val attempt = zeroBased + 1
            val startedAt = System.nanoTime()
            try {
                val response = getSttApi(config.baseUrl).transcribeAudioSync(
                    authorization = "Bearer ${config.apiToken}",
                    file = MultipartBody.Part.createFormData(
                        "file", uploadName, audioFile.asRequestBody(mediaType.toMediaTypeOrNull())
                    ),
                    model = config.model.toRequestBody("text/plain".toMediaTypeOrNull()),
                    responseFormat = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull()),
                    prompt = prompt?.toRequestBody("text/plain".toMediaTypeOrNull())
                ).execute()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                if (response.isSuccessful && response.body() != null) {
                    logCompletion(audioFile, config, mediaType, durationMs, attempt, response.code(), elapsedMs, null, prompt, evidence)
                    if (evidence && durationMs != null) {
                        onCompletion?.invoke(Completion(config, mediaType, durationMs, elapsedMs, attempt))
                    }
                    return Result.success(response.body()!!)
                }
                val code = response.code()
                val errorKind = if (response.isSuccessful) "emptyBody" else null
                logCompletion(audioFile, config, mediaType, durationMs, attempt, code, elapsedMs, errorKind, prompt, evidence)
                val failure = if (response.isSuccessful) {
                    SttRequestException("HTTP $code (empty response body)", httpCode = code)
                } else {
                    SttRequestException("HTTP $code", httpCode = code)
                }
                if (!isTransientHttp(code)) return Result.failure(failure)
                lastFailure = failure
            } catch (e: IOException) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                logCompletion(audioFile, config, mediaType, durationMs, attempt, null, elapsedMs, e.javaClass.simpleName, prompt, evidence)
                lastFailure = SttRequestException(e.message ?: e.javaClass.simpleName)
            }
            if (attempt < MAX_ATTEMPTS) sleep(backoffMs[zeroBased])
        }
        return Result.failure(lastFailure ?: SttRequestException("unknown STT failure"))
    }

    private fun isTransientHttp(code: Int): Boolean = code in 500..599 || code == 429

    private fun logCompletion(
        audioFile: File,
        config: SttProviderConfig,
        mediaType: String,
        durationMs: Long?,
        attempt: Int,
        httpCode: Int?,
        elapsedMs: Long,
        error: String?,
        prompt: String? = null,
        evidence: Boolean = true
    ) {
        if (!evidence) return
        logger?.log(
            "stt-latency",
            gson(SttRequestLog(
                event = EVENT_COMPLETION,
                file = audioFile.name,
                bytes = audioFile.length(),
                mediaType = mediaType,
                model = config.model,
                baseUrl = config.baseUrl,
                durationMs = durationMs,
                attempt = attempt,
                httpCode = httpCode,
                elapsedMs = elapsedMs,
                // prompt marker (#117): keeps the Phase 2 t(S) fit unpolluted
                // — prompt-induced latency must not read as audio-size cost
                promptChars = prompt?.length,
                error = error
            ))
        )
    }

    private fun gson(log: SttRequestLog): String =
        GsonBuilder().setPrettyPrinting().create().toJson(log)

    private fun isOgg(audioFile: File): Boolean =
        audioFile.extension.equals("ogg", ignoreCase = true)

    /**
     * Per-request record for both streams. Nullable fields are omitted by
     * Gson, so the `stt-upload.json` entry stays byte-shape-identical to the
     * pre-#116 `SttUploadLog`.
     */
    private data class SttRequestLog(
        val event: String? = null,
        val chunk: Int? = null,
        val chunkCount: Int? = null,
        val file: String,
        val bytes: Long,
        val mediaType: String,
        val model: String,
        val baseUrl: String,
        val durationMs: Long? = null,
        val attempt: Int? = null,
        val httpCode: Int? = null,
        val elapsedMs: Long? = null,
        val promptChars: Int? = null,
        val error: String? = null
    )

    companion object {
        const val MAX_ATTEMPTS = 3
        const val EVENT_COMPLETION = "complete"
    }
}
