package com.georgernstgraf.polishedrecognition.pipeline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Captures the raw STT/LLM HTTP responses verbatim and writes them (pretty-printed,
 * all fields preserved) to rotating logs via [RotatingJsonLogger]. Routes by URL path;
 * other endpoints (e.g. /models) are ignored. All logging failures are swallowed so
 * they can never break a transcription.
 */
class ResponseLoggerInterceptor(
    private val logger: RotatingJsonLogger
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        try {
            val baseName = routeForLog(chain.request().url.encodedPath)
            if (baseName != null) {
                val raw = response.peekBody(BYTE_CAP).string()
                logger.log(baseName, prettyOrRaw(raw))
            }
        } catch (_: Exception) {
            // never break transcription for logging failures
        }
        return response
    }

    companion object {
        private const val BYTE_CAP = 10L * 1024 * 1024 // 10 MB

        const val STT_RESPONSE = "stt-response"
        const val LLM_RESPONSE = "llm-response"

        /** Returns the log base name for an encoded URL path, or null to skip logging. */
        fun routeForLog(encodedPath: String): String? = when {
            encodedPath.endsWith("audio/transcriptions") -> STT_RESPONSE
            encodedPath.endsWith("chat/completions") -> LLM_RESPONSE
            else -> null
        }

        /** Pretty-prints raw JSON (preserving every field); falls back to the raw string if it isn't valid JSON. */
        fun prettyOrRaw(raw: String): String = try {
            val tree = Gson().fromJson(raw, JsonElement::class.java)
            GsonBuilder().setPrettyPrinting().create().toJson(tree)
        } catch (_: Exception) {
            raw
        }
    }
}
