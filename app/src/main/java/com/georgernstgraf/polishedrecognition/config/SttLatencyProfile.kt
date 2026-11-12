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

/** Persisted per-provider profile: the request samples plus the local cost/seam evidence. */
data class SttProviderProfile(
    val samples: List<SttProfileSample> = emptyList(),
    /**
     * #124: cumulative local ENCODE cost for this provider+format —
     * `sum(encodeMs)` over `sum(audio seconds)`. The encode rate is what
     * makes OGG punish large slices (measured 7.9 %/s vs 0.1 %/s for WAV) and
     * it sits on the critical path before every request.
     */
    val encodeMsTotal: Long = 0,
    val encodeAudioMsTotal: Long = 0,
    /**
     * #124: the search-window percentage the adaptive rule currently holds
     * for this provider+format (`null` = bootstrap 30 %).
     */
    val searchPercent: Double? = null,
    /** #124: searched cuts and how many of them found no silence candidate. */
    val seamsTotal: Int = 0,
    val seamsForced: Int = 0,
    /**
     * #124: the counter values the last percentage step was taken at — makes
     * the adaptive rule WINDOWED (only seams since the last step count) and
     * idempotent (no double step when the value is resolved twice).
     */
    val seamsTotalAtStep: Int = 0,
    val seamsForcedAtStep: Int = 0
) {
    /** Encode seconds per audio second, `null` until something was measured. */
    fun encodeRate(): Double? =
        if (encodeAudioMsTotal > 0) encodeMsTotal.toDouble() / encodeAudioMsTotal else null
}

