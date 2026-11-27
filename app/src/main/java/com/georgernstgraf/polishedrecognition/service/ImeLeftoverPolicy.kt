package com.georgernstgraf.polishedrecognition.service

/**
 * #217: what a foreign-app entry does with a PAUSED leftover — the successor
 * of the #152 kept-guard, which stalled the auto-start (the keyboard came up
 * in the pause look and stayed there: `kept=audio-without-transcripts`,
 * measured >1 min on the device). The owner's rule now secures what the take
 * holds instead of stalling:
 *
 * - **raw text** when transcripts exist AND no untranscribed audio is left
 *   anywhere (chain fully transcribed — the text is the only value; the #113
 *   cancel path then deletes the empty journal and the #217 append puts the
 *   raw text into the Workbench next to the clipboard copy);
 * - **the audio** (via the journal → the #185 recovery) when an untranscribed
 *   fragment is still in the chain — the recovery later produces ONE entry
 *   with the FULL raw text (the stored DONE transcripts joined with the newly
 *   transcribed ones), so appending the current transcripts NOW would
 *   duplicate them. Never both paths, never a silent loss.
 *
 * A take without audio is lossless to cancel, exactly as before the guard.
 */
object ImeLeftoverPolicy {

    enum class Action {
        /** Append the raw transcripts to the Workbench, then the #113 cancel (journal deleted). */
        SAVE_TEXT_AND_CANCEL,

        /** Release the take to the recovery (journal survives; no text appended now). */
        RELEASE_TO_RECOVERY,

        /** Nothing buffered — the plain #113 cancel is lossless. */
        CANCEL_ONLY
    }

    fun decide(
        hasTranscripts: Boolean,
        untranscribedAudio: Boolean,
        hasBufferedAudio: Boolean
    ): Action = when {
        hasTranscripts && !untranscribedAudio -> Action.SAVE_TEXT_AND_CANCEL
        hasTranscripts || hasBufferedAudio -> Action.RELEASE_TO_RECOVERY
        else -> Action.CANCEL_ONLY
    }
}
