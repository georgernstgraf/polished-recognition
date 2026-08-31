package com.georgernstgraf.polishedrecognition.service

import kotlin.math.ln

object RmsAlphaMapper {

    const val ALPHA_FLOOR = 0.5f
    const val NOISE_FLOOR = 200f
    const val RMS_CEILING = 2500f
    const val EMA_WEIGHT = 0.3f

    fun smooth(prev: Float, rms: Float): Float =
        prev * (1f - EMA_WEIGHT) + rms * EMA_WEIGHT

    fun alpha(smoothedRms: Float): Float {
        val level = normalizedLevel(smoothedRms)
        return ALPHA_FLOOR + (1f - ALPHA_FLOOR) * level
    }

    private fun normalizedLevel(smoothedRms: Float): Float {
        val effective = (smoothedRms - NOISE_FLOOR).coerceIn(0f, RMS_CEILING - NOISE_FLOOR)
        val max = ln(1f + RMS_CEILING - NOISE_FLOOR)
        return (ln(1f + effective) / max).coerceIn(0f, 1f)
    }
}
