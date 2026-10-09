package com.georgernstgraf.polishedrecognition.config

/**
 * Classifies STT endpoint URLs as LOCAL vs cloud (#117): the shadow seam
 * comparison runs only against local endpoints — the extra full-context
 * pass costs GPU time on the user's own box, but real money against cloud
 * providers. Pure string heuristic — no DNS on any caller thread.
 */
object SttEndpointPolicy {

    /** RFC 1918 / loopback / localhost / mDNS / bracketed IPv6 literals. */
    private val LOCAL_HOST = Regex("^127\\.|^10\\.|^192\\.168\\.|^172\\.(1[6-9]|2\\d|3[01])\\.")

    fun isLocal(baseUrl: String): Boolean {
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local")) return true
        if (host.startsWith("[")) return true // bracketed IPv6 loop/link-local literals
        return LOCAL_HOST.containsMatchIn(host)
    }
}
