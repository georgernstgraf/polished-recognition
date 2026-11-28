package com.georgernstgraf.polishedrecognition.ui

/**
 * #222: the ONE anchor policy for the owned toast strip — where a toast
 * appears instead of the platform's bottom-edge pin (`setGravity` is a
 * no-op for apps targeting SDK 30+).
 *
 * The two surfaces anchor DIFFERENTLY, and that is deliberate:
 *
 * - **Workbench (overlay):** the activity's window covers the whole screen,
 *   so the strip must do the screen math itself — its bottom edge lands on
 *   [ANCHOR_FRACTION] of the content height (`top = fraction · contentHeight
 *   − stripHeight`, clamped ≥ 0). "Of the host's usable height" is the
 *   content root's height here.
 * - **IME (docked):** the strip is simply the first child of the voice bar's
 *   root — the PLATFORM places the IME window above the keyboard area
 *   (≈ 55–60 % screen height in portrait), so the window position already
 *   IS the anchor and no screen math runs inside the IME. A strip that did
 *   its own screen math there would drift with keyboard height, insets and
 *   orientation.
 *
 * Pure and test-pinned ([ToastStripPolicyTest]) like every layout rule in
 * this app; the Workbench host wires [overlayTop] into its overlay.
 */
object ToastStripPolicy {

    /**
     * The anchor fraction of the host's usable height — ~60 %, i.e. above
     * the bottom third, NOT y-centred.
     */
    const val ANCHOR_FRACTION = 0.6f

    /**
     * The top offset of the Workbench overlay strip so that its BOTTOM edge
     * sits on `fraction · contentHeight`. Unlaid-out or oversized hosts
     * clamp to 0 (never a negative offset).
     */
    fun overlayTop(contentHeight: Int, stripHeight: Int, fraction: Float = ANCHOR_FRACTION): Int =
        (fraction * contentHeight - stripHeight).toInt().coerceAtLeast(0)
}
