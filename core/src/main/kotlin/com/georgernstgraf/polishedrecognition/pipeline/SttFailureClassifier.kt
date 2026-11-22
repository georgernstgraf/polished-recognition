package com.georgernstgraf.polishedrecognition.pipeline

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.EOFException
import java.io.IOException

/**
 * #183: WHY an STT attempt failed, decided BEFORE anything acts on it.
 *
 * The #116 retry policy only knew "transient" (5xx/429/IOException) and
 * "everything else", which conflated two very different cases: a dead token
 * (retrying is pointless and, worse, hammers the provider) and a server that
 * answers with something unusable (retrying once is exactly right).
 *
 * The class never decides alone — [SttRequestRunner] owns the attempts, the
 * journal records the class, and the IME turns AUTH into an actionable
 * message. That separation is why this is a pure classifier with no IO.
 */
enum class SttFailure {
    /**
     * Timeout, socket/DNS trouble, HTTP 408/429/5xx: the SAME request may
     * succeed later, so back off and retry.
     */
    TRANSIENT,

    /**
     * HTTP 401/403: the token is rejected or expired. Retrying cannot help,
     * and every extra attempt is one more failed auth against a provider that
     * may be counting them. Stop the session's requests, keep the audio, and
     * let the user replace the token.
     */
    AUTH,

    /**
     * A 2xx whose envelope cannot be used, or a body that cannot be decoded
     * (truncated JSON, a proxy's HTML page with status 200): retry ONCE, then
     * treat it as manual work.
     */
    PROTOCOL,

    /** Any other client-side refusal (400/410/413/415, unexpected exceptions). */
    TERMINAL,

    /**
     * #75: the provider's own error body names a spend/quota wall
     * (`budget_exceeded`, `insufficient_quota`, `quota_exceeded`).
     *
     * The measured case is a LiteLLM spend cap answering `429` with
     * `ExceededBudget: … Spend=5.0006, Budget=5.0` — a status the #183 matrix
     * calls TRANSIENT. Asking again cannot clear a budget: the runner makes
     * exactly ONE attempt, the transcriber latches the session (no further
     * requests), #185 never auto-retries it, and the user reads the provider's
     * own sentence plus an actionable hint. A bare `429`, a
     * `rate_limit_exceeded` or an unparseable body stays TRANSIENT — the body
     * is the signal, never the status and never the (strippable) headers.
     */
    BUDGET,

    /**
     * #186: the device had no USABLE network when the fragment was recorded —
     * the platform's `NET_CAPABILITY_VALIDATED` was absent, so the request was
     * not attempted at all.
     *
     * This is deliberately **not an error**: the audio is complete, the reason
     * is recorded, and the session transcribes as soon as the network is back
     * (a reconnect while the Workbench is visible, the next entry, or the
     * manual Retry STT). The class exists so the journal, the ring and the IME
     * can say "offline — saved" instead of "transcription failed".
     */
    OFFLINE
}

/**
 * #183: maps an attempt's outcome to its [SttFailure]. Pure — the matrix is
 * the contract, and the runner's behaviour is derived from it.
 *
 * #75 extends it with BUDGET, decided from the provider's error BODY
 * ([ProviderError]) instead of from the status code: the spend cap that
 * produced this extension answers `429`, which the matrix alone would keep
 * retrying forever.
 */
object SttFailureClassifier {

    /**
     * @param httpCode the response status, or `null` when the attempt threw
     * @param throwable the exception the attempt threw, or `null` on a response
     * @param envelopeUnusable `true` for a 2xx whose parsed body carries no
     *   transcript ([com.georgernstgraf.polishedrecognition.api.dto.SttResponse]
     *   gives no validation: Gson leaves `text` null when the key is missing)
     * @param providerError #75: the parsed non-2xx body, when there was one. A
     *   4xx whose body names a budget/quota condition outranks the #183 matrix
     *   (`429` → BUDGET instead of TRANSIENT); a 401/403 stays AUTH, because a
     *   rejected credential is the more specific fact and retrying is wrong
     *   either way. Everything else keeps the matrix untouched.
     * @param offline #186: the device had no validated network (the `:app`
     *   `NetworkGate`). That dominates EVERY other input: no request went
     *   out, so no status and no exception describes the situation better
     *   than the missing network. Only ever `true` on a path that already
     *   decided not to ask — a `false` value leaves the whole matrix as it
     *   was.
     */
    fun classify(
        httpCode: Int?,
        throwable: Throwable? = null,
        envelopeUnusable: Boolean = false,
        providerError: ProviderError? = null,
        offline: Boolean = false
    ): SttFailure {
        if (offline) return SttFailure.OFFLINE
        if (envelopeUnusable) return SttFailure.PROTOCOL
        if (httpCode != null) {
            return when {
                httpCode == 401 || httpCode == 403 -> SttFailure.AUTH
                // #75: the body decides before the status does. 402 is
                // "Payment Required" and is simply the other 4xx a spend cap
                // may use.
                providerError?.isBudget == true && httpCode in 400..499 -> SttFailure.BUDGET
                httpCode == 408 || httpCode == 429 || httpCode in 500..599 -> SttFailure.TRANSIENT
                // A 2xx that reached here was rejected by the caller as
                // unusable; classifying it as PROTOCOL keeps the function
                // total without inventing a success.
                httpCode in 200..299 -> SttFailure.PROTOCOL
                else -> SttFailure.TERMINAL
            }
        }
        if (throwable != null) {
            return when {
                // Gson/Retrofit converter failures: the bytes arrived but the
                // envelope is garbage. `MalformedJsonException` and
                // `EOFException` (truncated body) are IOExceptions, so they
                // MUST be matched before the IOException branch.
                throwable is JsonParseException -> SttFailure.PROTOCOL
                throwable is MalformedJsonException -> SttFailure.PROTOCOL
                throwable is EOFException -> SttFailure.PROTOCOL
                throwable is IOException -> SttFailure.TRANSIENT
                else -> SttFailure.TERMINAL
            }
        }
        return SttFailure.TERMINAL
    }
}
