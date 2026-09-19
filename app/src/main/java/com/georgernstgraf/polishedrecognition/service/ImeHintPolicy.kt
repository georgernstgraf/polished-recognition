package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.R

/**
 * Press-hold hints for the lower-row IME buttons (#88). The framework
 * tooltip proved unreadable (it pops up directly under the thumb), so the
 * IME shows these fixed hints in the stage-text area above the buttons
 * while the finger is down instead. Returns the string resource for the
 * hint, or null for views without one (top row).
 */
object ImeHintPolicy {
    fun hintFor(viewId: Int): Int? = when (viewId) {
        R.id.ime_cancel_button -> R.string.ime_cancel_hint
        R.id.ime_flush_button -> R.string.ime_flush_hint
        R.id.ime_pause_resume_button -> R.string.ime_pause_resume_hint
        R.id.ime_mic_send_button -> R.string.ime_mic_send_hint
        else -> null
    }
}
