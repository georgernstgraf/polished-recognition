package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.graphics.Color
import android.widget.CheckBox
import android.widget.EditText
import com.georgernstgraf.polishedrecognition.R
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #160: the "Advanced" fragment-geometry fields were INVISIBLE in the owner's system
 * dark mode.
 *
 * Root cause: `SettingsActivity` took a theme attribute as an `Int`
 * (`TypedValue.data`) and passed it to `setTextColor(int)`. That is only an ARGB
 * colour while the attribute resolves to a LITERAL — `Theme.DeviceDefault` does that
 * on AOSP, which is why the bug never showed on an emulator or in any test. An OEM
 * night skin (OxygenOS/ColorOS) points `textColor*` at `@color` SELECTORS instead, so
 * `data` is a resource id (e.g. `0x0106xxxx`) and `setTextColor` read it as ARGB:
 * alpha `0x01` → 0.4 % opacity → invisible, in both the locked (grey) and the
 * unlocked state.
 *
 * These tests therefore launch the REAL activity under `Theme.Material` /
 * `Theme.Material.Light`, whose `textColorPrimary`/`textColorTertiary` are
 * `@color` selectors — the OEM shape, available on the pristine platform, so the
 * regression is reproducible on the host.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeColorResolutionTest {

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("polished_recognition_settings", 0)

    /** Launches the real screen under [themeStyle], i.e. with selector-based text colours. */
    private fun launch(themeStyle: Int): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controller.get().setTheme(themeStyle)
        return controller.setup().get()
    }

    /**
     * The colour the THEME says this attribute is **for the view's own state** — resolved the
     * way the platform intends (selector + enabled/disabled), not as a flat int.
     */
    private fun resolvedThemeColor(activity: Activity, attr: Int, state: IntArray): Int {
        val attrs = activity.obtainStyledAttributes(intArrayOf(attr))
        return try {
            val colors = attrs.getColorStateList(0) ?: return Color.TRANSPARENT
            colors.getColorForState(state, colors.defaultColor)
        } finally {
            attrs.recycle()
        }
    }

    private fun assertFieldShowsThemeColor(themeStyle: Int, fieldId: Int, locked: Boolean) {
        val activity = launch(themeStyle)
        // Auto (the default) LOCKS the field and shows the resolved value; unchecking it
        // keeps that value as the editable start.
        if (!locked) {
            activity.findViewById<CheckBox>(
                if (fieldId == R.id.slice_seconds) R.id.slice_auto else R.id.search_auto
            ).isChecked = false
        }

        val field = activity.findViewById<EditText>(fieldId)
        assertThat(field.isEnabled).isEqualTo(!locked)

        val attr = if (locked) android.R.attr.textColorTertiary else android.R.attr.textColorPrimary
        // Locked is a read-only DISPLAY of the resolved value: the theme colour is taken at its
        // enabled variant, because `isEnabled = false` would stack the selector's disabled entry
        // on top of the already-secondary tertiary colour (2.12:1 on the OnePlus, dark mode).
        val state = if (locked) intArrayOf(android.R.attr.state_enabled) else field.drawableState
        val expected = resolvedThemeColor(activity, attr, state)
        val actual = field.textColors.getColorForState(field.drawableState, field.currentTextColor)

        assertThat(hex(actual)).isEqualTo(hex(expected))
        assertThat(Color.alpha(actual)).isGreaterThan(0x20)
    }

    private fun hex(color: Int) = "#%08X".format(color)

    @Test
    fun sliceField_keepsThemeColor_inDarkSelectorTheme() {
        assertFieldShowsThemeColor(android.R.style.Theme_Material, R.id.slice_seconds, locked = true)
        assertFieldShowsThemeColor(android.R.style.Theme_Material, R.id.slice_seconds, locked = false)
    }

    @Test
    fun searchField_keepsThemeColor_inDarkSelectorTheme() {
        assertFieldShowsThemeColor(android.R.style.Theme_Material, R.id.search_percent, locked = true)
        assertFieldShowsThemeColor(android.R.style.Theme_Material, R.id.search_percent, locked = false)
    }

    @Test
    fun sliceField_keepsThemeColor_inLightSelectorTheme() {
        assertFieldShowsThemeColor(
            android.R.style.Theme_Material_Light, R.id.slice_seconds, locked = true
        )
        assertFieldShowsThemeColor(
            android.R.style.Theme_Material_Light, R.id.slice_seconds, locked = false
        )
    }

    @Test
    fun searchField_keepsThemeColor_inLightSelectorTheme() {
        assertFieldShowsThemeColor(
            android.R.style.Theme_Material_Light, R.id.search_percent, locked = true
        )
        assertFieldShowsThemeColor(
            android.R.style.Theme_Material_Light, R.id.search_percent, locked = false
        )
    }
}
