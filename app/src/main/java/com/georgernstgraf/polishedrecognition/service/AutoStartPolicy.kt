package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

object AutoStartPolicy {

    enum class EntryAction {
        /** Continue the paused dictation: foreground + `resume()` (appends to the old audio). */
        RESUME,

        /**
         * A fresh dictation. A PAUSED leftover is first ended through the
         * normal cancel path (#113 salvage: clipboard + Toast for fragments
         * that were already transcribed), then a fresh empty take starts.
         */
        FRESH_START,

        /** No automatic action (no mic permission, or a live session that must not be touched). */
        NONE
    }

    /**
     * What the IME does automatically when it (re)binds an input view (final
     * state of the `ImeStartDecision` tail — FREEZE/PROVISIONAL_FREEZE never
     * reach this).
     *
     * ## The target app decides (#192)
     *
     * A pause used to be anonymous, so "PAUSED + same field" resumed a take the
     * owner had left via the keyboard switch minutes earlier and merged its
     * audio into the next send (measured on the OnePlus: `INSERTION
     * length=1125` at 20:23:44 carrying audio from before the 20:17 pause).
     * The owner's rule is the shortest one that fixes it:
     *
     * - **Same target app → [EntryAction.RESUME]**, always: the pause button,
     *   the X (#155), a nav-bar close, a keyboard that merely hid — and a take
     *   restored from disk after a process death or a reboot (the #67
     *   snapshot) behave identically. No explicit ↺ tap, no time window, no restore
     *   special case. Why this cannot be "within N seconds": the owner's
     *   "continue even after ten seconds" (and finally "always continue, no
     *   exceptions") means elapsed time never decides anything.
     * - **Another app → [EntryAction.FRESH_START]**: an old dictation must not
     *   resurrect in a foreign app (#134, now keyed on the PACKAGE rather than
     *   the field) — it is discarded through the normal cancel path, which
     *   copies whatever was already transcribed (#113 salvage), and a fresh
     *   empty recording starts. Nothing is written for the paused take: no
     *   Workbench entry, no journal session, no persisted state.
     * - [VoiceSessionController.State.IDLE] + permission →
     *   [EntryAction.FRESH_START] (plain auto-start).
     * - RECORDING/PROCESSING → [EntryAction.NONE].
     *
     * Known consequence of "always continue, no exceptions" (owner's explicit
     * call): a take paused on an earlier day still belongs to its app, so it can
     * join a much later dictation in that same app.
     *
     * @param sameTarget the app bound now is the one the paused take belongs to
     *   ([VoiceSessionController.pausedPackage] against the bound package; a
     *   snapshot without one falls back to the last served field).
     */
    fun entryAction(
        state: VoiceSessionController.State,
        sameTarget: Boolean,
        hasMicPermission: Boolean
    ): EntryAction {
        if (!hasMicPermission) return EntryAction.NONE
        return when (state) {
            VoiceSessionController.State.IDLE -> EntryAction.FRESH_START
            VoiceSessionController.State.PAUSED ->
                if (sameTarget) EntryAction.RESUME else EntryAction.FRESH_START
            else -> EntryAction.NONE
        }
    }
}
