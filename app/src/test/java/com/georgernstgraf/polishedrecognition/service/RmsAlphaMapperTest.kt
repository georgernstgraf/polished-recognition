package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RmsAlphaMapperTest {

    @Test
    fun `below noise gate maps to alpha floor`() {
        assertThat(RmsAlphaMapper.alpha(0f)).isEqualTo(RmsAlphaMapper.ALPHA_FLOOR)
        assertThat(RmsAlphaMapper.alpha(RmsAlphaMapper.NOISE_FLOOR))
            .isEqualTo(RmsAlphaMapper.ALPHA_FLOOR)
    }

    @Test
    fun `typical ambient noise stays near the floor`() {
        val a = RmsAlphaMapper.alpha(100f)
        assertThat(a).isLessThan(RmsAlphaMapper.ALPHA_FLOOR + 0.01f)
    }

    @Test
    fun `typical speech clearly exceeds the floor`() {
        assertThat(RmsAlphaMapper.alpha(400f)).isAtLeast(0.8f)
        assertThat(RmsAlphaMapper.alpha(800f)).isAtLeast(0.88f)
        assertThat(RmsAlphaMapper.alpha(2000f)).isAtLeast(0.95f)
    }

    @Test
    fun `alpha is monotonically non-decreasing in rms`() {
        var prev = RmsAlphaMapper.alpha(0f)
        for (rms in 1..3000 step 50) {
            val a = RmsAlphaMapper.alpha(rms.toFloat())
            assertThat(a).isAtLeast(prev)
            prev = a
        }
    }

    @Test
    fun `alpha never leaves floor-one bounds`() {
        assertThat(RmsAlphaMapper.alpha(-50f)).isEqualTo(RmsAlphaMapper.ALPHA_FLOOR)
        assertThat(RmsAlphaMapper.alpha(1_000_000f)).isAtMost(1f)
    }

    @Test
    fun `smoothing converges toward a constant signal`() {
        var smoothed = 0f
        repeat(50) { smoothed = RmsAlphaMapper.smooth(smoothed, 1000f) }
        assertThat(smoothed).isWithin(1f).of(1000f)
    }

    @Test
    fun `smoothing bounds the influence of a single spike`() {
        val base = 500f
        val spiked = RmsAlphaMapper.smooth(base, 5000f)
        assertThat(spiked).isLessThan(base + (5000f - base) * 0.5f)
        assertThat(spiked).isGreaterThan(base)
    }
}
