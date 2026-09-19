package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ImeHintPolicyTest {

    @Test
    fun `lower-row buttons map to their fixed hints`() {
        assertThat(ImeHintPolicy.hintFor(R.id.ime_cancel_button))
            .isEqualTo(R.string.ime_cancel_hint)
        assertThat(ImeHintPolicy.hintFor(R.id.ime_flush_button))
            .isEqualTo(R.string.ime_flush_hint)
        assertThat(ImeHintPolicy.hintFor(R.id.ime_pause_resume_button))
            .isEqualTo(R.string.ime_pause_resume_hint)
        assertThat(ImeHintPolicy.hintFor(R.id.ime_send_button))
            .isEqualTo(R.string.ime_send_hint)
    }

    @Test
    fun `hints explain what the button ultimately does`() {
        val ctx = RuntimeEnvironment.getApplication()
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_cancel_button)!!))
            .isEqualTo("Cancel — discard the recording and close")
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_flush_button)!!))
            .isEqualTo("Discard — clear audio and timer, stay here")
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_pause_resume_button)!!))
            .isEqualTo("Pause / resume — recorded audio is kept")
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_send_button)!!))
            .isEqualTo("Send — transcribe, polish and insert the text")
    }

    @Test
    fun `top-row buttons map to their hints`() {
        assertThat(ImeHintPolicy.hintFor(R.id.ime_switch_keyboard_button))
            .isEqualTo(R.string.ime_switch_hint)
        assertThat(ImeHintPolicy.hintFor(R.id.ime_settings_button))
            .isEqualTo(R.string.ime_settings_hint)
        val ctx = RuntimeEnvironment.getApplication()
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_switch_keyboard_button)!!))
            .isEqualTo("Keyboard — back to your text keyboard")
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_settings_button)!!))
            .isEqualTo("Settings — providers, language and prompts")
    }

    @Test
    fun `views without help have no hint`() {
        assertThat(ImeHintPolicy.hintFor(R.id.ime_language_spinner)).isNull()
        assertThat(ImeHintPolicy.hintFor(R.id.ime_raw)).isNull()
        assertThat(ImeHintPolicy.hintFor(-1)).isNull()
    }
}
