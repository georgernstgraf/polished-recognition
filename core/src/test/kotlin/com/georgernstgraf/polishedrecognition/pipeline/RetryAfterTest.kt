package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #183: both RFC 9110 forms of `Retry-After` — delta-seconds (what a rate
 * limiter sends) and an HTTP date (what nginx's `limit_req` can send).
 */
class RetryAfterTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `delta seconds are milliseconds`() {
        assertThat(RetryAfter.parse("30", now)).isEqualTo(30_000L)
        assertThat(RetryAfter.parse(" 7 ", now)).isEqualTo(7_000L)
        assertThat(RetryAfter.parse("0", now)).isEqualTo(0L)
    }

    @Test
    fun `an http date is the difference to now`() {
        // Wed, 21 Oct 2015 07:28:00 GMT = 1445412480000
        assertThat(RetryAfter.parse("Wed, 21 Oct 2015 07:28:00 GMT", 1445412480000L - 10_000L))
            .isEqualTo(10_000L)
    }

    @Test
    fun `a date in the past never yields a negative wait`() {
        assertThat(RetryAfter.parse("Wed, 21 Oct 2015 07:28:00 GMT", now)).isEqualTo(0L)
        assertThat(RetryAfter.parse("-5", now)).isEqualTo(0L)
    }

    @Test
    fun `absent or unparseable advice is null, never a guess`() {
        assertThat(RetryAfter.parse(null, now)).isNull()
        assertThat(RetryAfter.parse("", now)).isNull()
        assertThat(RetryAfter.parse("soon", now)).isNull()
    }
}
