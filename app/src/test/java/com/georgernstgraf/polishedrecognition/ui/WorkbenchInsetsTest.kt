package com.georgernstgraf.polishedrecognition.ui

import androidx.core.graphics.Insets
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #169: the edge-to-edge padding rule — max per side, never a sum. */
class WorkbenchInsetsTest {

    private val base = WorkbenchInsets.Paddings(16, 16, 16, 16)

    @Test
    fun `system bars are added to the base padding`() {
        val result = WorkbenchInsets.paddings(
            base,
            systemBars = Insets.of(0, 63, 0, 48),
            displayCutout = Insets.NONE,
            ime = Insets.NONE,
        )

        assertThat(result).isEqualTo(WorkbenchInsets.Paddings(16, 79, 16, 64))
    }

    @Test
    fun `the ime inset clears the keyboard when it is taller than the navigation bar`() {
        val result = WorkbenchInsets.paddings(
            base,
            systemBars = Insets.of(0, 0, 0, 48),
            displayCutout = Insets.NONE,
            ime = Insets.of(0, 0, 0, 900),
        )

        assertThat(result.bottom).isEqualTo(916)
    }

    @Test
    fun `bar and cutout on the same edge are merged with max, not summed`() {
        val result = WorkbenchInsets.paddings(
            base,
            systemBars = Insets.of(0, 0, 0, 0),
            displayCutout = Insets.of(88, 0, 0, 0),
            ime = Insets.NONE,
        )

        assertThat(result.left).isEqualTo(16 + 88)
    }

    @Test
    fun `repeated dispatch from the base never grows the padding`() {
        val first = WorkbenchInsets.paddings(
            base,
            systemBars = Insets.of(0, 63, 0, 48),
            displayCutout = Insets.NONE,
            ime = Insets.of(0, 0, 0, 900),
        )
        val second = WorkbenchInsets.paddings(
            base,
            systemBars = Insets.of(0, 63, 0, 48),
            displayCutout = Insets.NONE,
            ime = Insets.of(0, 0, 0, 900),
        )

        assertThat(second).isEqualTo(first)
    }
}
