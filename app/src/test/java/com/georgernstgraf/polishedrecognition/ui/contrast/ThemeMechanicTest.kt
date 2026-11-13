package com.georgernstgraf.polishedrecognition.ui.contrast

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.XmlResourceParser
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.R
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * #161: the THEME MECHANIC — a layout attribute that names a theme attribute must reach the
 * view as that attribute's resolved `ColorStateList`, for every state.
 *
 * [ThemeColorResolutionTest] pins this for the two fragment-geometry fields (#160). This test
 * generalises it: it discovers every `android:textColor="?android:attr/X"` on an id'd view in
 * every layout, inflates the layout and compares the view's colour to the theme's own answer
 * for the same drawable state. The failure it catches is the #160 class — a theme reference
 * taken as a flat `Int`, which reads a resource id as ARGB and yields alpha 0x01.
 *
 * Runs under the OEM-shaped platform themes, whose `textColor*` attributes are `@color`
 * SELECTORS (the OxygenOS/ColorOS shape), in both modes.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeMechanicTest {

    /** One `android:textColor="?android:attr/X"` declaration found in a layout. */
    private data class Declaration(val layout: Int, val viewId: Int, val attr: Int)

    @Test
    @Config(qualifiers = "notnight")
    fun `every themed textColor reaches its view - light OEM theme`() {
        assertMechanic(android.R.style.Theme_Material_Light)
    }

    @Test
    @Config(qualifiers = "night")
    fun `every themed textColor reaches its view - dark OEM theme`() {
        assertMechanic(android.R.style.Theme_Material)
    }

    private fun assertMechanic(themeRes: Int) {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), themeRes)
        val declarations = declarations(context)
        println(
            "mechanic: ${declarations.size} themed textColor declaration(s) across " +
                "${declarations.map { it.layout }.distinct().size} layout(s), " +
                "theme=${context.resources.getResourceEntryName(themeRes)}"
        )
        assertWithMessage("no themed textColor declaration was found in any layout")
            .that(declarations).isNotEmpty()

        declarations.forEach { declaration ->
            val layoutName = context.resources.getResourceEntryName(declaration.layout)
            val attrName = context.resources.getResourceEntryName(declaration.attr)
            val viewName = context.resources.getResourceEntryName(declaration.viewId)
            val message = "$layoutName/$viewName textColor=?android:attr/$attrName"

            val root = LayoutInflater.from(context).inflate(declaration.layout, null, false)
            val view = root.findViewById<View>(declaration.viewId)
            assertWithMessage("$message — the id'd view is missing after inflation")
                .that(view).isNotNull()
            val textView = view as TextView
            val state = textView.drawableState

            val actual = textView.textColors.getColorForState(state, textView.currentTextColor)
            val expected = themeColorStateList(context, declaration.attr)
                .getColorForState(state, textView.currentTextColor)

            assertWithMessage(
                "$message — view paints ${Contrast.hex(actual)}, theme says " +
                    "${Contrast.hex(expected)} for state ${state.toList()}"
            ).that(actual).isEqualTo(expected)
            assertWithMessage("$message — colour is effectively invisible (#160)")
                .that(Contrast.alpha(actual)).isGreaterThan(0x20)
        }
    }

    /**
     * Walks every layout resource, reads the compiled XML with an [XmlPullParser] and keeps
     * the start tags that carry BOTH an id and a theme-reference `android:textColor`.
     *
     * The compiled XML stores a theme reference as the string `?<attr resource id>`, which is
     * the only place the attribute NAME survives — `obtainStyledAttributes` has already
     * resolved it against the theme by the time a TypedArray is handed out.
     */
    private fun declarations(context: Context): List<Declaration> {
        val out = mutableListOf<Declaration>()
        layoutIds().forEach { resId ->
            val parser = context.resources.getXml(resId)
            try {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        declarationOf(parser, resId)?.let { out += it }
                    }
                    event = parser.next()
                }
            } finally {
                parser.close()
            }
        }
        return out
    }

    private fun declarationOf(parser: XmlResourceParser, layoutRes: Int): Declaration? {
        var viewId = View.NO_ID
        var attr = 0
        for (i in 0 until parser.attributeCount) {
            when (parser.getAttributeNameResource(i)) {
                android.R.attr.id ->
                    viewId = parser.getAttributeResourceValue(i, View.NO_ID)
                android.R.attr.textColor ->
                    attr = themeAttributeOf(parser.getAttributeValue(i))
            }
        }
        return if (viewId == View.NO_ID || attr == 0) null else Declaration(layoutRes, viewId, attr)
    }

    /** `?16842806` → `0x01010036`; 0 for anything that is not a theme reference. */
    private fun themeAttributeOf(raw: String?): Int {
        if (raw == null || !raw.startsWith("?")) return 0
        val id = raw.substring(1)
        return try {
            if (id.startsWith("0x")) id.substring(2).toLong(16).toInt() else id.toLong().toInt()
        } catch (_: NumberFormatException) {
            0
        }
    }

    private fun themeColorStateList(context: Context, attr: Int): ColorStateList {
        val typed = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            typed.getColorStateList(0) ?: throw AssertionError(
                "theme attribute ${context.resources.getResourceEntryName(attr)} " +
                    "is not a colour"
            )
        } finally {
            typed.recycle()
        }
    }

    /** Every `R.layout.*` id, by reflection — a new layout is covered without touching this test. */
    private fun layoutIds(): List<Int> =
        R.layout::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { field ->
                field.isAccessible = true
                field.get(null) as Int
            }
}
