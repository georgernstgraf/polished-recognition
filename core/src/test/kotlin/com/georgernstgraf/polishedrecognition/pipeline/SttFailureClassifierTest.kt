package com.georgernstgraf.polishedrecognition.pipeline

import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * #183: the classification matrix is the contract the runner, the journal and
 * the UI all act on, so every branch is pinned here — including the trap that
 * `MalformedJsonException`/`EOFException` are IOExceptions and must not be
 * classified as TRANSIENT.
 */
class SttFailureClassifierTest {

    @Test
    fun `401 and 403 are auth`() {
        assertThat(SttFailureClassifier.classify(401)).isEqualTo(SttFailure.AUTH)
        assertThat(SttFailureClassifier.classify(403)).isEqualTo(SttFailure.AUTH)
    }

    @Test
    fun `408, 429 and 5xx are transient`() {
        assertThat(SttFailureClassifier.classify(408)).isEqualTo(SttFailure.TRANSIENT)
        assertThat(SttFailureClassifier.classify(429)).isEqualTo(SttFailure.TRANSIENT)
        for (code in listOf(500, 502, 503, 504, 599)) {
            assertThat(SttFailureClassifier.classify(code)).isEqualTo(SttFailure.TRANSIENT)
        }
    }

    @Test
    fun `other 4xx are terminal`() {
        for (code in listOf(400, 404, 410, 413, 415, 422)) {
            assertThat(SttFailureClassifier.classify(code)).isEqualTo(SttFailure.TERMINAL)
        }
    }

    @Test
    fun `a 2xx is protocol when its envelope is unusable`() {
        assertThat(SttFailureClassifier.classify(200, envelopeUnusable = true))
            .isEqualTo(SttFailure.PROTOCOL)
        // total function: a 2xx that reached the classifier without the flag is
        // still not a success
        assertThat(SttFailureClassifier.classify(200)).isEqualTo(SttFailure.PROTOCOL)
    }

    @Test
    fun `io failures are transient`() {
        assertThat(SttFailureClassifier.classify(null, IOException("broken pipe")))
            .isEqualTo(SttFailure.TRANSIENT)
        assertThat(SttFailureClassifier.classify(null, SocketTimeoutException("read timed out")))
            .isEqualTo(SttFailure.TRANSIENT)
        assertThat(SttFailureClassifier.classify(null, UnknownHostException("no dns")))
            .isEqualTo(SttFailure.TRANSIENT)
    }

    @Test
    fun `undecodable bodies are protocol, not transient`() {
        // Both are IOExceptions; classifying them as TRANSIENT would retry a
        // response the server is unlikely to improve and would hide the class
        // from the recovery record.
        assertThat(SttFailureClassifier.classify(null, JsonSyntaxException("Expected BEGIN_OBJECT")))
            .isEqualTo(SttFailure.PROTOCOL)
        assertThat(SttFailureClassifier.classify(null, MalformedJsonException("unterminated")))
            .isEqualTo(SttFailure.PROTOCOL)
        assertThat(SttFailureClassifier.classify(null, EOFException("truncated")))
            .isEqualTo(SttFailure.PROTOCOL)
    }

    @Test
    fun `anything unexpected is terminal`() {
        assertThat(SttFailureClassifier.classify(null, IllegalStateException("boom")))
            .isEqualTo(SttFailure.TERMINAL)
        assertThat(SttFailureClassifier.classify(null, null)).isEqualTo(SttFailure.TERMINAL)
    }

    // ---- #75: the provider's own body decides a budget wall -----------------

    /** The body the device actually produced (LiteLLM spend cap, opo logs). */
    private val budgetBody =
        """{"error":{"message":"ExceededBudget: User=grafg over budget. Spend=5.00060467499998, Budget=5.0","type":"budget_exceeded","code":"429"}}"""

    @Test
    fun `a 429 whose body names a budget is BUDGET, not transient`() {
        val provider = ProviderError.parse(budgetBody)!!

        // the status alone would say "retry" — the body is the signal
        assertThat(SttFailureClassifier.classify(429, providerError = provider))
            .isEqualTo(SttFailure.BUDGET)
        // 402 Payment Required is the other 4xx a spend cap may use
        assertThat(SttFailureClassifier.classify(402, providerError = provider))
            .isEqualTo(SttFailure.BUDGET)
        assertThat(SttFailureClassifier.classify(400, providerError = provider))
            .isEqualTo(SttFailure.BUDGET)
    }

    @Test
    fun `a rate_limit_exceeded 429 stays transient`() {
        val provider = ProviderError.parse(
            """{"error":{"message":"Rate limit reached","type":"rate_limit_exceeded","code":"429"}}"""
        )!!

        assertThat(SttFailureClassifier.classify(429, providerError = provider))
            .isEqualTo(SttFailure.TRANSIENT)
    }

    @Test
    fun `an unparseable 429 body stays transient`() {
        // an HTML page or an empty body says nothing about a budget
        assertThat(SttFailureClassifier.classify(429, providerError = null))
            .isEqualTo(SttFailure.TRANSIENT)
        assertThat(SttFailureClassifier.classify(429, providerError = ProviderError.parse("<html/>")))
            .isEqualTo(SttFailure.TRANSIENT)
    }

    @Test
    fun `a budget body never turns a rejected credential into a budget`() {
        val provider = ProviderError(type = "insufficient_quota")

        // AUTH is the more specific fact and retrying is wrong either way
        assertThat(SttFailureClassifier.classify(401, providerError = provider))
            .isEqualTo(SttFailure.AUTH)
        assertThat(SttFailureClassifier.classify(403, providerError = provider))
            .isEqualTo(SttFailure.AUTH)
    }

    @Test
    fun `a budget body on a 5xx does not outrank the transient matrix`() {
        // a gateway that forwards the provider's 429 body with its own 502 is
        // still a gateway problem — the matrix owns the statuses above 4xx
        assertThat(SttFailureClassifier.classify(502, providerError = ProviderError(type = "budget_exceeded")))
            .isEqualTo(SttFailure.TRANSIENT)
    }
}
