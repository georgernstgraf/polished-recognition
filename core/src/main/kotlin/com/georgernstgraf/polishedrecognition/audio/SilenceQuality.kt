package com.georgernstgraf.polishedrecognition.audio

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * #126 silence-quality metric — the replacement for the deleted
 * absolute-threshold silence search (`SilenceCutter`, #117/#124): a
 * continuous deficit-area quality Q over a per-fragment ADAPTIVE axis, cut
 * at the area centroid.
 *
 * The live failure it fixes: a word-final fricative + stop closure +
 * following pause merge into one "silence run" under an absolute threshold,
 * and the geometric-center cut landed inside the word ("… informiert
 * **bist. bist.** …" — the word was split and transcribed by BOTH
 * fragments). The new design makes silence a *quality*, not a Boolean:
 *
 * ```
 * M      = mean frame level over the search span (recomputed per fragment —
 *          room-change-safe, gain-tracking)
 * X      = k·M + c          — the axis; k multiplicative (gain-invariant
 *          separation), c the only absolute element (whisper/quiet-room
 *          anchor)
 * d(t)   = max(0, 1 − e(t)/X)   — normalized deficit curve
 * candidates = contiguous d>0 regions in the search span
 * Q      = deficit AREA of a candidate (depth×duration in one value; depth
 *          is bounded by the axis, so a Q threshold implies a minimum pause
 *          duration — the #128 stitching gate builds on this)
 * cut    = AREA CENTROID of the best-Q candidate, clamped to keep a
 *          K-ms margin from the candidate's over-axis edges
 * ```
 *
 * Frames sit ABOVE the axis ⇒ zero deficit — a fricative above the axis is
 * HARD-EXCLUDED from the candidate (no dilution of the centroid); when the
 * span's mean is low enough that the axis threads under the fricative band,
 * its shallow deficit merely pulls the centroid slightly left while the
 * deep pause still dominates the area.
 *
 * The measurement runs on an **80-Hz-high-passed copy** of the span
 * ([PcmConditioner.highPassOnly] — the same one-pole the upload path uses,
 * WITHOUT its peak normalization): a loud 25-Hz hum otherwise beats against
 * the 10-ms frame grid and fabricates phase-aligned fake silences; the
 * decision must see what the server sees.
 *
 * No candidate (min ≈ M — continuous speech, or a whisper room where the
 * absolute anchor c holds the axis above everything) → the cut is still
 * best-effort: the QUIETEST frame's centre, with Q = 0. The cut is always
 * placed; quality only decides STITCHING later (#128 —
 * `candidateFound == (Q > 0)` is the interim `silenceAligned` mapping).
 *
 * Pure-JVM, allocation-light, integer-first: frames, candidate edges, the
 * margin and all offsets are integers; floats appear only where unavoidable
 * — the per-frame RMS (a sqrt of the integer mean square), the axis/deficit
 * arithmetic derived from it, and the centroid division. The scan runs on
 * the FragmentPreparer's encoder thread over already-buffered PCM (a ≤10-s
 * span is one pass + one filter pass — microseconds-to-milliseconds) and
 * must never show up in the STT latency measurements.
 */
object SilenceQuality {

    /**
     * 10-ms analysis frame at 16 kHz mono 16-bit: 160 samples = 320 bytes.
     * Frame-aligned cut offsets are automatically sample-aligned (even).
     */
    const val FRAME_BYTES = 320

    /** Samples per frame — the divisor of the integer mean square. */
    private const val FRAME_SAMPLES = 160

    /**
     * The floor statistic is a low percentile of the QUIETEST frames, never
     * a single-frame minimum (one fluke frame must not read as the room
     * floor). 5 = P5-ish; at spans below ~20 frames it degenerates toward
     * the minimum (documented, acceptable — real spans are ≥ 50 frames).
     */
    private const val FLOOR_PERCENTILE = 5

    /**
     * One evaluated search span. `min` is the P5-ish floor statistic (NOT a
     * single-frame min); `Q` is the deficit area of the best candidate in
     * 10-ms frame units (Q ≤ the candidate's frame count — depth ≤ 1);
     * [centroidOffset] is the raw area centroid, [cutOffset] the
     * margin-clamped cut actually used (both byte offsets relative to the
     * span start, frame-centre aligned and thus sample-aligned). With no
     * candidate both offsets are the quietest frame's centre and Q = 0.
     */
    data class Result(
        val meanLevel: Double,
        val floorLevel: Double,
        val maxLevel: Double,
        val axis: Double,
        val quality: Double,
        val centroidOffset: Int,
        val cutOffset: Int,
        val candidateFound: Boolean
    )

