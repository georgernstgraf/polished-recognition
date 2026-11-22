package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.SttFailure

/**
 * #183: the ACTIONABLE half of a failed transcription.
 *
 * Two of the four failure classes need the user to do something, and saying
 * "HTTP 401" instead of "the token is dead" was the whole complaint that
 * produced the issue. The hint is a separate, pure lookup so the mapping is
 * testable without Robolectric; the IME prefixes it to the technical detail
 * (CONVENTIONS: the user must SEE why, not only that something failed).
 * (#75 adds a fourth: a spend cap no retry can clear.)
 *
 * `null` means: no advice for this class, show the detail alone.
 */
object SttFailureText {

    /** The hint resource for a failure class, or `null` when it needs none. */
    fun resFor(failure: SttFailure?): Int? = when (failure) {
        // 401/403: retrying with the same token is pointless — the user has to
        // replace it. The session is parked PAUSED, so Send re-runs it.
        SttFailure.AUTH -> R.string.ime_stt_token_rejected
        // A 2xx we could not read: the audio is in the journal, one more send
        // is the honest offer.
        SttFailure.PROTOCOL -> R.string.ime_stt_unusable_response
        // #75: the provider's spend cap. Only the user can clear it (top up,
        // or point the app at another provider) — the provider's own sentence
        // follows as the technical detail.
        SttFailure.BUDGET -> R.string.ime_stt_budget_exhausted
        SttFailure.TRANSIENT, SttFailure.TERMINAL, null -> null
    }
}
