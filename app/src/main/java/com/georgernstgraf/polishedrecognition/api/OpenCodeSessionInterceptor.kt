package com.georgernstgraf.polishedrecognition.api

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * #153: OpenCode's **Go** endpoint refuses requests without a session id —
 * HTTP 400 `Request is missing x-opencode-session and cannot be routed
 * efficiently` (device finding 2026-11-26 on the owner's OnePlus; the docs'
 * "no longer necessary" note covers ANOTHER client's native header, not
 * this one).
 *
 * Owner requirement: the header is sent ONLY for the Go endpoint — a URL
 * under [OPENCODE_HOST] whose path starts with [GO_PATH_PREFIX]. The
 * Console endpoint (`/inference/openai/…`), any other opencode.ai path and
 * every foreign host get the request untouched — byte-identical on the wire.
 *
 * The id is a routing/prompt-cache hint, not an additional identity: the
 * workspace is already identified by the API key beside it.
 */
class OpenCodeSessionInterceptor(private val sessionId: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isGoRequest(request.url)) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header(SESSION_HEADER, sessionId).build()
        )
    }

    companion object {
        /** The header OpenCode Go requires on every request (#153). */
        const val SESSION_HEADER = "x-opencode-session"

        /** The only host the session header may ever be sent to. */
        const val OPENCODE_HOST = "opencode.ai"

        /** The Go endpoint's path prefix — the header's exclusive scope. */
        const val GO_PATH_PREFIX = "/inference/go/"

        fun isGoRequest(url: HttpUrl): Boolean =
            url.host == OPENCODE_HOST && url.encodedPath.startsWith(GO_PATH_PREFIX)
    }
}
