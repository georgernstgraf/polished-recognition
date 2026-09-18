package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.service.ImeStartDecision.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImeStartDecisionTest {

    private val recording = VoiceSessionController.State.RECORDING
    private val paused = VoiceSessionController.State.PAUSED
    private val processing = VoiceSessionController.State.PROCESSING
    private val idle = VoiceSessionController.State.IDLE

    // The observed Oplus order: rebind lands ~150 ms BEFORE the mark, so at
    // bind time there is no rotation signal despite an ongoing rotation.
    // Same-field live sessions must survive it without any signal.

    @Test
    fun `RECORDING same-field rebind with no signal freezes (mark-late rotation)`() {
        assertThat(
            ImeStartDecision.decide(recording, true, false, false, false)
        ).isEqualTo(Outcome.FREEZE)
    }

    @Test
    fun `PROCESSING same-field rebind with no signal freezes`() {
        assertThat(
            ImeStartDecision.decide(processing, true, false, false, false)
        ).isEqualTo(Outcome.FREEZE)
    }

    @Test
    fun `PAUSED same-field rebind with no signal is provisional (no instant resume)`() {
        assertThat(
            ImeStartDecision.decide(paused, true, false, false, false)
        ).isEqualTo(Outcome.PROVISIONAL_FREEZE)
    }

    @Test
    fun `IDLE same-field rebind proceeds to auto-start`() {
        assertThat(
            ImeStartDecision.decide(idle, true, false, false, false)
        ).isEqualTo(Outcome.PROCEED)
    }

    // Mark-before-rebind order: gate fresh at bind time.

    @Test
    fun `fresh gate on same field freezes every live state`() {
        assertThat(ImeStartDecision.decide(recording, true, false, true, false))
            .isEqualTo(Outcome.FREEZE)
        assertThat(ImeStartDecision.decide(paused, true, false, true, false))
            .isEqualTo(Outcome.FREEZE)
        assertThat(ImeStartDecision.decide(processing, true, false, true, false))
            .isEqualTo(Outcome.FREEZE)
    }

    @Test
    fun `instance rotation signal freezes regardless of field`() {
        assertThat(ImeStartDecision.decide(recording, true, true, false, false))
            .isEqualTo(Outcome.FREEZE)
        assertThat(ImeStartDecision.decide(recording, false, true, false, false))
            .isEqualTo(Outcome.FREEZE)
    }

    @Test
    fun `restored snapshot freezes regardless of everything`() {
        assertThat(ImeStartDecision.decide(paused, false, false, false, true))
            .isEqualTo(Outcome.FREEZE)
        assertThat(ImeStartDecision.decide(idle, false, false, false, true))
            .isEqualTo(Outcome.FREEZE)
    }

    // Genuine field change: different identity, no signal.

    @Test
    fun `RECORDING on other field cancels`() {
        assertThat(
            ImeStartDecision.decide(recording, false, false, false, false)
        ).isEqualTo(Outcome.CANCEL)
    }

    @Test
    fun `PROCESSING on other field cancels`() {
        assertThat(
            ImeStartDecision.decide(processing, false, false, false, false)
        ).isEqualTo(Outcome.CANCEL)
    }

    @Test
    fun `PAUSED on other field proceeds to auto-resume`() {
        assertThat(
            ImeStartDecision.decide(paused, false, false, false, false)
        ).isEqualTo(Outcome.PROCEED)
    }

    @Test
    fun `IDLE on other field proceeds to auto-start`() {
        assertThat(
            ImeStartDecision.decide(idle, false, false, false, false)
        ).isEqualTo(Outcome.PROCEED)
    }

    @Test
    fun `fresh gate on other field still cancels live session`() {
        assertThat(ImeStartDecision.decide(recording, false, false, true, false))
            .isEqualTo(Outcome.CANCEL)
    }
}
