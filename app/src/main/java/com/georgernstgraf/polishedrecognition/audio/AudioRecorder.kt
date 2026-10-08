package com.georgernstgraf.polishedrecognition.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class AudioRecorder : PcmSource {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private val bufferStream = ByteArrayOutputStream()

    fun start(resetBuffer: Boolean = true) {
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
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            isRecording = false
            return
        }
        if (resetBuffer) {
            synchronized(bufferStream) { bufferStream.reset() }
        }
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

    fun pause() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    fun resume() {
        start(resetBuffer = false)
    }

    fun stop(): ByteArray = stopPreservingBuffer().also { flushBuffer() }

    /**
     * Stops capture and returns the WAV without clearing the buffered PCM
     * (#84): the buffer stays so a failed pipeline can park the session as
     * PAUSED with the audio intact (retry re-sends, resume appends). Call
     * [flushBuffer] (or [cancel]) to discard after a successful upload.
     */
    fun stopPreservingBuffer(): ByteArray {
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
        }

        return WavWriter.write(pcmData, 16000, 1, 16)
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

    /**
     * Discards the captured PCM without disturbing a live session (#86,
     * flush button): a running AudioRecord thread keeps capturing
     * (RECORDING) or stays stopped (PAUSED) — only the buffered bytes are
     * reset, so the user re-records from scratch in the same mode.
     */
    fun flushBuffer() {
        synchronized(bufferStream) {
            bufferStream.reset()
        }
    }

    /**
     * Copies the captured raw PCM bytes without disturbing the session, for
     * the process-death snapshot (`VoiceSessionController.snapshot`, #67).
     */
    fun snapshotPcm(): ByteArray =
        synchronized(bufferStream) { bufferStream.toByteArray() }

    /**
     * Restores PCM bytes captured by [snapshotPcm] after process death. The
     * recorder stays stopped — the session resumes to PAUSED and the next
     * `resume()` appends to the restored bytes (`resetBuffer = false`).
     */
    fun restorePcm(pcm: ByteArray) {
        synchronized(bufferStream) {
            bufferStream.reset()
            bufferStream.write(pcm)
        }
    }

    /**
     * [PcmSource] access for the fragment preparer (#115): the buffer is
     * append-only while a session lives. `toByteArray()` snapshots the whole
     * buffer under the capture lock (the capture thread may be reallocating
     * the internal array at any moment) — one full copy per fragment is
     * cheap against a 7-s encode cadence.
     */
    override fun pcmSize(): Long =
        synchronized(bufferStream) { bufferStream.size().toLong() }

    override fun copyPcmRange(start: Long, end: Long): ByteArray {
        require(start >= 0 && end >= start) { "invalid PCM range [$start, $end)" }
        val target = ByteArray((end - start).toInt())
        synchronized(bufferStream) {
            val bytes = bufferStream.toByteArray()
            require(end <= bytes.size) { "PCM range [$start, $end) beyond buffer (${bytes.size}B)" }
            System.arraycopy(bytes, start.toInt(), target, 0, target.size)
        }
        return target
    }
}
