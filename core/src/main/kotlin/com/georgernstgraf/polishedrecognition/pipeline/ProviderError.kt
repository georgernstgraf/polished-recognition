package com.georgernstgraf.polishedrecognition.pipeline

import com.google.gson.Gson
import com.google.gson.JsonObject
import okio.Buffer
import retrofit2.Response

/**
 * #75: the provider's OWN refusal sentence, parsed from a non-2xx response body.
 *
 * The measured case (OPO, 9 of 100 request records) is a LiteLLM spend cap:
 *
 * ```
 * HTTP 429
 * {"error":{"message":"ExceededBudget: User=grafg over budget.
 *            Spend=5.00060467499998, Budget=5.0",
 *           "type":"budget_exceeded","code":"429"}}
 * ```
 *
 * The status alone says "429 → transient, retry": three attempts per fragment,
 * a parked session and — via #185's auto-retry — another three on the next
 * Workbench visit, against a wall that cannot clear. The BODY says "budget",
 * and that is the only signal that is really there: the school gateway
 * (nginx + LiteLLM) may strip or rewrite `x-ratelimit-*`/`retry-after`, so the
 * headers stay diagnostics and never decide anything (see `docs/ai/PITFALLS.md`).
 *
 * Deliberately defensive — an error page is whatever the server felt like
 * sending:
 * - `type`/`code` may be JSON strings OR numbers (`code: 429`),
 * - the envelope may be `{"error":{…}}` (OpenAI/LiteLLM) or flat, or absent
 *   entirely (an nginx HTML page, an empty body),
 * - NOTHING is assumed non-null: this object exists precisely because Gson
 *   leaves absent fields null and validates nothing.
 *
 * [message] is safe to show and to log: single-lined, redacted through
 * [LogRedaction] (an error page may echo the `Authorization` header back) and
 * capped at [MAX_MESSAGE_CHARS]. The parsed value is purely descriptive — the
 * classes and the attempts are decided in [SttFailureClassifier] and
 * [SttRequestRunner], which is where every #183 rule already lives.
 */
class ProviderError(
    /** The provider's machine-readable kind (`budget_exceeded`, `insufficient_quota`, …). */
    val type: String? = null,
    /** The provider's code — the envelope's `code`, NOT the HTTP status. */
    val code: String? = null,
    /** The provider's human-readable sentence, already single-lined/redacted/capped. */
    val message: String? = null
) {

    /**
     * #75: a spend/quota/credit wall — the one refusal that retrying cannot fix
     * within the session. Matched case-insensitively against [type] AND [code]:
     * LiteLLM names it `budget_exceeded`, OpenAI-compatible gateways use
     * `insufficient_quota`/`quota_exceeded`. A bare `429`, a
     * `rate_limit_exceeded` or an unparseable body is NOT one of these — those
     * stay `TRANSIENT` (the #183 matrix is knowingly kept).
     */
    val isBudget: Boolean
        get() = matchesBudget(type) || matchesBudget(code)

    /**
     * #75: `<base> — <provider message>` when the body named one, else [base]
     * untouched. Both paths (STT and the LLM polish) compose their headline
     * this way, so the user always sees the provider's own words next to what
     * the app was doing.
     */
    fun appendTo(base: String): String = message?.let { "$base — $it" } ?: base

    /**
     * #75: the log/bundle shape. Nulls are omitted by Gson, so a value parsed
     * from a body that carried only a message stays a one-line record.
     */
    fun asMap(): Map<String, Any?> = buildMap {
        type?.let { put("type", it) }
        code?.let { put("code", it) }
        message?.let { put("message", it) }
    }

    companion object {
        /**
         * #75: how much of an error body is read. A provider sentence is a few
         * hundred bytes; the cap keeps a 10-MB HTML error page out of memory
         * (the response logger peeks at the whole body separately, #183).
         */
        const val MAX_BODY_BYTES = 4 * 1024

        /** #75: the shard of a provider message that is ever shown or logged. */
        const val MAX_MESSAGE_CHARS = 300

        /** #75: per-field cap for `type`/`code` — they are labels, not prose. */
        private const val MAX_FIELD_CHARS = 64

        /** #75: the only markers that count as a spend/quota wall. */
        private val BUDGET_MARKERS = setOf("budget_exceeded", "insufficient_quota", "quota_exceeded")

        /** Gson is used through `fromJson` — this repo's Gson predates `JsonParser.parseString`. */
        private val gson = Gson()

        /**
         * #75: reads a non-2xx body for parsing, capped at [MAX_BODY_BYTES].
         * Never throws and never leaves the body unclosed — a diagnostics read
         * must not be able to fail a request.
         */
        fun bodyOf(response: Response<*>): String? = try {
            response.errorBody()?.use { body ->
                val buffer = Buffer()
                body.source().read(buffer, MAX_BODY_BYTES.toLong())
                buffer.readUtf8()
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * #75: parses an error body into its descriptive parts, or `null` when
         * the body is empty, not JSON (an HTML proxy page), or carries nothing
         * usable. Never throws.
         */
        fun parse(body: String?): ProviderError? {
            val text = body?.trim().orEmpty()
            // Fast rejection: JSON bodies are objects, and every non-JSON body
            // (HTML, plain text, empty) has no envelope to read.
            if (text.length < 2 || !text.startsWith("{")) return null
            val root = try {
                gson.fromJson(text, JsonObject::class.java)
            } catch (_: Throwable) {
                null
            } ?: return null
            // `{"error":{…}}` is the OpenAI/LiteLLM envelope; a flat body is
            // accepted too. Anything else (an `error` string, an array) falls
            // back to the root object rather than throwing.
            val envelope = try {
                root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
            } catch (_: Throwable) {
                root
            }
            val type = field(envelope, "type")?.take(MAX_FIELD_CHARS) ?: field(root, "type")?.take(MAX_FIELD_CHARS)
            val code = field(envelope, "code")?.take(MAX_FIELD_CHARS) ?: field(root, "code")?.take(MAX_FIELD_CHARS)
            val message = normalizeMessage(field(envelope, "message") ?: field(root, "message"))
            if (type == null && code == null && message == null) return null
            return ProviderError(type = type, code = code, message = message)
        }

        /** A trimmed string/number member, or null when absent/non-primitive/blank. */
        private fun field(obj: JsonObject, key: String): String? = try {
            obj.get(key)
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }

        /** One line, credentials removed, length-capped — safe to show and log. */
        private fun normalizeMessage(raw: String?): String? {
            val single = raw?.replace(WHITESPACE, " ")?.trim().orEmpty()
            if (single.isEmpty()) return null
            return LogRedaction.redact(single).take(MAX_MESSAGE_CHARS)
        }

        private val WHITESPACE = Regex("\\s+")

        private fun matchesBudget(value: String?): Boolean {
            val normalized = value?.lowercase() ?: return false
            return normalized in BUDGET_MARKERS
        }
    }
}
