package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguageMapperTest {

    @Test
    fun `en maps to English`() {
        assertThat(LanguageMapper.mapCodeToName("en")).isEqualTo("English")
    }

    @Test
    fun `de maps to German`() {
        assertThat(LanguageMapper.mapCodeToName("de")).isEqualTo("German")
    }

    @Test
    fun `fr maps to French`() {
        assertThat(LanguageMapper.mapCodeToName("fr")).isEqualTo("French")
    }

    @Test
    fun `es maps to Spanish`() {
        assertThat(LanguageMapper.mapCodeToName("es")).isEqualTo("Spanish")
    }

    @Test
    fun `unknown code passes through`() {
        assertThat(LanguageMapper.mapCodeToName("xyz")).isEqualTo("xyz")
    }

    @Test
    fun `null returns unknown`() {
        assertThat(LanguageMapper.mapCodeToName(null)).isEqualTo("unknown")
    }

    @Test
    fun `uppercase input is normalized`() {
        assertThat(LanguageMapper.mapCodeToName("DE")).isEqualTo("German")
    }

    @Test
    fun `whitespace is trimmed`() {
        assertThat(LanguageMapper.mapCodeToName("  de  ")).isEqualTo("German")
    }

    @Test
    fun `supportedLanguages is sorted`() {
        val languages = LanguageMapper.supportedLanguages
        assertThat(languages).isNotEmpty()
        assertThat(languages).isEqualTo(languages.sorted())
    }
}
