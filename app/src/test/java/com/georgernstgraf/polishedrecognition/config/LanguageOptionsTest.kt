package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguageOptionsTest {

    @Test
    fun `buildLanguageList includes Polish only and English by default`() {
        val list = LanguageOptions.buildLanguageList(emptyList())
        assertThat(list).containsExactly("Polish only", "English").inOrder()
    }

    @Test
    fun `buildLanguageList appends custom languages sorted`() {
        val list = LanguageOptions.buildLanguageList(listOf("Italian", "German", "French"))
        assertThat(list).containsExactly("Polish only", "English", "French", "German", "Italian").inOrder()
    }

    @Test
    fun `buildLanguageList Polish only always first`() {
        val list = LanguageOptions.buildLanguageList(listOf("A", "B"))
        assertThat(list[0]).isEqualTo("Polish only")
    }

    @Test
    fun `buildLanguageList English always second`() {
        val list = LanguageOptions.buildLanguageList(listOf("A", "B"))
        assertThat(list[1]).isEqualTo("English")
    }

    @Test
    fun `buildLanguageList handles duplicates from custom languages`() {
        val list = LanguageOptions.buildLanguageList(listOf("English", "German"))
        assertThat(list).containsAtLeast("Polish only", "English", "German")
    }

    @Test
    fun `buildLanguageList empty custom returns exactly two entries`() {
        val list = LanguageOptions.buildLanguageList(emptyList())
        assertThat(list).hasSize(2)
    }

    @Test
    fun `NONE_TARGET_LANGUAGE is Polish only`() {
        assertThat(LanguageOptions.NONE_TARGET_LANGUAGE).isEqualTo("Polish only")
    }
}
