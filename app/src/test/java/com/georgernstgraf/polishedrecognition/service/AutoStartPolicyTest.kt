package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoStartPolicyTest {

    @Test
    fun `IDLE with permission auto-starts`() {
        assertThat(
            AutoStartPolicy.shouldAutoStart(VoiceSessionController.State.IDLE, true)
        ).isTrue()
    }

    @Test
    fun `IDLE without permission does not auto-start`() {
        assertThat(
            AutoStartPolicy.shouldAutoStart(VoiceSessionController.State.IDLE, false)
        ).isFalse()
    }

    @Test
    fun `RECORDING never auto-starts`() {
        assertThat(
            AutoStartPolicy.shouldAutoStart(VoiceSessionController.State.RECORDING, true)
        ).isFalse()
    }

    @Test
    fun `PAUSED never auto-starts`() {
        assertThat(
            AutoStartPolicy.shouldAutoStart(VoiceSessionController.State.PAUSED, true)
        ).isFalse()
    }

    @Test
    fun `PROCESSING never auto-starts`() {
        assertThat(
            AutoStartPolicy.shouldAutoStart(VoiceSessionController.State.PROCESSING, true)
        ).isFalse()
    }
}
