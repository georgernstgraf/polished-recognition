package com.georgernstgraf.polishedrecognition.service

/**
 * Pure rotation-vs-navigation decision for the voice IME (#83 follow-ups).
 *
 * Two earlier designs failed on Oplus, where the IME service (and, as the
 * owner proved on-device, at times the whole process) dies on rotation:
 *
 * 1. The `configChangeInProgress` instance flag never reaches the new
 *    instance after service recreation.
 * 2. Gating on `restarting` fails because Oplus rebinds input when the
 *    *client app* rotates, delivering `onStartInputView(restarting=false)` —
 *    indistinguishable from a genuine field change by that flag alone.
 *
 * The rule therefore uses two app-scoped signals, both written in
 * `onConfigurationChanged` / `onStartInputView` and held in
 * `PolishedRecognitionApp`:
 *
 * - a rotation timestamp (monotonic uptime ms) — fresh within
 *   [FRESH_WINDOW_MS] means a rotation just happened; the window
 *   self-cleans, so a stale mark can never leak into a later
 *   keyboard-switch return;
 * - the last served field identity (`packageName` + `fieldId` from
 *   `EditorInfo`) — a client-app recreation keeps both, so rotation on the
 *   same field is recognized even with `restarting=false`. A real field
 *   switch inside the window compares unequal and still cancels as before.
 *   The bias is deliberate: an id-less field (`fieldId == 0`) still matches
 *   on package, because wrongly surviving a field change (user taps cancel)
 *   is harmless while wrongly cancelling a rotation loses dictation.
 */
object RotationGate {

    const val FRESH_WINDOW_MS = 3000L

    data class FieldId(val packageName: String?, val fieldId: Int)

    fun isFresh(lastConfigChangeMs: Long, nowMs: Long): Boolean =
        lastConfigChangeMs > 0L && nowMs - lastConfigChangeMs <= FRESH_WINDOW_MS

    fun isSameField(last: FieldId?, current: FieldId): Boolean {
        if (last == null) return false
        if (last.packageName != current.packageName) return false
        // Id-less fields (fieldId == 0, common when the EditorInfo is
        // sparsely populated) match on package alone: wrongly surviving a
        // field change (user taps cancel) is harmless while wrongly
        // cancelling a rotation loses dictation.
        if (last.fieldId == 0 || current.fieldId == 0) return true
        return last.fieldId == current.fieldId
    }

    fun isRotation(
        lastConfigChangeMs: Long,
        nowMs: Long,
        lastField: FieldId?,
        currentField: FieldId
    ): Boolean = isFresh(lastConfigChangeMs, nowMs) && isSameField(lastField, currentField)
}
