package com.georgernstgraf.polishedrecognition.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #185: the reason shown per row and the auto-retry rule — both derived from
 * the #183 failure classes, never invented by the UI.
 */
class RecoveryReasonTest {

    @Test
    fun `the recorded classes map to the four reasons`() {
        assertThat(RecoveryReason.of(false, "AUTH")).isEqualTo(RecoveryReason.Kind.AUTH)
        assertThat(RecoveryReason.of(false, "PROTOCOL")).isEqualTo(RecoveryReason.Kind.PROTOCOL)
        assertThat(RecoveryReason.of(false, "TRANSIENT")).isEqualTo(RecoveryReason.Kind.NETWORK)
        assertThat(RecoveryReason.of(false, "TERMINAL")).isEqualTo(RecoveryReason.Kind.TERMINAL)
    }

    @Test
    fun `a recorded budget wall gets its own reason and is never retried automatically`() {
        // #75: the provider said "over budget" — waiting cannot clear it, so
        // the row must say so and the automatic path must leave it alone
        assertThat(RecoveryReason.of(false, "BUDGET")).isEqualTo(RecoveryReason.Kind.BUDGET)
        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.BUDGET)).isFalse()
    }

    @Test
    fun `an interrupted session carries no class`() {
        assertThat(RecoveryReason.of(false, null)).isEqualTo(RecoveryReason.Kind.INTERRUPTED)
        assertThat(RecoveryReason.of(false, "something-new")).isEqualTo(RecoveryReason.Kind.INTERRUPTED)
    }

    @Test
    fun `a completed session wins over any leftover class`() {
        assertThat(RecoveryReason.of(true, "AUTH")).isEqualTo(RecoveryReason.Kind.COMPLETED)
    }

    @Test
    fun `only network failures and interruptions are retried automatically`() {
        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.NETWORK)).isTrue()
        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.INTERRUPTED)).isTrue()

        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.AUTH)).isFalse()
        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.PROTOCOL)).isFalse()
        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.TERMINAL)).isFalse()
        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.COMPLETED)).isFalse()
    }

    @Test
    fun `a session recorded offline has its own reason and heals by waiting`() {
        // #186: it never reached the provider, so waiting is exactly what
        // helps — the ring retries it on entry, on a reconnect and by hand
        assertThat(RecoveryReason.of(false, "OFFLINE")).isEqualTo(RecoveryReason.Kind.OFFLINE)
        assertThat(RecoveryReason.autoRetryable(RecoveryReason.Kind.OFFLINE)).isTrue()
    }
}
