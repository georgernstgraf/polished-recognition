package com.georgernstgraf.polishedrecognition.ui

import android.view.ContextThemeWrapper
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

    // ---- the owned strip hosts (#222) --------------------------------------

    /** Records what a host surface was told — the strip is not a ShadowToast. */
    private class RecordingStripHost : Toasts.StripHost {
        val shown = mutableListOf<Pair<CharSequence, Long>>()
        var cancels = 0

        override fun showStrip(text: CharSequence, durationMs: Long) {
            shown += text to durationMs
        }

        override fun cancelStrip() {
            cancels++
        }
    }

    @Test
    fun `a show on a hosted context renders through the strip - never the platform`() {
        val context = RuntimeEnvironment.getApplication()
        val host = RecordingStripHost()
        Toasts.attachHost(context, host)

        Toasts.show(context, "one strip message")
        runToEnd()

        assertThat(host.shown).containsExactly("one strip message" to Toasts.DEFAULT_TARGET_MS)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `the short variant renders through the strip with the short duration`() {
        val context = RuntimeEnvironment.getApplication()
        val host = RecordingStripHost()
        Toasts.attachHost(context, host)

        Toasts.short(context, "Saved")

        assertThat(host.shown).containsExactly("Saved" to Toasts.SHORT_TARGET_MS)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `a context without a host stays on the platform fallback`() {
        val app = RuntimeEnvironment.getApplication()
        val host = RecordingStripHost()
        // Two DISTINCT context instances: only the first one has a host.
        Toasts.attachHost(ContextThemeWrapper(app, android.R.style.Theme_Material_Light), host)

        Toasts.show(ContextThemeWrapper(app, android.R.style.Theme_Material), "platform message")
        runToEnd()

        assertThat(host.shown).isEmpty()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(2)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("platform message")
    }

    @Test
    fun `a fresh strip show is cancel-safe - the old text never resurrects`() {
        val context = RuntimeEnvironment.getApplication()
        val host = RecordingStripHost()
        Toasts.attachHost(context, host)

        Toasts.show(context, "the first message")
        Toasts.show(context, "the second message")

        // The replacement cancelled the previous strip surface exactly once…
        assertThat(host.cancels).isEqualTo(1)
        // …and the host was told the SECOND message only.
        assertThat(host.shown).containsExactly(
            "the first message" to Toasts.DEFAULT_TARGET_MS,
            "the second message" to Toasts.DEFAULT_TARGET_MS,
        ).inOrder()
    }

    @Test
    fun `a strip show cancels a live hint too`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.hint(context, "long-press hint", 120)
        val host = RecordingStripHost()
        Toasts.attachHost(context, host)

        Toasts.show(context, "the strip message")

        assertThat(hintSlot()).isNull()
        assertThat(host.shown.single().first).isEqualTo("the strip message")
        // Only the hint's own initial show ever happened on the platform.
        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
    }

    @Test
    fun `a strip show cancels the running platform chain`() {
        val context = RuntimeEnvironment.getApplication()
        Toasts.show(context, "the chained platform message")
        val host = RecordingStripHost()
        Toasts.attachHost(context, host)

        Toasts.show(context, "the strip message")
        runToEnd()

        // The platform chain contributed its initial show and NOTHING else.
        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
        assertThat(host.shown.single().first).isEqualTo("the strip message")
    }

    @Test
    fun `a platform show cancels a visible strip`() {
        val app = RuntimeEnvironment.getApplication()
        val host = RecordingStripHost()
        val hosted = ContextThemeWrapper(app, android.R.style.Theme_Material_Light)
        Toasts.attachHost(hosted, host)
        Toasts.show(hosted, "the strip message")

        // A platform show (a context WITHOUT a host) hides the strip: one
        // toast alive across surfaces.
        Toasts.show(ContextThemeWrapper(app, android.R.style.Theme_Material), "the platform message")

        assertThat(host.cancels).isEqualTo(1)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("the platform message")
    }

    @Test
    fun `cancelAll covers the strip`() {
        val context = RuntimeEnvironment.getApplication()
        val host = RecordingStripHost()
        Toasts.attachHost(context, host)
        Toasts.show(context, "the strip message")

        Toasts.cancelAll()

        assertThat(host.cancels).isEqualTo(1)
    }

    @Test
    fun `detachHost hides a visible strip immediately and falls back to the platform`() {
        val context = RuntimeEnvironment.getApplication()
        val host = RecordingStripHost()
        Toasts.attachHost(context, host)
        Toasts.show(context, "the strip message")

        Toasts.detachHost(context)

        assertThat(host.cancels).isEqualTo(1)

        // The registry no longer holds the host: the next show is a plain
        // platform toast, exactly like the pre-#222 fallback contract.
        Toasts.show(context, "the platform message")
        runToEnd()
        assertThat(host.shown).hasSize(1)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(2)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("the platform message")
    }
}
