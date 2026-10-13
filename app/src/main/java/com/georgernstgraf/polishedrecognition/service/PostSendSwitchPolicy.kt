package com.georgernstgraf.polishedrecognition.service

/**
 * Decides what happens to the keyboard after a successful send (#133):
 * with the setting on, the IME hands the field back to the user's typing
 * keyboard (the same target the keyboard-switch button resolves via
 * [SwitchTargetPolicy]) instead of only hiding — so the next focus opens
 * the typing keyboard, not a fresh dictation.
 *
 * The decision runs on the `Completed`-success path only: cancel and
 * pipeline-failure paths stay on the voice keyboard so the dictation can be
 * retried, and the delegated RecognitionService surface never touches IME
 * switching at all (the calling keyboard owns insertion there).
 */
object PostSendSwitchPolicy {

    enum class Decision {
        /** Switch to the resolved typing keyboard, then hide. */
        SWITCH,

        /** Today's behaviour: only hide the input view. */
        HIDE_ONLY
    }

    /**
     * @param autoSwitch the `switchBackAfterSend` setting (default ON).
     * @param target the last-used typing keyboard, or `null` when none is
     *   available (Polished is the only enabled IME). Unlike the manual
     *   keyboard-switch button, the automatic switch never opens the system
     *   keyboard settings — a missing target silently hides.
     */
    fun decide(autoSwitch: Boolean, target: String?): Decision =
        if (autoSwitch && target != null) Decision.SWITCH else Decision.HIDE_ONLY
}
