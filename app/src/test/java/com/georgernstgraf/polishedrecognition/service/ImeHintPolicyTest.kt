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
        // #155: the X is the keyboard switch, so it carries the keyboard hint.
        assertThat(ImeHintPolicy.hintFor(R.id.ime_switch_button))
            .isEqualTo(R.string.ime_switch_hint)
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
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_switch_button)!!))
            .isEqualTo("Keyboard — back to your text keyboard")
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_flush_button)!!))
            .isEqualTo("Discard — clear audio and timer, stay here")
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_pause_resume_button)!!))
            .isEqualTo("Pause / resume — recorded audio is kept")
        assertThat(ctx.getString(ImeHintPolicy.hintFor(R.id.ime_send_button)!!))
            .isEqualTo("Send — transcribe, polish and insert the text")
    }

    @Test
    fun `top-row buttons map to their hints`() {
        // The settings gear has no press-hold hint: its long-press opens the
        // Help screen instead (#107).
        assertThat(ImeHintPolicy.hintFor(R.id.ime_settings_button)).isNull()
    }

    @Test
    fun `views without help have no hint`() {
        assertThat(ImeHintPolicy.hintFor(R.id.ime_language_spinner)).isNull()
        assertThat(ImeHintPolicy.hintFor(R.id.ime_llm)).isNull()
        // Delete-word button (#90): long-press clears the field, so it must
        // never gain a press-hold hint.
        assertThat(ImeHintPolicy.hintFor(R.id.ime_delete_word_button)).isNull()
        assertThat(ImeHintPolicy.hintFor(-1)).isNull()
    }
}
