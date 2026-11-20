package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #183: a provider error page can echo the request headers back, so the raw
 * response log must never carry the token. Everything that is NOT a
 * credential must survive untouched — the log is forensic evidence.
 */
class LogRedactionTest {

    @Test
    fun `a bearer token is replaced, the scheme stays`() {
        val body = """{"error":"Unauthorized","received":"Authorization: Bearer sk-live-abcdef123456"}"""

        val redacted = LogRedaction.redact(body)

        assertThat(redacted).doesNotContain("abcdef123456")
        assertThat(redacted).contains("Bearer <redacted>")
        assertThat(redacted).contains("\"error\":\"Unauthorized\"")
    }

    @Test
    fun `provider key shapes are replaced`() {
        assertThat(LogRedaction.redact("key gsk_ABCDEFGHIJKLMNOP rejected"))
            .isEqualTo("key <redacted> rejected")
        assertThat(LogRedaction.redact("key sk-proj-abcdefghijklmnop rejected"))
            .isEqualTo("key <redacted> rejected")
        assertThat(LogRedaction.redact("key rk_1234567890abcdef rejected"))
            .isEqualTo("key <redacted> rejected")
    }

    @Test
    fun `ordinary prose is left alone`() {
        val body = """{"text":"a risk-free task-list for the api-users"}"""
        assertThat(LogRedaction.redact(body)).isEqualTo(body)
    }

    @Test
    fun `a redacted body is still valid json`() {
        val body = """{"error":"Bearer sk-live-abcdef123456"}"""
        val tree = com.google.gson.Gson().fromJson(LogRedaction.redact(body), Map::class.java)
        assertThat(tree["error"]).isEqualTo("Bearer <redacted>")
    }
}
