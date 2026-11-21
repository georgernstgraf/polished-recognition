package com.georgernstgraf.polishedrecognition.recovery

import com.georgernstgraf.polishedrecognition.audio.WavReader
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * #185: the "share the audio" half of the recovery UI.
 *
 * The journal stores the **composed upload** per fragment (pre-roll +
 * fragment). Sharing those files as they are would duplicate ~1 s of audio at
 * every seam, so a WAV session is reconstructed: each piece's pre-roll is cut
 * off (the record knows its length, 32 bytes per ms at 16 kHz/16 bit/mono) and
 * the PCM is tiled into one canonical WAV — exactly the analyzer's
 * `reconstruct` rule, minus the ffmpeg dependency.
 *
 * `compress_audio` sessions upload OGG, which the app cannot decode: those
 * share the fragment files themselves (multi-send) instead of pretending to
 * rebuild a single stream.
 *
 * Pure JVM: no Android types, unit-testable.
 */
object RecoveryAudio {

    /** Cache subdirectory the shareable WAV is written into. */
    const val SHARE_DIR = "recovery-share"

    private const val BYTES_PER_MS = 32 // 16 kHz · 16 bit · mono

    /** `true` when every fragment's upload is a WAV the app can tile. */
    fun canReconstructWav(records: List<FragmentJournal.FragmentRecord>): Boolean =
        records.isNotEmpty() && records.all { record ->
            record.uploadFile?.endsWith(".wav", ignoreCase = true) == true
        }

    /**
     * Tiles the session's WAV pieces into one WAV, pre-rolls removed. `null`
     * when a piece is missing or not a readable canonical WAV — the caller
     * falls back to sharing the files as they are.
     */
    fun reconstructWav(dir: File, records: List<FragmentJournal.FragmentRecord>): ByteArray? {
        if (!canReconstructWav(records)) return null
        return try {
            val pcm = ByteArrayOutputStream()
            var sampleRate = 16_000
            records.forEach { record ->
                val file = File(dir, record.uploadFile!!)
                if (!file.isFile) return null
                val read = WavReader.read(file.readBytes())
                sampleRate = read.sampleRate
                val skip = ((record.preRollMs ?: 0L) * BYTES_PER_MS)
                    .toInt()
                    .coerceIn(0, read.data.size)
                pcm.write(read.data, skip, read.data.size - skip)
            }
            if (pcm.size() == 0) null else WavWriter.write(pcm.toByteArray(), sampleRate)
        } catch (_: Throwable) {
            null
        }
    }

    /** The fragment files to attach when no single stream can be built. */
    fun pieceFiles(dir: File, records: List<FragmentJournal.FragmentRecord>): List<File> =
        records.mapNotNull { record ->
            record.uploadFile?.let { name -> File(dir, name).takeIf { it.isFile } }
        }
}
