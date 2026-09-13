package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PulseAlphaPolicyTest {

    private val floor = RmsAlphaMapper.ALPHA_FLOOR

    @Test
    fun `RECORDING pulses with the louder of breath and voice`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.RECORDING, 0.9f, floor)
        ).isEqualTo(0.9f)
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.RECORDING, floor, 0.8f)
        ).isEqualTo(0.8f)
    }

    @Test
    fun `RECORDING in the deep dwell phase still pulses low`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.RECORDING, 0.15f, 0.15f)
        ).isEqualTo(0.15f)
    }

    @Test
    fun `PAUSED during the deep dwell phase is pinned to full opacity`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.PAUSED, 0.15f, 0.15f)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `PAUSED ignores any stale pulse values`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.PAUSED, 0.42f, 0.73f)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `IDLE is full opacity`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.IDLE, 0.15f, 0.15f)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `PROCESSING is full opacity`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.PROCESSING, 0.15f, 0.15f)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `full opacity is one`() {
        assertThat(PulseAlphaPolicy.FULL).isEqualTo(1f)
    }
}
