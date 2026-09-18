package com.georgernstgraf.polishedrecognition.service

import android.content.Context
import android.os.Process
import java.io.File

/**
 * Append-only IME lifecycle trace for rotation debugging on ROMs that
 * suppress app-level IME logcat (Oplus). Lives next to the rotating API
 * logs (`getExternalFilesDir/logs/ime-lifecycle.log`, readable via adb
 * without root), capped at [MAX_LINES] so it cannot grow unbounded.
 * Every line carries the process pid — a pid change across rotation proves
 * process death, identical pids prove survival.
 *
 * Best-effort by design: logging must never break the IME.
 */
object ImeLifecycleTrace {

    private const val MAX_LINES = 200

    fun log(app: Context, event: String, detail: String = "") {
        try {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
            dir.mkdirs()
            val file = File(dir, "ime-lifecycle.log")
            val lines = if (file.exists()) file.readLines().takeLast(MAX_LINES - 1) else emptyList()
            val line = "${System.currentTimeMillis()} pid=${Process.myPid()} $event" +
                (if (detail.isNotEmpty()) " $detail" else "")
            file.writeText((lines + line).joinToString("\n"))
        } catch (_: Throwable) {
        }
    }
}
