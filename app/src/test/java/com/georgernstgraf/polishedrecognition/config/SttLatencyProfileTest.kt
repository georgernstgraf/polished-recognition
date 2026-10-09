package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-math tests of the #116 Phase 2 robust latency fit (Theil–Sen) and the
 * derived fragment size — no Android deps, no Robolectric.
 */
class SttLatencyProfileTest {

    private fun sample(seconds: Double, elapsedSeconds: Double) =
        SttProfileSample((seconds * 1000).toLong(), (elapsedSeconds * 1000).toLong())

    @Test
    fun `below the sample gate there is no fit`() {
        val samples = (0 until SttLatencyProfile.MIN_SAMPLES - 1).map {
            sample(10.0 + it, 1.0 + it * 0.05)
        }
        assertThat(SttLatencyProfile.fit(samples)).isNull()
    }

    @Test
    fun `exact line is recovered from the samples`() {
        // t = 0.6 + 0.05·S — samples on the line
        val samples = (0 until SttLatencyProfile.MIN_SAMPLES).map {
            sample(10.0 + it * 3.0, 0.6 + 0.05 * (10.0 + it * 3.0))
        }
        val fit = SttLatencyProfile.fit(samples)!!
        assertThat(fit.slopeSecondsPerSecond).isWithin(1e-9).of(0.05)
        assertThat(fit.interceptSeconds).isWithin(1e-9).of(0.6)
    }

    /** The robustness point: a single wild outlier must not drag the line. */
    @Test
    fun `a single outlier does not drag the fit`() {
        val samples = (0 until SttLatencyProfile.MIN_SAMPLES).map {
            sample(10.0 + it * 3.0, 0.6 + 0.05 * (10.0 + it * 3.0))
        } + sample(45.0, 30.0) // 30 s for 45 s audio — wildly off the line
        val fit = SttLatencyProfile.fit(samples)!!
        assertThat(fit.slopeSecondsPerSecond).isWithin(0.005).of(0.05)
        assertThat(fit.interceptSeconds).isWithin(0.15).of(0.6)
    }

    @Test
    fun `non-positive slope has no size lever`() {
        // faster with MORE audio — nonsense physically, rejected by the fit
        val samples = (0 until SttLatencyProfile.MIN_SAMPLES).map {
            sample(10.0 + it * 3.0, 5.0 - it * 0.1)
        }
        assertThat(SttLatencyProfile.fit(samples)).isNull()
    }

    @Test
    fun `no duration spread has no slope information`() {
        val samples = (0 until SttLatencyProfile.MIN_SAMPLES).map {
            SttProfileSample(21_000, 500L + it * 10)
        }
        assertThat(SttLatencyProfile.fit(samples)).isNull()
    }

    @Test
    fun `derivation follows the owner formula and clamps`() {
        // t = 0.6 + 0.05·S → S = (1.3 − 0.3 − 1.5·0.6)/0.05 = 2.0 → clamped to 7
        val fast = SttLatencyProfile.fit(
            (0 until 12).map { sample(10.0 + it * 3.0, 0.6 + 0.05 * (10.0 + it * 3.0)) }
        )!!
        assertThat(fast.fragmentSeconds()).isEqualTo(SttLatencyProfile.MIN_FRAGMENT_SECONDS)

        // t = 0.06 + 0.02·S → S = (1.0 − 0.09)/0.02 = 45.5 s (inside the clamp)
        val mid = SttLatencyProfile.fit(
            (0 until 12).map { sample(10.0 + it * 3.0, 0.06 + 0.02 * (10.0 + it * 3.0)) }
        )!!
        assertThat(mid.fragmentSeconds()).isWithin(1e-9).of(45.5)

        // t = 0.01 + 0.001·S → S = (1.0 − 0.015)/0.001 = 985 → clamped to 60
        val turbo = SttLatencyProfile.fit(
            (0 until 12).map { sample(10.0 + it * 3.0, 0.01 + 0.001 * (10.0 + it * 3.0)) }
        )!!
        assertThat(turbo.fragmentSeconds()).isEqualTo(SttLatencyProfile.MAX_FRAGMENT_SECONDS)
    }

    @Test
    fun `stepwise caps the change at ±50 percent per step`() {
        assertThat(SttLatencyProfile.stepwise(null, 13.0)).isEqualTo(13.0)
        // derived 13 from an applied 21 → within [10.5, 42] → applied unchanged
        assertThat(SttLatencyProfile.stepwise(21.0, 13.0)).isEqualTo(13.0)
        // derived 4 (clamped floor was 7 in practice, but stepwise guards anyway)
        assertThat(SttLatencyProfile.stepwise(21.0, 4.0)).isEqualTo(10.5)
        // derived 100 from an applied 21 → capped at 42
        assertThat(SttLatencyProfile.stepwise(21.0, 100.0)).isEqualTo(42.0)
    }
}
