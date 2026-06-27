package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PromptLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newLogger(): Pair<File, PromptLogger> {
        val dir = tmp.newFolder("logs")
        return dir to PromptLogger(dir)
    }

    @Test
    fun `log writes prompt json with verbatim content`() {
        val (dir, logger) = newLogger()
        logger.log("""{"model":"x"}""")
        assertThat(File(dir, "prompt.json").readText()).isEqualTo("""{"model":"x"}""")
    }

    @Test
    fun `rotation shifts files and uses json extension`() {
        val (dir, logger) = newLogger()
        repeat(3) { i -> logger.log("content $i") }

        assertThat(File(dir, "prompt.json").exists()).isTrue()
        assertThat(File(dir, "prompt_1.json").exists()).isTrue()
        assertThat(File(dir, "prompt_2.json").exists()).isTrue()
        assertThat(File(dir, "prompt_3.json").exists()).isFalse()

        assertThat(File(dir, "prompt.json").readText()).isEqualTo("content 2")
        assertThat(File(dir, "prompt_1.json").readText()).isEqualTo("content 1")
        assertThat(File(dir, "prompt_2.json").readText()).isEqualTo("content 0")
    }

    @Test
    fun `rotation caps at prompt_9 json`() {
        val (dir, logger) = newLogger()
        repeat(11) { i -> logger.log("content $i") }

        assertThat(File(dir, "prompt.json").readText()).isEqualTo("content 10")
        assertThat(File(dir, "prompt_9.json").readText()).isEqualTo("content 1")
        assertThat(File(dir, "prompt_10.json").exists()).isFalse()
        // content 0 is the oldest dropped entry — must not survive anywhere
        dir.listFiles().orEmpty().forEach { assertThat(it.readText()).isNotEqualTo("content 0") }
    }

    @Test
    fun `legacy md log files are swept on construction`() {
        val dir = tmp.newFolder("legacy")
        File(dir, "prompt.md").writeText("old")
        File(dir, "prompt_3.md").writeText("old3")

        PromptLogger(dir)

        assertThat(File(dir, "prompt.md").exists()).isFalse()
        assertThat(File(dir, "prompt_3.md").exists()).isFalse()
    }
}
