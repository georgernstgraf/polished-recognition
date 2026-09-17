package com.georgernstgraf.polishedrecognition.service

/**
 * Pure formatting for the IME REC time counter (#71): active mic time as
 * m:ss, rendered as "REC m:ss" while recording and frozen "m:ss" while
 * paused. Negative inputs clamp to zero; hours fold into total minutes.
 */
object RecTimeFormatter {

    fun recording(durationMs: Long): String {
        val (m, s) = split(durationMs)
        return "REC %d:%02d".format(m, s)
    }

    fun paused(durationMs: Long): String {
        val (m, s) = split(durationMs)
        return "%d:%02d".format(m, s)
    }

    private fun split(durationMs: Long): Pair<Long, Long> {
        val totalSeconds = (if (durationMs < 0) 0L else durationMs) / 1000
        return Pair(totalSeconds / 60, totalSeconds % 60)
    }
}
