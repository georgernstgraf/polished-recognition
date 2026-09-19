package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.R

/**
 * Fixed long-press hints for the IME buttons (#88), shown as an overlay
 * Toast at the top of the screen while pressed. (Two dropped attempts:
 * framework tooltips pop up under the thumb; stage-line text shifts the
 * bar mid-tap and breaks single taps.) The send button is send-only —
 * recording auto-starts, so no microphone wording remains. Returns the
 * string resource for the hint, or null for views without one.
 */
object ImeHintPolicy {
    fun hintFor(viewId: Int): Int? = when (viewId) {
        R.id.ime_cancel_button -> R.string.ime_cancel_hint
        R.id.ime_flush_button -> R.string.ime_flush_hint
        R.id.ime_pause_resume_button -> R.string.ime_pause_resume_hint
        R.id.ime_send_button -> R.string.ime_send_hint
        R.id.ime_switch_keyboard_button -> R.string.ime_switch_hint
        R.id.ime_settings_button -> R.string.ime_settings_hint
        else -> null
    }
}
