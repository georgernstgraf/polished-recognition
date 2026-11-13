package com.georgernstgraf.polishedrecognition.ui.contrast

import android.content.res.Resources
import android.view.View
import android.widget.TextView

/**
 * #161: how a measured view is NAMED in a report — the presentation half shared by the
 * view-tree audit ([ContrastAudit]) and the pixel test ([RenderSmokeTest]).
 *
 * Both tests must print the same view id and the same text snippet, otherwise the two
 * reports cannot be diffed against each other. No assertions here.
 */
object AuditFormat {

    /** The text column is capped so a long paragraph cannot blow up the table. */
    const val MAX_SNIPPET = 30

    /** One-line text excerpt: newlines flattened, capped at [MAX_SNIPPET] with a `..` tail. */
    fun snippet(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= MAX_SNIPPET) flat else flat.take(MAX_SNIPPET - 2) + ".."
    }

    /** The resource entry name of [view], or its class name when it has no (resolvable) id. */
    fun viewIdName(view: View): String {
        if (view.id == View.NO_ID) return view.javaClass.simpleName
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Resources.NotFoundException) {
            view.javaClass.simpleName
        }
    }

    /**
     * WCAG "large text" needs the real weight: a bold 14sp label counts as large, a regular
     * one does not. Covers both a bold typeface and the synthetic bold paint flag.
     */
    fun isBold(tv: TextView): Boolean =
        tv.typeface?.isBold == true || tv.paint.isFakeBoldText
}
