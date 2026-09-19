package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI

class PulseAlphaPolicyTest {

    private val floor = 0.3f
    private val ceil = 1f

    @Test
    fun `RECORDING follows the blink value`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.RECORDING, 0.9f)
        ).isEqualTo(0.9f)
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.RECORDING, floor)
        ).isEqualTo(floor)
    }

    @Test
    fun `PAUSED during the dim phase is pinned to full opacity`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.PAUSED, floor)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `PAUSED ignores any stale blink values`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.PAUSED, 0.42f)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `IDLE is full opacity`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.IDLE, floor)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `PROCESSING is full opacity`() {
        assertThat(
            PulseAlphaPolicy.target(VoiceSessionController.State.PROCESSING, floor)
        ).isEqualTo(PulseAlphaPolicy.FULL)
    }

    @Test
    fun `full opacity is one`() {
        assertThat(PulseAlphaPolicy.FULL).isEqualTo(1f)
    }

    @Test
    fun `blink starts at the midpoint rising`() {
        assertThat(PulseAlphaPolicy.blinkAlpha(0f, floor, ceil))
            .isWithin(1e-6f).of((floor + ceil) / 2f)
    }

    @Test
    fun `blink peaks at the ceiling at half-pi`() {
        assertThat(PulseAlphaPolicy.blinkAlpha((PI / 2).toFloat(), floor, ceil))
            .isWithin(1e-6f).of(ceil)
    }

    @Test
    fun `blink returns to the midpoint at pi`() {
        assertThat(PulseAlphaPolicy.blinkAlpha(PI.toFloat(), floor, ceil))
            .isWithin(1e-6f).of((floor + ceil) / 2f)
    }

    @Test
    fun `blink bottoms at the floor at three-half-pi`() {
        assertThat(PulseAlphaPolicy.blinkAlpha((3 * PI / 2).toFloat(), floor, ceil))
            .isWithin(1e-6f).of(floor)
    }

    @Test
    fun `blink never leaves the floor-ceiling bounds`() {
        for (deg in 0..360 step 5) {
            val a = PulseAlphaPolicy.blinkAlpha(
                Math.toRadians(deg.toDouble()).toFloat(), floor, ceil
            )
            assertThat(a).isAtLeast(floor - 1e-6f)
            assertThat(a).isAtMost(ceil + 1e-6f)
        }
    }
}
