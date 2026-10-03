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

    @Test
    fun `hyphenated word breaks after the hyphen`() {
        assertThat(LineWrapPolicy.wrap("Datenschutz-Grundverordnung", 20))
            .isEqualTo("Datenschutz-\nGrundverordnung")
    }

    @Test
    fun `hyphenated word breaks after a preceding word`() {
        assertThat(LineWrapPolicy.wrap("Die Datenschutz-Grundverordnung", 20))
            .isEqualTo("Die Datenschutz-\nGrundverordnung")
    }

    @Test
    fun `en dash and em dash are break points`() {
        assertThat(LineWrapPolicy.wrap("alpha\u2013beta\u2014gamma", 6))
            .isEqualTo("alpha\u2013\nbeta\u2014\ngamma")
    }

    @Test
    fun `url like token is never split at hyphens`() {
        val url = "https://example.com/langer-pfad-name"
        assertThat(LineWrapPolicy.wrap(url, 12)).isEqualTo(url)
        assertThat(LineWrapPolicy.wrap("www.example-site.com", 8)).isEqualTo("www.example-site.com")
    }

    @Test
    fun `non-breaking hyphen is not a break point`() {
        val word = "aaaa\u2011bbbb\u2011cccc"
        assertThat(LineWrapPolicy.wrap(word, 8)).isEqualTo(word)
    }

    @Test
    fun `short leading fragment is merged forward`() {
        val wrapped = LineWrapPolicy.wrap("a-really-long-word", 12)
        assertThat(wrapped).isEqualTo("a-really-\nlong-word")
        wrapped.split("\n").forEach { line -> assertThat(line).isNotEqualTo("a-") }
    }

    @Test
    fun `short trailing fragment is merged backward`() {
        val wrapped = LineWrapPolicy.wrap("long-word-a", 8)
        assertThat(wrapped).isEqualTo("long-\nword-a")
        wrapped.split("\n").forEach { line -> assertThat(line.length).isAtLeast(4) }
    }

    @Test
    fun `dash-free words wrap exactly as before`() {
        val text = ("word ".repeat(60)).trim()
        val wrapped = LineWrapPolicy.wrap(text, 80)
        assertThat(wrapped.replace("\n", " ")).isEqualTo(text)
        wrapped.split("\n").forEach { line -> assertThat(line.length).isAtMost(80) }
    }

    @Test
    fun `zero width leaves hyphenated text unchanged`() {
        val text = "Datenschutz-Grundverordnung und foo-bar"
        assertThat(LineWrapPolicy.wrap(text, 0)).isEqualTo(text)
    }
}
