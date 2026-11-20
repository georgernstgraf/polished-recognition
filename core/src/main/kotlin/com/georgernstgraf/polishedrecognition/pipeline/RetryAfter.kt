package com.georgernstgraf.polishedrecognition.pipeline

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * #183: the `Retry-After` response header as a wait in milliseconds.
 *
 * RFC 9110 allows two forms and real gateways use both: a **delay in seconds**
 * (`Retry-After: 30`, the common case for a rate limiter) and an **HTTP date**
 * (`Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`, where the wait is the
 * difference to now). Anything unparseable is treated as "no advice" rather
 * than a guess.
 *
 * Pure and clock-injectable, so the date form is testable without waiting.
 */
object RetryAfter {

    /**
     * @return the requested wait in ms (never negative), or `null` when the
     *   header is absent, blank or unparseable
     */
    fun parse(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (header.isNullOrBlank()) return null
        val value = header.trim()
        value.toLongOrNull()?.let { seconds -> return (seconds * 1_000L).coerceAtLeast(0L) }
        return try {
            val atMs = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (atMs - nowMs).coerceAtLeast(0L)
        } catch (_: Throwable) {
            null
        }
    }
}
