package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

object PulseAlphaPolicy {

    const val FULL = 1f

    fun target(
        state: VoiceSessionController.State,
        breathAlpha: Float,
        voiceAlpha: Float
    ): Float = when (state) {
        VoiceSessionController.State.RECORDING -> maxOf(breathAlpha, voiceAlpha)
        else -> FULL
    }
}
