package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

/**
 * Pure bind decision for `PolishedVoiceInputIME.onStartInputView` (#83 v3).
 *
 * Two earlier designs failed on Oplus, where a client-app rotation rebinds
 * input (`restarting=false`, same field) ~150 ms BEFORE `onConfigurationChanged`
 * delivers the rotation mark to the IME (proven by the on-device
 * `ime-lifecycle.log`: rebind at `...722741`, mark at `...722887`). Any
 * decision that needs the mark at bind time therefore mis-fires on every
 * rotation — it cancelled the live session and auto-started a fresh `00:00`
 * one.
 *
 * The rule exploits one structural fact: a rebind onto the SAME view
 * (`packageName` + `fieldId` equal) can never be a genuine field change — a
 * real switch always carries a different identity. Cancelling there is never
 * correct, mark or no mark. The freshness gate then only governs *timing*:
 *
 * - [Outcome.FREEZE] — present the session untouched: no cancel, no
 *   auto-resume, no auto-start. Rotation (either signal path), restored
 *   post-death snapshots, and same-field RECORDING/PROCESSING rebinds.
 * - [Outcome.CANCEL] — different field with a live RECORDING/PROCESSING
 *   session: discard it as before, then run the normal tail (which re-reads
 *   the now-IDLE state and may auto-start on the new field).
 *
 *   #113 amendment (2026-10-10 data loss): inside the fresh rotation window
 *   (`gateFresh`) a live session NEVER cancels — the outcome is FREEZE, the
 *   session keeps recording and the user delivers it where they intended.
 *   Reason: the rotation cascade delivers MULTIPLE rebinds, and the rotated
 *   client app can re-create its editor with a CHANGED field identity
 *   (observed: `fieldId` changed mid-cascade), which is indistinguishable
 *   from a genuine field switch at bind time. Both misjudgment costs stay
 *   inside the documented bias — wrongly surviving a field change is
 *   harmless (the user taps cancel), wrongly cancelling a rotation loses
 *   dictation (proven: 10.5 min destroyed, 2026-10-10 01:24 CEST).
 * - [Outcome.PROVISIONAL_FREEZE] — PAUSED on the same field with no rotation
 *   signal *yet*. The mark may simply be late, so the caller must NOT
 *   auto-resume now; instead it re-checks after a short delay (see
 *   `PROVISIONAL_RESUME_DELAY_MS` at the call site): gate still stale and
 *   still PAUSED → resume per #65 (hide/show, switch return); gate fresh →
 *   rotation confirmed, stay frozen.
 * - [Outcome.PROCEED] — run the normal tail immediately (auto-resume PAUSED
 *   on a different field per #65/#83, auto-start IDLE).
 */
object ImeStartDecision {

    enum class Outcome { FREEZE, CANCEL, PROVISIONAL_FREEZE, PROCEED }

    fun decide(
        state: VoiceSessionController.State,
        sameField: Boolean,
        instRotation: Boolean,
        gateFresh: Boolean,
        restored: Boolean
    ): Outcome {
        if (restored || instRotation) return Outcome.FREEZE
        if (gateFresh && sameField) return Outcome.FREEZE
        if (!sameField) {
            // #113: a live session survives the rotation window even when
            // the re-created editor reports a different field identity —
            // cancelling here destroyed a 10.5-min dictation (2026-10-10).
            if (gateFresh && (
                state == VoiceSessionController.State.RECORDING ||
                    state == VoiceSessionController.State.PROCESSING
                )
            ) {
                return Outcome.FREEZE
            }
            return if (
                state == VoiceSessionController.State.RECORDING ||
                state == VoiceSessionController.State.PROCESSING
            ) {
                Outcome.CANCEL
            } else {
                Outcome.PROCEED
            }
        }
        return when (state) {
            VoiceSessionController.State.RECORDING,
            VoiceSessionController.State.PROCESSING -> Outcome.FREEZE
            VoiceSessionController.State.PAUSED -> Outcome.PROVISIONAL_FREEZE
            VoiceSessionController.State.IDLE -> Outcome.PROCEED
        }
    }
}