class SttLatencyProfile private constructor(
    val interceptSeconds: Double,
    val slopeSecondsPerSecond: Double
) {

    companion object {
        /** Auto-sizing starts only with this many usable samples (bootstrap). */
        const val MIN_SAMPLES = 12

        /**
         * #124: the latency budget the auto-sizer optimises against —
         * stop-tap → raw text. FIXED IN CODE (owner decision 2026-11-12, no
         * UI). 2.0 s reproduces the measured status quo on gregor (median
         * 1.98 s) and lets GROQ use very large slices (measured: 137 s).
         */
        const val BUDGET_STOP_TO_RAW_SECONDS = 2.0

        /**
         * #124: quality floor for the derived slice. The sizer never proposes
         * a slice whose SHORTEST possible fragment (`slice·(1−p)`) would be
         * below this — a pathological fit must not flood the transcript with
         * seams. Internal invariant only: the Settings fields accept 5–180 s
         * regardless (a user may shoot their own foot, owner decision).
         */
        const val MIN_EFFECTIVE_FRAGMENT_SECONDS = 8.0

        /** Slice bounds (seconds) — the same range the Settings field offers. */
        const val MIN_SLICE_SECONDS = 5.0
        const val MAX_SLICE_SECONDS = 180.0

        /** Bootstrap slice before the profile is ready (owner set). */
        const val BOOTSTRAP_SLICE_SECONDS = 21.0

        /** Search-window percentage bounds and bootstrap (#124, owner set). */
        const val MIN_SEARCH_PERCENT = 10.0
        const val MAX_SEARCH_PERCENT = 60.0
        const val BOOTSTRAP_SEARCH_PERCENT = 30.0

        /** A seam must be measured this often before the adaptive % steps. */
        const val MIN_SEAM_SAMPLES = 12

        /** Adaptive % step and its trigger band (owner rule). */
        const val SEARCH_PERCENT_STEP = 5.0
        const val FORCED_RATE_HIGH = 0.02
        const val FORCED_RATE_LOW = 0.005

        /** Local encode cost fallbacks when no measurement exists yet. */
        const val DEFAULT_ENCODE_RATE_OGG = 0.079
        const val DEFAULT_ENCODE_RATE_WAV = 0.001

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
         * #124: the derived SLICE — the largest slice whose predicted
         * `stop→raw` still fits [BUDGET_STOP_TO_RAW_SECONDS], respecting the
         * quality floor `slice ≥ MIN_EFFECTIVE_FRAGMENT_SECONDS / (1 − p)`.
         *
         * Cost model, measured on 787 real requests:
         * `stop→raw ≈ encode(Tail) + (encode + t)²/(2·L̄) + a + b·Tail`
         * with `L̄ = slice·(1 − p/2)`, the tail uniformly in `[0, slice]`
         * (mean `slice/2`), `encode = encodeRate·L`, `t = a + b·L`. The
         * third term is the expected wait for the request that happens to be
         * in flight when the user taps.
         *
         * Monotone in the slice, so the search is a simple descending walk.
         * When even the floor violates the budget, the FLOOR WINS (quality
         * over latency).
         */
        fun chooseSlice(
            fit: SttLatencyProfile,
            encodeRateSecondsPerSecond: Double,
            searchPercent: Double,
            budgetSeconds: Double = BUDGET_STOP_TO_RAW_SECONDS
        ): Double {
            val p = (searchPercent / 100.0).coerceIn(0.0, 0.95)
            val floor = (MIN_EFFECTIVE_FRAGMENT_SECONDS / (1.0 - p))
                .coerceIn(MIN_SLICE_SECONDS, MAX_SLICE_SECONDS)
            var slice = MAX_SLICE_SECONDS
            while (slice > floor && predictedStopToRaw(slice, fit, encodeRateSecondsPerSecond, p) > budgetSeconds) {
                slice -= 1.0
            }
            return slice.coerceAtLeast(floor).coerceIn(MIN_SLICE_SECONDS, MAX_SLICE_SECONDS)
        }

        /** The #124 cost model behind [chooseSlice] (exposed for tests + the log line). */
        fun predictedStopToRaw(
            sliceSeconds: Double,
            fit: SttLatencyProfile,
            encodeRateSecondsPerSecond: Double,
            searchPercentFraction: Double
        ): Double {
            val p = searchPercentFraction.coerceIn(0.0, 0.95)
            val meanLength = sliceSeconds * (1.0 - p / 2.0)
            val encode = encodeRateSecondsPerSecond * meanLength
            val request = fit.interceptSeconds + fit.slopeSecondsPerSecond * meanLength
            val wait = (encode + request) * (encode + request) / (2.0 * meanLength)
            val tailEncode = encodeRateSecondsPerSecond * (sliceSeconds / 2.0)
            val tailRequest = fit.interceptSeconds + fit.slopeSecondsPerSecond * (sliceSeconds / 2.0)
            return tailEncode + wait + tailRequest
        }

        /**
         * #124: the search-window percentage, adapted from the measured
         * FORCED-CUT rate (no candidate found in the window). Small p = long
         * fragments = few seams, so p may only grow when the window really is
         * too small:
         * `rate > 2 %` → +5 %, `rate < 0.5 %` → −5 %, else unchanged.
         */
        fun nextSearchPercent(
            currentPercent: Double,
            seamsTotal: Int,
            seamsForced: Int
        ): Double {
            val current = currentPercent.coerceIn(MIN_SEARCH_PERCENT, MAX_SEARCH_PERCENT)
            if (seamsTotal < MIN_SEAM_SAMPLES) return current
            val rate = seamsForced.toDouble() / seamsTotal
            return when {
                rate > FORCED_RATE_HIGH -> current + SEARCH_PERCENT_STEP
                rate < FORCED_RATE_LOW -> current - SEARCH_PERCENT_STEP
                else -> current
            }.coerceIn(MIN_SEARCH_PERCENT, MAX_SEARCH_PERCENT)
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}

/**
 * Resolves the #124 geometry for the CURRENT STT provider: the manual
 * overrides win, otherwise the provider's measured profile drives both
 * values. **Pure read** — the owner removed the stepwise adaptation
 * (2026-11-12), so nothing is written back here and the value shown in the
 * Settings grey field is exactly the value the next session uses.
 *
 * - [sliceSeconds]: the cost-model search over `stop→raw ≤ 2.0 s` (see
 *   [SttLatencyProfile.chooseSlice]) — `null` when the profile is not ready
 *   (< [SttLatencyProfile.MIN_SAMPLES]), the caller keeps its default.
 * - [searchPercent]: the adaptive forced-rate rule (see
 *   [SttLatencyProfile.nextSearchPercent]) — `null` under the seam-sample
 *   bootstrap, the caller keeps 30 %.
 */
class SttFragmentSizer(private val settings: SettingsStore) {

    /** The slice in seconds, or `null` when auto-sizing is not ready. */
    fun sliceSeconds(): Double? {
        settings.sliceSecondsOverride?.let { override ->
            return if (override >= 1f) override.toDouble() else null
        }
        val config = settings.sttProvider ?: return null
        val mediaType = if (settings.compressAudio) "audio/ogg" else "audio/wav"
        val profile = settings.sttProfile(config.baseUrl, config.model, mediaType) ?: return null
        val fit = SttLatencyProfile.fit(profile.samples) ?: return null
        val percent = searchPercent() ?: SttLatencyProfile.BOOTSTRAP_SEARCH_PERCENT
        val encodeRate = profile.encodeRate()
            ?: if (settings.compressAudio) {
                SttLatencyProfile.DEFAULT_ENCODE_RATE_OGG
            } else {
                SttLatencyProfile.DEFAULT_ENCODE_RATE_WAV
            }
        return SttLatencyProfile.chooseSlice(fit, encodeRate, percent)
    }

    /**
     * The search-window percentage, or `null` when no provider is configured.
     *
     * The adaptive rule is WINDOWED over the seams since the last step: 12+
     * new seams in the window and a forced rate above 2 %/below 0.5 % move it
     * by 5 %. A due step is persisted here (with the window re-anchored), so
     * resolving the value twice can never double-step — this is the one place
     * where reading legitimately advances the adaptation.
     */
    fun searchPercent(): Double? {
        settings.searchPercentOverride?.let { override ->
            return if (override > 0) override.toDouble() else null
        }
        val config = settings.sttProvider ?: return null
        val mediaType = if (settings.compressAudio) "audio/ogg" else "audio/wav"
        val profile = settings.sttProfile(config.baseUrl, config.model, mediaType) ?: return null
        val current = profile.searchPercent ?: SttLatencyProfile.BOOTSTRAP_SEARCH_PERCENT
        val windowTotal = profile.seamsTotal - profile.seamsTotalAtStep
        if (windowTotal < SttLatencyProfile.MIN_SEAM_SAMPLES) return current
        val windowForced = profile.seamsForced - profile.seamsForcedAtStep
        val next = SttLatencyProfile.nextSearchPercent(current, windowTotal, windowForced)
        if (next == current) return current
        settings.setSttProfileSearchPercent(config.baseUrl, config.model, mediaType, next)
        return next
    }
}
