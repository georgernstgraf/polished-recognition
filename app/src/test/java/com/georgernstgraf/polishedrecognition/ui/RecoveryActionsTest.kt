package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #185: the enablement rule of a recovery row — one place, no rule in the
 * Activity or the XML.
 */
class RecoveryActionsTest {

    private fun actions(
        completed: Boolean = false,
        hasAudio: Boolean = true,
        busy: Boolean = false,
        providerConfigured: Boolean = true
    ) = RecoveryActions.enabled(completed, hasAudio, busy, providerConfigured)

    @Test
    fun `an unfinished session with audio may be re-run, shared and dismissed`() {
        val a = actions()

        assertThat(a.reRun).isTrue()
        assertThat(a.restore).isFalse()
        assertThat(a.share).isTrue()
        assertThat(a.dismiss).isTrue()
    }

    @Test
    fun `re-run needs a configured provider`() {
        assertThat(actions(providerConfigured = false).reRun).isFalse()
        // sharing and dismissing never need a provider
        assertThat(actions(providerConfigured = false).share).isTrue()
        assertThat(actions(providerConfigured = false).dismiss).isTrue()
    }

    @Test
    fun `re-run needs audio`() {
        val a = actions(hasAudio = false)

        assertThat(a.reRun).isFalse()
        assertThat(a.share).isFalse()
        assertThat(a.dismiss).isTrue()
    }

    @Test
    fun `a completed session restores its text instead of re-running`() {
        val a = actions(completed = true)

        assertThat(a.reRun).isFalse()
        assertThat(a.restore).isTrue()
        assertThat(a.share).isTrue()
    }

    @Test
    fun `a running recovery blocks every row`() {
        val a = actions(completed = true, busy = true)

        assertThat(a.reRun).isFalse()
        assertThat(a.restore).isFalse()
        assertThat(a.share).isFalse()
        assertThat(a.dismiss).isFalse()
    }
}
