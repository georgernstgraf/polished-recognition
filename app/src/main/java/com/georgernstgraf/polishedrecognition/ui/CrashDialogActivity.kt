package com.georgernstgraf.polishedrecognition.ui

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

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

        val textView = TextView(this).apply {
            text = message
            textSize = 12f
            isSingleLine = false
            setPadding(24, 24, 24, 24)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        AlertDialog.Builder(this)
            .setTitle("Fatal Error")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("Close App") { _, _ ->
                finishAffinity()
                kotlin.system.exitProcess(1)
            }
            .show()
    }

    companion object {
        const val EXTRA_EXCEPTION_TYPE = "exception_type"
        const val EXTRA_EXCEPTION_MESSAGE = "exception_message"
        const val EXTRA_STACK_TRACE = "stack_trace"
    }
}
