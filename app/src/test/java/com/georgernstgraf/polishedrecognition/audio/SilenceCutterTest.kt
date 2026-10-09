package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for the silence-aligned cut-point search (#117): the seam
 * fix for the word drops the owner observed at fixed 7-s fragment cuts.
 */
class SilenceCutterTest {

    /** Full-amplitude 16-bit noise — never reads as silent. */
    private fun noisy(size: Int) =
        ByteArray(size) { i -> if (i % 2 == 0) 0x55 else 0xAA.toByte() }

    @Test
    fun `cut lands at the center of the silence run`() {
        // 2 noisy frames, 5 silent frames (1600 B), 1 noisy frame
        val pcm = noisy(640) + ByteArray(1600) + noisy(320)

        val cut = SilenceCutter.findCut(pcm)

        // run frames 2..6 → center frame 4 → byte 4 · 320 = 1280
        assertThat(cut).isEqualTo(1280)
    }

    @Test
    fun `the longest of several runs wins`() {
        val pcm = noisy(640) + ByteArray(640) + noisy(320) + ByteArray(1600) + noisy(320)

        val cut = SilenceCutter.findCut(pcm)

        // short run frames 2..3 (rejected on its own), long run frames 5..9 →
        // center frame 7 → 2240
        assertThat(cut).isEqualTo(2240)
    }

    @Test
    fun `runs shorter than 40 ms are ignored`() {
        // 3 silent frames (30 ms) < MIN_RUN_FRAMES
        val pcm = noisy(320) + ByteArray(960) + noisy(320)

        assertThat(SilenceCutter.findCut(pcm)).isNull()
    }

    @Test
    fun `continuous audio has no cut`() {
        assertThat(SilenceCutter.findCut(noisy(10 * SilenceCutter.FRAME_BYTES))).isNull()
    }

    @Test
    fun `windows below one frame have no cut`() {
        assertThat(SilenceCutter.findCut(ByteArray(SilenceCutter.FRAME_BYTES - 1))).isNull()
        assertThat(SilenceCutter.findCut(ByteArray(0))).isNull()
    }

    @Test
    fun `an all-silent window cuts at its center`() {
        val frames = 6
        val cut = SilenceCutter.findCut(ByteArray(frames * SilenceCutter.FRAME_BYTES))

        // run frames 0..5 → center frame 3 → 960
        assertThat(cut).isEqualTo(3 * SilenceCutter.FRAME_BYTES)
    }

    @Test
    fun `cut offsets are sample-aligned (even) with a mid-frame silence onset`() {
        val pcm = noisy(700) + ByteArray(1700) // silence starts mid-frame at byte 700

        val cut = SilenceCutter.findCut(pcm)

        // frame 2 ([640, 960)) mixes speech and silence → not silent; the run
        // is frames 3..6 → center frame 5 → 1600, inside the zero region
        assertThat(cut).isEqualTo(1600)
        assertThat(cut!! % 2).isEqualTo(0)
        assertThat(pcm[cut].toInt()).isEqualTo(0)
    }
}
