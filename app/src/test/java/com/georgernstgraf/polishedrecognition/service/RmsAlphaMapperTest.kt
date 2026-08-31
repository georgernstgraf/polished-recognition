package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RmsAlphaMapperTest {

    @Test
    fun `silence maps to alpha floor`() {
        assertThat(RmsAlphaMapper.alpha(0f)).isEqualTo(RmsAlphaMapper.ALPHA_FLOOR)
    }

    @Test
    fun `loud input maps close to full contrast`() {
        val loud = RmsAlphaMapper.alpha(RmsAlphaMapper.RMS_CEILING)
        assertThat(loud).isAtLeast(0.95f)
        assertThat(loud).isAtMost(1f)
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
