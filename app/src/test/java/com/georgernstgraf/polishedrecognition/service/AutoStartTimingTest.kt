package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.service.AutoStartTiming.Step
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #165: the auto-start may only run once the IME window is drawn.
 */
class AutoStartTimingTest {

    @Test
    fun `warm window runs the start immediately`() {
        assertThat(AutoStartTiming.step(windowDrawn = true, timedOut = false)).isEqualTo(Step.RUN)
    }

    @Test
    fun `cold bind waits for the first draw`() {
        assertThat(AutoStartTiming.step(windowDrawn = false, timedOut = false)).isEqualTo(Step.WAIT)
    }

    @Test
    fun `a window that never drew drops the start`() {
        assertThat(AutoStartTiming.step(windowDrawn = false, timedOut = true)).isEqualTo(Step.DROP)
    }

    @Test
    fun `a drawn window wins over a late timeout`() {
        // The draw callback and the timeout both run on the main thread; the
        // draw may arrive in the same message cycle. A drawn window must still
        // start — the timeout only decides when nothing was drawn.
        assertThat(AutoStartTiming.step(windowDrawn = true, timedOut = true)).isEqualTo(Step.RUN)
    }

    @Test
    fun `timeout is long enough for a cold start and short enough to drop a hidden keyboard`() {
        assertThat(AutoStartTiming.WINDOW_DRAW_TIMEOUT_MS).isAtLeast(500L)
        assertThat(AutoStartTiming.WINDOW_DRAW_TIMEOUT_MS).isAtMost(3000L)
    }

    @Test
    fun `a window on its way up keeps waiting past the timeout`() {
        // Measured on a loaded emulator: the first frame came ~1.1 s after the
        // bind. A slow cold start must not be cut off — only a window that is
        // not even showing gets dropped.
        assertThat(AutoStartTiming.onTimeout(windowDrawn = false, inputViewShown = true))
            .isEqualTo(Step.WAIT)
    }

    @Test
    fun `a window that never showed is dropped`() {
        assertThat(AutoStartTiming.onTimeout(windowDrawn = false, inputViewShown = false))
            .isEqualTo(Step.DROP)
    }

    @Test
    fun `a draw that beat the timeout still starts`() {
        assertThat(AutoStartTiming.onTimeout(windowDrawn = true, inputViewShown = false))
            .isEqualTo(Step.RUN)
    }
}
