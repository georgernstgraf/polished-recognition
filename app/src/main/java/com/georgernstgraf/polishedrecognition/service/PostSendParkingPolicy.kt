package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

/**
 * #177: the post-send **UI** park state of the voice keyboard.
 *
 * After a successful send the keyboard stays visible (#174) and must not start
 * another recording by itself — it presents the parked bar: ↺ (`ic_resume`,
 * enabled), send and flush disabled, gear/X enabled. The controller is
 * genuinely [VoiceSessionController.State.IDLE] (no session, no audio). This
 * state is deliberately NOT `PAUSED`: PAUSED means "audio is buffered, resume
 * appends" throughout the repo (#67/#84/#134), so parking the controller there
 * would make a resume resurrect a dictation that was already delivered. The
 * park therefore lives in the IME UI only — the controller never learns about
 * it.
 *
 * #192 (owner): the parked bar is also what a **fresh** IDLE looks like — "fresh
 * idle should advance to a parked at 00:00". So the rendered bar is identical in
 * both cases (plane disabled, ↺ live, timer 0:00) — and since #201/#204 that is
 * literally all: no stage line in either, the flag only suppresses the
 * auto-start after a send.
 *
 * The decision is pure so the whole event/state matrix is unit-tested; the IME
 * only owns the flag and the rendering.
 */
object PostSendParkingPolicy {

    /** What happened to the parked bar. */
    enum class Event {
        /** A finished dictation was inserted — the bar parks. */
        COMPLETED_SUCCESS,

        /** The user tapped ↺ on the parked bar — unpark, start a fresh take. */
        USER_TAP_RESUME,

        /** The user tapped send on the parked bar — unpark, start a fresh take. */
        USER_TAP_SEND,

        /** The input view bound a *different* field — the park belonged there. */
        FIELD_CHANGED,

        /**
         * The platform dismissed the keyboard (nav-bar down arrow, Back, the
         * window hiding). #192, owner decision: "if after send I close the IME
         * with nav, and then tap to edit text, it should just start a new
         * dictation, no?" — the park ends with the keyboard. It survives only
         * while the keyboard stays up (#177: a same-field hide/show without a
         * dismissal, i.e. a rotation, keeps it).
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
     * - Taps and a field change unpark; so does a dismissal (#192: the park
     *   belongs to a keyboard that is still up).
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
            Event.FIELD_CHANGED,
            Event.DISMISSED -> Parking(render = false, parked = false)
            Event.COMPLETED_SUCCESS -> Parking(render = true, parked = true)
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
