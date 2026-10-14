package com.georgernstgraf.polishedrecognition.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Captures 16-kHz mono PCM and spools it to DISK instead of accumulating it
 * in RAM (#136, owner directive: "after a fragment comes back, the buffer
 * gets free again" — with the spool the RAM cost of a dictation is O(1),
 * not O(session): a 60-min dictation costs the same few MB as a 2-min one).
 *
 * Replaces both earlier designs: the original single `ByteArrayOutputStream`
 * (whose per-fragment `toByteArray()` full copy OOMed long dictations on
 * small-heap devices — the 2026-10-13 A03 crash, #135) and #135's
 * in-RAM block chain (still O(session) RAM). Consumers read RANGES via
 * [PcmSource] — seek+read from the spool under one lock; the fragment
 * preparer's absolute-offset contract is untouched.
 *
 * Full-session artifacts are STREAMED, never materialized in RAM:
 * - [stopSessionWavFile] writes the canonical WAV (header + spool) with a
 *   64-KiB copy buffer — the controller threads the FILE to retention and
 *   the legacy path; the fragment pipeline reads the fragment files
 *   directly and never sees it.
 * - [snapshotToFile] / [restoreFromFile] move the session between the
 *   spool and the process-death snapshot (#67) as disk copies.
 */
class AudioRecorder(private val spoolFile: File) : PcmSource {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false

    private val lock = Any()
    private var raf: RandomAccessFile? = null
    /** Valid PCM bytes in the spool — the authoritative [PcmSource.pcmSize]. */
    private var spooledBytes = 0L

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
            synchronized(lock) { resetLocked() }
        }
        isRecording = true

        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    synchronized(lock) { writeLocked(buffer, bytesRead) }
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

    fun stop(): File = stopSessionWavFile().also { flushBuffer() }

    /**
     * Stops capture and writes the full-session WAV to a FILE, keeping the
     * spooled PCM for a parked retry (#84 semantics, now disk-backed): a
     * failed pipeline parks the session as PAUSED with the audio intact in
     * the spool; [flushBuffer] (or [cancel]) discards it after a successful
     * upload. RAM peak is the copy buffer, not the session (#136).
     */
    fun stopSessionWavFile(): File {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        val out = File(spoolFile.parentFile, SESSION_WAV_NAME)
        synchronized(lock) {
            val raf = rafOrOpen()
            val dataSize = spooledBytes.toInt()
            FileOutputStream(out).use { fos ->
                val header = ByteArray(WAV_HEADER_SIZE)
                WavWriter.writeHeader(header, dataSize, 16000, 1, 16)
                fos.write(header)
                raf.seek(0)
                val buf = ByteArray(COPY_BUFFER_BYTES)
                var remaining = spooledBytes
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
        return out
    }

    fun cancel() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        synchronized(lock) { resetLocked() }
    }

    /**
     * Discards the captured PCM without disturbing a live session (#86,
     * flush button): a running AudioRecord thread keeps capturing
     * (RECORDING) or stays stopped (PAUSED) — only the spooled bytes are
     * truncated, so the user re-records from scratch in the same mode.
     */
    fun flushBuffer() {
        synchronized(lock) { resetLocked() }
    }

    /**
     * Copies the captured raw PCM bytes without disturbing the session, for
     * callers that need the bytes themselves (tests, small buffers). The
     * controller's process-death snapshot uses [snapshotToFile] instead —
     * a disk copy that never materializes the session in RAM (#136).
     */
    fun snapshotPcm(): ByteArray =
        synchronized(lock) {
            val out = ByteArray(spooledBytes.toInt())
            readLocked(0L, out)
            out
        }

    /**
     * Restores PCM bytes captured by [snapshotPcm] after process death. The
     * recorder stays stopped — the session resumes to PAUSED and the next
     * `resume()` appends to the restored bytes (`resetBuffer = false`).
     */
    fun restorePcm(pcm: ByteArray) {
        synchronized(lock) {
            resetLocked()
            writeLocked(pcm, pcm.size)
        }
    }

    /**
     * Copies the spooled PCM into [target] — the process-death snapshot
     * (#67) as a pure disk operation: the session never materializes in
     * RAM, however long the dictation (#136). A missing/empty spool yields
     * an empty target file (pre-#136 semantics: an empty buffer snapshotted
     * as an empty `session.pcm`).
     */
    fun snapshotToFile(target: File) {
        synchronized(lock) {
            if (!spoolFile.isFile || spooledBytes == 0L) {
                target.writeBytes(ByteArray(0))
            } else {
                FileInputStream(spoolFile).use { input ->
                    FileOutputStream(target).use { output ->
                        input.channel.transferTo(0, spooledBytes, output.channel)
                    }
                }
            }
        }
    }

    /**
     * Adopts [source] as the spool after process death — RENAMED into place
     * when possible (cheap; the caller deletes the snapshot meta either
     * way), copied as a fallback. The session lives on disk from here;
     * `resume()` appends to it.
     */
    fun restoreFromFile(source: File) {
        synchronized(lock) {
            resetLocked()
            closeRafLocked()
            if (!source.renameTo(spoolFile)) {
                source.copyTo(spoolFile, overwrite = true)
                source.delete()
            }
            spooledBytes = if (spoolFile.isFile) spoolFile.length() else 0L
        }
    }

    /**
     * [PcmSource] access for the fragment preparer (#115): the spool is
     * append-only while a session lives. Copies ONLY the requested range —
     * a seek+read, never a full-session copy (#135/#136).
     */
    override fun pcmSize(): Long =
        synchronized(lock) { spooledBytes }

    override fun copyPcmRange(start: Long, end: Long): ByteArray {
        require(start >= 0 && end >= start) { "invalid PCM range [$start, $end)" }
        synchronized(lock) {
            require(end <= spooledBytes) { "PCM range [$start, $end) beyond buffer (${spooledBytes}B)" }
            val target = ByteArray((end - start).toInt())
            readLocked(start, target)
            return target
        }
    }

    /** Opens (or reopens) the spool for append+read; caller holds [lock]. */
    private fun rafOrOpen(): RandomAccessFile {
        raf?.let { return it }
        spoolFile.parentFile?.mkdirs()
        val file = RandomAccessFile(spoolFile, "rw")
        raf = file
        return file
    }

    private fun closeRafLocked() {
        runCatching { raf?.close() }
        raf = null
    }

    /** Appends [len] bytes of [data] at the spool end; caller holds [lock]. */
    private fun writeLocked(data: ByteArray, len: Int) {
        if (len <= 0) return
        val file = rafOrOpen()
        file.seek(spooledBytes)
        file.write(data, 0, len)
        spooledBytes += len
    }

    /** Reads exactly [target].size bytes from [start]; caller holds [lock]. */
    private fun readLocked(start: Long, target: ByteArray) {
        if (target.isEmpty()) return
        val file = rafOrOpen()
        file.seek(start)
        var offset = 0
        while (offset < target.size) {
            val n = file.read(target, offset, target.size - offset)
            if (n < 0) throw IOException("spool shorter than expected at ${start + offset}")
            offset += n
        }
    }

    private fun resetLocked() {
        closeRafLocked()
        runCatching {
            if (spoolFile.exists()) spoolFile.delete()
        }
        spooledBytes = 0L
    }

    companion object {
        /** Written next to the spool; the controller deletes it after retention. */
        internal const val SESSION_WAV_NAME = "session_stop.wav"

        private const val WAV_HEADER_SIZE = 44
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
