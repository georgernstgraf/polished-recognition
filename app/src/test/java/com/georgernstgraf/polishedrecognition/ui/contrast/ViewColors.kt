package com.georgernstgraf.polishedrecognition.ui.contrast

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.EditText
import android.widget.TextView

/**
 * #161: what a [TextView] actually PAINTS — not what the theme says in theory.
 *
 * The distinction matters because the #160 bug was invisible whenever a test resolved
 * the theme value instead of the view's own resolved state: `textColors` carries the
 * selector and the enabled/disabled state, and the background is usually a chain of
 * translucent layers (ripple → inset → 9-patch) over the window background.
 *
 * No assertions here — reused by #162 (pixels) and #163 (colour pairs).
 */
object ViewColors {

    /** A colour candidate for a text view: the text itself, or the hint of an empty field. */
    data class TextCandidate(val color: Int, val kind: String)

    const val KIND_TEXT = "text"
    const val KIND_HINT = "hint"

    /**
     * The colour the view paints for its CURRENT state. An empty [EditText] contributes
     * its hint colour as a second candidate — that is the only text such a field shows.
     * Empty when the view shows no text and no hint.
     */
    fun textCandidates(tv: TextView): List<TextCandidate> {
        val state = tv.drawableState
        val text = tv.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            return listOf(
                TextCandidate(
                    tv.textColors.getColorForState(state, tv.currentTextColor),
                    KIND_TEXT
                )
            )
        }
        if (tv !is EditText) return emptyList()
        val hints: ColorStateList? = tv.hintTextColors
        val hintColor = hints?.getColorForState(state, tv.currentHintTextColor) ?: 0
        // A field without a hint has a null/transparent hint list — nothing is painted.
        if (Color.alpha(hintColor) == 0) return emptyList()
        return listOf(TextCandidate(hintColor, KIND_HINT))
    }

    /** First candidate — the painted text colour, or `null` when the view shows nothing. */
    fun effectiveTextColor(tv: TextView): Int? = textCandidates(tv).firstOrNull()?.color

    /**
     * The opaque-or-not colour painted behind [view]: the first non-transparent background
     * found walking up the parent chain, with every translucent layer composited over the
     * next one below. Falls back to the theme's `?android:attr/colorBackground`.
     */
    fun effectiveBackground(view: View): Int {
        var accumulated: Int? = null
        var current: View? = view
        while (current != null) {
            val color = drawableColor(current.background, current.drawableState)
            if (color != null && Color.alpha(color) != 0) {
                accumulated = if (accumulated == null) color
                else Contrast.compositeOver(accumulated, color)
                if (Color.alpha(accumulated) == 255) return accumulated
            }
            current = current.parent as? View
        }
        val fallback = themeBackgroundColor(view.context)
        return if (accumulated == null) fallback
        else Contrast.compositeOver(accumulated, fallback)
    }

    /** Product of [View.getAlpha] up the parent chain — a faded container fades its text. */
    fun effectiveAlpha(view: View): Float {
        var alpha = 1f
        var current: View? = view
        while (current != null) {
            alpha *= current.alpha
            current = current.parent as? View
        }
        return alpha
    }

    /**
     * `?android:attr/colorBackground` of [context]'s theme, resolved as a ColorStateList —
     * never through `TypedValue.data`, which is a resource id for reference-valued
     * attributes (the #160 trap).
     */
    fun themeBackgroundColor(context: Context): Int = themeColorStateList(
        context, android.R.attr.colorBackground
    )?.defaultColor ?: Color.TRANSPARENT

    /** `?android:attr/colorBackgroundFloating` — the panel colour of dialogs. */
    fun themeFloatingBackgroundColor(context: Context): Int = themeColorStateList(
        context, android.R.attr.colorBackgroundFloating
    )?.defaultColor ?: Color.TRANSPARENT

    /** `?android:attr/windowBackground` — what the window itself paints. */
    fun themeWindowBackgroundColor(context: Context): Int = themeColorStateList(
        context, android.R.attr.windowBackground
    )?.defaultColor ?: Color.TRANSPARENT

    /**
     * `?android:attr/colorButtonNormal` for the ENABLED state — the fill a Material button
     * really paints. Resolved per state because the attribute is a selector whose default
     * entry is the disabled colour.
     */
    fun themeButtonNormalColor(context: Context): Int {
        val list = themeColorStateList(context, android.R.attr.colorButtonNormal)
            ?: return Color.TRANSPARENT
        return list.getColorForState(intArrayOf(android.R.attr.state_enabled), list.defaultColor)
    }

    private fun themeColorStateList(context: Context, attr: Int): ColorStateList? {
        val attrs = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            attrs.getColorStateList(0)
        } finally {
            attrs.recycle()
        }
    }

    /**
     * The colour a drawable contributes, or `null` when it paints no flat colour
     * (9-patch, bitmap, vector, stroke-only shape) — the caller then keeps walking up.
     */
    fun drawableColor(drawable: Drawable?, state: IntArray): Int? = when (drawable) {
        null -> null
        is ColorDrawable -> drawable.color
        // RippleDrawable IS a LayerDrawable: layer 0 is the CONTENT, the last layer is the
        // MASK. The mask is a stencil (white where the ripple may draw), never a colour —
        // reading it turns every Material button into white-on-white.
        is RippleDrawable ->
            if (drawable.numberOfLayers > 0) drawableColor(drawable.getDrawable(0), state)
            else null
        is LayerDrawable -> compositeLayers(drawable, state)
        is StateListDrawable -> drawableColor(drawable.current, state)
        is GradientDrawable -> drawable.color?.let {
            it.getColorForState(state, Color.TRANSPARENT)
        }?.takeIf { Color.alpha(it) != 0 }
        is PaintDrawable -> drawable.paint.color.takeIf { Color.alpha(it) != 0 }
        is DrawableWrapper -> drawableColor(drawable.drawable, state)
        else -> null
    }

    /** LayerDrawable draws index 0 first, i.e. index 0 is the BOTTOM layer. */
    private fun compositeLayers(layers: LayerDrawable, state: IntArray): Int? {
        var accumulated: Int? = null
        for (i in 0 until layers.numberOfLayers) {
            val color = drawableColor(layers.getDrawable(i), state) ?: continue
            if (Color.alpha(color) == 0) continue
            accumulated = if (accumulated == null) color
            else Contrast.compositeOver(color, accumulated)
        }
        return accumulated
    }
}
