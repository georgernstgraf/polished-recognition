package com.georgernstgraf.polishedrecognition.ui.contrast

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #161 — WCAG contrast audit, NIGHT mode, ENFORCED.
 *
 * The `night` qualifier flips both theme families: the app theme is DayNight, and the OEM
 * counterpart is `android.R.style.Theme_Material` (dark, selector-based text colours). The
 * same [ContrastGate] applies as in [ThemeContrastAuditTest]: zero app-theme violations, the
 * OEM run is measured but not ratio-gated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "night")
class ThemeContrastAuditNightTest {

    @Before
    fun setUp() {
        ContrastMatrix.clearPrefs()
    }

    @After
    fun tearDown() {
        ContrastMatrix.clearPrefs()
    }

    @Test
    fun `night mode - app theme holds the contrast gate, OEM theme is measured`() {
        val results = listOf(ContrastMatrix.THEME_APP, ContrastMatrix.THEME_OEM)
            .flatMap { ContrastMatrix.audits(ContrastMatrix.MODE_NIGHT, it) }

        ContrastMatrix.writeReport(ContrastMatrix.MODE_NIGHT, results)
        ContrastGate.report(results)

        ContrastGate.assertWalkMatched(results)
        ContrastGate.assertNoInvisibleText(results)
        ContrastGate.assertUnmeasuredOnlyButtons(results)
        ContrastGate.assertOemRunsAreMeasured(results)
        ContrastGate.assertAppThemeGate(results)
    }
}
