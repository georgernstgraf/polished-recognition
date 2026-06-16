package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceRecognitionActivityTest {

    @Test
    fun `buildLanguageList includes None and English by default`() {
        val list = VoiceRecognitionActivity.buildLanguageList(emptyList())
        assertThat(list).containsExactly("None", "English").inOrder()
    }

    @Test
    fun `buildLanguageList appends custom languages sorted`() {
        val list = VoiceRecognitionActivity.buildLanguageList(listOf("Italian", "German", "French"))
        assertThat(list).containsExactly("None", "English", "French", "German", "Italian").inOrder()
    }

    @Test
    fun `buildLanguageList None always first`() {
        val list = VoiceRecognitionActivity.buildLanguageList(listOf("A", "B"))
        assertThat(list[0]).isEqualTo("None")
    }

    @Test
    fun `buildLanguageList English always second`() {
        val list = VoiceRecognitionActivity.buildLanguageList(listOf("A", "B"))
        assertThat(list[1]).isEqualTo("English")
    }

    @Test
    fun `buildLanguageList handles duplicates from custom languages`() {
        val list = VoiceRecognitionActivity.buildLanguageList(listOf("English", "German"))
        assertThat(list).containsAtLeast("None", "English", "German")
    }

    @Test
    fun `buildLanguageList empty custom returns exactly two entries`() {
        val list = VoiceRecognitionActivity.buildLanguageList(emptyList())
        assertThat(list).hasSize(2)
    }

    @Test
    fun `NONE_TARGET_LANGUAGE is None`() {
        assertThat(VoiceRecognitionActivity.NONE_TARGET_LANGUAGE).isEqualTo("None")
    }
}
