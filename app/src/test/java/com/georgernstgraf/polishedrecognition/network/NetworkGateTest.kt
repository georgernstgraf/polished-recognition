package com.georgernstgraf.polishedrecognition.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #186: the two pure decisions the whole offline behaviour rests on. The gate
 * is deliberately trivial — a wrong answer here either spends a request on a
 * dead network or suppresses the one reconnect that would have healed a
 * dictation, so both directions are pinned.
 */
class NetworkGateTest {

    @Test
    fun `a request may only go out on a validated network`() {
        assertThat(NetworkGate.shouldAttempt(online = true)).isTrue()
        assertThat(NetworkGate.shouldAttempt(online = false)).isFalse()
    }

    @Test
    fun `only the edge from offline to validated fires`() {
        assertThat(NetworkGate.reconnectFires(validated = true, previouslyValidated = false))
            .isTrue()
    }

    @Test
    fun `a repeated callback while already online is not a reconnect`() {
        // ConnectivityManager repeats onCapabilitiesChanged for the same
        // network (bandwidth updates, portal re-checks) — each repeat must not
        // start another recovery run.
        assertThat(NetworkGate.reconnectFires(validated = true, previouslyValidated = true))
            .isFalse()
    }

    @Test
    fun `no callback about an unvalidated network ever fires`() {
        assertThat(NetworkGate.reconnectFires(validated = false, previouslyValidated = false))
            .isFalse()
        // losing validation (portal dropped) is not a reason to start a request
        assertThat(NetworkGate.reconnectFires(validated = false, previouslyValidated = true))
            .isFalse()
    }
}
