package com.georgernstgraf.polishedrecognition.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val bufferStream = ByteArrayOutputStream()

    fun start() {
        if (isRecording) return
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        bufferStream.reset()
        isRecording = true

        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    synchronized(bufferStream) {
                        bufferStream.write(buffer, 0, bytesRead)
                    }
                }
            }
        }.start()
    }

    fun stop(): ByteArray {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        val pcmData: ByteArray
        synchronized(bufferStream) {
            pcmData = bufferStream.toByteArray()
            bufferStream.reset()
        }

        return pcmToWav(pcmData, 16000, 1, 16)
    }

    fun cancel() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        synchronized(bufferStream) {
            bufferStream.reset()
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
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
        System.arraycopy(pcmData, 0, wav, 44, dataSize)

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
