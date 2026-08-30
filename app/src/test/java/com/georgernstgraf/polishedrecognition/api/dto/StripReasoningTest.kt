 
package com.georgernstgraf.polishedrecognition.api.dto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StripReasoningTest {

    @Test
    fun `think blocks are removed and surrounding text kept`() {
        val content = "<think>reasoning</think>part1<think>more</think>part2"
        assertThat(stripReasoning(content)).isEqualTo("part1part2")
    }

    @Test
    fun `content without tags is unchanged`() {
        assertThat(stripReasoning("hello world")).isEqualTo("hello world")
    }

    @Test
    fun `interleaved think blocks keep surrounding parts`() {
        val content = "A<think>x</think>B<think>y</think>C<think>z</think>D"
        assertThat(stripReasoning(content)).isEqualTo("ABCD")
    }

    @Test
    fun `reasoning tags are removed`() {
        val content = "<reasoning>why</reasoning>answer"
        assertThat(stripReasoning(content)).isEqualTo("answer")
    }

    @Test
    fun `unclosed think tag strips to end of string`() {
        assertThat(stripReasoning("answer <think>hidden")).isEqualTo("answer")
        assertThat(stripReasoning("<think>hidden")).isEmpty()
    }

    @Test
    fun `tags are matched case-insensitively`() {
        assertThat(stripReasoning("<THINK>r</THINK>ok")).isEqualTo("ok")
        assertThat(stripReasoning("<REASONING>r</REASONING>ok")).isEqualTo("ok")
    }

    @Test
    fun `orphaned closing tag is dropped`() {
        assertThat(stripReasoning("ok </think>")).isEqualTo("ok")
    }

    @Test
    fun `empty content returns empty string`() {
        assertThat(stripReasoning("")).isEmpty()
    }

    @Test
    fun `only reasoning returns empty string`() {
        assertThat(stripReasoning("<think>all reasoning</think>")).isEmpty()
    }

    @Test
    fun `blank lines left by removed blocks are collapsed`() {
        val content = "part1\n<think>r</think>\n\n\npart2"
        assertThat(stripReasoning(content)).isEqualTo("part1\n\npart2")
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertThat(stripReasoning("\n\nanswer\n\n")).isEqualTo("answer")
    }
}
