package com.georgernstgraf.polishedrecognition.service

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

/**
 * #157: the IME window keeps its content clear of the navigation bar and the
 * display cutout.
 *
 * Up to Android 14 the platform applied the navigation bar inset to the IME
 * content itself, so the keyboard layout could ignore insets completely
 * (verified on API 31: the IME window frame reaches the display bottom, the
 * content does not). From Android 15 (API 35) the IME window is laid out
 * edge-to-edge and the content must handle the insets — otherwise the bottom
 * control row is drawn underneath the navigation bar, where the system
 * buttons are (verified on API 35: IME window `[0,2058][1080,2400]`,
 * navigation bar `[0,2274][1080,2400]`).
 *
 * The padding is always computed from the XML base paddings plus the current
 * insets — never from the previous view padding — because insets are
 * dispatched repeatedly (show, split, rotation, theme changes) and a growing
 * padding would eat the keyboard alive. A platform that already inset the
 * content reports the consumed inset as zero, so the same rule is harmless
 * there.
 */
object ImeWindowInsets {

    data class Paddings(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Adds the system insets to [base].
     *
     * The navigation bar and the display cutout are merged per side with
     * `max` instead of being summed: in landscape the 3-button navigation bar
     * sits where the cutout is, and adding both would over-pad.
     */
    fun paddings(
        base: Paddings,
        navigationBars: Insets,
        displayCutout: Insets,
    ): Paddings = Paddings(
        left = base.left + maxOf(navigationBars.left, displayCutout.left),
        top = base.top + maxOf(navigationBars.top, displayCutout.top),
        right = base.right + maxOf(navigationBars.right, displayCutout.right),
        bottom = base.bottom + maxOf(navigationBars.bottom, displayCutout.bottom),
    )

    /** Reads the paddings the layout declares, i.e. the value [apply] builds on. */
    fun baseOf(root: View) = Paddings(
        root.paddingLeft,
        root.paddingTop,
        root.paddingRight,
        root.paddingBottom
    )

    /**
     * Applies [base] plus the navigation-bar and cutout insets to [root].
     *
     * The insets stay unconsumed: the IME window itself is still placed by the
     * platform from them.
     */
    fun apply(root: View, base: Paddings, insets: WindowInsetsCompat): Paddings {
        val target = paddings(
            base = base,
            navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()),
            displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()),
        )
        root.setPadding(target.left, target.top, target.right, target.bottom)
        return target
    }
}
