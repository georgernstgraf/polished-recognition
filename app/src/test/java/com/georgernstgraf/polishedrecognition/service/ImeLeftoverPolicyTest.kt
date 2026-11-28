package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #217: the cross-app leftover decision — secure what the take holds instead
 * of stalling the auto-start (the #152 kept-guard came up in the pause look
 * and stayed there, measured >1 min on the device). The matrix is the owner's
 * sentence: the raw TEXT when it is the only value, the AUDIO (journal →
 * recovery) while an untranscribed fragment is in the chain, and a lossless
 * cancel when nothing is buffered.
 */
class ImeLeftoverPolicyTest {

    @Test
    fun `fully transcribed text is saved and the lossless cancel follows`() {
        val d = ImeLeftoverPolicy.decide(
            hasTranscripts = true, untranscribedAudio = false, hasBufferedAudio = true
        )
        assertThat(d).isEqualTo(ImeLeftoverPolicy.Action.SAVE_TEXT_AND_CANCEL)
    }

    @Test
    fun `untranscribed audio in the chain releases the take to the recovery`() {
        // transcripts exist AND untranscribed audio: appending now would
        // duplicate — the recovery's later entry joins the FULL raw text.
        assertThat(
            ImeLeftoverPolicy.decide(
                hasTranscripts = true, untranscribedAudio = true, hasBufferedAudio = true
            )
        ).isEqualTo(ImeLeftoverPolicy.Action.RELEASE_TO_RECOVERY)

        // pure audio, nothing transcribed: the measured device case.
        assertThat(
            ImeLeftoverPolicy.decide(
                hasTranscripts = false, untranscribedAudio = true, hasBufferedAudio = true
            )
        ).isEqualTo(ImeLeftoverPolicy.Action.RELEASE_TO_RECOVERY)
    }

    @Test
    fun `a take without audio is cancelled losslessly`() {
        val d = ImeLeftoverPolicy.decide(
            hasTranscripts = false, untranscribedAudio = false, hasBufferedAudio = false
        )
        assertThat(d).isEqualTo(ImeLeftoverPolicy.Action.CANCEL_ONLY)
    }

    /**
     * #219: the keep-checkbox is OFF — the pre-#217 discard. EVERY case,
     * including the ones that would secure text or audio, collapses to the
     * lossless cancel; the IME says so with its discard toast.
     */
    @Test
    fun `keepInWorkbench false discards every take`() {
        val cases = listOf(
            // would SAVE_TEXT_AND_CANCEL (fully transcribed)
            Triple(true, false, true),
            // would RELEASE_TO_RECOVERY (transcripts + untranscribed audio)
            Triple(true, true, true),
            // would RELEASE_TO_RECOVERY (the measured device case)
            Triple(false, true, true),
            // already CANCEL_ONLY
            Triple(false, false, false)
        )
        cases.forEach { (transcripts, untranscribed, buffered) ->
            assertThat(
                ImeLeftoverPolicy.decide(
                    hasTranscripts = transcripts,
                    untranscribedAudio = untranscribed,
                    hasBufferedAudio = buffered,
                    keepInWorkbench = false
                )
            ).isEqualTo(ImeLeftoverPolicy.Action.CANCEL_ONLY)
        }
    }
}
