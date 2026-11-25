package com.georgernstgraf.polishedrecognition.ui

import android.widget.Toast
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * #207: the central toast policy. The chain math ([Toasts.reshows]) is pure;
 * the Robolectric cases pin the runtime contract: a chained toast re-shows
 * the SAME instance until the target elapses, a fresh show cancels the
 * previous chain (no old text resurrects), the trivial short variant is a
 * single unchained show, and the #88 hint stays a reused, cancellable
 * instance.
 */
@RunWith(RobolectricTestRunner::class)
class ToastsTest {

    @Before
    fun setUp() {
        ShadowToast.reset()
        Toasts.cancelAll()
    }

    @After
    fun tearDown() {
        Toasts.cancelAll()
    }

    private fun runToEnd() = shadowOf(android.os.Looper.getMainLooper()).runToEndOfTasks()

    // ---- the pure chain policy ------------------------------------------

    @Test
    fun `the default target chains exactly one re-show`() {
        // 5.5 s over 3.5 s segments: the first display would end at 3.5 s
        // (before the target), the re-show at 3.25 s covers until 6.75 s.
        assertThat(Toasts.reshows(Toasts.DEFAULT_TARGET_MS)).containsExactly(3_250L)
    }

    @Test
    fun `a target within one segment is unchained`() {
        assertThat(Toasts.reshows(Toasts.SEGMENT_MS)).isEmpty()
        assertThat(Toasts.reshows(3_000L)).isEmpty()
        assertThat(Toasts.reshows(0L)).isEmpty()
    }

    @Test
    fun `longer targets re-show uniformly until the target is covered`() {
        // 7 s: re-shows at 3.25 s (covers to 6.75 s — still short) and
        // 6.5 s (covers to 10 s — past the target).
        assertThat(Toasts.reshows(7_000L)).containsExactly(3_250L, 6_500L).inOrder()
        assertThat(Toasts.reshows(10_500L)).containsExactly(3_250L, 6_500L, 9_750L).inOrder()
    }

    @Test
    fun `long chains re-show uniformly and the last segment covers the target`() {
        val delays = Toasts.reshows(30_000L)
        // The smallest chain whose last segment (re-show + 3.5 s) reaches 30 s.
        assertThat(delays).hasSize(9)
        assertThat(delays.first()).isEqualTo(3_250L)
        assertThat(delays.windowed(2).map { (previous, next) -> next - previous }.toSet())
            .containsExactly(3_250L)
        assertThat(delays.last() + Toasts.SEGMENT_MS).isAtLeast(30_000L)
    }

    // ---- the runtime contract -------------------------------------------

    @Test
    fun `the default show chains to exactly two shows of the same text`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.show(context, "one informative message")
        runToEnd()

        assertThat(ShadowToast.shownToastCount()).isEqualTo(2)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("one informative message")
    }

    @Test
    fun `the short variant is one unchained show`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.short(context, "Saved")
        runToEnd()

        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
    }

    @Test
    fun `a fresh show cancels the previous chain - no old text resurrects`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.show(context, "the first message")
        Toasts.show(context, "the second message")
        runToEnd()

        // The second message's own chain = its initial show + one re-show;
        // the first message contributes its initial show and NOTHING else —
        // its cancelled chain never resurrects the old text.
        assertThat(ShadowToast.shownToastCount()).isEqualTo(3)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("the second message")
    }

    @Test
    fun `the hint reuses one instance and dismissHint cancels it`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.hint(context, "first hint", 120)
        Toasts.hint(context, "second hint", 120)

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("second hint")
        assertThat(ShadowToast.shownToastCount()).isEqualTo(2)

        Toasts.dismissHint()
        runToEnd()

        // No chain ever existed for a hint — nothing re-shows after cancel.
        assertThat(ShadowToast.shownToastCount()).isEqualTo(2)
    }

    @Test
    fun `a message shown after a hint is not killed by dismissHint`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.hint(context, "long-press hint", 120)
        Toasts.show(context, "the delivery message")

        Toasts.dismissHint()

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("the delivery message")
    }

    /** Reads the private hint slot — the invariant "one toast alive" lives there. */
    private fun hintSlot(): Any? =
        Toasts::class.java.getDeclaredField("hint").apply { isAccessible = true }.get(Toasts)

    @Test
    fun `a show dismisses a live hint - one surface at a time`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.hint(context, "long-press hint", 120)
        Toasts.show(context, "the delivery message")

        // The hint slot is empty the moment a message takes over: a later
        // dismissHint() can no longer touch anything.
        assertThat(hintSlot()).isNull()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("the delivery message")
    }

    @Test
    fun `a short dismisses a live hint too`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.hint(context, "long-press hint", 120)
        Toasts.short(context, "Saved")

        assertThat(hintSlot()).isNull()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Saved")
    }

    @Test
    fun `hint keeps its instance - it reuses, never cancels, the hint`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.hint(context, "long-press hint", 120)

        // hint() must NOT churn the reused #88 instance away.
        assertThat(hintSlot()).isNotNull()
    }
}
