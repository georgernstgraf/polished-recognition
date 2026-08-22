package com.georgernstgraf.polishedrecognition.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.georgernstgraf.polishedrecognition.R

class CrashDialogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exceptionType = intent.getStringExtra(EXTRA_EXCEPTION_TYPE) ?: "Unknown"
        val exceptionMessage = intent.getStringExtra(EXTRA_EXCEPTION_MESSAGE) ?: "No message"
        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No stack trace"

        val message = buildString {
            appendLine("Type: $exceptionType")
            appendLine()
            appendLine("Message: $exceptionMessage")
            appendLine()
            appendLine("Stack Trace:")
            appendLine(stackTrace)
        }

        val view = layoutInflater.inflate(R.layout.dialog_crash, null)
        view.findViewById<TextView>(R.id.crash_text).text = message

        view.findViewById<Button>(R.id.crash_copy_button).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Polished Recognition crash", message))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.crash_close_button).setOnClickListener {
            finishAffinity()
            kotlin.system.exitProcess(1)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setView(view)
            .setCancelable(false)
            .show()
    }

    companion object {
        const val EXTRA_EXCEPTION_TYPE = "exception_type"
        const val EXTRA_EXCEPTION_MESSAGE = "exception_message"
        const val EXTRA_STACK_TRACE = "stack_trace"
    }
}
