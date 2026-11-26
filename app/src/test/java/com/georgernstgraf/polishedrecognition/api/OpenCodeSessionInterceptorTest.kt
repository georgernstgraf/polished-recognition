package com.georgernstgraf.polishedrecognition.api

import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #153: OpenCode's Go endpoint answers HTTP 400 without `x-opencode-session`
 * (device finding 2026-11-26). The header is sent ONLY for the Go endpoint
 * (`opencode.ai/inference/go/…`) — the Console endpoint, other opencode.ai
 * paths and foreign hosts keep the request byte-identical (owner requirement).
 */
class OpenCodeSessionInterceptorTest {

    /** A minimal chain that records the request it is asked to proceed with. */
    private fun chainFor(request: Request): Pair<Interceptor.Chain, CapturingSlot<okhttp3.Request>> {
        val chain = mockk<Interceptor.Chain>()
        val captured = slot<okhttp3.Request>()
        every { chain.request() } returns request
        every { chain.proceed(capture(captured)) } answers {
            Response.Builder()
                .request(captured.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        return chain to captured
    }

    @Test
    fun `openCode Go requests carry the session header`() {
        val request = Request.Builder()
            .url("https://opencode.ai/inference/go/openai/v1/models")
            .build()
        val (chain, captured) = chainFor(request)

        OpenCodeSessionInterceptor("test-session-id").intercept(chain)

        assertThat(captured.captured.header(OpenCodeSessionInterceptor.SESSION_HEADER))
            .isEqualTo("test-session-id")
        // Nothing else about the request changed.
        assertThat(captured.captured.url).isEqualTo(request.url)
    }

    @Test
    fun `the Console endpoint on the same host gets NO session header`() {
        val request = Request.Builder()
            .url("https://opencode.ai/inference/openai/v1/models")
            .build()
        val (chain, captured) = chainFor(request)

        OpenCodeSessionInterceptor("test-session-id").intercept(chain)

        assertThat(captured.captured).isSameInstanceAs(request)
    }

    @Test
    fun `existing session header is overwritten with the stable id`() {
        val request = Request.Builder()
            .url("https://opencode.ai/inference/go/openai/v1/models")
            .header(OpenCodeSessionInterceptor.SESSION_HEADER, "stale")
            .build()
        val (chain, captured) = chainFor(request)

        OpenCodeSessionInterceptor("stable-id").intercept(chain)

        assertThat(captured.captured.header(OpenCodeSessionInterceptor.SESSION_HEADER)).isEqualTo("stable-id")
    }

    @Test
    fun `foreign hosts get no session header`() {
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .build()
        val (chain, captured) = chainFor(request)

        OpenCodeSessionInterceptor("test-session-id").intercept(chain)

        // Byte-identical request — the same instance, untouched.
        assertThat(captured.captured).isSameInstanceAs(request)
    }
}
