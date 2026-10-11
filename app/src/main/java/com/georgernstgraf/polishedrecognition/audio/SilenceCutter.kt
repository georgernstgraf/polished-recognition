package com.georgernstgraf.polishedrecognition.audio

/**
 * Finds a silence-aligned cut point in a PCM window (#117): the seam fix for
 * the word drops the owner observed at the fixed 7-s fragment boundaries.
 *
 * A fragment boundary that falls inside a word splits it — the tail ends one
 * fragment as a bare phoneme snippet, the onset starts the next one without
 * context, and both halves are typically dropped by the STT model. Cutting
 * at the CENTER of a silence run instead leaves fragment *i* ending in a
 * pause and fragment *i+1* starting just before the next onset — the input
 * shape Whisper handles best.
 *
 * Pure-JVM and allocation-light: the scan runs on the FragmentPreparer's
 * encoder thread over already-buffered PCM (a ≤2-s window is a few
 * microseconds of work) and must never show up in the STT latency
 * measurements that feed the Phase 2 per-provider profiles.
 */
object SilenceCutter {

    /**
     * 10-ms analysis frame at 16 kHz mono 16-bit: 160 samples = 320 bytes.
     * Frame-aligned cut offsets are automatically sample-aligned (even).
     */
    const val FRAME_BYTES = 320

    /** A silence run must span at least this many frames (40 ms). */
    const val MIN_RUN_FRAMES = 4

    /**
     * A frame is "silent" when its RMS stays at or below this 16-bit
     * amplitude (≈ −36 dBFS) — well under speech, above typical mic noise
     * floors. Tunable via the `prepare.json` evidence on-device.
     */
    const val DEFAULT_RMS_AMPLITUDE = 500

    /**
     * Returns the byte offset of the best cut within [pcm] (relative to the
     * window start), or `null` when no qualifying silence run exists.
     *
     * Candidate runs are the maximal stretches of consecutive silent frames
     * at least [MIN_RUN_FRAMES] long; the LONGEST one wins (earliest on a
     * tie) — the longest pause is the most natural break. The cut lands at
     * the run's center so both fragment edges get pause context.
     */
    fun findCut(
        pcm: ByteArray,
        rmsAmplitudeThreshold: Int = DEFAULT_RMS_AMPLITUDE
    ): Int? {
        if (pcm.size < FRAME_BYTES) return null
        val frames = pcm.size / FRAME_BYTES
        val thresholdSq = rmsAmplitudeThreshold.toLong() * rmsAmplitudeThreshold

        var runStart = -1
        var bestStart = -1
        var bestLength = 0
        for (frame in 0 until frames) {
            if (isSilent(pcm, frame, thresholdSq)) {
                if (runStart < 0) runStart = frame
                val length = frame - runStart + 1
                if (length >= MIN_RUN_FRAMES && length > bestLength) {
                    bestStart = runStart
                    bestLength = length
                }
            } else {
                runStart = -1
            }
        }
        if (bestStart < 0) return null
        // center frame of the winning run → byte offset (FRAME_BYTES is even,
        // so the cut is sample-aligned)
        return (bestStart + bestLength / 2) * FRAME_BYTES
    }

    /**
     * #124 forced-cut fallback: the byte offset (relative to the window start)
     * of the CENTER of the minimum-energy 10-ms frame, or `null` when the
     * window holds less than one full frame. Frames tile the window from its
     * start; a trailing partial frame is ignored. Ties resolve to the
     * EARLIEST frame (deterministic). The caller passes only the LATE half of
     * the search window (`[1.5S, 2S]` when `search = S`) so the cut can never
     * land before the nominal boundary — a continuous-speech stream still
     * cuts at its least energetic frame instead of mid-word at the nominal
     * boundary.
     */
    fun quietestFrameCut(pcm: ByteArray): Int? {
        if (pcm.size < FRAME_BYTES) return null
        val frames = pcm.size / FRAME_BYTES
        var bestFrame = -1
        var bestEnergy = Long.MAX_VALUE
        for (frame in 0 until frames) {
            val energy = frameEnergy(pcm, frame)
            if (energy < bestEnergy) {
                bestEnergy = energy
                bestFrame = frame
            }
        }
        if (bestFrame < 0) return null
        // center of the 10-ms frame — FRAME_BYTES / 2 is even, so the cut is
        // sample-aligned
        return bestFrame * FRAME_BYTES + FRAME_BYTES / 2
    }

    /**
     * Frame RMS ≤ threshold test without floats: the running sum of squares
     * is compared against `samplesSeen · threshold²` with an early exit.
     */
    private fun isSilent(pcm: ByteArray, frame: Int, thresholdSq: Long): Boolean {
        val base = frame * FRAME_BYTES
        var sum = 0L
        for (i in 0 until FRAME_BYTES step 2) {
            val sample = ((pcm[base + i + 1].toInt()) shl 8) or (pcm[base + i].toInt() and 0xFF)
            sum += sample.toLong() * sample
            val samplesSeen = (i / 2 + 1).toLong()
            if (sum > thresholdSq * samplesSeen) return false
        }
        return true
    }

    /** Sum of squares of one 10-ms frame's samples (no threshold, no floats). */
    private fun frameEnergy(pcm: ByteArray, frame: Int): Long {
        val base = frame * FRAME_BYTES
        var sum = 0L
        for (i in 0 until FRAME_BYTES step 2) {
            val sample = ((pcm[base + i + 1].toInt()) shl 8) or (pcm[base + i].toInt() and 0xFF)
            sum += sample.toLong() * sample
        }
        return sum
    }
}
