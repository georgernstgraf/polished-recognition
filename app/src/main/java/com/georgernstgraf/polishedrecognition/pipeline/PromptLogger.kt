package com.georgernstgraf.polishedrecognition.pipeline

import java.io.File

class PromptLogger(private val logDir: File) {

    private val maxCount = 5

    init {
        logDir.mkdirs()
    }

    fun log(systemPrompt: String, userPrompt: String) {
        try {
            File(logDir, "prompt_$maxCount.md").delete()
            for (i in maxCount - 1 downTo 1) {
                File(logDir, "prompt_$i.md")
                    .renameTo(File(logDir, "prompt_${i + 1}.md"))
            }
            File(logDir, "prompt.md").renameTo(File(logDir, "prompt_1.md"))
            File(logDir, "prompt.md").writeText(
                "# system\n\n$systemPrompt\n\n# user\n\n$userPrompt\n"
            )
        } catch (_: Exception) {
            // never break transcription for logging failures
        }
    }
}
