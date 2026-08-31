package com.georgernstgraf.polishedrecognition.service

import kotlin.math.ln

object RmsAlphaMapper {

    const val ALPHA_FLOOR = 0.5f
    const val RMS_CEILING = 2500f
    const val EMA_WEIGHT = 0.3f

    fun smooth(prev: Float, rms: Float): Float =
        prev * (1f - EMA_WEIGHT) + rms * EMA_WEIGHT

    fun alpha(smoothedRms: Float): Float {
        val level = normalizedLevel(smoothedRms)
        return ALPHA_FLOOR + (1f - ALPHA_FLOOR) * level
    }

    private fun normalizedLevel(smoothedRms: Float): Float {
        if (smoothedRms <= 0f) return 0f
        val rms = smoothedRms.coerceAtMost(RMS_CEILING)
        val max = ln(1f + RMS_CEILING)
        val value = ln(1f + rms) / max
        return value.coerceIn(0f, 1f)
    }
}
