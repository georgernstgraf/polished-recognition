package com.georgernstgraf.polishedrecognition.audio

/**
 * Reads the canonical 44-byte-header PCM WAV produced by [AudioRecorder]
 * (uncompressed 16-bit PCM, mono). Fails fast on anything unexpected rather
 * than feeding garbage PCM downstream.
 */
object WavReader {

    data class Pcm(val sampleRate: Int, val data: ByteArray)

    fun read(wav: ByteArray): Pcm {
        require(wav.size >= 44) { "WAV too short (${wav.size} bytes)" }
        require(tag(wav, 0) == "RIFF") { "Not a RIFF file" }
        require(tag(wav, 8) == "WAVE") { "Not a WAVE file" }
        require(tag(wav, 12) == "fmt ") { "Missing fmt chunk" }
        require(readShortLE(wav, 20) == 1.toShort()) { "Not uncompressed PCM" }
        require(readShortLE(wav, 22) == 1.toShort()) { "Not mono" }
        val sampleRate = readIntLE(wav, 24)
        require(sampleRate > 0) { "Invalid sample rate $sampleRate" }
        require(readShortLE(wav, 34) == 16.toShort()) { "Not 16-bit PCM" }
        require(tag(wav, 36) == "data") { "Missing data chunk" }
        val dataSize = readIntLE(wav, 40)
        require(dataSize > 0) { "No audio data" }
        require(44 + dataSize <= wav.size) { "Truncated data chunk" }
        return Pcm(sampleRate = sampleRate, data = wav.copyOfRange(44, 44 + dataSize))
    }

    private fun tag(buf: ByteArray, offset: Int): String =
        String(buf, offset, 4, Charsets.US_ASCII)

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(buf: ByteArray, offset: Int): Short =
        (((buf[offset + 1].toInt() and 0xFF) shl 8) or (buf[offset].toInt() and 0xFF)).toShort()
}
