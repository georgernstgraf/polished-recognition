package com.georgernstgraf.polishedrecognition.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioRecorder : PcmSource {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false

    /**
     * The session PCM as an append-only chain of fixed-size blocks under one
     * lock (#135). Replaces the former single `ByteArrayOutputStream`: a
     * BAOS forced EVERY ranged read (`copyPcmRange`) to copy the WHOLE
     * session via `toByteArray()` — on a ~24-min dictation that transient
     * (plus the ≤2× internal growth array) blew the 128-MB heap of low-RAM
     * devices (A03 crash, trc.txt 2026-10-13). Blocks keep the per-read
     * transient at O(requested range) and grow one block at a time — no
     * contiguous double-allocation at any session length. All full-buffer
     * consumers (snapshot, stop WAV) walk the blocks into their single
     * result array instead of stacking full copies.
     */
    private val blocks = ArrayDeque<ByteArray>()
    /** Total valid PCM bytes across [blocks] (all but the last are full). */
    private var size = 0L
    /** Bytes used in the last (possibly partially filled) block. */
    private var tailUsed = 0

    private val lock = Any()

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
                    append(buffer, bytesRead)
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
     *
     * The WAV is assembled directly into its final array (header via
     * [WavWriter.writeHeader], PCM block-walked in) — exactly ONE full
     * session copy, where the former `toByteArray()` + `write()` stacked
     * two (#135).
     */
    fun stopPreservingBuffer(): ByteArray {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        synchronized(lock) {
            val dataSize = size.toInt()
            val wav = ByteArray(WAV_HEADER_SIZE + dataSize)
            WavWriter.writeHeader(wav, dataSize, 16000, 1, 16)
            copyLockedInto(wav, WAV_HEADER_SIZE, 0L, size)
            return wav
        }
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
     * (RECORDING) or stays stopped (PAUSED) — only the buffered bytes are
     * reset, so the user re-records from scratch in the same mode.
     */
    fun flushBuffer() {
        synchronized(lock) { resetLocked() }
    }

    /**
     * Copies the captured raw PCM bytes without disturbing the session, for
     * the process-death snapshot (`VoiceSessionController.snapshot`, #67).
     * One full copy — unavoidable, the bytes leave via disk — walked block
     * by block instead of stacking an intermediate BAOS copy (#135).
     */
    fun snapshotPcm(): ByteArray =
        synchronized(lock) {
            val out = ByteArray(size.toInt())
            copyLockedInto(out, 0, 0L, size)
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
            appendLocked(pcm, pcm.size)
        }
    }

    /**
     * [PcmSource] access for the fragment preparer (#115): the buffer is
     * append-only while a session lives. Copies ONLY the requested range —
     * a block walk that never materializes the whole session (#135; the
     * former `toByteArray()` full copy OOMed long dictations on small-heap
     * devices).
     */
    override fun pcmSize(): Long =
        synchronized(lock) { size }

    override fun copyPcmRange(start: Long, end: Long): ByteArray {
        require(start >= 0 && end >= start) { "invalid PCM range [$start, $end)" }
        synchronized(lock) {
            require(end <= size) { "PCM range [$start, $end) beyond buffer (${size}B)" }
            val target = ByteArray((end - start).toInt())
            copyLockedInto(target, 0, start, end - start)
            return target
        }
    }

    /** Appends [len] bytes of [data] under [lock]; shared by capture and [restorePcm]. */
    private fun append(data: ByteArray, len: Int) {
        synchronized(lock) { appendLocked(data, len) }
    }

    private fun appendLocked(data: ByteArray, len: Int) {
        var offset = 0
        while (offset < len) {
            var tail = blocks.lastOrNull()
            if (tail == null || tailUsed == tail.size) {
                tail = ByteArray(BLOCK_BYTES)
                blocks.addLast(tail)
                tailUsed = 0
            }
            val n = minOf(len - offset, tail.size - tailUsed)
            System.arraycopy(data, offset, tail, tailUsed, n)
            tailUsed += n
            offset += n
            size += n
        }
    }

    private fun resetLocked() {
        blocks.clear()
        size = 0L
        tailUsed = 0
    }

    /**
     * Copies [length] bytes starting at absolute offset [start] into [target]
     * at [targetOffset], walking the block chain. Caller holds [lock]. Blocks
     * before the last are exactly [BLOCK_BYTES]; the last holds [tailUsed].
     */
    private fun copyLockedInto(target: ByteArray, targetOffset: Int, start: Long, length: Long) {
        if (length == 0L) return
        var blockStart = 0L
        var dst = targetOffset
        var remaining = length
        for (block in blocks) {
            val blockEnd = blockStart + if (block === blocks.last()) tailUsed else block.size
            val from = maxOf(start, blockStart)
            val to = minOf(start + length, blockEnd)
            if (to > from) {
                System.arraycopy(
                    block,
                    (from - blockStart).toInt(),
                    target,
                    dst,
                    (to - from).toInt()
                )
                dst += (to - from).toInt()
                remaining -= (to - from)
            }
            blockStart = blockEnd
            if (remaining <= 0L) break
        }
    }

    private companion object {
        /** ~0.5 MB per block: a fragment-sized ranged read (≤ 2S ≈ 640 KB)
         *  touches ≤ 3 blocks, and a 24-min session is only ~92 entries —
         *  the walk is arithmetic + 1–2 arraycopies, never a full copy. */
        const val BLOCK_BYTES = 524_288
        const val WAV_HEADER_SIZE = 44
    }
}
