package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #185: the extracted echo-trim rule — the same behaviour
 * `FragmentTranscriberTest` pins through the live worker, now testable as the
 * pure function the recovery re-run calls.
 */
class EchoTrimTest {

    private val tail = "Der Patient klagt über Schmerzen"

    private fun trim(text: String, knownTail: String? = tail, hadPreRoll: Boolean = true) =
        EchoTrim.trim(text = text, knownTail = knownTail, hadPreRoll = hadPreRoll)

    @Test
    fun `the echoed tail is dropped and only the fresh words remain`() {
        val result = trim("Der Patient klagt über Schmerzen und Fieber")

        assertThat(result.echoTokens).isEqualTo(5)
        assertThat(result.text).isEqualTo("und Fieber")
    }

    @Test
    fun `a single matching tail token is enough`() {
        val result = trim("Schmerzen und Fieber")

        assertThat(result.echoTokens).isEqualTo(1)
        assertThat(result.text).isEqualTo("und Fieber")
    }

    @Test
    fun `no match keeps everything - duplication beats loss`() {
        val result = trim("Völlig neuer Gedanke beginnt hier")

        assertThat(result.echoTokens).isEqualTo(0)
        assertThat(result.text).isEqualTo("Völlig neuer Gedanke beginnt hier")
    }

    @Test
    fun `without a pre-roll nothing is trimmed, however identical it looks`() {
        val result = trim("Der Patient klagt über Schmerzen und Fieber", hadPreRoll = false)

        assertThat(result.echoTokens).isEqualTo(0)
        assertThat(result.text).isEqualTo("Der Patient klagt über Schmerzen und Fieber")
    }

    @Test
    fun `a blank known tail trims nothing`() {
        assertThat(trim("irgendwas Neues", knownTail = "  ").echoTokens).isEqualTo(0)
        assertThat(trim("irgendwas Neues", knownTail = null).text).isEqualTo("irgendwas Neues")
    }

    @Test
    fun `a fully echoed fragment trims to empty`() {
        val result = trim(tail)

        assertThat(result.echoTokens).isEqualTo(5)
        assertThat(result.text).isEmpty()
    }

    @Test
    fun `the match window is capped at eight tokens`() {
        val longTail = (1..12).joinToString(" ") { "wort$it" }
        val echoedEight = (5..12).joinToString(" ") { "wort$it" }
        val echoedNine = (4..12).joinToString(" ") { "wort$it" }

        val eight = EchoTrim.trim("$echoedEight frisch", longTail, hadPreRoll = true)
        val nine = EchoTrim.trim("$echoedNine frisch", longTail, hadPreRoll = true)

        assertThat(eight.echoTokens).isEqualTo(EchoTrim.MAX_ECHO_TOKENS)
        assertThat(eight.text).isEqualTo("frisch")
        // a NINE-token echo is outside the window and is deliberately kept
        assertThat(nine.echoTokens).isEqualTo(0)
        assertThat(nine.text).isEqualTo("$echoedNine frisch")
    }

    @Test
    fun `punctuation and whitespace right after the echo are dropped too`() {
        val result = trim("Der Patient klagt über Schmerzen, und Fieber")

        assertThat(result.echoTokens).isEqualTo(5)
        assertThat(result.text).isEqualTo("und Fieber")
    }

    @Test
    fun `one mismatch inside the window is tolerated for k of at least two`() {
        val result = trim("Der Patient nennt über Schmerzen und Fieber")

        assertThat(result.echoTokens).isGreaterThan(1)
        assertThat(result.text).isEqualTo("und Fieber")
    }

    @Test
    fun `a mismatching head token never matches`() {
        val result = trim("Anders Patient klagt über Schmerzen und Fieber")

        // head[0] differs, so no k may match — even though the rest would
        assertThat(result.echoTokens).isEqualTo(0)
    }
}
