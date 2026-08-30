package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import com.georgernstgraf.polishedrecognition.R

class SettingsHintActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_hint_title)
            .setMessage(R.string.settings_hint_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setCancelable(true)
            .show()
    }
}
