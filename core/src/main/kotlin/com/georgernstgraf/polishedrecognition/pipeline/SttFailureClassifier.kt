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
    TERMINAL
}

/**
 * #183: maps an attempt's outcome to its [SttFailure]. Pure — the matrix is
 * the contract, and the runner's behaviour is derived from it.
 */
object SttFailureClassifier {

    /**
     * @param httpCode the response status, or `null` when the attempt threw
     * @param throwable the exception the attempt threw, or `null` on a response
     * @param envelopeUnusable `true` for a 2xx whose parsed body carries no
     *   transcript ([com.georgernstgraf.polishedrecognition.api.dto.SttResponse]
     *   gives no validation: Gson leaves `text` null when the key is missing)
     */
    fun classify(
        httpCode: Int?,
        throwable: Throwable? = null,
        envelopeUnusable: Boolean = false
    ): SttFailure {
        if (envelopeUnusable) return SttFailure.PROTOCOL
        if (httpCode != null) {
            return when {
                httpCode == 401 || httpCode == 403 -> SttFailure.AUTH
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
