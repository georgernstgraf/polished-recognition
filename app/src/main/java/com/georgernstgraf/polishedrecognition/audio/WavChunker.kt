package com.georgernstgraf.polishedrecognition.audio

/**
 * Splits a recording into WAV chunks small enough for the STT endpoint.
 * Port of the sister project aitranscribe's `chunk_audio()` (core.py, #115):
 * STT backends truncate very long single uploads — and Whisper cuts off
 * mid-text even when the file is small — so nothing longer than the
 * duration limit is ever uploaded as one file. Records within both limits
 * come back unsplit.
 *
 * Splitting happens on raw PCM at sample boundaries and re-encodes each
 * chunk through [WavWriter] (the platform Opus path then compresses per
 * chunk), so no media/codec code is involved here — pure JVM, unit-testable
 * without Robolectric.
 */
object WavChunker {

    /** Default upload-size limit in bytes (aitranscribe `MAX_AUDIO_SIZE_MB = 25`). */
    const val MAX_CHUNK_BYTES: Int = 25 * 1024 * 1024

    /** Default per-chunk duration limit in seconds (`max_duration_s = 600`). */
    const val MAX_CHUNK_SECONDS: Double = 600.0

    /** Minimum segment duration in seconds, mirroring aitranscribe's `max(60, ...)`. */
    const val MIN_SEGMENT_SECONDS: Double = 60.0

    /**
     * Size margin for derived cut points, mirroring aitranscribe's keyframe
     * headroom — kept in step with the sister implementation.
     */
    private const val SIZE_MARGIN = 0.95

    /**
     * Splits `wav` (44-byte-header 16-bit PCM as produced by
     * [com.georgernstgraf.polishedrecognition.audio.AudioRecorder]) into
     * chunks, each within both limits. Returns the original byte array
     * unchanged when no split is needed.
     */
    fun chunk(
        wav: ByteArray,
        maxChunkBytes: Int = MAX_CHUNK_BYTES,
        maxChunkSeconds: Double = MAX_CHUNK_SECONDS,
        minSegmentSeconds: Double = MIN_SEGMENT_SECONDS
    ): List<ByteArray> {
        require(maxChunkBytes > 0) { "maxChunkBytes must be positive" }
        require(maxChunkSeconds > 0) { "maxChunkSeconds must be positive" }

        val header = WavReader.read(wav)
        val bytesPerSecond = header.sampleRate * 2 // mono · 16 bit
        val durationS = header.data.size.toDouble() / bytesPerSecond
        if (durationS <= maxChunkSeconds && wav.size <= maxChunkBytes) {
            return listOf(wav)
        }

        // Segment length: the smaller of the size-derived value (bytes the
        // chunk header+data may occupy) and the duration-derived even split
        // (so EVERY chunk stays within maxChunkSeconds) — same structure as
        // core.py, floored at minSegmentSeconds.
        val numChunks = kotlin.math.ceil(durationS / maxChunkSeconds).toInt().coerceAtLeast(1)
        val sizeBasedBytes = (maxChunkBytes * SIZE_MARGIN).toInt() - 44
        val durationBasedBytes = (header.data.size + numChunks - 1) / numChunks
        val segmentBytes = maxOf(
            (minSegmentSeconds * bytesPerSecond).toInt(),
            minOf(sizeBasedBytes, durationBasedBytes)
        )

        // A whole minimum segment must still respect the size limit, else
        // the floor would violate it when maxChunkBytes is small.
        val effectiveSegment = minOf(segmentBytes, sizeBasedBytes)
        // Align to 2-byte sample frames.
        val frameAligned = effectiveSegment - effectiveSegment % 2

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < header.data.size) {
            val end = minOf(offset + frameAligned, header.data.size)
            chunks.add(WavWriter.write(header.data.copyOfRange(offset, end), header.sampleRate))
            offset = end
        }
        return chunks
    }
}
