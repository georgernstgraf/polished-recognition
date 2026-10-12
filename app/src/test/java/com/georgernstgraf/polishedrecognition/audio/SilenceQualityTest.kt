package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure-JVM tests for the #126 silence-quality metric: synthetic PCM by
 * formula (deterministic — every value below was cross-checked against an
 * exact mirror of the metric), covering the live-failure regression (the
 * "bist" word split) and every design property: fricative hard-exclusion,
 * hum immunity via the high-pass, whisper regimes, gain invariance, room
 * change, depth×duration quality, best-effort forced cuts.
 *
 * Frame material: **Nyquist squares** — sample k = +amp (even) / −amp (odd).
 * Zero-mean (the 80-Hz one-pole passes them at ~0.985·amp, so the level is
 * fully controllable), every full frame carries the SAME RMS, and segment
 * sizes are multiples of [SilenceQuality.FRAME_BYTES] so frames never
 * straddle segments (only the one-pole's ~1-frame transient at segment
 * boundaries blurs a single frame — deterministic).
 */
class SilenceQualityTest {

    private val k = 0.65
    private val c = 100.0
    private val margin = 15 // frames = 150 ms

    /** Nyquist-square PCM segment: sample k alternates ±[amp]. */
    private fun seg(bytes: Int, amp: Int): ByteArray {
        val out = ByteArray(bytes)
        var sample = 0
        for (i in 0 until bytes step 2) {
            val v = if (sample % 2 == 0) amp else -amp
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            sample++
        }
        return out
    }

    private fun frame(n: Int, amp: Int) = seg(n * SilenceQuality.FRAME_BYTES, amp)

