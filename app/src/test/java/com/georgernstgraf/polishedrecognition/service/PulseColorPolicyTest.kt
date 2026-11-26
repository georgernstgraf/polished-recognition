package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #213 correction: the owner's red belongs to the FONT, never to the IME
 * background — and there is no colour wave: the font is SOLIDLY red for the
 * whole recording (only the alpha pulses via [PulseAlphaPolicyTest]) and
 * pins back to the neutral base outside RECORDING. The owner's explicit
 * requirement: when the pulsing ends, the font is NOT red any more — so the
 * red is scoped to exactly the RECORDING state and every other state
 * (including PAUSED, PROCESSING, IDLE) pins the base.
 */
class PulseColorPolicyTest {

    private val base = 0xFF212121.toInt() // neutral text colour, light theme
    private val red = 0xFFC62828.toInt() // recording_pulse_red, light variant

    @Test
    fun `recording shows the red font`() {
        assertThat(
            PulseColorPolicy.target(VoiceSessionController.State.RECORDING, base, red)
        ).isEqualTo(red)
    }

    @Test
    fun `every other state pins the neutral base — no red can freeze`() {
        for (state in VoiceSessionController.State.entries) {
            if (state == VoiceSessionController.State.RECORDING) continue
            assertThat(PulseColorPolicy.target(state, base, red)).isEqualTo(base)
        }
    }
}
