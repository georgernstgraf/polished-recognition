package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LineWrapPolicyTest {

    @Test
    fun `zero width returns text unchanged`() {
        val text = "a ".repeat(100).trim()
        assertThat(LineWrapPolicy.wrap(text, 0)).isEqualTo(text)
    }

    @Test
    fun `negative width returns text unchanged`() {
        val text = "a ".repeat(100).trim()
        assertThat(LineWrapPolicy.wrap(text, -1)).isEqualTo(text)
    }

    @Test
    fun `short text stays on one line`() {
        assertThat(LineWrapPolicy.wrap("hello world", 80)).isEqualTo("hello world")
    }

    @Test
    fun `empty text returns empty`() {
        assertThat(LineWrapPolicy.wrap("", 80)).isEmpty()
    }

    @Test
    fun `long text wraps so no line exceeds width`() {
        val text = ("word ".repeat(60)).trim()
        val wrapped = LineWrapPolicy.wrap(text, 80)
        assertThat(wrapped).contains("\n")
        wrapped.split("\n").forEach { line ->
            assertThat(line.length).isAtMost(80)
        }
        // Words are preserved, joined with single spaces.
        assertThat(wrapped.replace("\n", " ")).isEqualTo(text)
    }

    @Test
    fun `wrap at 90 and 200 honors the width`() {
        val text = ("word ".repeat(120)).trim()
        listOf(90, 200).forEach { width ->
            LineWrapPolicy.wrap(text, width).split("\n").forEach { line ->
                assertThat(line.length).isAtMost(width)
            }
        }
        // Narrower width produces at least as many lines as a wider one.
        val lines90 = LineWrapPolicy.wrap(text, 90).split("\n").size
        val lines200 = LineWrapPolicy.wrap(text, 200).split("\n").size
        assertThat(lines90).isAtLeast(lines200)
    }

    @Test
    fun `existing paragraphs are wrapped independently`() {
        val wrapped = LineWrapPolicy.wrap("aaa bbb ccc\nddd eee fff", 7)
        assertThat(wrapped).isEqualTo("aaa bbb\nccc\nddd eee\nfff")
    }

    @Test
    fun `single word longer than width is kept intact`() {
        val longWord = "a".repeat(100)
        assertThat(LineWrapPolicy.wrap("hi $longWord bye", 80)).contains(longWord)
    }
}
