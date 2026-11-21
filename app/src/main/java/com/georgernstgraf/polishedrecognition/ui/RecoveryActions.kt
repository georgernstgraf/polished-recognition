package com.georgernstgraf.polishedrecognition.ui

/**
 * #185: which recovery actions a row may offer — the ONE place the enablement
 * rule lives (house pattern: [WorkbenchActions], `PostSendParkingPolicy`).
 *
 * The asymmetry is deliberate:
 *
 * - **re-run** needs a provider, audio and an idle device: a second STT stream
 *   during a live dictation would compete with the latency-critical path.
 * - **restore** exists only for a COMPLETED session — every fragment already
 *   has its text, so a re-run would be a pointless upload.
 * - **share** needs audio, which a restored/completed session still has.
 * - **dismiss** is only blocked while this very session is being worked on —
 *   deleting audio under a running re-run would strand its writes.
 */
object RecoveryActions {

    data class Actions(
        val reRun: Boolean,
        val restore: Boolean,
        val share: Boolean,
        val dismiss: Boolean
    )

    fun enabled(
        completed: Boolean,
        hasAudio: Boolean,
        busy: Boolean,
        providerConfigured: Boolean
    ): Actions = Actions(
        reRun = !completed && hasAudio && !busy && providerConfigured,
        restore = completed && !busy,
        share = hasAudio && !busy,
        dismiss = !busy
    )
}
