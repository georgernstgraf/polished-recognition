package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #75: the provider's refusal body is the ONLY trustworthy signal (the school
 * gateway may strip `x-ratelimit-*`/`retry-after`), so the parser is pinned
 * against the body the device actually produced — and against every shape a
 * gateway invents when it is not LiteLLM: a flat envelope, an HTML page, an
 * empty body, a numeric `code`.
 */
class ProviderErrorTest {

    /** Verbatim from the OPO logs (9 of 100 request records, all identical). */
    private val measuredBudgetBody =
        """{"error":{"message":"ExceededBudget: User=grafg over budget.\nSpend=5.00060467499998, Budget=5.0","type":"budget_exceeded","code":"429"}}"""

    @Test
    fun `the measured budget body parses and is a budget refusal`() {
        val error = ProviderError.parse(measuredBudgetBody)!!

        assertThat(error.type).isEqualTo("budget_exceeded")
        assertThat(error.code).isEqualTo("429")
        // whitespace is collapsed: a JSON string may carry newlines, a Toast
        // and a one-line log must not
        assertThat(error.message)
            .isEqualTo("ExceededBudget: User=grafg over budget. Spend=5.00060467499998, Budget=5.0")
        assertThat(error.isBudget).isTrue()
    }

    @Test
    fun `a rate limit is not a budget`() {
        val error = ProviderError.parse(
            """{"error":{"message":"Rate limit reached","type":"rate_limit_exceeded","code":"429"}}"""
        )!!

        assertThat(error.type).isEqualTo("rate_limit_exceeded")
        // a bare 429 stays TRANSIENT: a minute later the request may work
        assertThat(error.isBudget).isFalse()
    }

    @Test
    fun `the other quota markers count as a budget too`() {
        for (marker in listOf("insufficient_quota", "quota_exceeded", "BUDGET_EXCEEDED")) {
            assertThat(ProviderError.parse("""{"error":{"type":"$marker"}}""")!!.isBudget).isTrue()
        }
        assertThat(
            ProviderError.parse("""{"error":{"code":"insufficient_quota"}}""")!!.isBudget
        ).isTrue()
    }

    @Test
    fun `a flat envelope is read as well as the nested one`() {
        val error = ProviderError.parse(
            """{"message":"out of credits","type":"insufficient_quota","code":429}"""
        )!!

        assertThat(error.type).isEqualTo("insufficient_quota")
        assertThat(error.message).isEqualTo("out of credits")
        // a NUMERIC code is legitimate: providers mix strings and numbers
        assertThat(error.code).isEqualTo("429")
        assertThat(error.isBudget).isTrue()
    }

    @Test
    fun `an html error page is not an envelope`() {
        assertThat(ProviderError.parse("<html><body><h1>502 Bad Gateway</h1></body></html>")).isNull()
        assertThat(ProviderError.parse("error")).isNull()
        assertThat(ProviderError.parse("")).isNull()
        assertThat(ProviderError.parse(null)).isNull()
    }

    @Test
    fun `a json body without any usable field is no provider error`() {
        assertThat(ProviderError.parse("{}")).isNull()
        assertThat(ProviderError.parse("""{"error":"bad gateway"}""")).isNull()
        assertThat(ProviderError.parse("""{"error":{"message":"   "}}""")).isNull()
    }

    @Test
    fun `the message is redacted and capped`() {
        val leaked = ProviderError.parse(
            """{"error":{"type":"auth_error","message":"bad key Bearer sk-abcdefghij1234567890"}}"""
        )!!

        // an error page may echo the Authorization header back — a provider
        // sentence is shown and logged, so it passes through LogRedaction
        assertThat(leaked.message).doesNotContain("abcdefghij1234567890")
        assertThat(leaked.message).contains("Bearer <redacted>")

        val long = ProviderError.parse("""{"error":{"message":"${"x".repeat(900)}"}}""")!!
        assertThat(long.message!!.length).isEqualTo(ProviderError.MAX_MESSAGE_CHARS)
    }

    @Test
    fun `appending keeps the app's headline first`() {
        val error = ProviderError.parse(measuredBudgetBody)!!

        assertThat(error.appendTo("HTTP 429"))
            .startsWith("HTTP 429 — ExceededBudget: User=grafg over budget.")
        // nothing is appended when the body named no sentence
        assertThat(ProviderError(type = "budget_exceeded").appendTo("HTTP 429"))
            .isEqualTo("HTTP 429")
    }

    @Test
    fun `the log shape omits absent fields`() {
        val map = ProviderError(type = "budget_exceeded", message = "over budget").asMap()

        assertThat(map.keys).containsExactly("type", "message")
        assertThat(map["type"]).isEqualTo("budget_exceeded")
    }
}
