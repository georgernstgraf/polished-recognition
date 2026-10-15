package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class WavChunkerTest {

    /** 16-bit PCM → 2 bytes per sample frame; 32 000 bytes per second at 16 kHz. */
    private fun bytesForSeconds(seconds: Double): Int = (seconds * 32_000).toInt()

    private fun buildWav(seconds: Double): ByteArray {
        val pcm = ByteArray(bytesForSeconds(seconds))
        // mark every frame with its own 16-bit value so boundary checks can
        // detect lost/duplicated samples
        var sample = 0
        for (i in pcm.indices step 2) {
            pcm[i] = (sample and 0xFF).toByte()
            pcm[i + 1] = ((sample shr 8) and 0x7F).toByte()
            sample++
        }
        return WavWriter.write(pcm, 16_000)
    }

    @Test
    fun `recording within both limits is returned unsplit`() {
        val wav = buildWav(1.0)
        val chunks = WavChunker.chunk(
            wav,
            maxChunkBytes = 10 * 1024 * 1024,
            maxChunkSeconds = 600.0
        )
        assertThat(chunks).hasSize(1)
        assertThat(chunks.single()).isSameInstanceAs(wav)
    }

    @Test
    fun `recording beyond the duration limit is split into chunks`() {
        val seconds = 3.0
        val chunkSeconds = 1.0
        val chunks = WavChunker.chunk(
            buildWav(seconds),
            maxChunkBytes = 100 * 1024 * 1024,
            maxChunkSeconds = chunkSeconds,
            minSegmentSeconds = 0.5
        )

        // 3 s / 1 s → 3 chunks; every chunk parses and concatenation restores
        // the original PCM exactly (no sample lost or duplicated).
        assertThat(chunks).hasSize(3)
        val joined = PcmSink(chunks)
        val original = WavReader.read(buildWav(seconds))
        assertThat(joined.sampleRate).isEqualTo(16_000)
        assertThat(joined.data).isEqualTo(original.data)
    }

    @Test
    fun `every chunk is within the duration limit`() {
        val chunks = WavChunker.chunk(
            buildWav(7.5),
            maxChunkBytes = 100 * 1024 * 1024,
            maxChunkSeconds = 2.0,
            minSegmentSeconds = 0.5
        )
        assertThat(chunks).hasSize(4)
        chunks.forEach { chunk ->
            val pcm = WavReader.read(chunk)
            assertThat(pcm.data.size / 32_000.0).isAtMost(2.0)
        }
    }

    @Test
    fun `small but long recording is still split (the Zoom case)`() {
        // ~8 s audio but a 20 kB byte budget → size limit binds first.
        val chunks = WavChunker.chunk(
            buildWav(8.0),
            maxChunkBytes = 20 * 1024,
            maxChunkSeconds = 600.0
        )
        assertThat(chunks.size).isAtLeast(2)
        chunks.forEach { chunk -> assertThat(chunk.size).isAtMost(20 * 1024) }
        val joined = PcmSink(chunks)
        assertThat(joined.data).isEqualTo(WavReader.read(buildWav(8.0)).data)
    }

    @Test
    fun `recording exactly at the duration limit is unsplit`() {
        val chunks = WavChunker.chunk(
            buildWav(600.0),
            maxChunkBytes = 100 * 1024 * 1024,
            maxChunkSeconds = 600.0
        )
        assertThat(chunks).hasSize(1)
    }

    @Test
    fun `default duration cap is the hard 5-minute limit`() {
        // #120: >5-min uploads make Whisper hallucinate/loop on gregor + GROQ.
        assertThat(WavChunker.MAX_CHUNK_SECONDS).isEqualTo(300.0)
    }

    @Test
    fun `long recording splits into blocks no longer than the 5-minute cap`() {
        val seconds = 700.0
        // Default limits (#120): the 300-s duration cap binds, not the byte cap.
        val chunks = WavChunker.chunk(buildWav(seconds))

        assertThat(chunks.size).isAtLeast(3)
        chunks.forEach { chunk ->
            assertThat(WavReader.read(chunk).data.size / 32_000.0).isAtMost(300.0)
        }
        // No sample lost or duplicated across the block boundary.
        assertThat(PcmSink(chunks).data).isEqualTo(WavReader.read(buildWav(seconds)).data)
    }

    @Test
    fun `minimum segment floor of 60 seconds is kept`() {
        // duration-based split would want ~0.5 s chunks; MIN_SEGMENT_SECONDS = 60 wins.
        val chunks = WavChunker.chunk(
            buildWav(120.0),
            maxChunkBytes = 100 * 1024 * 1024,
            maxChunkSeconds = 1.0
        )
        val segments = chunks.map { WavReader.read(it).data.size / 32_000.0 }
        assertThat(chunks).hasSize(2)
        segments.forEach { assertThat(it).isAtLeast(60.0) }
    }

    @Test
    fun `chunk pcm stays frame-aligned at 2-byte boundaries`() {
        val chunks = WavChunker.chunk(
            buildWav(4.0),
            maxChunkBytes = 64 * 1024 + 44, // odd-ish size → forced misalignment unless corrected
            maxChunkSeconds = 600.0
        )
        chunks.forEach { chunk ->
            val pcm = WavReader.read(chunk)
            assertThat(pcm.data.size % 2).isEqualTo(0)
        }
    }

    @Test
    fun `rejects zero or negative limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavChunker.chunk(buildWav(1.0), maxChunkBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavChunker.chunk(buildWav(1.0), maxChunkSeconds = -1.0)
        }
    }

    /** Joins a chunk list back to the original PCM for round-trip assertions. */
    private class PcmSink(chunks: List<ByteArray>) {
        val sampleRate: Int = WavReader.read(chunks.first()).sampleRate
        val data: ByteArray = chunks.flatMap { WavReader.read(it).data.toList() }
            .toByteArray()
    }
}
