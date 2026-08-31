package com.georgernstgraf.polishedrecognition.audio

import java.io.File

/**
 * Transcodes the WAV bytes produced by [AudioRecorder] into a compressed format
 * accepted by OpenAI-compatible STT endpoints, writing the result to outFile.
 *
 * Implementations must throw on any failure — callers fall back to uploading
 * the original WAV.
 */
interface AudioTranscoder {
    fun transcode(wav: ByteArray, outFile: File): File
}
