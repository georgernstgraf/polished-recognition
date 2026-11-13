package com.georgernstgraf.polishedrecognition.service

import androidx.core.graphics.Insets
import com.georgernstgraf.polishedrecognition.service.ImeWindowInsets.Paddings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #157: pure padding arithmetic. The IME root keeps its XML paddings and adds
 * the navigation-bar / cutout insets on top — every side, `max` where the two
 * sources overlap.
 */
class ImeWindowInsetsTest {

    private val base = Paddings(left = 12, top = 8, right = 12, bottom = 8)
    private val none = Insets.NONE

    @Test
    fun `no insets keeps the declared paddings`() {
        assertThat(ImeWindowInsets.paddings(base, none, none)).isEqualTo(base)
    }

    @Test
    fun `three button navigation bar lifts the bottom row`() {
        val bars = Insets.of(0, 0, 0, 126)
        assertThat(ImeWindowInsets.paddings(base, bars, none))
            .isEqualTo(Paddings(left = 12, top = 8, right = 12, bottom = 8 + 126))
    }

    @Test
    fun `gesture handle lifts the bottom row by its smaller inset`() {
        val bars = Insets.of(0, 0, 0, 63)
        assertThat(ImeWindowInsets.paddings(base, bars, none).bottom).isEqualTo(8 + 63)
    }

    @Test
    fun `landscape cutout and side navigation bar pad left and right`() {
        val cutout = Insets.of(100, 0, 0, 0)
        val bars = Insets.of(0, 0, 126, 0)
        assertThat(ImeWindowInsets.paddings(base, bars, cutout))
            .isEqualTo(Paddings(left = 12 + 100, top = 8, right = 12 + 126, bottom = 8))
    }

    @Test
    fun `navigation bar and cutout on the same side are not summed`() {
        // Landscape on a notched phone: the 3-button bar sits inside the cutout
        // area. Adding both would over-pad the row.
        val cutout = Insets.of(0, 0, 130, 126)
        val bars = Insets.of(0, 0, 126, 126)
        val result = ImeWindowInsets.paddings(base, bars, cutout)
        assertThat(result.right).isEqualTo(12 + 130)
        assertThat(result.bottom).isEqualTo(8 + 126)
    }

    @Test
    fun `applying the same insets twice gives the same paddings`() {
        // The listener is called again on every show, split and rotation; the
        // computation must not depend on the previous result.
        val bars = Insets.of(0, 0, 0, 126)
        val first = ImeWindowInsets.paddings(base, bars, none)
        val second = ImeWindowInsets.paddings(base, bars, none)
        assertThat(second).isEqualTo(first)
    }
}
