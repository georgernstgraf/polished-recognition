package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.service.PostSendParkingPolicy.Event
import com.georgernstgraf.polishedrecognition.service.PostSendParkingPolicy.Parking
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #177: after a successful send the bar parks — ↺ enabled, everything with
 * nothing to act on disabled — and stays parked until the user acts, the
 * field changes or the keyboard is dismissed (#192). The controller itself
 * stays IDLE: parking is a UI state, not a session state (PAUSED means "audio
 * is buffered, resume appends").
 */
class PostSendParkingPolicyTest {

    private val idle = VoiceSessionController.State.IDLE

    @Test
    fun `a successful send parks the bar and asks for the parked rendering`() {
        assertThat(PostSendParkingPolicy.decide(idle, parked = false, Event.COMPLETED_SUCCESS))
            .isEqualTo(Parking(render = true, parked = true))
    }

    @Test
    fun `parking does not depend on the reported state - Completed arrives while still PROCESSING`() {
        // The flag is set even though the state is still PROCESSING; the
        // parked form itself appears with the IDLE that follows.
        assertThat(
            PostSendParkingPolicy.decide(
                VoiceSessionController.State.PROCESSING,
                parked = false,
                Event.COMPLETED_SUCCESS
            )
        ).isEqualTo(Parking(render = false, parked = true))
    }

    @Test
    fun `a repeated success keeps the park`() {
        assertThat(PostSendParkingPolicy.decide(idle, parked = true, Event.COMPLETED_SUCCESS))
            .isEqualTo(Parking(render = true, parked = true))
    }
    @Test
    fun `IDLE plus parked renders the resume form`() {
        assertThat(PostSendParkingPolicy.renders(idle, parked = true)).isTrue()
    }

    @Test
    fun `only an idle bar can render parked`() {
        listOf(
            VoiceSessionController.State.RECORDING,
            VoiceSessionController.State.PAUSED,
            VoiceSessionController.State.PROCESSING
        ).forEach { state ->
            assertThat(PostSendParkingPolicy.renders(state, parked = true)).isFalse()
        }
        assertThat(PostSendParkingPolicy.renders(idle, parked = false)).isFalse()
    }

    @Test
    fun `the resume tap ends the park - the next take is fresh, not resumed`() {
        assertThat(PostSendParkingPolicy.decide(idle, parked = true, Event.USER_TAP_RESUME))
            .isEqualTo(Parking(render = false, parked = false))
    }

    @Test
    fun `the send tap ends the park too`() {
        assertThat(PostSendParkingPolicy.decide(idle, parked = true, Event.USER_TAP_SEND))
            .isEqualTo(Parking(render = false, parked = false))
    }

    @Test
    fun `a field change ends the park - the parked bar belonged to the old field`() {
        assertThat(PostSendParkingPolicy.decide(idle, parked = true, Event.FIELD_CHANGED))
            .isEqualTo(Parking(render = false, parked = false))
    }

    @Test
    fun `the same events on an unparked bar change nothing`() {
        listOf(Event.USER_TAP_RESUME, Event.USER_TAP_SEND, Event.FIELD_CHANGED).forEach { event ->
            assertThat(PostSendParkingPolicy.decide(idle, parked = false, event))
                .isEqualTo(Parking(render = false, parked = false))
        }
    }

    @Test
    fun `dismissing the keyboard ends the park - the park belongs to a keyboard that is still up`() {
        assertThat(PostSendParkingPolicy.decide(idle, parked = true, Event.DISMISSED))
            .isEqualTo(Parking(render = false, parked = false))
        assertThat(PostSendParkingPolicy.decide(idle, parked = false, Event.DISMISSED))
            .isEqualTo(Parking(render = false, parked = false))
    }

    @Test
    fun `a live session voids a stale park`() {
        listOf(
            VoiceSessionController.State.RECORDING,
            VoiceSessionController.State.PAUSED,
            VoiceSessionController.State.PROCESSING
        ).forEach { state ->
            Event.entries.forEach { event ->
                val parking = PostSendParkingPolicy.decide(state, parked = true, event)
                if (event == Event.COMPLETED_SUCCESS) {
                    assertThat(parking).isEqualTo(Parking(render = false, parked = true))
                } else {
                    assertThat(parking).isEqualTo(Parking(render = false, parked = false))
                }
            }
        }
    }

    @Test
    fun `the full state-event matrix obeys the two invariants`() {
        VoiceSessionController.State.entries.forEach { state ->
            listOf(false, true).forEach { parked ->
                Event.entries.forEach { event ->
                    val parking = PostSendParkingPolicy.decide(state, parked, event)
                    // 1. The rendering asks for the parked form exactly when
                    //    the flag is set AND the controller is idle.
                    assertThat(parking.render)
                        .isEqualTo(PostSendParkingPolicy.renders(state, parking.parked))
                    // 2. A park never survives outside IDLE; inside IDLE only
                    //    a success creates or keeps it.
                    if (parking.parked && state != idle) {
                        assertThat(event).isEqualTo(Event.COMPLETED_SUCCESS)
                    }
                    if (parking.parked && !parked) {
                        assertThat(event).isEqualTo(Event.COMPLETED_SUCCESS)
                    }
                }
            }
        }
    }
}
