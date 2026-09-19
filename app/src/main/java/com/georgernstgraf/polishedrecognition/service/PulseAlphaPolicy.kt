package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import kotlin.math.sin

object PulseAlphaPolicy {

    const val FULL = 1f

    fun target(
        state: VoiceSessionController.State,
        breathAlpha: Float
    ): Float = when (state) {
        VoiceSessionController.State.RECORDING -> breathAlpha
        else -> FULL
    }

    /**
     * Volume-independent sine blink (#87): maps a linear animator phase in
     * radians to `[floor, ceil]`. The sine eases in/out at the extrema
     * naturally, so the animator needs no interpolator keyframes.
     */
    fun blinkAlpha(phaseRadians: Float, floor: Float, ceil: Float): Float =
        ((floor + ceil) / 2f) + ((ceil - floor) / 2f) * sin(phaseRadians)
}
