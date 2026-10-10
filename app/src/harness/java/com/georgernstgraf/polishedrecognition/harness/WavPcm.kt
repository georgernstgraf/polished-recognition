package com.georgernstgraf.polishedrecognition.harness

/**
 * #122 harness-only lenient WAV parser. The app's [com.georgernstgraf
 * .polishedrecognition.audio.WavReader] deliberately expects the recorder's
 * canonical 44-byte header; ffmpeg-produced corpus files carry extra chunks
 * (a `LIST`/`bext` before `data`), so the harness walks the RIFF chunk list
 * instead. Accepts ONLY the mic format the pipeline assumes: 16 kHz, mono,
 * 16-bit PCM.
 */
internal object WavPcm {

    fun extract(wav: ByteArray): ByteArray {
        require(wav.size >= 12) { "WAV too short (${wav.size} B)" }
        require(tag(wav, 0) == "RIFF" && tag(wav, 8) == "WAVE") { "not a RIFF/WAVE file" }
        var i = 12
        var sampleRate = -1
        var channels = -1
        var bits = -1
        while (i + 8 <= wav.size) {
            val id = tag(wav, i)
            val size = le32(wav, i + 4)
            val body = i + 8
            if (size < 0) break
            when (id) {
                "fmt " -> {
                    channels = le16(wav, body + 2)
                    sampleRate = le32(wav, body + 4)
                    bits = le16(wav, body + 14)
                }
                "data" -> {
                    val len = minOf(size, wav.size - body)
                    require(len > 0) { "empty data chunk" }
                    require(sampleRate == 16_000 && channels == 1 && bits == 16) {
                        "expected 16 kHz mono 16-bit PCM, got ${sampleRate}Hz/${channels}ch/${bits}bit"
                    }
                    return wav.copyOfRange(body, body + len)
                }
            }
            i = body + size + (size and 1) // RIFF chunks are word-aligned
        }
        error("no data chunk found")
    }

    private fun tag(buf: ByteArray, offset: Int): String =
        String(buf, offset, 4, Charsets.US_ASCII)

    private fun le16(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or
            ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or
            ((buf[o + 3].toInt() and 0xFF) shl 24)
}
