package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #213: the colour twin of [PulseAlphaPolicyTest] — the IME foreground tilts
 * toward the warm pulse red at the wave floor while the red background itself
 * never pulses. Outside RECORDING the base colour pins (the #77 rule, applied
 * to the tint: a pause during the red phase cannot freeze the IME coloured).
 */
class PulseColorPolicyTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFFB4A9.toInt() // recording_pulse_red, light variant

    @Test
    fun `mix at zero is the base and at one the pulse red`() {
        assertThat(PulseColorPolicy.mix(white, red, 0f)).isEqualTo(white)
        assertThat(PulseColorPolicy.mix(white, red, 1f)).isEqualTo(red)
    }

    @Test
    fun `mix interpolates every channel monotonically`() {
        val half = PulseColorPolicy.mix(white, red, 0.5f)
        val r = (half shr 16) and 0xFF
        val g = (half shr 8) and 0xFF
        val b = half and 0xFF
        // White 255/255/255 → red 255/180/169: red stays, green/blue fall
        // halfway (the lerp truncates toward zero: 217.5 → 217 handled as
        // -37.5 → -37 on the offset).
        assertThat(r).isEqualTo(255)
        assertThat(g).isEqualTo(218)
        assertThat(b).isEqualTo(212)

        val quarter = PulseColorPolicy.mix(white, red, 0.25f)
        assertThat(((quarter shr 8) and 0xFF)).isGreaterThan(g)
        assertThat(quarter and 0xFF).isGreaterThan(b)
    }

    @Test
    fun `mix clamps out-of-range t`() {
        assertThat(PulseColorPolicy.mix(white, red, -1f)).isEqualTo(white)
        assertThat(PulseColorPolicy.mix(white, red, 2f)).isEqualTo(red)
    }

    @Test
    fun `recording maps the breath wave inversely onto the colour mix`() {
        val floor = 0.3f
        val ceil = 1f
        // At the crest (full alpha) the foreground is the plain base colour.
        assertThat(
            PulseColorPolicy.target(
                VoiceSessionController.State.RECORDING, white, red, ceil, floor, ceil
            )
        ).isEqualTo(white)
        // At the floor (dim phase) the foreground has tilted fully into the red.
        assertThat(
            PulseColorPolicy.target(
                VoiceSessionController.State.RECORDING, white, red, floor, floor, ceil
            )
        ).isEqualTo(red)
        // Mid-wave sits strictly between the endpoints.
        val mid = PulseColorPolicy.target(
            VoiceSessionController.State.RECORDING, white, red, (floor + ceil) / 2f, floor, ceil
        )
        assertThat(mid).isNotEqualTo(white)
        assertThat(mid).isNotEqualTo(red)
    }

    @Test
    fun `outside recording the base colour pins`() {
        for (state in VoiceSessionController.State.entries) {
            if (state == VoiceSessionController.State.RECORDING) continue
            assertThat(
                PulseColorPolicy.target(state, white, red, 0.3f, 0.3f, 1f)
            ).isEqualTo(white)
        }
    }
}
