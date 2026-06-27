package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResponseLoggerInterceptorTest {

    @Test
    fun `routeForLog maps transcription and chat paths`() {
        assertThat(ResponseLoggerInterceptor.routeForLog("/v1/audio/transcriptions"))
            .isEqualTo("stt-response")
        assertThat(ResponseLoggerInterceptor.routeForLog("/openai/v1/chat/completions"))
            .isEqualTo("llm-response")
    }

    @Test
    fun `routeForLog skips non transcription paths`() {
        assertThat(ResponseLoggerInterceptor.routeForLog("/v1/models")).isNull()
        assertThat(ResponseLoggerInterceptor.routeForLog("/v1/audio/translations")).isNull()
        assertThat(ResponseLoggerInterceptor.routeForLog("/")).isNull()
    }

    @Test
    fun `prettyOrRaw pretty-prints compact json preserving all fields`() {
        val raw = """{"b":2,"a":{"z":9},"usage":{"tokens":5}}"""
        val pretty = ResponseLoggerInterceptor.prettyOrRaw(raw)

        assertThat(pretty).contains("\"a\": {")
        assertThat(pretty).contains("\"usage\": {")
        assertThat(pretty).contains("\"tokens\": 5")
        assertThat(pretty).contains("\n") // multi-line
        // field set preserved regardless of input order
        assertThat(pretty).contains("\"b\": 2")
    }

    @Test
    fun `prettyOrRaw falls back to raw for non json`() {
        val raw = "<html>not json</html>"
        assertThat(ResponseLoggerInterceptor.prettyOrRaw(raw)).isEqualTo(raw)
    }
}
