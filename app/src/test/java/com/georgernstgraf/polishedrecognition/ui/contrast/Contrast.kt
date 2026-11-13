package com.georgernstgraf.polishedrecognition.ui.contrast

import android.graphics.Color
import java.util.Locale
import kotlin.math.pow

/**
 * #161: WCAG 2.1 contrast arithmetic — pure functions, no Android view types.
 *
 * Deliberately free of any test-framework type: the same helpers are reused by the
 * pixel test (#162) and the colour-pair test (#163), so nothing here may assert.
 */
object Contrast {

    /** WCAG 2.1 relative luminance of an sRGB colour. Alpha is IGNORED (composite first). */
    fun relativeLuminance(argb: Int): Double {
        val r = linearize(Color.red(argb) / 255.0)
        val g = linearize(Color.green(argb) / 255.0)
        val b = linearize(Color.blue(argb) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    /** WCAG contrast ratio `(Lhi + 0.05) / (Llo + 0.05)`, 1.0 … 21.0. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Source-over alpha compositing of [over] on top of [under] — the colour that is
     * actually seen. `compositeOver(opaque, anything)` is [over].
     */
    fun compositeOver(over: Int, under: Int): Int {
        val aOver = Color.alpha(over) / 255.0
        val aUnder = Color.alpha(under) / 255.0
        val outAlpha = aOver + aUnder * (1.0 - aOver)
        if (outAlpha <= 0.0) return Color.TRANSPARENT
        val r = blend(Color.red(over), Color.red(under), aOver, aUnder, outAlpha)
        val g = blend(Color.green(over), Color.green(under), aOver, aUnder, outAlpha)
        val b = blend(Color.blue(over), Color.blue(under), aOver, aUnder, outAlpha)
        return Color.argb(
            (outAlpha * 255.0).toInt().coerceIn(0, 255),
            r, g, b
        )
    }

    private fun blend(over: Int, under: Int, aOver: Double, aUnder: Double, outAlpha: Double): Int =
        ((over * aOver + under * aUnder * (1.0 - aOver)) / outAlpha)
            .toInt().coerceIn(0, 255)

    /** `#AARRGGBB` — the alpha byte is kept, it is the diagnostic for the #160 class. */
    fun hex(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)

    /**
     * WCAG "large text": >= 18sp, or >= 14sp AND bold. [textSizePx] is divided by
     * [scaledDensity] to get sp; the epsilon absorbs float round-trips (18sp at
     * density 2.75 is not exactly 18.0f).
     */
    fun isLargeText(textSizePx: Float, isBold: Boolean, scaledDensity: Float): Boolean {
        if (scaledDensity <= 0f) return false
        val sp = textSizePx / scaledDensity
        return sp >= LARGE_SP - EPSILON || (isBold && sp >= LARGE_BOLD_SP - EPSILON)
    }

    /** Minimum ratio: 3.0 for large text and for disabled controls, else 4.5. */
    fun thresholdFor(large: Boolean, disabled: Boolean): Double =
        if (large || disabled) 3.0 else 4.5

    private const val LARGE_SP = 18f
    private const val LARGE_BOLD_SP = 14f
    private const val EPSILON = 0.05f
}
