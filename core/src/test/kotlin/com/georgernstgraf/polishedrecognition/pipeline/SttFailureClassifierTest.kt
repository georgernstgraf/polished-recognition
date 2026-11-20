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
}
