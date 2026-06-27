package com.georgernstgraf.polishedrecognition.pipeline

import java.io.File

class PromptLogger(private val logDir: File) {

    private val maxCount = 9

    init {
        logDir.mkdirs()
        // one-time cleanup of the legacy .md log format (pre-#40)
        File(logDir, "prompt.md").delete()
        for (i in 1..5) File(logDir, "prompt_$i.md").delete()
    }

    fun log(content: String) {
        try {
            File(logDir, "prompt_$maxCount.json").delete()
            for (i in maxCount - 1 downTo 1) {
                File(logDir, "prompt_$i.json")
                    .renameTo(File(logDir, "prompt_${i + 1}.json"))
            }
            File(logDir, "prompt.json").renameTo(File(logDir, "prompt_1.json"))
            File(logDir, "prompt.json").writeText(content)
        } catch (_: Exception) {
            // never break transcription for logging failures
        }
    }
}
