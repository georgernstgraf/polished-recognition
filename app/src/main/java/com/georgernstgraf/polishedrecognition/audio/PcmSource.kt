package com.georgernstgraf.polishedrecognition.audio

/**
 * Read access to an append-only PCM byte stream (#115). The recording
 * buffer only ever GROWS while a session lives (and is reset wholesale by
 * flush/cancel), which is the invariant that makes prepared fragments
 * permanently reusable: bytes [0, k) never change once written.
 */
interface PcmSource {

    /** Bytes accumulated so far. */
    fun pcmSize(): Long

    /** Copies the PCM range [start, end) — both byte offsets, `end` exclusive. */
    fun copyPcmRange(start: Long, end: Long): ByteArray
}
