package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

/**
 * #118 Phase C — a rebind storm must not re-do work it does not need.
 *
 * One physical rotation delivers repeated bind cycles
 * (`onStartInputView` → `onConfigurationChanged` → `onFinishInputView` → …,
 * see PITFALLS "rebind cascade"). The session survives that storm since #113
 * (`ImeStartDecision` FREEZE), but every cycle used to repaint every control,
 * re-assert the microphone foreground service, rewrite the notification and
 * restart the breathing animation of the mic button. The animation restart was
 * the most visible one: `startBreathing()` cancels and recreates the
 * `ValueAnimator`, so the breath phase jumped back to its start on every
 * cycle — the owner's "far from clean".
 *
 * These functions answer "must this work happen?" and nothing else: no Android
 * type, no state, no side effect. Every one of them returns `false` for an
 * unchanged input, so a caller that consults them can only ever SKIP an
 * identical repeat — it can never suppress a real change.
 *
 * [churnLine] is the measurement: `PolishedVoiceInputIME` appends one trailing
 * `churn=render:<r> fgs:<f> notify:<n> breath:<b>` field to its existing
 * `onStartInputView` trace line, reporting the work that really happened since
 * the previous line (the interval that just ended).
 */
internal object RebindChurn {

    /**
     * Exactly the inputs `applyUiState` reads. Everything else it touches is
     * derived from the state (the rec timer, the language line) or repaired by
     * the tick, so an equal signature means an identical paint.
     */
    data class UiSignature(
        val state: VoiceSessionController.State,
        val postSendParked: Boolean,
        val llmForcedOff: Boolean
    )

    /**
     * True when the bar must be painted. `null` (a fresh view, or none painted
     * yet) always renders once — see `invalidateUiRender`.
     */
    fun render(previous: UiSignature?, next: UiSignature): Boolean = previous != next

    /** True when the notification text differs from the last posted one. */
    fun notify(previousText: String?, nextText: String): Boolean = previousText != nextText

    /**
     * True when the microphone foreground service still has to be asserted.
     * A NEW instance starts with `alreadyAsserted = false`, which is exactly
     * the case the old unconditional re-assert was written for ("the previous
     * instance may have died"); the same live instance never needs it twice.
     */
    fun assertForeground(alreadyAsserted: Boolean): Boolean = !alreadyAsserted

    /**
     * True when the breath state changed — the caller then starts (`next`) or
     * cancels. Unchanged means the animator must stay completely untouched,
     * because recreating it resets the visible phase.
     */
    fun startBreath(currentlyFlashing: Boolean, nextFlashing: Boolean): Boolean =
        currentlyFlashing != nextFlashing

    /** The measurement appended to the `onStartInputView` trace line. */
    fun churnLine(
        renders: Int,
        foregroundAssertions: Int,
        notificationPosts: Int,
        breathStarts: Int
    ): String = "churn=render:$renders fgs:$foregroundAssertions " +
        "notify:$notificationPosts breath:$breathStarts"
}
