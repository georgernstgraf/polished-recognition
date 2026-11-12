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
    fun `slice search picks the largest slice inside the budget`() {
        // t = 0.06 + 0.02·S: a slow provider. 30 % window, no encode cost (WAV).
        val slow = SttLatencyProfile.fit(
            (0 until 12).map { sample(10.0 + it * 3.0, 0.06 + 0.02 * (10.0 + it * 3.0)) }
        )!!
        val slowSlice = SttLatencyProfile.chooseSlice(slow, 0.001, 30.0)
        assertThat(slowSlice >= SttLatencyProfile.MIN_SLICE_SECONDS && slowSlice <= SttLatencyProfile.MAX_SLICE_SECONDS)
            .isTrue()
        // the model's own prediction for it must fit the budget, the next
        // second must not (monotone search, largest fitting slice)
        assertThat(
            SttLatencyProfile.predictedStopToRaw(slowSlice, slow, 0.001, 0.30) <=
                SttLatencyProfile.BUDGET_STOP_TO_RAW_SECONDS + 1e-9
        ).isTrue()
        if (slowSlice < SttLatencyProfile.MAX_SLICE_SECONDS) {
            assertThat(SttLatencyProfile.predictedStopToRaw(slowSlice + 1.0, slow, 0.001, 0.30))
                .isGreaterThan(SttLatencyProfile.BUDGET_STOP_TO_RAW_SECONDS)
        }

        // t = 0.01 + 0.001·S: a turbo provider → the 180-s cap binds
        val turbo = SttLatencyProfile.fit(
            (0 until 12).map { sample(10.0 + it * 3.0, 0.01 + 0.001 * (10.0 + it * 3.0)) }
        )!!
        assertThat(SttLatencyProfile.chooseSlice(turbo, 0.001, 30.0))
            .isEqualTo(SttLatencyProfile.MAX_SLICE_SECONDS)
    }

    @Test
    fun `the quality floor wins over the budget`() {
        // an absurdly slow provider: even a tiny slice blows the budget, so
        // the derived slice stops at the floor 8 s / (1 − 0.30)
        val awful = SttLatencyProfile.fit(
            (0 until 12).map { sample(10.0 + it * 3.0, 0.5 + 0.5 * (10.0 + it * 3.0)) }
        )!!
        val slice = SttLatencyProfile.chooseSlice(awful, 0.079, 30.0)
        assertThat(slice).isWithin(1e-9).of(8.0 / 0.7)
        assertThat(
            slice * (1.0 - 0.30) >= SttLatencyProfile.MIN_EFFECTIVE_FRAGMENT_SECONDS - 1e-9
        ).isTrue()
    }

    @Test
    fun `the encode rate shrinks the slice for OGG`() {
        val fit = SttLatencyProfile.fit(
            (0 until 12).map { sample(10.0 + it * 3.0, 0.06 + 0.02 * (10.0 + it * 3.0)) }
        )!!
        val wav = SttLatencyProfile.chooseSlice(fit, 0.001, 30.0)
        val ogg = SttLatencyProfile.chooseSlice(fit, 0.079, 30.0)
        assertThat(ogg).isLessThan(wav)
    }

    @Test
    fun `the adaptive search percent follows the forced-cut rate`() {
        // bootstrap: too few seams → unchanged
        assertThat(SttLatencyProfile.nextSearchPercent(30.0, seamsTotal = 5, seamsForced = 5))
            .isEqualTo(30.0)
        // > 2 % forced → the window grows
        assertThat(SttLatencyProfile.nextSearchPercent(30.0, seamsTotal = 100, seamsForced = 5))
            .isEqualTo(35.0)
        // < 0.5 % forced → the window shrinks (fewer seams)
        assertThat(SttLatencyProfile.nextSearchPercent(30.0, seamsTotal = 100, seamsForced = 0))
            .isEqualTo(25.0)
        // inside the band → unchanged
        assertThat(SttLatencyProfile.nextSearchPercent(30.0, seamsTotal = 100, seamsForced = 1))
            .isEqualTo(30.0)
        // bounds hold
        assertThat(SttLatencyProfile.nextSearchPercent(60.0, seamsTotal = 100, seamsForced = 50))
            .isEqualTo(SttLatencyProfile.MAX_SEARCH_PERCENT)
        assertThat(SttLatencyProfile.nextSearchPercent(10.0, seamsTotal = 100, seamsForced = 0))
            .isEqualTo(SttLatencyProfile.MIN_SEARCH_PERCENT)
    }
}
