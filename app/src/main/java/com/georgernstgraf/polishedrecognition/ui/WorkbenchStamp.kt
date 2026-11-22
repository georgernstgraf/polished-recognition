package com.georgernstgraf.polishedrecognition.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * #188: the Workbench's "when" line — one tiered stamp instead of a full
 * date/time everywhere.
 *
 * A page has to say *when* it last changed without pushing the text off the
 * screen, so the label gets coarser the further back the instant lies:
 *
 * | distance | label |
 * |---|---|
 * | same local day | `HH:mm` |
 * | 1–7 local days | `EEE HH:mm` |
 * | older | `d MMM`, plus `yyyy` when the year differs |
 *
 * Deliberate details:
 *
 * - **Local days, not 24-hour buckets**: "today" is the calendar day the user
 *   lives in, so a dictation from 23:50 yesterday reads `Wed 23:50`, never
 *   20 minutes ago. The day distance is measured between LOCAL DATES, which
 *   makes it immune to DST (a 23-hour night is still one day).
 * - **24-hour time**, independent of the locale's preference: the app's
 *   audience is used to it and a mixed `2:05 PM` / `14:05` line is noise.
 * - **Day and month names come from the locale** (`EEE`/`MMM`), so the line
 *   translates; only the shape is fixed.
 *
 * Pure JVM (java.time), so the tier boundaries and the DST edge are testable
 * without Android.
 */
object WorkbenchStamp {

    /** The seven-day tier: `EEE HH:mm` up to and including a week back. */
    private const val WEEK_DAYS = 7L

    private const val SAME_DAY = "HH:mm"
    private const val THIS_WEEK = "EEE HH:mm"
    private const val OLDER = "d MMM"
    private const val OLDER_OTHER_YEAR = "d MMM yyyy"

    /**
     * @param atMs the page's change instant; `null` (a journal session without
     *   a recorded start) has no label at all — the caller renders nothing.
     * @param nowMs the reference instant ("today"), injectable for tests.
     * @param zone the user's zone; a seam for the DST test, production passes
     *   the system default.
     */
    fun label(
        atMs: Long?,
        nowMs: Long,
        locale: Locale,
        zone: ZoneId = ZoneId.systemDefault()
    ): String? {
        if (atMs == null) return null
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val atDate = at.toLocalDate()
        val nowDate = now.toLocalDate()
        val pattern = when {
            atDate == nowDate -> SAME_DAY
            ChronoUnit.DAYS.between(atDate, nowDate) <= WEEK_DAYS -> THIS_WEEK
            at.year == now.year -> OLDER
            else -> OLDER_OTHER_YEAR
        }
        return DateTimeFormatter.ofPattern(pattern, locale).format(at)
    }
}
