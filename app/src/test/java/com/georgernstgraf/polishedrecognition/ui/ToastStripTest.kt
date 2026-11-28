package com.georgernstgraf.polishedrecognition.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.service.PolishedVoiceInputIME
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * #222: the two owned strip surfaces and their anchoring. The Workbench
 * overlay rides the activity's content root and derives its top offset from
 * [ToastStripPolicy.overlayTop]; the IME strip is DOCKED as the first child
 * of the voice bar root (the window position does the anchoring). Both are
 * `GONE` while idle, hidden by a detach and replaced cancel-safely.
 */
@RunWith(RobolectricTestRunner::class)
class ToastStripTest {

    // ---- the Workbench overlay ---------------------------------------------

    @Test
    fun `the workbench hosts its strip on the content root and anchors it with the policy`() {
        val activity = Robolectric.buildActivity(WorkbenchActivity::class.java).setup().get()
        val host = activity.toastStripHost!!
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val strip = host.strip

        try {
            assertThat(strip.visibility).isEqualTo(View.GONE)

            // The real path: the calling context resolves to the host.
            Toasts.show(activity, "owned message")

            assertThat(strip.visibility).isEqualTo(View.VISIBLE)
            assertThat(strip.text).isEqualTo("owned message")
            assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
            // The anchor: whatever the real heights are, the top offset is the
            // policy's — the wiring must never hand-place the strip.
            val lp = strip.layoutParams as FrameLayout.LayoutParams
            assertThat(lp.topMargin)
                .isEqualTo(ToastStripPolicy.overlayTop(content.height, strip.measuredHeight))

            // Cancel-safe replacement: no old text resurrects.
            Toasts.show(activity, "the newer message")
            assertThat(strip.text).isEqualTo("the newer message")
        } finally {
            host.detach()
        }
    }

    @Test
    fun `a detached workbench host falls back to the platform`() {
        val activity = Robolectric.buildActivity(WorkbenchActivity::class.java).setup().get()
        activity.toastStripHost!!.detach()

        // The registry no longer holds the activity: the toast is a plain
        // platform one again (the fallback contract), and the strip is gone.
        Toasts.show(activity, "platform message")

        assertThat(activity.toastStripText()).isNull()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("platform message")
    }

    // ---- the IME dock --------------------------------------------------------

    @Test
    fun `the ime strip is docked as the first child of the voice bar root`() {
        val service = Robolectric.buildService(PolishedVoiceInputIME::class.java).create().get()
        val view = service.onCreateInputView()
        val root = view.findViewById<LinearLayout>(R.id.ime_root)
        val host = service.toastStrip!!

        assertThat(root.getChildAt(0)).isSameInstanceAs(host.strip)
        assertThat(host.strip.visibility).isEqualTo(View.GONE)

        // The real path: the calling context (the IME) resolves to the host.
        Toasts.show(service, "docked message")

        assertThat(service.toastStripText()).isEqualTo("docked message")
        assertThat(host.strip.visibility).isEqualTo(View.VISIBLE)

        // The keyboard hide cancels the strip — no stale text on a re-show.
        service.onFinishInputView(false)
        assertThat(service.toastStripText()).isNull()

        service.onDestroy()
    }

    /** A re-created input view replaces the host without resurrecting old text. */
    @Test
    fun `a re-created input view rebuilds the strip and hides the old one`() {
        val service = Robolectric.buildService(PolishedVoiceInputIME::class.java).create().get()
        val firstRoot = service.onCreateInputView().findViewById<LinearLayout>(R.id.ime_root)
        Toasts.show(service, "old text")
        val oldStrip = service.toastStrip!!.strip

        val secondRoot = service.onCreateInputView().findViewById<LinearLayout>(R.id.ime_root)

        // The old strip left the old tree entirely; the new host is docked in
        // the new one and starts hidden.
        assertThat(oldStrip.parent).isNull()
        assertThat(service.toastStrip!!.strip.parent).isSameInstanceAs(secondRoot)
        assertThat(secondRoot.getChildAt(0)).isSameInstanceAs(service.toastStrip!!.strip)
        assertThat(service.toastStrip!!.strip.visibility).isEqualTo(View.GONE)

        service.onDestroy()
    }
}
