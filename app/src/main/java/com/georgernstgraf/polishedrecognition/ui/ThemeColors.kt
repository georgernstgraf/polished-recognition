package com.georgernstgraf.polishedrecognition.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color

/**
 * #160: resolves a theme attribute to a [ColorStateList] — the only safe way to take a
 * text colour out of the current theme.
 *
 * Why not `TypedValue.data`: `Theme.resolveAttribute(..., resolveRefs = true)` only
 * yields an ARGB literal when the attribute VALUE is a literal. `Theme.DeviceDefault`
 * does that on AOSP, but OEM night skins (OxygenOS/ColorOS) point `textColorPrimary` /
 * `textColorTertiary` at `@color` selectors — then `data` is a RESOURCE ID
 * (`0x01xxxxxx`), and `setTextColor(int)` reads its high byte as ALPHA: `0x01` →
 * 0.4 % opacity → invisible. The id-as-colour also destroys the state list, so the
 * disabled (dimmed) variant is lost.
 *
 * [android.content.res.Resources.Theme.obtainStyledAttributes] applies the same
 * resolution rules as a real widget and returns the selector itself, so both the OEM
 * colour and the enabled/disabled states survive.
 */
internal fun Context.themeColorStateList(attr: Int): ColorStateList {
    val attrs = obtainStyledAttributes(intArrayOf(attr))
    return try {
        attrs.getColorStateList(0) ?: ColorStateList.valueOf(Color.GRAY)
    } finally {
        attrs.recycle()
    }
}
