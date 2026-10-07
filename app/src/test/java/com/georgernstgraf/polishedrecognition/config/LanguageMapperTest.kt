package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanguageMapperTest {

    @Test
    fun `ISO code resolves to display name`() {
        assertThat(LanguageMapper.toDisplayName("de")).isEqualTo("German")
    }

    @Test
    fun `ISO code is case-insensitive`() {
        assertThat(LanguageMapper.toDisplayName("DE")).isEqualTo("German")
    }

    @Test
    fun `full name passes through normalized`() {
        assertThat(LanguageMapper.toDisplayName("German")).isEqualTo("German")
        assertThat(LanguageMapper.toDisplayName("german")).isEqualTo("German")
    }

    @Test
    fun `three-letter code resolves`() {
        assertThat(LanguageMapper.toDisplayName("yue")).isEqualTo("Cantonese")
    }

    @Test
    fun `multi-word name passes through`() {
        assertThat(LanguageMapper.toDisplayName("Haitian Creole")).isEqualTo("Haitian Creole")
    }

    @Test
    fun `unknown code falls back to capitalized words`() {
        assertThat(LanguageMapper.toDisplayName("xx")).isEqualTo("Xx")
    }

    @Test
    fun `null returns Unknown`() {
        assertThat(LanguageMapper.toDisplayName(null)).isEqualTo("Unknown")
    }
}
