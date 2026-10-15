package com.georgernstgraf.polishedrecognition.audio

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Estimates the media duration of the files the app uploads for STT (#116):
 * canonical 44-byte-header WAV (data size / byte rate, see [WavWriter]) and
 * Ogg/Opus (granule position of the LAST Ogg page at Opus' fixed 48 kHz).
 *
 * The result feeds the per-request `durationMs` completion record in
 * `stt-latency.json` — the Phase 2 latency-profile input. A null return is
 * never an error: it only means the profile gets no sample for that request.
 */
object AudioDuration {

    /** Opus is always coded at 48 kHz internally, regardless of input rate. */
    private const val OPUS_SAMPLE_RATE_HZ = 48_000L

    private val OGG_PAGE_PATTERN = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())

    /** How much of the file tail to scan for the last Ogg page. */
    private const val OGG_TAIL_SCAN_BYTES = 256 * 1024

    fun estimateMs(file: File): Long? = try {
        when (file.extension.lowercase()) {
            "wav" -> wavDurationMs(file)
            "ogg", "opus" -> oggOpusDurationMs(file)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Canonical 44-byte-header WAV: `byteRate` @ 28, `dataSize` @ 40
     * (exactly the layout [WavWriter] produces and [WavReader] expects).
     */
    private fun wavDurationMs(file: File): Long? {
        if (file.length() < 44) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val header = ByteArray(44)
            input.readFully(header)
            if (!header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) return null
            if (!header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) return null
            if (!header.copyOfRange(36, 40).contentEquals("data".toByteArray())) return null
            val byteRate = readIntLE(header, 28)
            val dataSize = readIntLE(header, 40)
            if (byteRate <= 0 || dataSize < 0) return null
            return dataSize * 1000L / byteRate
        }
    }

    /**
     * Scans the file tail for the last structurally valid Ogg page and reads
     * its granule position (8 bytes LE @ +6). Invalid candidates (version
     * byte ≠ 0, granule ≤ 0 — Ogg uses −1 for "no packet finishes here" — or
     * implausibly large granules) are skipped in favour of earlier pages;
     * "OggS" can also occur by chance inside compressed packet data, so a
     * hit is only trusted after the structure check.
     */
    private fun oggOpusDurationMs(file: File): Long? {
        val tail = readTail(file, OGG_TAIL_SCAN_BYTES) ?: return null
        var index = lastIndexOfPattern(tail, OGG_PAGE_PATTERN, tail.size - 1)
        while (index >= 0) {
            val granule = validPageGranule(tail, index)
            if (granule != null) return granule * 1000L / OPUS_SAMPLE_RATE_HZ
            index = lastIndexOfPattern(tail, OGG_PAGE_PATTERN, index - 1)
        }
        return null
    }

    private fun validPageGranule(buf: ByteArray, pos: Int): Long? {
        if (pos + 27 > buf.size) return null
        if (buf[pos + 4].toInt() != 0) return null // stream structure version
        val segments = buf[pos + 26].toInt() and 0xFF
        if (segments == 0) return null
        val granule = readLongLE(buf, pos + 6)
        if (granule <= 0 || granule > OPUS_SAMPLE_RATE_HZ * 60 * 60 * 24) return null
        return granule
    }

    private fun readTail(file: File, maxBytes: Int): ByteArray? {
        val length = file.length()
        if (length == 0L) return null
        val skip = maxOf(0L, length - maxBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(skip)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = raf.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** Reverse search for a byte pattern; `fromIndex` is inclusive. */
    private fun lastIndexOfPattern(buf: ByteArray, pattern: ByteArray, fromIndex: Int): Int {
        val start = minOf(fromIndex, buf.size - pattern.size)
        for (i in start downTo 0) {
            var matched = true
            for (j in pattern.indices) {
                if (buf[i + j] != pattern[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLongLE(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (buf[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