    /** 25-Hz sine segment (LE 16-bit), starting at [startSample] with a phase offset. */
    private fun hum(bytes: Int, amp: Int, phaseRad: Double, startSample: Int): ByteArray {
        val out = ByteArray(bytes)
        for (i in 0 until bytes / 2) {
            val sample = startSample + i
            val v = (amp * sin(2.0 * PI * 25.0 * sample / 16000.0 + phaseRad)).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Per-sample saturating mix of two same-length PCM segments. */
    private fun mix(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size)
        for (i in 0 until a.size / 2) {
            val s = readSample(a, 2 * i) + readSample(b, 2 * i)
            val v = s.coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readSample(buf: ByteArray, offset: Int): Int {
        val v = ((buf[offset + 1].toInt() and 0xFF) shl 8) or (buf[offset].toInt() and 0xFF)
        return if (v >= 32768) v - 65536 else v
    }

    private fun evaluate(pcm: ByteArray, axisK: Double = k, axisC: Double = c) =
        SilenceQuality.evaluate(pcm, 16_000, axisK, axisC, margin)!!

    private fun cutIsInsidePauseWithMargins(
        r: SilenceQuality.Result,
        pauseBytes: IntRange,
        leftOverAxisEdge: Int,
        rightOverAxisEdge: Int
    ) {
        assertThat(r.cutOffset).isGreaterThan(pauseBytes.first)
        assertThat(r.cutOffset).isLessThan(pauseBytes.last + 1)
        // K-margin from both over-axis edges (150 ms = 4800 B)
        assertThat(r.cutOffset - leftOverAxisEdge).isAtLeast(4800)
        assertThat(rightOverAxisEdge - r.cutOffset).isAtLeast(4800)
    }

    // ---- the live #126 regression: the "bist" word split ---------------

    /**
     * The word-final fricative + stop closure + following pause merge into
     * ONE below-axis candidate under a speech-dominated span (the fricative
     * is BELOW the axis here — the dilution regime): the AREA centroid must
     * still land strictly inside the true pause (the old geometric-center
     * cut landed wherever the run happened to start), with the K-margin
     * from both over-axis edges, and both word segments on their correct
     * sides of the cut.
     *
     * Span: 20 frames voiced(3500) · 10 fricative(250) · 10 closure(0) ·
     * 30 true pause(0) · 20 voiced(3500). True pause bytes [12800, 22400).
     */
    @Test
    fun `bist fixture - dilution regime cuts strictly inside the true pause with margins`() {
        val pcm = frame(20, 3500) + frame(10, 250) + frame(10, 0) +
            frame(30, 0) + frame(20, 3500)

        val r = evaluate(pcm)

        assertThat(r.candidateFound).isTrue()
        assertThat(r.quality).isGreaterThan(40.0) // solid candidate
        assertThat(r.quality).isAtMost(50.0)      // depth bounded: Q <= candidate frames
        assertThat(r.cutOffset).isEqualTo(14560)
        cutIsInsidePauseWithMargins(r, 12800 until 22400, leftOverAxisEdge = 19 * 320, rightOverAxisEdge = 70 * 320)
        // both words on their correct sides: the whole first word (voiced +
        // fricative ends at 9600) before, the next onset (22400) after
        assertThat(r.cutOffset).isGreaterThan(9600)
    }

    /**
     * The EXCLUSION regime — the discriminating live regression: a
     * quiet-dominated span holds the axis UNDER the fricative band, so the
     * fricative sits ABOVE the axis and contributes ZERO deficit area (Q <=
     * the pause's frame count — dilution would push Q past it). The deleted
     * absolute-threshold algorithm cut this exact span at the merged run's
     * geometric center — frame 27, INSIDE the fricative [0, 4800).
     *
     * Span: 15 frames fricative(400) · 40 pause(0). Pause bytes [4800, 17600).
     */
    @Test
    fun `fricative above the axis is hard-excluded - zero area, not dilution`() {
        val pcm = frame(15, 400) + frame(40, 0)

        val r = evaluate(pcm)

        assertThat(r.candidateFound).isTrue()
        // the 40 pause frames carry the whole area; the 15 fricative frames
        // contribute ~0.01 (one boundary frame's transient) — dilution would
        // add ~15 frames of shallow deficit
        assertThat(r.quality).isGreaterThan(39.0)
        assertThat(r.quality).isAtMost(40.0)
        assertThat(r.cutOffset).isEqualTo(11360)
        cutIsInsidePauseWithMargins(r, 4800 until 17600, leftOverAxisEdge = 14 * 320, rightOverAxisEdge = 55 * 320)
    }

    // ---- 25-Hz hum: the high-pass is load-bearing -----------------------

    /**
     * A strong 25-Hz sine (amplitude 4000 — louder than the 3000 speech)
     * under the speech bursts and ALONE in the pause: without the 80-Hz
     * high-pass the hum beats against the 10-ms frame grid (40-ms period =
     * 4 frames) and fragments the pause into alternating single-frame
     * candidates (mirrored control: best Q collapses 17.5 → 0.27). With
     * the high-pass the pause survives as ONE solid candidate and the cut
     * lands inside it with margins.
     *
     * Span: 25 frames speech+hum · 40 pause (hum only) · 25 speech+hum.
     * Pause bytes [8000, 20800).
     */
    @Test
    fun `25-Hz hum does not fabricate beat candidates - the pause stays one solid candidate`() {
        val phase = PI / 4.0
        val speechHum1 = mix(frame(25, 3000), hum(25 * SilenceQuality.FRAME_BYTES, 4000, phase, 0))
        val pauseHum = hum(40 * SilenceQuality.FRAME_BYTES, 4000, phase, 25 * 160)
        val speechHum2 = mix(frame(25, 3000), hum(25 * SilenceQuality.FRAME_BYTES, 4000, phase, 65 * 160))
        val pcm = speechHum1 + pauseHum + speechHum2

        val r = evaluate(pcm)

        assertThat(r.candidateFound).isTrue()
        // solid: a beat-fragmented best candidate would carry a single
        // frame's shallow deficit (Q << 1); the hum residue under the
        // high-pass keeps every pause frame below the axis
        assertThat(r.quality).isGreaterThan(10.0)
        assertThat(r.cutOffset).isEqualTo(14560)
        cutIsInsidePauseWithMargins(r, 8000 until 20800, leftOverAxisEdge = 24 * 320, rightOverAxisEdge = 65 * 320)
    }

    // ---- whisper regimes -------------------------------------------------

    /**
     * Whisper, THREADING regime (#126 issue body): a 6-dB dynamic-range
     * whisper band with the axis inside it — every quiet-block frame is a
     * shallow candidate. This is the borderline-Q case the issue describes:
     * the Q-gate (#128) arms stitching exactly here. The cut is still
     * best-effort — the centroid of the first (quietest, earliest) block.
     */
    @Test
    fun `whisper band with the axis threading it yields a shallow low-Q candidate`() {
        var pcm = ByteArray(0)
        repeat(12) { blk ->
            pcm += frame(5, if (blk % 2 == 0) 120 else 240)
        }

        val r = evaluate(pcm)

        assertThat(r.candidateFound).isTrue()
        assertThat(r.quality).isLessThan(5.0) // borderline — #128's Q-gate territory
        assertThat(r.cutOffset).isEqualTo(800) // centre of the first quiet block [0, 1600)
    }

    /**
     * Whisper, FORCED regime (the brief's expectation): a tight
     * dynamic-range whisper where NO frame falls below the axis — the
     * k-term's headroom (min ≈ M, so `(1−k)·M ≈ 138` at this level) exceeds
     * the absolute anchor c = 100, keeping `X = k·M + c` UNDER the whole
     * band → no candidate → best-effort cut at the quietest frame, Q = 0.
     * (With the provisional c = 100 this needs a < 1-dB band and
     * M ≥ c/(1−k) ≈ 286; a 6-dB band at whisper level threads or
     * overshoots the band instead — see the threading test. The regime
     * boundary is #129's to measure on real audio.)
     */
    @Test
    fun `tight whisper band over the axis takes the forced best-point path`() {
        var pcm = ByteArray(0)
        repeat(12) { blk ->
            pcm += frame(5, if (blk % 2 == 0) 380 else 420)
        }

        val r = evaluate(pcm)

        assertThat(r.candidateFound).isFalse()
        assertThat(r.quality).isEqualTo(0.0)
        assertThat(r.axis).isLessThan(r.floorLevel) // no frame is below-axis
        assertThat(r.cutOffset).isEqualTo(160) // quietest frame (the 380 block's start)
    }

    // ---- gain invariance / room change ----------------------------------

    /**
     * k·M is gain-invariant: with the absolute anchor OFF (c = 0), scaling
     * the whole window by 0.5 halves every level AND the axis — the deficit
     * curve is unchanged, so Q and the centroid must not move. (The short
     * truncation of filtered samples leaves a ~1e-4 relative residue —
     * hence the tolerance.)
     */
    @Test
    fun `gain invariance - scaling the window by half moves neither Q nor the centroid (c = 0)`() {
        val full = frame(20, 3500) + frame(10, 250) + frame(10, 0) +
            frame(30, 0) + frame(20, 3500)
        val half = frame(20, 1750) + frame(10, 125) + frame(10, 0) +
            frame(30, 0) + frame(20, 1750)

        val r1 = evaluate(full, axisC = 0.0)
        val r2 = evaluate(half, axisC = 0.0)

        assertThat(r2.quality).isWithin(0.01).of(r1.quality)
        assertThat(r2.centroidOffset).isEqualTo(r1.centroidOffset)
        assertThat(r2.cutOffset).isEqualTo(r1.cutOffset)
        // the axis halves up to the short-truncation residue of the filtered
        // samples (~0.03%)
        assertThat(r2.axis).isWithin(0.5).of(r1.axis / 2.0)
    }

    /**
     * The absolute anchor c DELIBERATELY breaks scale invariance (it is the
     * whisper/quiet-room anchor, not a gain term): with c > 0 the scaled
     * window's Q differs.
     */
    @Test
    fun `the absolute anchor deliberately breaks scale invariance (c = 100)`() {
        val full = frame(20, 3500) + frame(10, 250) + frame(10, 0) +
            frame(30, 0) + frame(20, 3500)
        val half = frame(20, 1750) + frame(10, 125) + frame(10, 0) +
            frame(30, 0) + frame(20, 1750)

        val r1 = evaluate(full)
        val r2 = evaluate(half)

        assertThat(r2.quality).isNotWithin(0.01).of(r1.quality)
    }

    /**
     * Room change: the same pause shape at twice the overall level — the
     * per-fragment axis X adapts (not a pure ×2: the anchor c does not
     * scale) and the cut stays valid (identical here).
     */
    @Test
    fun `room change - the axis adapts and the cut stays inside the pause`() {
        val quiet = frame(20, 3500) + frame(10, 250) + frame(10, 0) +
            frame(30, 0) + frame(20, 3500)
        val loud = frame(20, 7000) + frame(10, 500) + frame(10, 0) +
            frame(30, 0) + frame(20, 7000)

        val r1 = evaluate(quiet)
        val r2 = evaluate(loud)

        assertThat(r2.candidateFound).isTrue()
        assertThat(r2.quality).isGreaterThan(40.0)
        // adapted, but not doubled — c is absolute
        assertThat(r2.axis).isGreaterThan(r1.axis)
        assertThat(r2.axis).isLessThan(2.0 * r1.axis)
        assertThat(r2.cutOffset).isEqualTo(r1.cutOffset)
        cutIsInsidePauseWithMargins(r2, 12800 until 22400, leftOverAxisEdge = 19 * 320, rightOverAxisEdge = 70 * 320)
    }

    // ---- continuous speech / depth×duration ------------------------------

    /** Continuous speech: min ≈ M, no frame below the axis → forced, Q = 0. */
    @Test
    fun `continuous speech has no candidate - best-effort cut at the quietest frame`() {
        val pcm = frame(60, 3000)

        val r = evaluate(pcm)

        assertThat(r.candidateFound).isFalse()
        assertThat(r.quality).isEqualTo(0.0)
        assertThat(r.floorLevel).isWithin(1.0).of(r.meanLevel) // min ≈ M
        // the one-pole's start overshoot makes frame 0 slightly LOUDER than
        // frame 1 — the quietest full frame is 1 (deterministic)
        assertThat(r.cutOffset).isEqualTo(480)
    }

    /**
     * Q is depth×duration in ONE value: a SHORT deep pause (15 frames at
     * floor, Q ≈ 15) outscores a LONG shallow dip (25 frames just under the
     * axis, Q ≈ 6) — and Q never exceeds the candidate's frame count, so a
     * Q threshold implies a minimum pause duration.
     *
     * Span: 20 speech(3000) · 25 dip(800) · 5 speech separator · 15 pause(0).
     * Pause bytes [16000, 20800).
     */
    @Test
    fun `a short deep pause outscores a long shallow dip - Q bounds the minimum duration`() {
        val pcm = frame(20, 3000) + frame(25, 800) + frame(5, 3000) + frame(15, 0)

        val r = evaluate(pcm)

        assertThat(r.candidateFound).isTrue()
        assertThat(r.quality).isGreaterThan(14.0) // the pause won, not the 25-frame dip
        assertThat(r.quality).isAtMost(15.5)      // Q <= the 15 pause frames (+ boundary sliver)
        assertThat(r.cutOffset).isEqualTo(18400)  // the pause's midpoint (margin-unsatisfiable: 150 ms < 2·150 ms)
        assertThat(r.cutOffset).isGreaterThan(16000)
        assertThat(r.cutOffset).isLessThan(20800)
    }

    // ---- degenerate inputs ----------------------------------------------

    @Test
    fun `spans below one frame return null`() {
        assertThat(
            SilenceQuality.evaluate(ByteArray(SilenceQuality.FRAME_BYTES - 1), 16_000, k, c, margin)
        ).isNull()
        assertThat(SilenceQuality.evaluate(ByteArray(0), 16_000, k, c, margin)).isNull()
    }
}