    /**
     * Evaluates the search-span [pcm] (16-bit LE mono) and returns the cut
     * decision, or `null` when the span holds less than one full frame (the
     * caller falls back to its hard cut). [marginFrames] is the cut's
     * minimum distance from the candidate's over-axis edges, in 10-ms
     * frames (K ms quantized to the frame grid — the caller converts).
     *
     * Deterministic: ties (equal Q, equal level) always resolve to the
     * EARLIEST candidate/frame.
     */
    fun evaluate(
        pcm: ByteArray,
        sampleRate: Int,
        axisK: Double,
        axisC: Double,
        marginFrames: Int
    ): Result? {
        val frames = pcm.size / FRAME_BYTES
        if (frames == 0) return null

        // The decision sees what the server sees: the 80-Hz high-passed
        // copy (no normalization — a scalar gain must not move the axis).
        val hp = PcmConditioner.highPassOnly(pcm, sampleRate)

        // Per-frame RMS levels — the only float step (sqrt of the integer
        // mean square, the deleted SilenceCutter's accumulation pattern).
        val levels = DoubleArray(frames)
        for (frame in 0 until frames) {
            val base = frame * FRAME_BYTES
            var sum = 0L
            for (i in 0 until FRAME_BYTES step 2) {
                val sample = ((hp[base + i + 1].toInt()) shl 8) or (hp[base + i].toInt() and 0xFF)
                sum += sample.toLong() * sample
            }
            levels[frame] = sqrt(sum.toDouble() / FRAME_SAMPLES)
        }

        val mean = levels.sum() / frames
        val max = levels.max()
        val floor = floorPercentile(levels)

        val axis = axisK * mean + axisC
        if (axis <= 0.0) {
            // A non-positive axis cannot host a deficit — best-effort path.
            return forcedResult(levels, mean, floor, max, axis)
        }

        // Candidates: maximal contiguous runs of below-axis frames. The
        // best candidate is the largest deficit AREA (earliest on a tie).
        var bestQ = 0.0
        var bestWeighted = 0.0
        var bestStart = -1
        var bestEnd = -1
        var i = 0
        while (i < frames) {
            if (levels[i] >= axis) {
                i++
                continue
            }
            var j = i
            var q = 0.0
            var weighted = 0.0
            while (j < frames && levels[j] < axis) {
                val d = 1.0 - levels[j] / axis
                q += d
                weighted += d * j
                j++
            }
            if (q > bestQ) {
                bestQ = q
                bestWeighted = weighted
                bestStart = i
                bestEnd = j
            }
            i = j
        }

        if (bestStart < 0) {
            // No frame below the axis (continuous speech, or a whisper room
            // where the absolute anchor holds the axis above everything).
            return forcedResult(levels, mean, floor, max, axis)
        }

        val centroidFrame = bestWeighted / bestQ
        val centroidOffset = frameCentre(centroidFrame.roundToInt())
        // Margin: the cut keeps >= marginFrames distance from the
        // candidate's over-axis edges (the below-axis run's first/last
        // frame). A candidate too short to host the margin still gets cut —
        // best-effort at its midpoint (the margin is a preference, not a
        // rejection; quality gates are #128's job).
        val lo = bestStart + marginFrames
        val hi = bestEnd - 1 - marginFrames
        val cutFrame = if (lo > hi) {
            (bestStart + bestEnd) / 2
        } else {
            centroidFrame.roundToInt().coerceIn(lo, hi)
        }
        return Result(
            meanLevel = mean,
            floorLevel = floor,
            maxLevel = max,
            axis = axis,
            quality = bestQ,
            centroidOffset = centroidOffset,
            cutOffset = frameCentre(cutFrame),
            candidateFound = true
        )
    }

    /** The best-effort decision when no candidate exists: quietest frame, Q = 0. */
    private fun forcedResult(
        levels: DoubleArray,
        mean: Double,
        floor: Double,
        max: Double,
        axis: Double
    ): Result {
        var quietest = 0
        for (frame in levels.indices) {
            if (levels[frame] < levels[quietest]) quietest = frame
        }
        val offset = frameCentre(quietest)
        return Result(
            meanLevel = mean,
            floorLevel = floor,
            maxLevel = max,
            axis = axis,
            quality = 0.0,
            centroidOffset = offset,
            cutOffset = offset,
            candidateFound = false
        )
    }

    /** Byte offset of a frame's centre — FRAME_BYTES / 2 is even, so sample-aligned. */
    private fun frameCentre(frame: Int): Int = frame * FRAME_BYTES + FRAME_BYTES / 2

    /** P5-ish floor: the [FLOOR_PERCENTILE]-th percentile of the sorted levels. */
    private fun floorPercentile(levels: DoubleArray): Double {
        val sorted = levels.sorted()
        val index = (ceil(sorted.size * FLOOR_PERCENTILE / 100.0).toInt() - 1)
            .coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

/**
 * Which part of the forward search window the #126 metric measures (#129
 * study arm; the owner decision for production is the late half).
 */
enum class SearchSpan {
    /**
     * The LATE half `[nominalEnd + search/2, nominalEnd + search]`
     * (`[1.5S, 2S]` when `search = S`) — production default. Keeps the cut
     * away from the nominal boundary (where a word-final fricative coda
     * lives, the live #126 failure) and matches the #124 fallback range.
     */
    LATE_HALF,

    /** The FULL forward window `[nominalEnd, nominalEnd + search]`. */
    FULL_WINDOW
}
