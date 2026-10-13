package com.georgernstgraf.polishedrecognition.audio

/**
 * Encodes 16-bit PCM into the canonical 44-byte-header WAV that
 * [WavReader] can parse back and the app uploads. Extracted from
 * [AudioRecorder]'s `pcmToWav` so the chunker (#115) produces the same
 * byte format the recorder produces.
 */
object WavWriter {

    fun write(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val wav = ByteArray(HEADER_SIZE + pcm.size)
        writeHeader(wav, pcm.size, sampleRate, channels, bitsPerSample)
        System.arraycopy(pcm, 0, wav, HEADER_SIZE, pcm.size)
        return wav
    }

    /**
     * Writes the canonical 44-byte WAV header into the FIRST 44 bytes of
     * [wav], describing [dataSize] PCM bytes that the caller places at
     * offset 44. Lets a caller assemble the WAV directly into a
     * pre-allocated array without an intermediate full PCM copy (#135 —
     * [AudioRecorder.stopPreservingBuffer] streams the session buffer in).
     */
    fun writeHeader(
        wav: ByteArray,
        dataSize: Int,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalSize = HEADER_SIZE + dataSize

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

    private const val HEADER_SIZE = 44
}
