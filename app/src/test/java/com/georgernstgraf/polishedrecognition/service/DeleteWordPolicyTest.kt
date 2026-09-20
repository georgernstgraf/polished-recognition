package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeleteWordPolicyTest {

    @Test
    fun `single word deletes whole token`() {
        assertThat(DeleteWordPolicy.charsToDelete("hello")).isEqualTo(5)
    }

    @Test
    fun `last word of a sentence deletes only that word`() {
        assertThat(DeleteWordPolicy.charsToDelete("hello world")).isEqualTo(5)
    }

    @Test
    fun `trailing spaces go with the preceding word`() {
        assertThat(DeleteWordPolicy.charsToDelete("hello world   ")).isEqualTo(8)
    }

    @Test
    fun `punctuation stays glued to its token`() {
        assertThat(DeleteWordPolicy.charsToDelete("hello, world!")).isEqualTo(6)
        assertThat(DeleteWordPolicy.charsToDelete("well (this)")).isEqualTo(6)
    }

    @Test
    fun `whitespace-only deletes everything`() {
        assertThat(DeleteWordPolicy.charsToDelete("   ")).isEqualTo(3)
    }

    @Test
    fun `empty input deletes nothing`() {
        assertThat(DeleteWordPolicy.charsToDelete("")).isEqualTo(0)
    }

    @Test
    fun `newline and nbsp count as separators`() {
        assertThat(DeleteWordPolicy.charsToDelete("hello\nworld")).isEqualTo(5)
        assertThat(DeleteWordPolicy.charsToDelete("hello world")).isEqualTo(5)
    }

    @Test
    fun `unicode words delete whole`() {
        assertThat(DeleteWordPolicy.charsToDelete("Grüße，走")).isEqualTo(7)
    }
}
