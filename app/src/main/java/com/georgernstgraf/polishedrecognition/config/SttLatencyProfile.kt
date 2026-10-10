package com.georgernstgraf.polishedrecognition.config

/**
 * Per-provider STT latency profile (#116 Phase 2) — the persisted input and
 * the robust fit the auto fragment sizer derives from.
 *
 * One [SttProfileSample] per SUCCESSFUL STT request: `durationMs` is the
 * uploaded audio duration (S — this INCLUDES the 1-s pre-roll, which is the
 * uploaded-size truth and the correct fit basis) and `elapsedMs` the measured
 * request round-trip (t). Samples are keyed `baseUrl + model + mediaType`
 * (a model or format change invalidates the profile; OGG and WAV never mix).
 *
 * The fit is a Theil–Sen median regression (`t(S) ≈ a + b·S`): the slope is
 * the median over all pairwise slopes, the intercept the median of the
 * residuals. Content variance is real (two same-length cuts measured 2.7 s
 * vs 3.8 s, #64 bench), so a plain least-squares line would let single
 * outliers drag the derived fragment size; the median estimators shrug one
 * bad sample off.
 */
data class SttProfileSample(
    /** Uploaded audio duration in ms (S, pre-roll included). */
    val durationMs: Long,
    /** Measured request round-trip in ms (t). */
    val elapsedMs: Long
)

/** Persisted per-provider profile: the sample window plus the last applied size. */
data class SttProviderProfile(
    val samples: List<SttProfileSample> = emptyList(),
    /**
     * The fragment size (seconds) the auto-sizer last APPLIED for this
     * profile — the anchor of the stepwise adaptation (max ±50 % per step,
     * so a bad fit can never swing the size wildly in one session).
     */
    val lastAppliedSeconds: Double? = null
)

class SttLatencyProfile private constructor(
    val interceptSeconds: Double,
    val slopeSecondsPerSecond: Double
) {

    /**
     * Fragment size derived from the latency target T (owner formula):
     * stop→raw ≈ `0.3 s + 1.5·a + b·S`, so
     * `S = (T − 0.3 − 1.5·a) / b`, clamped to the sane fragment range.
     */
    fun fragmentSeconds(targetSeconds: Double = TARGET_STOP_TO_RAW_SECONDS): Double =
        ((targetSeconds - STOP_OVERHEAD_SECONDS - INTERCEPT_FACTOR * interceptSeconds) / slopeSecondsPerSecond)
            .coerceIn(MIN_FRAGMENT_SECONDS, MAX_FRAGMENT_SECONDS)

    companion object {
        /** Auto-sizing starts only with this many usable samples (bootstrap). */
        const val MIN_SAMPLES = 12

        /** Perceived-latency target (stop-tap → first raw text), owner set. */
        const val TARGET_STOP_TO_RAW_SECONDS = 1.3

        /** Fixed stop-tap overhead in the latency model (encode + delivery). */
        const val STOP_OVERHEAD_SECONDS = 0.3

        /** Intercept safety factor in the latency model (owner formula). */
        const val INTERCEPT_FACTOR = 1.5

        /**
         * Derived fragment size bounds (seconds). The floor was raised 7 → 10 s
         * (#122): the 7-s floor produced ~118 fragments on a 14-min recording
         * — a lot of seams, each a potential word-drop/dup site (see #121). It
         * bounds the *live* fragment size only; the stop-tap tail is taken whole
         * at any length (no floor).
         */
        const val MIN_FRAGMENT_SECONDS = 10.0
        const val MAX_FRAGMENT_SECONDS = 60.0

        /** Max stepwise change per adaptation step (±50 %, owner spec). */
        const val STEP_FACTOR = 2.0

        /** Sample window per profile (bounds the fit's O(n²) pair loop). */
        const val MAX_SAMPLES = 32

        /**
         * Robust fit over [samples]; `null` when the profile is not ready to
         * drive the auto-sizer: too few samples, no duration spread (all
         * same-size uploads — no slope information), or a non-positive slope
         * (a provider whose time does not grow with size has no size lever).
         */
        fun fit(samples: List<SttProfileSample>): SttLatencyProfile? {
            val points = samples
                .filter { it.durationMs > 0 && it.elapsedMs > 0 }
                .map { it.durationMs / 1000.0 to it.elapsedMs / 1000.0 }
            if (points.size < MIN_SAMPLES) return null
            val slopes = ArrayList<Double>(points.size * points.size / 2)
            for (i in points.indices) {
                for (j in i + 1 until points.size) {
                    val dS = points[j].first - points[i].first
                    if (kotlin.math.abs(dS) < 1e-9) continue
                    slopes += (points[j].second - points[i].second) / dS
                }
            }
            if (slopes.isEmpty()) return null
            val b = median(slopes)
            if (b <= 1e-6) return null
            val a = median(points.map { (s, t) -> t - b * s })
            return SttLatencyProfile(a, b)
        }

        /**
         * Stepwise adaptation (owner spec): the applied size may move at most
         * ±50 % per step away from the last applied value — a wild fit is
         * spread over several sessions instead of landing in one leap.
         */
        fun stepwise(previousSeconds: Double?, derivedSeconds: Double): Double {
            if (previousSeconds == null || previousSeconds <= 0.0) return derivedSeconds
            return derivedSeconds.coerceIn(previousSeconds / STEP_FACTOR, previousSeconds * STEP_FACTOR)
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}

/**
 * Resolves the fragment size (seconds) for the CURRENT STT provider (#116
 * Phase 2): the manual override wins; otherwise the provider's measured
 * profile is fitted and the derived size applied stepwise (max ±50 % from
 * the last applied value). `null` when no override exists and the profile
 * is not ready yet (< [SttLatencyProfile.MIN_SAMPLES] usable samples) — the
 * caller then keeps its static default.
 */
class SttFragmentSizer(private val settings: SettingsStore) {

    fun fragmentSeconds(): Double? {
        settings.fragmentSecondsOverride?.let { override ->
            return if (override >= 1f) override.toDouble() else null
        }
        val config = settings.sttProvider ?: return null
        val mediaType = if (settings.compressAudio) "audio/ogg" else "audio/wav"
        val profile = settings.sttProfile(config.baseUrl, config.model, mediaType) ?: return null
        val fit = SttLatencyProfile.fit(profile.samples) ?: return null
        val applied = SttLatencyProfile.stepwise(profile.lastAppliedSeconds, fit.fragmentSeconds())
        settings.setSttProfileAppliedSeconds(config.baseUrl, config.model, mediaType, applied)
        return applied
    }
}
