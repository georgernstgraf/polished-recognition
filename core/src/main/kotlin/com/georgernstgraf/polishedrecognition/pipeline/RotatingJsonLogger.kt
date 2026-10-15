package com.georgernstgraf.polishedrecognition.pipeline

import java.io.File

class RotatingJsonLogger(private val logDir: File, private val maxCount: Int = 9) {

    init {
        logDir.mkdirs()
        // one-time cleanup of legacy log file names
        File(logDir, "prompt.md").delete()
        for (i in 1..5) File(logDir, "prompt_$i.md").delete()
        File(logDir, "prompt.json").delete()
        for (i in 1..maxCount) File(logDir, "prompt_$i.json").delete()
    }

    fun log(baseName: String, content: String) {
        try {
            File(logDir, "${baseName}_$maxCount.json").delete()
            for (i in maxCount - 1 downTo 1) {
                File(logDir, "${baseName}_$i.json")
                    .renameTo(File(logDir, "${baseName}_${i + 1}.json"))
            }
            File(logDir, "$baseName.json").renameTo(File(logDir, "${baseName}_1.json"))
            File(logDir, "$baseName.json").writeText(content)
        } catch (_: Exception) {
            // never break transcription for logging failures
        }
    }
}
