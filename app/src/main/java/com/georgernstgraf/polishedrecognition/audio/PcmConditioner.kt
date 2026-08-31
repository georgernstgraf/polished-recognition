package com.georgernstgraf.polishedrecognition.audio

/**
 * Light "make it easier for the STT model" pass over raw 16-bit little-endian PCM:
 * a gentle high-pass filter (removes rumble/hum below ~80 Hz) followed by
 * peak normalization (amplifies quiet recordings, never attenuates).
 *
 * Pure JVM, deterministic, no Android dependencies — the heavy lifting is done
 * by the STT endpoint; this only fixes the two most common mic-path artifacts.
 */
object PcmConditioner {

    private const val HIGH_PASS_HZ = 80.0
    private const val TARGET_PEAK = 26214 // ~0.8 × full scale
    private const val MAX_GAIN = 10f
    // Below this the buffer is effectively silence — matches the recorder's
    // speech-begin threshold; amplifying ambient noise would hurt, not help.
    private const val MIN_PEAK = 200

    fun condition(pcm: ByteArray, sampleRate: Int): ByteArray {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return pcm
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) samples[i] = readSample(pcm, 2 * i)

        highPass(samples, sampleRate)
        normalize(samples)

        val out = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) writeSample(out, 2 * i, samples[i])
        return out
    }

    private fun highPass(samples: ShortArray, sampleRate: Int) {
        if (sampleRate <= 0) return
        val rc = 1.0 / (2.0 * Math.PI * HIGH_PASS_HZ)
        val alpha = rc / (rc + 1.0 / sampleRate)
        var prevIn = 0.0
        var prevOut = 0.0
        for (i in samples.indices) {
            val x = samples[i].toDouble()
            val y = alpha * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            samples[i] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private fun normalize(samples: ShortArray) {
        var peak = 0
        for (s in samples) {
            val magnitude = if (s < 0) -s.toInt() else s.toInt()
            if (magnitude > peak) peak = magnitude
        }
        if (peak < MIN_PEAK) return
        val gain = (TARGET_PEAK.toFloat() / peak).coerceAtMost(MAX_GAIN)
        if (gain <= 1.01f) return
        for (i in samples.indices) {
            samples[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun readSample(buf: ByteArray, offset: Int): Short {
        val value = ((buf[offset + 1].toInt() and 0xFF) shl 8) or (buf[offset].toInt() and 0xFF)
        return if (value >= 32768) (value - 65536).toShort() else value.toShort()
    }

    private fun writeSample(buf: ByteArray, offset: Int, value: Short) {
        val v = value.toInt() and 0xFFFF
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
