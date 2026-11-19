package com.georgernstgraf.polishedrecognition.ui

import android.view.View
import androidx.core.graphics.Insets

/**
 * #169 (epic #166): the Workbench is the first activity that has to handle
 * edge-to-edge insets itself (targetSdk 36). The IME solves the same problem
 * in [com.georgernstgraf.polishedrecognition.service.ImeWindowInsets] for its
 * own window; the rule is the same:
 *
 *  - the paddings are always rebuilt from the layout's XML base paddings,
 *    never from the current view padding (insets are dispatched repeatedly),
 *  - navigation bar and display cutout are merged per side with `max`, not
 *    summed (in landscape they occupy the same edge),
 *  - the IME inset shares the bottom edge with the navigation bar, so the
 *    content clears whichever is larger.
 */
object WorkbenchInsets {

    data class Paddings(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun paddings(
        base: Paddings,
        systemBars: Insets,
        displayCutout: Insets,
        ime: Insets,
    ): Paddings = Paddings(
        left = base.left + maxOf(systemBars.left, displayCutout.left),
        top = base.top + maxOf(systemBars.top, displayCutout.top),
        right = base.right + maxOf(systemBars.right, displayCutout.right),
        bottom = base.bottom + maxOf(systemBars.bottom, displayCutout.bottom, ime.bottom),
    )

    /** Reads the paddings the layout declares, i.e. the value [paddings] builds on. */
    fun baseOf(root: View) = Paddings(
        root.paddingLeft,
        root.paddingTop,
        root.paddingRight,
        root.paddingBottom
    )
}
