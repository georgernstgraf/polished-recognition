package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import kotlin.math.abs

/**
 * #213: the colour twin of [PulseAlphaPolicy]. The recording red background
 * itself never pulses (the owner: "the background does not pulse") — the
 * FOREGROUND pulses in alpha (as before) and additionally tilts toward a
 * warm red at the wave floor ("alpha + rot"): white at the crest, the pulse
 * red at the floor, one driver ([breathAlpha]) moves both in sync so the
 * wave stays coherent.
 *
 * Outside RECORDING both components pin: full alpha, plain base colour — a
 * pause during the dim/red phase cannot freeze the IME tinted (#77 rule,
 * colour-wise).
 */
object PulseColorPolicy {

    /** ARGB channel lerp, `t` clamped to [0, 1] — 0 = base, 1 = pulse red. */
    fun mix(base: Int, pulse: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val p = (pulse shr shift) and 0xFF
            val v = b + ((p - b) * clamped).toInt()
            return v.coerceIn(0, 255) shl shift
        }
        // Alpha comes from the row alpha pulse, the colour mix carries full opacity.
        return 0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
    }

    /**
     * The foreground colour for the current state: RECORDING maps the breath
     * wave position onto the base↔pulse mix — INVERSE to the alpha, so the
     * crest (alpha 1) shows the plain base colour and the floor (alpha floor)
     * has tilted fully into the pulse red. Every other state pins the base.
     */
    fun target(
        state: VoiceSessionController.State,
        base: Int,
        pulse: Int,
        breathAlpha: Float,
        floor: Float,
        ceil: Float
    ): Int = when (state) {
        VoiceSessionController.State.RECORDING -> {
            val span = abs(ceil - floor).coerceAtLeast(1e-6f)
            mix(base, pulse, (ceil - breathAlpha) / span)
        }
        else -> base
    }
}
