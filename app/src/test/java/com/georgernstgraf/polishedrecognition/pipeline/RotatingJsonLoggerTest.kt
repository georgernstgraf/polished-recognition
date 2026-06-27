package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RotatingJsonLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newLogger(): Pair<File, RotatingJsonLogger> {
        val dir = tmp.newFolder("logs")
        return dir to RotatingJsonLogger(dir)
    }

    @Test
    fun `log writes base json with verbatim content`() {
        val (dir, logger) = newLogger()
        logger.log("llm-prompt", """{"model":"x"}""")
        assertThat(File(dir, "llm-prompt.json").readText()).isEqualTo("""{"model":"x"}""")
    }

    @Test
    fun `rotation shifts files per base name`() {
        val (dir, logger) = newLogger()
        repeat(3) { i -> logger.log("stt-response", "content $i") }

        assertThat(File(dir, "stt-response.json").readText()).isEqualTo("content 2")
        assertThat(File(dir, "stt-response_1.json").readText()).isEqualTo("content 1")
        assertThat(File(dir, "stt-response_2.json").readText()).isEqualTo("content 0")
        assertThat(File(dir, "stt-response_3.json").exists()).isFalse()
    }

    @Test
    fun `bases rotate independently`() {
        val (dir, logger) = newLogger()
        logger.log("llm-prompt", "p0")
        logger.log("stt-response", "s0")
        logger.log("llm-prompt", "p1")

        assertThat(File(dir, "llm-prompt.json").readText()).isEqualTo("p1")
        assertThat(File(dir, "llm-prompt_1.json").readText()).isEqualTo("p0")
        assertThat(File(dir, "stt-response.json").readText()).isEqualTo("s0")
        assertThat(File(dir, "stt-response_1.json").exists()).isFalse()
    }

    @Test
    fun `rotation caps at underscore 9`() {
        val (dir, logger) = newLogger()
        repeat(11) { i -> logger.log("llm-response", "content $i") }

        assertThat(File(dir, "llm-response.json").readText()).isEqualTo("content 10")
        assertThat(File(dir, "llm-response_9.json").readText()).isEqualTo("content 1")
        assertThat(File(dir, "llm-response_10.json").exists()).isFalse()
        // content 0 is the oldest dropped entry — must not survive anywhere
        dir.listFiles().orEmpty().forEach { assertThat(it.readText()).isNotEqualTo("content 0") }
    }

    @Test
    fun `legacy prompt json and md files are swept on construction`() {
        val dir = tmp.newFolder("legacy")
        File(dir, "prompt.md").writeText("old md")
        File(dir, "prompt_3.md").writeText("old md3")
        File(dir, "prompt.json").writeText("old json")
        File(dir, "prompt_5.json").writeText("old json5")

        RotatingJsonLogger(dir)

        assertThat(File(dir, "prompt.md").exists()).isFalse()
        assertThat(File(dir, "prompt_3.md").exists()).isFalse()
        assertThat(File(dir, "prompt.json").exists()).isFalse()
        assertThat(File(dir, "prompt_5.json").exists()).isFalse()
    }
}
