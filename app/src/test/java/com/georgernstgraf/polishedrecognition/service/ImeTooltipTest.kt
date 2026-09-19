package com.georgernstgraf.polishedrecognition.service

import android.view.LayoutInflater
import android.widget.ImageButton
import com.georgernstgraf.polishedrecognition.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Long-press help (#88): the four lower-row IME buttons carry a fixed
 * tooltip (shown by the framework while pressed); the top-row buttons
 * (switch-keyboard, settings) deliberately have none.
 */
@RunWith(RobolectricTestRunner::class)
class ImeTooltipTest {

    private val root by lazy {
        LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.ime_voice_input, null)
    }

    private fun tooltip(id: Int): CharSequence? =
        root.findViewById<ImageButton>(id).tooltipText

    @Test
    fun `lower-row buttons show fixed combined hints`() {
        assertThat(tooltip(R.id.ime_cancel_button)?.toString()).isEqualTo("Cancel")
        assertThat(tooltip(R.id.ime_flush_button)?.toString()).isEqualTo("Discard recording")
        assertThat(tooltip(R.id.ime_pause_resume_button)?.toString()).isEqualTo("Pause / Resume")
        assertThat(tooltip(R.id.ime_mic_send_button)?.toString()).isEqualTo("Record / Send")
    }

    @Test
    fun `top-row buttons have no tooltip`() {
        assertThat(tooltip(R.id.ime_switch_keyboard_button)).isNull()
        assertThat(tooltip(R.id.ime_settings_button)).isNull()
    }
}
