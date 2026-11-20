package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

/**
 * #177: the post-send **UI** park state of the voice keyboard.
 *
 * After a successful send the keyboard stays visible (#174) and must not start
 * another recording by itself — it presents the parked bar: ▶ (`ic_resume`,
 * enabled), send and flush disabled, gear/X enabled, timer hidden. The
 * controller is genuinely [VoiceSessionController.State.IDLE] (no session, no
 * audio). This state is deliberately NOT `PAUSED`: PAUSED means "audio is
 * buffered, resume appends" throughout the repo (#67/#84/#134), so parking the
 * controller there would make a resume resurrect a dictation that was already
 * delivered. The park therefore lives in the IME UI only — the controller
 * never learns about it.
 *
 * The decision is pure so the whole event/state matrix is unit-tested; the IME
 * only owns the flag and the rendering.
 */
object PostSendParkingPolicy {

    /** What happened to the parked bar. */
    enum class Event {
        /** A finished dictation was inserted — the bar parks. */
        COMPLETED_SUCCESS,

        /** The user tapped ▶ on the parked bar — unpark, start a fresh take. */
        USER_TAP_RESUME,

        /** The user tapped send on the parked bar — unpark, start a fresh take. */
        USER_TAP_SEND,

        /** The input view bound a *different* field — the park belonged there. */
        FIELD_CHANGED,

        /**
         * The platform dismissed the keyboard. Deliberately keeps the park: a
         * hide/show inside the same field is a return to the same place, and
         * an auto-start on return would be exactly the #177 bug.
         */
        DISMISSED
    }

    /**
     * @property render the controls must be drawn in the parked form now.
     * @property parked the flag to keep for the next decision.
     */
    data class Parking(val render: Boolean, val parked: Boolean)

    /**
     * One transition of the park flag.
     *
     * - [Event.COMPLETED_SUCCESS] always parks, regardless of the reported
     *   state: the IME sees the event while the controller is still
     *   PROCESSING and flips to IDLE right after it, so a state gate here
     *   would drop the park on every send. The rendering decision is still
     *   state-bound (the parked form appears with the IDLE that follows).
     * - Any other event only matters while the bar actually is (or could be)
     *   parked, i.e. in [VoiceSessionController.State.IDLE]; a live session
     *   voids a stale flag defensively.
     * - Taps and a field change unpark; everything else
     *   ([Event.DISMISSED]) leaves the flag as it is.
     */
    fun decide(
        state: VoiceSessionController.State,
        parked: Boolean,
        event: Event
    ): Parking {
        if (event == Event.COMPLETED_SUCCESS) {
            return Parking(render = renders(state, parked = true), parked = true)
        }
        if (!renders(state, parked)) return Parking(render = false, parked = false)
        return when (event) {
            Event.USER_TAP_RESUME,
            Event.USER_TAP_SEND,
            Event.FIELD_CHANGED -> Parking(render = false, parked = false)
            Event.DISMISSED, Event.COMPLETED_SUCCESS -> Parking(render = true, parked = true)
        }
    }

    /**
     * True when the bar must be drawn in the parked form: the flag is set AND
     * the controller really is idle. Between `Completed` and the following
     * `StateChanged(IDLE)` the flag is already set while the state is still
     * PROCESSING — that must render as processing, never as parked.
     */
    fun renders(state: VoiceSessionController.State, parked: Boolean): Boolean =
        parked && state == VoiceSessionController.State.IDLE
}
