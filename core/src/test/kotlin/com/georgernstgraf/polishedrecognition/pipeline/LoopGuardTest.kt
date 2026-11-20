package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #123 production loop guard: [LoopGuard.collapse] on top of the [LoopMask]
 * detector. The guard only runs for fragments behind a FORCED cut (see
 * [LoopGuard] KDoc); the seam gate itself is covered in
 * [FragmentTranscriberTest].
 */
class LoopGuardTest {

    @Test
    fun `text without a repetition passes through unchanged`() {
        val text = "Wir haben das gestern schon besprochen und verworfen."
        assertThat(LoopGuard.collapse(text)).isEqualTo(text)
    }

    @Test
    fun `three repetitions stay below the threshold`() {
        val text = "Das war sehr sehr sehr gut."
        assertThat(LoopGuard.collapse(text)).isEqualTo(text)
    }

    @Test
    fun `a tail loop collapses to one occurrence`() {
        val text = "Der Text ist fertig " + List(40) { "me" }.joinToString(" ")
        assertThat(LoopGuard.collapse(text)).isEqualTo("Der Text ist fertig me")
    }

    @Test
    fun `a multi-word loop keeps the text that follows it`() {
        val phrase = "das ist ein test"
        val text = "$phrase $phrase $phrase $phrase und dann noch was"
        assertThat(LoopGuard.collapse(text)).isEqualTo("$phrase und dann noch was")
    }

    @Test
    fun `a fragment that is one big loop keeps a single occurrence`() {
        assertThat(LoopGuard.collapse("me me me me me")).isEqualTo("me")
    }

    @Test
    fun `character offsets survive umlauts and punctuation`() {
        val text = "Größe prüfen prüfen prüfen prüfen, dann weiter."
        assertThat(LoopGuard.collapse(text)).isEqualTo("Größe prüfen, dann weiter.")
    }

    @Test
    fun `casing of the kept period is preserved`() {
        val text = "Ja ja Ja ja ja ja, weiter geht es."
        assertThat(LoopGuard.collapse(text)).isEqualTo("Ja, weiter geht es.")
    }

    @Test
    fun `blank text is returned untouched`() {
        assertThat(LoopGuard.collapse("")).isEmpty()
        assertThat(LoopGuard.collapse("   ")).isEqualTo("   ")
    }
}
