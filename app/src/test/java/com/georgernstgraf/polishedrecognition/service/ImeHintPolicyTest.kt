package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImeHintPolicyTest {

    @Test
    fun `lower-row buttons map to their fixed hints`() {
        assertThat(ImeHintPolicy.hintFor(R.id.ime_cancel_button))
            .isEqualTo(R.string.ime_cancel_desc)
        assertThat(ImeHintPolicy.hintFor(R.id.ime_flush_button))
            .isEqualTo(R.string.ime_flush_desc)
        assertThat(ImeHintPolicy.hintFor(R.id.ime_pause_resume_button))
            .isEqualTo(R.string.ime_pause_resume_hint)
        assertThat(ImeHintPolicy.hintFor(R.id.ime_mic_send_button))
            .isEqualTo(R.string.ime_mic_send_hint)
    }

    @Test
    fun `top-row buttons and unknown views have no hint`() {
        assertThat(ImeHintPolicy.hintFor(R.id.ime_switch_keyboard_button)).isNull()
        assertThat(ImeHintPolicy.hintFor(R.id.ime_settings_button)).isNull()
        assertThat(ImeHintPolicy.hintFor(R.id.ime_language_spinner)).isNull()
        assertThat(ImeHintPolicy.hintFor(-1)).isNull()
    }
}
