package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #222: the anchor policy of the owned toast strip — pure, like every
 * layout rule in this app. The fraction lives in ONE constant; the Workbench
 * overlay derives its top offset from [ToastStripPolicy.overlayTop] (the IME
 * strip is docked and does no screen math — see the policy's doc).
 */
class ToastStripPolicyTest {

    @Test
    fun `the anchor is 0_6 of the host's usable height`() {
        assertThat(ToastStripPolicy.ANCHOR_FRACTION).isEqualTo(0.6f)
    }

    @Test
    fun `the overlay's bottom edge lands on the anchor of the content height`() {
        // 60 % of 2400 = 1440; a 120-px strip sits at 1440 − 120 = 1320.
        assertThat(ToastStripPolicy.overlayTop(2400, 120)).isEqualTo(1320)
        // The strip height comes from the real view — the offset follows it.
        assertThat(ToastStripPolicy.overlayTop(2400, 60)).isEqualTo(1380)
    }

    @Test
    fun `a strip taller than the anchor room clamps at zero`() {
        assertThat(ToastStripPolicy.overlayTop(100, 300)).isEqualTo(0)
    }

    @Test
    fun `an unlaid-out host clamps at zero`() {
        assertThat(ToastStripPolicy.overlayTop(0, 0)).isEqualTo(0)
    }
}
