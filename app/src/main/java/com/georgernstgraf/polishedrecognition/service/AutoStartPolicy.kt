package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

object AutoStartPolicy {

    enum class EntryAction {
        /** Continue the paused dictation: foreground + `resume()` (appends to the old audio). */
        RESUME,

        /** A fresh dictation: discard any leftover (salvage via `cancel()`) and start recording. */
        FRESH_START,

        /** No automatic action (no mic permission, or a live session that must not be touched). */
        NONE
    }

    /**
     * What the IME does automatically when it (re)binds an input view (final
     * state of the `ImeStartDecision` tail — FREEZE/PROVISIONAL_FREEZE never
     * reach this).
     *
     * - IDLE + permission → [EntryAction.FRESH_START] (plain auto-start).
     * - PAUSED + **same field** → [EntryAction.RESUME]: the dictation the user
     *   paused (or left via the keyboard-switch/gear detour) survives and
     *   continues (#65). A same-field rebind can never be a genuine field
     *   change (#83), so "same field" reliably means "returning to my own
     *   dictation".
     * - PAUSED + **different field** → [EntryAction.FRESH_START] (#134, owner
     *   decision): an old paused dictation must not resurrect in another app —
     *   it is discarded through the normal cancel path (#113 salvage keeps
     *   already-transcribed fragments recoverable) and a fresh empty recording
     *   starts. This supersedes the old "auto-resume PAUSED on a different
     *   field" clause of #65/#83.
     */
    fun entryAction(
        state: VoiceSessionController.State,
        sameField: Boolean,
        hasMicPermission: Boolean
    ): EntryAction {
        if (!hasMicPermission) return EntryAction.NONE
        return when (state) {
            VoiceSessionController.State.IDLE -> EntryAction.FRESH_START
            VoiceSessionController.State.PAUSED ->
                if (sameField) EntryAction.RESUME else EntryAction.FRESH_START
            else -> EntryAction.NONE
        }
    }
}
