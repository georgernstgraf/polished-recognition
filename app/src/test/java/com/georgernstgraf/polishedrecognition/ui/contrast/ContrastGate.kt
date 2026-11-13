package com.georgernstgraf.polishedrecognition.ui.contrast

import com.google.common.truth.Truth.assertWithMessage

/**
 * #161 wave 1B: the ENFORCED contrast gate, shared by the light and the night audit test.
 *
 * The gate is deliberately asymmetric, per the owner's decision:
 *
 *  - **app theme** (`Theme.PolishedRecognition.Plain`) — zero violations. This is our theme,
 *    we own its palette, so a ratio below the WCAG floor is a defect.
 *  - **OEM theme** (`Theme_Material` / `Theme_Material_Light`) — no ratio gate. Those are the
 *    platform defaults of a foreign skin; the ratios are printed for triage only. What IS
 *    asserted there is that no text is invisible (#160: an id read as ARGB has alpha 0x01).
 *
 * [ContrastAudit] only measures; every assertion lives here so the two audit tests cannot
 * drift apart.
 */
object ContrastGate {

    /** Every screen that shows text must have produced at least one measured row. */
    fun assertWalkMatched(results: List<ContrastAudit.AuditResult>) {
        results.filter { it.expectsText }.forEach { result ->
            assertWithMessage(
                "${result.screen} [${result.mode}/${result.theme}] produced no measured text view"
            ).that(result.textViews).isGreaterThan(0)
        }
    }

    /** No text may be effectively invisible (#160) — the one gate the OEM runs keep. */
    fun assertNoInvisibleText(results: List<ContrastAudit.AuditResult>) {
        results.flatMap { it.rows }.forEach { row ->
            assertWithMessage(
                "${row.screen} [${row.mode}/${row.theme}] ${row.viewId} fg=${Contrast.hex(row.fg)}"
            ).that(Contrast.alpha(row.fg)).isGreaterThan(0x20)
        }
    }

    /**
     * UNMEASURED is reserved for the platform `Button` fill the host cannot resolve — the
     * 9-patch/ripple chain whose theme reference Robolectric substitutes with opaque white.
     * The host limitation is covered by the pixel test #162; here it may only ever excuse a
     * Button. Every other view with an unresolvable background is a
     * [ContrastAudit.Violation.KIND_UNRESOLVED_BACKGROUND] failure, in BOTH theme families.
     */
    fun assertUnmeasuredOnlyButtons(results: List<ContrastAudit.AuditResult>) {
        val rows = results.flatMap { it.rows }

        rows.filter { it.unmeasured }.forEach { row ->
            assertWithMessage(
                "${row.screen} [${row.mode}/${row.theme}] ${row.viewId} is UNMEASURED but is a " +
                    "${row.viewClass} — only android.widget.Button may be UNMEASURED"
            ).that(isButton(row.viewClass)).isTrue()
        }

        // The same rule from the other side: an unresolved background on a non-Button is a
        // violation even in the report-only OEM runs.
        val structural = results.flatMap { it.violations }
            .filter { it.kind == ContrastAudit.Violation.KIND_UNRESOLVED_BACKGROUND }
        assertWithMessage(
            "unresolvable background on a non-Button view (${structural.size}):\n" +
                format(structural)
        ).that(structural).isEmpty()
    }

    /** The app-theme gate: not one row below its WCAG floor. */
    fun assertAppThemeGate(results: List<ContrastAudit.AuditResult>) {
        val violations = results.filter { it.theme == ContrastMatrix.THEME_APP }
            .flatMap { it.violations }
        assertWithMessage(
            "app-theme contrast gate: ${violations.size} violation(s)\n" + format(violations)
        ).that(violations).isEmpty()
    }

    /** The OEM runs: no ratio gate — the numbers are printed for triage, not asserted. */
    fun assertOemRunsAreMeasured(results: List<ContrastAudit.AuditResult>) {
        val oem = results.filter { it.theme == ContrastMatrix.THEME_OEM }
        assertWithMessage("the OEM matrix must be measured").that(oem).isNotEmpty()
    }

    /** One line per violation, worst first — the message body of a failing gate. */
    fun format(violations: List<ContrastAudit.Violation>): String =
        violations.sortedBy { it.ratio }.joinToString("\n") {
            "  ${it.screen} [${it.mode}/${it.theme}] ${it.viewId} '${it.textSnippet}' " +
                "fg=${it.fgHex} bg=${it.bgHex} ratio=${"%.2f".format(it.ratio)} " +
                "< ${"%.1f".format(it.threshold)} (${it.kind})"
        }

    /** The lines the audit tests print — shared so the two reports stay identical. */
    fun report(results: List<ContrastAudit.AuditResult>) {
        results.forEach { result ->
            println(
                "contrast ${result.mode}/${result.theme} ${result.screen}: " +
                    "${result.textViews} text views, ${result.violations.size} below threshold, " +
                    "${result.rows.count { it.unmeasured }} unmeasured" +
                    (result.note?.let { " ($it)" } ?: "")
            )
        }
        results.flatMap { it.rows }.filter { !it.passes && it.gated }.sortedBy { it.ratio }
            .forEach { row ->
                println(
                    "contrast FAIL ${row.mode}/${row.theme} ${row.screen} ${row.viewId} " +
                        "'${row.text}' fg=${Contrast.hex(row.fg)} bg=${Contrast.hex(row.bg)} " +
                        "ratio=%.2f < %.1f".format(row.ratio, row.threshold)
                )
            }
    }

    private fun isButton(className: String): Boolean = try {
        android.widget.Button::class.java.isAssignableFrom(Class.forName(className))
    } catch (_: ClassNotFoundException) {
        false
    }
}
