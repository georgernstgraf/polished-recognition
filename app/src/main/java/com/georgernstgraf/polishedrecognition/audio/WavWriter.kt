package com.georgernstgraf.polishedrecognition.audio

/**
 * Encodes 16-bit PCM into the canonical 44-byte-header WAV that
 * [WavReader] can parse back and the app uploads. Extracted from
 * [AudioRecorder]'s `pcmToWav` so the chunker (#115) produces the same
 * byte format the recorder produces.
 */
object WavWriter {

    fun write(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val headerSize = 44
        val totalSize = headerSize + dataSize
        val wav = ByteArray(totalSize)

        wav[0] = 'R'.code.toByte()
        wav[1] = 'I'.code.toByte()
        wav[2] = 'F'.code.toByte()
        wav[3] = 'F'.code.toByte()
        writeIntLE(wav, 4, totalSize - 8)
        wav[8] = 'W'.code.toByte()
        wav[9] = 'A'.code.toByte()
        wav[10] = 'V'.code.toByte()
        wav[11] = 'E'.code.toByte()
        wav[12] = 'f'.code.toByte()
        wav[13] = 'm'.code.toByte()
        wav[14] = 't'.code.toByte()
        wav[15] = ' '.code.toByte()
        writeIntLE(wav, 16, 16)
        writeShortLE(wav, 20, 1)
        writeShortLE(wav, 22, channels.toShort())
        writeIntLE(wav, 24, sampleRate)
        writeIntLE(wav, 28, byteRate)
        writeShortLE(wav, 32, blockAlign.toShort())
        writeShortLE(wav, 34, bitsPerSample.toShort())
        wav[36] = 'd'.code.toByte()
        wav[37] = 'a'.code.toByte()
        wav[38] = 't'.code.toByte()
        wav[39] = 'a'.code.toByte()
        writeIntLE(wav, 40, dataSize)
        System.arraycopy(pcm, 0, wav, 44, dataSize)

        return wav
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Short) {
        buf[offset] = (value.toInt() and 0xFF).toByte()
        buf[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }
}
