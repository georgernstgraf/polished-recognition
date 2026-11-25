package com.georgernstgraf.polishedrecognition.service

/**
 * #207: what happens to a polished result that could not be delivered at
 * Send because the field was unreadable — the device case (2026-11-25): two
 * sends parked `outcome=UNREADABLE` and were cleared by the resume tap with
 * no evidence the text ever arrived anywhere. The hold is IME-instance
 * state; this decision runs at every readable moment afterwards (the resume
 * tap, the next bind) and the teardown forces the salvage.
 *
 * #192 target rule: the ORIGIN identity decides — a hold without one is
 * never inserted into "whatever field is bound now", it is salvaged to the
 * clipboard instead. A field the user has since repurposed (a different
 * field) is not written either: clipboard + a toast naming where the text
 * is, never a blind insertion, never a silent drop.
 */
object HeldDeliveryPolicy {

    enum class Action {
        /** Not readable yet — the hold waits for the next readable moment. */
        KEEP,

        /** The bound field IS the dictation origin — insert the held text. */
        DELIVER,

        /** Wrong field, or no origin identity — clipboard + toast. */
        SALVAGE
    }

    /**
     * @param origin   the field the dictation was sent from (the Send press
     *   records it, #118); `null` = unknown — treated as a foreign field.
     * @param current  the field bound now.
     * @param readable true when a connection exposes a field identity.
     */
    fun decide(
        origin: RotationGate.FieldId?,
        current: RotationGate.FieldId?,
        readable: Boolean
    ): Action = when {
        !readable -> Action.KEEP
        origin == null -> Action.SALVAGE
        current != null && RotationGate.isSameField(origin, current) -> Action.DELIVER
        else -> Action.SALVAGE
    }
}
