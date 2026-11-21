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
}
