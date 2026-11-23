package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController.State
import com.georgernstgraf.polishedrecognition.service.RebindChurn.UiSignature
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * #118 Phase C: the rebind storm used to repaint every control, re-assert the
 * microphone foreground service, rewrite the notification and restart the
 * breathing animation on every cycle. All four policies share one rule —
 * an UNCHANGED input asks for no work — and a CHANGED one always asks for it,
 * so skipping can never suppress a real change.
 */
class RebindChurnTest {

    private val idle = UiSignature(State.IDLE, postSendParked = false, llmForcedOff = false)

    @Test
    fun `the first render always happens`() {
        assertThat(RebindChurn.render(null, idle)).isTrue()
    }

    @Test
    fun `an unchanged ui signature never renders again`() {
        assertThat(RebindChurn.render(idle, idle.copy())).isFalse()
    }

    @Test
    fun `every input the render reads repaints when it changes`() {
        listOf(
            idle.copy(state = State.RECORDING),
            idle.copy(postSendParked = true),
            idle.copy(llmForcedOff = true)
        ).forEach { changed ->
            assertThat(RebindChurn.render(idle, changed)).isTrue()
        }
    }

    @Test
    fun `the first notification is always posted`() {
        assertThat(RebindChurn.notify(null, "Listening")).isTrue()
    }

    @Test
    fun `an identical notification text is not posted again`() {
        assertThat(RebindChurn.notify("Listening", "Listening")).isFalse()
    }

    @Test
    fun `a changed notification text is posted`() {
        assertThat(RebindChurn.notify("Processing", "Listening")).isTrue()
    }

    @Test
    fun `the foreground service is asserted once per instance`() {
        assertThat(RebindChurn.assertForeground(alreadyAsserted = false)).isTrue()
        assertThat(RebindChurn.assertForeground(alreadyAsserted = true)).isFalse()
    }

    @Test
    fun `the breath animator is untouched while the flashing state is unchanged`() {
        assertThat(RebindChurn.startBreath(currentlyFlashing = false, nextFlashing = false))
            .isFalse()
        assertThat(RebindChurn.startBreath(currentlyFlashing = true, nextFlashing = true))
            .isFalse()
    }

    @Test
    fun `a flashing change is applied in both directions`() {
        assertThat(RebindChurn.startBreath(currentlyFlashing = false, nextFlashing = true))
            .isTrue()
        assertThat(RebindChurn.startBreath(currentlyFlashing = true, nextFlashing = false))
            .isTrue()
    }

    /** The one rule all four share: unchanged ⇒ no work. */
    @Test
    fun `no function asks for work on an unchanged input`() {
        val checks = mapOf<String, () -> Boolean>(
            "render" to { RebindChurn.render(idle, idle.copy()) },
            "notify" to { RebindChurn.notify("Processing", "Processing") },
            "assertForeground" to { RebindChurn.assertForeground(alreadyAsserted = true) },
            "startBreath" to {
                RebindChurn.startBreath(currentlyFlashing = true, nextFlashing = true)
            }
        )
        checks.forEach { (name, check) ->
            assertWithMessage(name).that(check()).isFalse()
        }
    }

    @Test
    fun `the churn field names all four counters`() {
        assertThat(RebindChurn.churnLine(1, 2, 3, 4))
            .isEqualTo("churn=render:1 fgs:2 notify:3 breath:4")
    }
}
