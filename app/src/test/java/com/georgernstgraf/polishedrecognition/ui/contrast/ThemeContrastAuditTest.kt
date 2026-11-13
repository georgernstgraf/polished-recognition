package com.georgernstgraf.polishedrecognition.ui.contrast

import android.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #161 wave 1A — WCAG contrast audit, LIGHT mode, REPORT MODE.
 *
 * Measures every painted text view of SettingsActivity, CrashDialogActivity,
 * SettingsHintActivity, the IME layout and MicrophonePermissionActivity under the app theme
 * and under the OEM-shaped platform theme (`Theme_Material_Light`, whose `textColor*`
 * attributes are `@color` selectors — the OxygenOS shape that hid #160).
 *
 * The contrast ratios are PRINTED to `build/contrast-report.txt`, NOT asserted: this wave
 * produces the measurement the next wave acts on. Only two things are asserted:
 *
 *  1. every screen that has text yields at least one measured text view (a walk that
 *     silently matches nothing would otherwise look like a clean audit),
 *  2. no text colour is effectively invisible through an id-read-as-ARGB alpha (#160).
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
    fun `light mode - contrast of every screen is measured and reported`() {
        val results = listOf(ContrastMatrix.THEME_APP, ContrastMatrix.THEME_OEM)
            .flatMap { ContrastMatrix.audits(ContrastMatrix.MODE_LIGHT, it) }

        ContrastMatrix.writeReport(ContrastMatrix.MODE_LIGHT, results)
        report(results)

        // 1. the walk matched something on every screen that shows text.
        results.filter { it.expectsText }.forEach { result ->
            assertWithMessage(
                "${result.screen} [${result.mode}/${result.theme}] produced no measured text view"
            ).that(result.textViews).isGreaterThan(0)
        }

        // 2. no text is invisible because an id was read as ARGB (#160).
        results.flatMap { it.rows }.forEach { row ->
            assertWithMessage(
                "${row.screen} [${row.mode}/${row.theme}] ${row.viewId} fg=${Contrast.hex(row.fg)}"
            ).that(Color.alpha(row.fg)).isGreaterThan(0x20)
        }
    }

    private fun report(results: List<ContrastAudit.AuditResult>) {
        results.forEach { result ->
            println(
                "contrast ${result.mode}/${result.theme} ${result.screen}: " +
                    "${result.textViews} text views, ${result.violations.size} below threshold" +
                    (result.note?.let { " ($it)" } ?: "")
            )
        }
        results.flatMap { it.rows }.filter { !it.passes }.sortedBy { it.ratio }.forEach { row ->
            println(
                "contrast FAIL ${row.mode}/${row.theme} ${row.screen} ${row.viewId} " +
                    "'${row.text}' fg=${Contrast.hex(row.fg)} bg=${Contrast.hex(row.bg)} " +
                    "ratio=%.2f < %.1f".format(row.ratio, row.threshold)
            )
        }
    }
}
