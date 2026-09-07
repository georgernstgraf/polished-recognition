package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwitchTargetPolicyTest {

    private val self = "com.georgernstgraf.polishedrecognition/.service.PolishedVoiceInputIME"
    private val latin = "com.android.inputmethod.latin/.LatinIME"
    private val anySoft = "com.menny.android.anysoftkeyboard/.SoftKeyboard"
    private val googleVoice = "com.google.android.googlequicksearchbox/.VoiceInputMethodService"

    private fun ime(id: String, hasKeys: Boolean = true) =
        SwitchTargetPolicy.EnabledIme(id, hasKeys)

    @Test
    fun `history parses most recent first`() {
        val raw = "$self;100:$latin;-1:$anySoft;200"
        assertThat(SwitchTargetPolicy.parseHistory(raw))
            .containsExactly(self, latin, anySoft)
            .inOrder()
    }

    @Test
    fun `history parsing tolerates null and empty`() {
        assertThat(SwitchTargetPolicy.parseHistory(null)).isEmpty()
        assertThat(SwitchTargetPolicy.parseHistory("")).isEmpty()
    }

    @Test
    fun `history parsing skips subtype hashes and tolerates malformed entries`() {
        val raw = "$latin;-1:$self"
        assertThat(SwitchTargetPolicy.parseHistory(raw)).containsExactly(latin, self).inOrder()
    }

    @Test
    fun `targets most recent enabled keyboard that is not self`() {
        val history = listOf(self, latin, anySoft)
        val enabled = listOf(ime(self), ime(latin), ime(anySoft))
        assertThat(SwitchTargetPolicy.targetKeyboard(history, enabled, self)).isEqualTo(latin)
    }

    @Test
    fun `skips self at history head and picks next keyboard`() {
        val history = listOf(self, latin)
        val enabled = listOf(ime(self), ime(latin))
        assertThat(SwitchTargetPolicy.targetKeyboard(history, enabled, self)).isEqualTo(latin)
    }

    @Test
    fun `skips disabled keyboards`() {
        val history = listOf(latin, anySoft)
        val enabled = listOf(ime(self), ime(anySoft))
        assertThat(SwitchTargetPolicy.targetKeyboard(history, enabled, self)).isEqualTo(anySoft)
    }

    @Test
    fun `skips voice-only keyboards`() {
        val history = listOf(googleVoice, latin)
        val enabled = listOf(ime(self), ime(googleVoice, hasKeys = false), ime(latin))
        assertThat(SwitchTargetPolicy.targetKeyboard(history, enabled, self)).isEqualTo(latin)
    }

    @Test
    fun `returns null when only self is enabled`() {
        val history = listOf(self, latin)
        val enabled = listOf(ime(self))
        assertThat(SwitchTargetPolicy.targetKeyboard(history, enabled, self)).isNull()
    }

    @Test
    fun `returns null when history is empty`() {
        val enabled = listOf(ime(self), ime(latin))
        assertThat(SwitchTargetPolicy.targetKeyboard(emptyList(), enabled, self)).isNull()
    }

    @Test
    fun `returns null when no enabled keyboard is in the history`() {
        val history = listOf(self, latin)
        val enabled = listOf(ime(self), ime(anySoft))
        assertThat(SwitchTargetPolicy.targetKeyboard(history, enabled, self)).isNull()
    }
}
