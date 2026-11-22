package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * #188: the tiered "when" line. The tiers are local-day based (never 24-hour
 * buckets), which is what makes them survive a DST night, and the day/month
 * names come from the locale.
 */
class WorkbenchStampTest {

    private val vienna: ZoneId = ZoneId.of("Europe/Vienna")
    private val locale: Locale = Locale.US

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId = vienna
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `a page from today shows the 24-hour time only`() {
        val now = at(2026, 11, 22, 15, 0)

        assertThat(WorkbenchStamp.label(at(2026, 11, 22, 11, 5), now, locale, vienna))
            .isEqualTo("11:05")
    }

    @Test
    fun `yesterday still counts as a day, not as hours`() {
        // 23:50 yesterday vs 00:10 today: the calendar day decides, so this is
        // a dated label and not "20 minutes ago".
        val now = at(2026, 11, 22, 0, 10)

        assertThat(WorkbenchStamp.label(at(2026, 11, 21, 23, 50), now, locale, vienna))
            .isEqualTo("Sat 23:50")
    }

    @Test
    fun `inside the week the weekday is added`() {
        val now = at(2026, 11, 22, 15, 0)

        assertThat(WorkbenchStamp.label(at(2026, 11, 16, 9, 30), now, locale, vienna))
            .isEqualTo("Mon 09:30")
    }

    @Test
    fun `exactly seven days back is still the weekday tier`() {
        val now = at(2026, 11, 22, 15, 0)

        assertThat(WorkbenchStamp.label(at(2026, 11, 15, 9, 30), now, locale, vienna))
            .isEqualTo("Sun 09:30")
    }

    @Test
    fun `eight days back drops to the date`() {
        val now = at(2026, 11, 22, 15, 0)

        assertThat(WorkbenchStamp.label(at(2026, 11, 14, 9, 30), now, locale, vienna))
            .isEqualTo("14 Nov")
    }

    @Test
    fun `a DST night stays one local day`() {
        // Europe/Vienna jumps from 02:00 to 03:00 on 2026-03-29: the night is
        // 23 hours long, the two instants are still the same local date.
        val now = at(2026, 3, 29, 4, 0)

        assertThat(WorkbenchStamp.label(at(2026, 3, 29, 1, 30), now, locale, vienna))
            .isEqualTo("01:30")
    }

    @Test
    fun `another year appends the year`() {
        val now = at(2026, 1, 10, 10, 0)

        assertThat(WorkbenchStamp.label(at(2025, 12, 20, 10, 0), now, locale, vienna))
            .isEqualTo("20 Dec 2025")
    }

    @Test
    fun `no instant means no label`() {
        assertThat(WorkbenchStamp.label(null, at(2026, 11, 22, 15, 0), locale, vienna)).isNull()
    }
}
