package com.georgernstgraf.polishedrecognition.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream

interface AudioRecorderListener {
    fun onRmsChanged(rms: Float)
    fun onSpeechBegin() {}
}

class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val bufferStream = ByteArrayOutputStream()
    private var listener: AudioRecorderListener? = null
    private var didReportSpeechBegin = false

    fun start(listener: AudioRecorderListener? = null) {
        if (isRecording) {
            Log.w(TAG, "start() called but already recording — ignoring")
            return
        }
        Log.d(TAG, "start() — initializing AudioRecord")
        this.listener = listener
        this.didReportSpeechBegin = false

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
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize!")
            isRecording = false
            return
        }
        bufferStream.reset()
        isRecording = true

        audioRecord?.startRecording()
        Log.d(TAG, "start() — AudioRecord started, bufferSize=$bufferSize sampleRate=$sampleRate")

        Thread {
            val buffer = ByteArray(bufferSize)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    synchronized(bufferStream) {
                        bufferStream.write(buffer, 0, bytesRead)
                    }
                    val rms = computeRms(buffer, bytesRead)
                    listener?.onRmsChanged(rms)
                    if (!didReportSpeechBegin && rms > 200f) {
                        didReportSpeechBegin = true
                        Log.d(TAG, "speechBegin detected (rms=%.0f)".format(rms))
                        listener?.onSpeechBegin()
                    }
                }
            }
            Log.d(TAG, "recording thread exiting")
        }.start()
    }

    fun stop(): ByteArray {
        Log.d(TAG, "stop() — stopping recording")
        isRecording = false
        listener = null
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
        listener = null
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

    private fun computeRms(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0L
        for (i in 0 until bytesRead step 2) {
            val sample = ((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i].toInt() and 0xFF)
            sum += (sample * sample).toLong()
        }
        val samples = (bytesRead / 2).coerceAtLeast(1)
        return kotlin.math.sqrt(sum.toDouble() / samples).toFloat()
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

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
