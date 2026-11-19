package com.georgernstgraf.polishedrecognition.delivery

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * #170 (epic #166 D11/D12): the Workbench is a **text supplier** — the text on
 * screen is put on the clipboard, unconditionally and silently.
 *
 * Because the delivery is automatic, the copy is not the deliberate "copy the
 * transcript" gesture of the IME salvage path: a client dictation must not
 * land in the system clipboard history or the cloud clipboard. From API 33 the
 * clip is therefore marked `EXTRA_IS_SENSITIVE`; below that (minSdk 30) the
 * platform offers no protection — accepted in #166 D12.
 *
 * An empty text is a no-op: a delivery must never overwrite a real clipboard
 * with nothing.
 */
object ClipboardDelivery {

    /** Returns true when the clipboard was actually written. */
    fun deliver(context: Context, text: String, label: String): Boolean {
        if (text.isEmpty()) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return false
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }
        clipboard.setPrimaryClip(clip)
        return true
    }
}
