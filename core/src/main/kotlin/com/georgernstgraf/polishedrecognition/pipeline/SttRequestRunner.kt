package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.AudioDuration
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Runs ONE STT request for one audio file with the #116 retry policy, refined
 * by the #183 failure classification: up to [MAX_ATTEMPTS] attempts, retried
 * only while the failure class allows it —
 *
 * - **[SttFailure.TRANSIENT]** (IOException/timeouts, 408, 429, 5xx) backs off
 *   and retries, honouring `Retry-After` up to [maxRetryWaitMs] per wait and
 *   [maxTotalWaitMs] per request.
 * - **[SttFailure.AUTH]** (401/403) and **[SttFailure.TERMINAL]** (other 4xx)
 *   fail IMMEDIATELY — a dead token must not be hammered.
 * - **[SttFailure.PROTOCOL]** (2xx without a transcript, undecodable body)
 *   retries EXACTLY ONCE with the identical request: the envelope broke, not
 *   the request shape, so repeating the bytes is the whole fix (#184 keeps
 *   the repeat idempotent).
 *
 * Writes two evidence streams:
 * - `stt-upload.json` (unchanged #115 semantics): pre-send proof that the
 *   request went out, with size/model/endpoint — on a read-timeout no
 *   response ever arrives, so this is the only immediate proof of the send.
 * - `stt-latency.json` (new, #116): one completion record PER ATTEMPT with
 *   the audio duration (`durationMs`, parsed via [AudioDuration]), HTTP
 *   code, elapsed time and failure class. This is the Phase 2 per-provider
 *   latency-profile input (robust fit of t(S) ≈ a + b·S → auto fragment
 *   size) and closes the #64 evidence gap ("&gt;2 min per
 *   600-s chunk" was neither provable nor refutable from the old logs).
 *
 * #184: a fragment request ALSO carries `session_id`, `fragment_index` and
 * `attempt` as ordinary multipart fields (see [run]); the pre-send record
 * mirrors the identity into `stt-upload.json`. The legacy chunk path passes
 * none of them, so its request is byte-identical to the pre-#184 format.
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
     * successful request. `durationMs` is the uploaded audio duration
     * (pre-roll included — the uploaded-size truth), `elapsedMs` this
     * single attempt's round-trip.
     */
    private val onCompletion: ((Completion) -> Unit)? = null,
    /**
     * #183: the longest a single wait may last. A `Retry-After` asking for
     * more is NOT honoured by waiting less (that is what a rate limiter
     * punishes) — the request fails instead.
     */
    private val maxRetryWaitMs: Long = MAX_RETRY_WAIT_MS,
    /** #183: the whole request's wait budget, so no server can stall the worker. */
    private val maxTotalWaitMs: Long = MAX_TOTAL_WAIT_MS
) {

    /** Successful request measurement (#116 Phase 2 profile sample). */
    data class Completion(
        val config: SttProviderConfig,
        val mediaType: String,
        val durationMs: Long?,
        val elapsedMs: Long,
        val attempt: Int
    )

    /**
     * Final failure after all attempts; [httpCode] is set for HTTP failures and
     * [failure] carries the #183 class the caller acts on (the transcriber
     * stops a session on AUTH, the journal marks PROTOCOL as manual work).
     * Defaults to TERMINAL: a failure nobody classified is not retry-worthy.
     */
    class SttRequestException(
        message: String,
        val httpCode: Int? = null,
        val failure: SttFailure = SttFailure.TERMINAL
    ) : Exception(message)

    /**
     * Executes one upload. Fails fast on non-retryable errors; otherwise
     * retries up to [MAX_ATTEMPTS] total attempts, as far as the failure
     * class and the wait budget allow.
     *
     * [prompt] (#117): optional Whisper conditioning prompt — the tail of
     * the previous fragment's transcript. Conditioned text is NOT echoed
     * into the output, so no join-side dedup is needed.
     */
    suspend fun run(
        audioFile: File,
        config: SttProviderConfig,
        chunk: Int? = null,
        chunkCount: Int? = null,
        prompt: String? = null,
        /**
         * #184: the fragment's durable identity. When [sessionId] is non-null
         * every attempt carries `session_id`, `fragment_index` and `attempt`
         * as ordinary multipart fields, so a server can dedupe on
         * `session_id:fragment_index` and a post-mortem can tell a retry from
         * a new fragment. `null` (the legacy batch/chunk path) sends NOTHING
         * extra — byte-identical to the pre-#184 request.
         */
        sessionId: String? = null,
        fragmentIndex: Int? = null
    ): Result<SttResponse> {
        val mediaType = if (isOgg(audioFile)) "audio/ogg" else "audio/wav"
        val uploadName = if (isOgg(audioFile)) "audio.ogg" else "audio.wav"
        val durationMs = AudioDuration.estimateMs(audioFile)

        // Pre-send evidence (#115 semantics, unchanged shape).
        logger?.log(
            "stt-upload",
            gson(SttRequestLog(
                chunk = chunk,
                chunkCount = chunkCount,
                file = audioFile.name,
                bytes = audioFile.length(),
                mediaType = mediaType,
                model = config.model,
                baseUrl = config.baseUrl,
                sessionId = sessionId,
                fragmentIndex = fragmentIndex
            ))
        )

        var lastFailure: SttRequestException? = null
        var waitedMs = 0L
        repeat(MAX_ATTEMPTS) { zeroBased ->
            val attempt = zeroBased + 1
            val startedAt = System.nanoTime()
            var retryAfter: Long? = null
            // The failure is the try-block's VALUE: `val` cannot be assigned
            // from both the try body and the catch clause (Kotlin's definite
            // assignment cannot prove exclusivity).
            val failure: SttRequestException = try {
                val response = getSttApi(config.baseUrl).transcribeAudioSync(
                    authorization = "Bearer ${config.apiToken}",
                    file = MultipartBody.Part.createFormData(
                        "file", uploadName, audioFile.asRequestBody(mediaType.toMediaTypeOrNull())
                    ),
                    model = config.model.toRequestBody("text/plain".toMediaTypeOrNull()),
                    responseFormat = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull()),
                    prompt = prompt?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    extraFields = idempotencyFields(sessionId, fragmentIndex, attempt)
                ).execute()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                val code = response.code()

                if (response.isSuccessful) {
                    val body = response.body()
                    // #183: a 2xx is only a success when the envelope carries a
                    // transcript. A blank transcript is legitimate (a silent
                    // fragment); a MISSING one is not — Gson leaves `text` null
                    // and Retrofit validates nothing.
                    if (body != null && hasTranscript(body)) {
                        logCompletion(audioFile, config, mediaType, durationMs, attempt, code, elapsedMs, null, prompt)
                        if (durationMs != null) {
                            onCompletion?.invoke(Completion(config, mediaType, durationMs, elapsedMs, attempt))
                        }
                        return Result.success(body)
                    }
                    logCompletion(
                        audioFile, config, mediaType, durationMs, attempt, code, elapsedMs,
                        if (body == null) "emptyBody" else "unusableEnvelope", prompt
                    )
                    SttRequestException(
                        "HTTP $code (unusable response body)",
                        httpCode = code,
                        failure = SttFailureClassifier.classify(code, envelopeUnusable = true)
                    )
                } else {
                    retryAfter = RetryAfter.parse(response.headers()["Retry-After"])
                    logCompletion(
                        audioFile, config, mediaType, durationMs, attempt, code, elapsedMs,
                        null, prompt, retryAfter
                    )
                    SttRequestException(
                        httpMessage(code, retryAfter),
                        httpCode = code,
                        failure = SttFailureClassifier.classify(code)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                logCompletion(audioFile, config, mediaType, durationMs, attempt, null, elapsedMs, e.javaClass.simpleName, prompt)
                SttRequestException(
                    e.message ?: e.javaClass.simpleName,
                    failure = SttFailureClassifier.classify(null, e)
                )
            }
            lastFailure = failure

            // #183: AUTH must not be hammered, TERMINAL cannot be fixed by
            // asking again, and PROTOCOL gets exactly ONE repeat.
            when (failure.failure) {
                SttFailure.AUTH, SttFailure.TERMINAL -> return Result.failure(failure)
                SttFailure.PROTOCOL -> if (attempt > 1) return Result.failure(failure)
                SttFailure.TRANSIENT -> Unit
            }
            if (attempt >= MAX_ATTEMPTS) return Result.failure(failure)
            // null = do not retry: the server asked for longer than we are
            // willing to wait, or this request's whole budget is spent.
            val wait = nextWaitMs(zeroBased, retryAfter, waitedMs) ?: return Result.failure(failure)
            if (wait > 0) {
                sleep(wait)
                waitedMs += wait
            }
        }
        return Result.failure(lastFailure ?: SttRequestException("unknown STT failure"))
    }

    /**
     * #183: the wait before the next attempt, or `null` when we must not
     * retry — a `Retry-After` above [maxRetryWaitMs] is honoured by giving up
     * rather than by waiting less, and a request whose [maxTotalWaitMs] is
     * spent stops instead of hammering. The configured backoff stays the
     * floor, so a server without `Retry-After` sees the #116 ladder unchanged.
     */
    private fun nextWaitMs(attemptIndex: Int, retryAfterMs: Long?, waitedMs: Long): Long? {
        val floor = backoffMs.getOrElse(attemptIndex) { backoffMs.lastOrNull() ?: 0L }
        val wanted = maxOf(floor, retryAfterMs ?: 0L)
        if (wanted > maxRetryWaitMs) return null
        if (waitedMs + wanted > maxTotalWaitMs) return null
        return wanted
    }

    /**
     * #183: is a 2xx body usable? Deliberately `text == null` and not
     * `isBlank()` — an empty transcript is a legitimate answer for a silent
     * fragment, a MISSING one means the server sent something else entirely.
     * The Kotlin type claims non-null, but Gson bypasses the constructor and
     * leaves absent reference fields null, so the comparison is real.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun hasTranscript(body: SttResponse): Boolean = body.text != null

    /** #183: the user/log visible message, with the server's wait advice. */
    private fun httpMessage(code: Int, retryAfterMs: Long?): String =
        if (retryAfterMs == null) "HTTP $code" else "HTTP $code (Retry-After ${retryAfterMs / 1_000}s)"

    /**
     * #184: the optional idempotency fields. An EMPTY map (no [sessionId])
     * leaves the multipart body exactly as it was before #184 — that is what
     * keeps the legacy chunk path and every "unknown fields are ignored"
     * server working unchanged. The attempt counter is included only
     * alongside an identity: on its own it says nothing a server could use.
     */
    private fun idempotencyFields(
        sessionId: String?,
        fragmentIndex: Int?,
        attempt: Int
    ): Map<String, okhttp3.RequestBody> {
        if (sessionId == null) return emptyMap()
        val plain = "text/plain".toMediaTypeOrNull()
        return buildMap {
            put("session_id", sessionId.toRequestBody(plain))
            if (fragmentIndex != null) put("fragment_index", fragmentIndex.toString().toRequestBody(plain))
            put("attempt", attempt.toString().toRequestBody(plain))
        }
    }

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
        /** #183: the server's wait advice, when it sent one. */
        retryAfterMs: Long? = null
    ) {
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
                retryAfterMs = retryAfterMs,
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
        /** #184: the fragment's wire identity (null for the legacy chunk path). */
        val sessionId: String? = null,
        val fragmentIndex: Int? = null,
        /** #183: the server's `Retry-After` advice for this attempt, if any. */
        val retryAfterMs: Long? = null,
        val error: String? = null
    )

    companion object {
        const val MAX_ATTEMPTS = 3
        const val EVENT_COMPLETION = "complete"

        /**
         * #183: ceiling for ONE wait. A gateway that asks for more is obeyed
         * by giving up (see [nextWaitMs]) instead of retrying early, which a
         * rate limiter treats as a violation.
         */
        const val MAX_RETRY_WAIT_MS = 10_000L

        /**
         * #183: the whole request's wait budget. Three attempts can therefore
         * cost at most ~20 s of waiting — a pathological server cannot stall
         * the worker, and the user-visible stop→raw path stays bounded.
         */
        const val MAX_TOTAL_WAIT_MS = 20_000L
    }
}
