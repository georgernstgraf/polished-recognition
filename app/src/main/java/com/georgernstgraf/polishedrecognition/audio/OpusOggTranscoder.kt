package com.georgernstgraf.polishedrecognition.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Transcodes the 16 kHz mono 16-bit WAV produced by [AudioRecorder] into an
 * Ogg/Opus file using the platform MediaCodec Opus encoder and MediaMuxer
 * (OGG muxing is API 30+, which matches the app's minSdk).
 *
 * Opus at 24 kbps is ~10x smaller than the equivalent 256 kbps WAV upload.
 * Throws on any failure — callers fall back to uploading the original WAV.
 */
class OpusOggTranscoder(
    private val bitrate: Int = DEFAULT_BITRATE
) : AudioTranscoder {

    override fun transcode(wav: ByteArray, outFile: File): File {
        val pcm = try {
            val raw = WavReader.read(wav)
            PcmConditioner.condition(raw.data, raw.sampleRate).let { Pcm(raw.sampleRate, it) }
        } catch (e: Exception) {
            outFile.delete()
            throw e
        }
        try {
            encodeToOgg(pcm.data, pcm.sampleRate, outFile)
        } catch (e: Exception) {
            outFile.delete()
            throw e
        }
        return outFile
    }

    private fun encodeToOgg(pcm: ByteArray, sampleRate: Int, outFile: File) {
        if (pcm.size < 2) throw IllegalArgumentException("no audio data to encode")

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw IllegalStateException("no MediaCodec Opus encoder available")

        val codec = MediaCodec.createByCodecName(encoderName)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

            var trackIndex = -1
            var samplesFed = 0L
            var inputOffset = 0
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer: ByteBuffer = requireNotNull(codec.getInputBuffer(inIndex))
                        inBuffer.clear()
                        val chunk = minOf(inBuffer.remaining(), pcm.size - inputOffset)
                        inBuffer.put(pcm, inputOffset, chunk)
                        val ptsUs = samplesFed * 1_000_000L / sampleRate
                        inputOffset += chunk
                        samplesFed += chunk / 2
                        val isLast = inputOffset >= pcm.size
                        codec.queueInputBuffer(
                            inIndex, 0, chunk, ptsUs,
                            if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                        inputDone = isLast
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        if (bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            trackIndex >= 0
                        ) {
                            val outBuffer: ByteBuffer = requireNotNull(codec.getOutputBuffer(outIndex))
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
            muxer?.release()
        }
    }

    private data class Pcm(val sampleRate: Int, val data: ByteArray)

    companion object {
        private const val TIMEOUT_US = 10_000L
        const val DEFAULT_BITRATE = 24_000
    }
}
