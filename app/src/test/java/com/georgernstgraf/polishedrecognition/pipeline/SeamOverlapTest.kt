package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #122: the seam-overlap repetition metric. */
class SeamOverlapTest {

    @Test
    fun `detects a repeated run straddling the seam`() {
        val previous = "Der Patient klagt über Schmerzen und Fieber"
        val next = "und Fieber am Abend stärker"
        assertThat(SeamOverlap.longestTokenOverlap(previous, next)).isEqualTo(2)
        assertThat(SeamOverlap.overlapText(previous, next)).isEqualTo("und fieber")
    }

    @Test
    fun `case and punctuation are ignored`() {
        assertThat(SeamOverlap.longestTokenOverlap("Hallo WELT!", "welt, wie gehts"))
            .isEqualTo(1)
    }

    @Test
    fun `no shared suffix-prefix run yields zero`() {
        assertThat(SeamOverlap.longestTokenOverlap("eins zwei drei", "vier fünf")).isEqualTo(0)
        assertThat(SeamOverlap.overlapText("eins zwei", "drei vier")).isNull()
    }

    @Test
    fun `whole-fragment repetition is the full overlap`() {
        assertThat(SeamOverlap.longestTokenOverlap("guten Tag", "guten Tag und willkommen"))
            .isEqualTo(2)
    }

    @Test
    fun `blank and null inputs are safe`() {
        assertThat(SeamOverlap.longestTokenOverlap(null, "x")).isEqualTo(0)
        assertThat(SeamOverlap.longestTokenOverlap("x", "  ")).isEqualTo(0)
        assertThat(SeamOverlap.wordCount(null)).isEqualTo(0)
        assertThat(SeamOverlap.wordCount("zwei Wörter hier")).isEqualTo(3)
    }
}
