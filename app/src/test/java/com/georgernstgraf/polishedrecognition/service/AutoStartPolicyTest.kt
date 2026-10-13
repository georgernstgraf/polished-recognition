package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoStartPolicyTest {

    @Test
    fun `IDLE with permission fresh-starts`() {
        assertThat(
            AutoStartPolicy.entryAction(VoiceSessionController.State.IDLE, sameField = true, true)
        ).isEqualTo(AutoStartPolicy.EntryAction.FRESH_START)
        assertThat(
            AutoStartPolicy.entryAction(VoiceSessionController.State.IDLE, sameField = false, true)
        ).isEqualTo(AutoStartPolicy.EntryAction.FRESH_START)
    }

    @Test
    fun `without mic permission nothing happens automatically`() {
        VoiceSessionController.State.entries.forEach { state ->
            assertThat(
                AutoStartPolicy.entryAction(state, sameField = true, false)
            ).isEqualTo(AutoStartPolicy.EntryAction.NONE)
            assertThat(
                AutoStartPolicy.entryAction(state, sameField = false, false)
            ).isEqualTo(AutoStartPolicy.EntryAction.NONE)
        }
    }

    @Test
    fun `PAUSED on the same field resumes - the dictation survives the detour (#65)`() {
        assertThat(
            AutoStartPolicy.entryAction(VoiceSessionController.State.PAUSED, sameField = true, true)
        ).isEqualTo(AutoStartPolicy.EntryAction.RESUME)
    }

    @Test
    fun `PAUSED on a different field fresh-starts - no resurrection in another app (#134)`() {
        assertThat(
            AutoStartPolicy.entryAction(VoiceSessionController.State.PAUSED, sameField = false, true)
        ).isEqualTo(AutoStartPolicy.EntryAction.FRESH_START)
    }

    @Test
    fun `live sessions are never touched automatically`() {
        listOf(
            VoiceSessionController.State.RECORDING,
            VoiceSessionController.State.PROCESSING
        ).forEach { state ->
            assertThat(
                AutoStartPolicy.entryAction(state, sameField = true, true)
            ).isEqualTo(AutoStartPolicy.EntryAction.NONE)
            assertThat(
                AutoStartPolicy.entryAction(state, sameField = false, true)
            ).isEqualTo(AutoStartPolicy.EntryAction.NONE)
        }
    }
}
