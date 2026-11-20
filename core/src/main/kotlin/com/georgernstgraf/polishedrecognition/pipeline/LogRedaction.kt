package com.georgernstgraf.polishedrecognition.pipeline

/**
 * #183: strips credentials out of a raw HTTP body before it reaches a log.
 *
 * The response logger records provider bodies verbatim, which is exactly what
 * a post-mortem needs — but an error page is a server's chance to echo back
 * what it received, `Authorization` header included. "No raw token ever lands
 * in a log" is therefore a property of this transform, not a hope.
 *
 * Deliberately conservative: only shapes that really are credentials are
 * replaced (`Bearer …`, `sk-…`/`gsk_…`-style keys), everything else stays
 * byte-identical, so the forensic value of the body is preserved. The size
 * bound is the caller's (`ResponseLoggerInterceptor` peeks at most 10 MB).
 */
object LogRedaction {

    private const val REDACTED = "<redacted>"

    /**
     * `Authorization: Bearer <token>` — keeps the scheme, drops the secret.
     * The token charset EXCLUDES quotes and whitespace: a `\S` class would
     * swallow the closing `"}` of the JSON string it sits in and turn the
     * logged body into invalid JSON.
     */
    private val BEARER = Regex("""(?i)(\bbearer\s+)[^\s"']{8,}""")

    /**
     * Provider key shapes: `sk-…` (OpenAI-style), `gsk_…` (Groq),
     * `rk_…`/`api-…` (common self-hosted patterns). The length floor keeps
     * prose like "risk-free" or "task-list" out of it.
     */
    private val API_KEY = Regex("""\b(?:sk|rk|gsk|api)[-_][A-Za-z0-9_-]{8,}""", RegexOption.IGNORE_CASE)

    /** Applies [BEARER] and [API_KEY]; a body without a candidate is untouched. */
    fun redact(text: String): String {
        var out = text
        if (out.contains("bearer", ignoreCase = true)) {
            out = BEARER.replace(out) { match -> match.groupValues[1] + REDACTED }
        }
        if (looksLikeKey(out)) {
            out = API_KEY.replace(out, REDACTED)
        }
        return out
    }

    /**
     * Cheap pre-check so the regex only runs on bodies that could contain a
     * key: the interceptor logs up to 10 MB per response and a transcription
     * body has no `sk-` in it.
     */
    private fun looksLikeKey(text: String): Boolean =
        text.contains("sk-", ignoreCase = true) ||
            text.contains("sk_", ignoreCase = true) ||
            text.contains("rk-", ignoreCase = true) ||
            text.contains("rk_", ignoreCase = true) ||
            text.contains("gsk", ignoreCase = true) ||
            text.contains("api-", ignoreCase = true) ||
            text.contains("api_", ignoreCase = true)
}
