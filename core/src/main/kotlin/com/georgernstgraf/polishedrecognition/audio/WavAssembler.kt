package com.georgernstgraf.polishedrecognition.audio

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Rebuilds ONE canonical WAV from several canonical WAV sources (#119).
 *
 * WAV cannot be byte-concatenated: every file carries its own 44-byte header
 * whose `data`-chunk size makes a decoder stop at the first file's end, so a
 * naive concat silently drops everything after the first source. Both places
 * that combine WAV files — the acoustic pre-roll upload composition
 * (`FragmentTranscriber.composeUpload`) and the stop-time chunk assembly
 * (`FragmentPreparer.assembleChunks`) — must go through here. (OGG needs no
 * such helper: page chaining is a native format feature and a byte-concat is
 * a valid stream.)
 */
object WavAssembler {

    /** Concatenates the PCM payloads of [sources] into a single valid WAV at [target]. */
    fun concat(sources: List<File>, target: File) {
        val out = ByteArrayOutputStream()
        var sampleRate = 16_000
        for (source in sources) {
            val pcm = WavReader.read(source.readBytes())
            sampleRate = pcm.sampleRate
            out.write(pcm.data)
        }
        target.writeBytes(WavWriter.write(out.toByteArray(), sampleRate))
    }
}
