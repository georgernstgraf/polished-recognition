package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

/**
 * #213 correction: the owner's red applies to the FONT, never to the IME
 * background — and there is no colour wave: the font is SOLIDLY red for the
 * whole recording (only the alpha pulses, via [PulseAlphaPolicy]) and pins
 * back to the neutral base outside RECORDING. A pause mid-pulse therefore
 * leaves no red anywhere: the colour is scoped to exactly the RECORDING
 * state, and `applyAlpha()` re-pins it on every state change.
 */
object PulseColorPolicy {

    /**
     * The foreground colour for the current state: red while RECORDING, the
     * neutral base colour in every other state (IDLE, PAUSED, PROCESSING).
     */
    fun target(
        state: VoiceSessionController.State,
        base: Int,
        pulse: Int
    ): Int = when (state) {
        VoiceSessionController.State.RECORDING -> pulse
        else -> base
    }
}
