package com.georgernstgraf.polishedrecognition.ui.contrast

import android.content.Context
import android.view.ContextThemeWrapper
import com.georgernstgraf.polishedrecognition.R
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #161: the owner's text palette, checked against the app background it is painted on.
 *
 * The audit ([ThemeContrastAuditTest]) cannot verify every palette entry by measurement: the
 * disabled colour only ever appears on the platform `Button` fill, which the host cannot
 * resolve (UNMEASURED). This test closes that hole — it resolves the four `@color/text_*`
 * resources per mode and asserts the ratio against the background the app theme really uses.
 *
 * The palette is an owner decision; the numbers below are the measured values it was chosen
 * for, not a target to tune.
 */
@RunWith(RobolectricTestRunner::class)
class TextPaletteContrastTest {

    private data class Entry(val name: String, val threshold: Double)

    private val palette = listOf(
        Entry("text_primary_enabled", 4.5),
        Entry("text_secondary_enabled", 4.5),
        Entry("text_tertiary_enabled", 4.5),
        Entry("text_disabled", 3.0),
    )

    @Test
    @Config(qualifiers = "notnight")
    fun `light palette clears the WCAG floor on the light app background`() {
        assertPalette(
            documented = listOf(LIGHT_PRIMARY, LIGHT_SECONDARY, LIGHT_TERTIARY, LIGHT_DISABLED)
        )
    }

    @Test
    @Config(qualifiers = "night")
    fun `night palette clears the WCAG floor on the night app background`() {
        assertPalette(
            documented = listOf(NIGHT_PRIMARY, NIGHT_SECONDARY, NIGHT_TERTIARY, NIGHT_DISABLED)
        )
    }

    private fun assertPalette(documented: List<Double>) {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_PolishedRecognition_Plain
        )
        val background = ViewColors.themeBackgroundColor(context)
        assertWithMessage("the app background must be opaque")
            .that(Contrast.alpha(background)).isEqualTo(0xFF)

        palette.forEachIndexed { index, entry ->
            val colour = colour(context, entry.name)
            val ratio = Contrast.contrastRatio(colour, background)
            val message = "${entry.name} ${Contrast.hex(colour)} on " +
                "${Contrast.hex(background)} = ${Contrast.formatRatio(ratio)}:1"
            assertWithMessage("$message — below the ${entry.threshold}:1 floor")
                .that(ratio).isAtLeast(entry.threshold)
            assertWithMessage("$message — deviates from the owner's measured palette")
                .that(ratio).isWithin(0.2).of(documented[index])
        }
    }

    private fun colour(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "color", context.packageName)
        assertWithMessage("colour resource $name is missing").that(id).isNotEqualTo(0)
        return context.getColor(id)
    }

    private companion object {
        // The owner's palette, measured against #F0F0F3 (light) and #191C1E (night).
        const val LIGHT_PRIMARY = 14.3
        const val LIGHT_SECONDARY = 7.8
        const val LIGHT_TERTIARY = 5.05
        const val LIGHT_DISABLED = 3.47
        const val NIGHT_PRIMARY = 17.1
        const val NIGHT_SECONDARY = 9.4
        const val NIGHT_TERTIARY = 6.5
        const val NIGHT_DISABLED = 5.0
    }
}
