package com.georgernstgraf.polishedrecognition.ui.contrast

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #161 — WCAG contrast audit, LIGHT mode, ENFORCED.
 *
 * Measures every painted text view of SettingsActivity, CrashDialogActivity,
 * SettingsHintActivity, the IME layout and MicrophonePermissionActivity under the app theme
 * and under the OEM-shaped platform theme (`Theme_Material_Light`, whose `textColor*`
 * attributes are `@color` selectors — the OxygenOS shape that hid #160).
 *
 * The gate ([ContrastGate]) is asymmetric by owner decision: the app theme must have ZERO
 * rows below its WCAG floor, the OEM theme is measured but not ratio-gated. Rows whose
 * background the host cannot resolve are `UNMEASURED`, and only a platform `Button` may be.
 *
 * The night counterpart is [ThemeContrastAuditNightTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class ThemeContrastAuditTest {

    @Before
    fun setUp() {
        ContrastMatrix.clearPrefs()
    }

    @After
    fun tearDown() {
        ContrastMatrix.clearPrefs()
    }

    @Test
    fun `light mode - app theme holds the contrast gate, OEM theme is measured`() {
        val results = listOf(ContrastMatrix.THEME_APP, ContrastMatrix.THEME_OEM)
            .flatMap { ContrastMatrix.audits(ContrastMatrix.MODE_LIGHT, it) }

        ContrastMatrix.writeReport(ContrastMatrix.MODE_LIGHT, results)
        ContrastGate.report(results)

        ContrastGate.assertWalkMatched(results)
        ContrastGate.assertNoInvisibleText(results)
        ContrastGate.assertUnmeasuredOnlyButtons(results)
        ContrastGate.assertOemRunsAreMeasured(results)
        ContrastGate.assertAppThemeGate(results)
    }
}
