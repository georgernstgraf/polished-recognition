package com.georgernstgraf.polishedrecognition.ui.contrast

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * #161: walks a laid-out view tree and measures the WCAG contrast of every painted text.
 *
 * This file measures and formats, it never asserts — the gate lives in [ContrastGate], so
 * the app-theme runs can be enforced while the OEM runs stay report-only.
 */
object ContrastAudit {

    /** One measured text view — the row of the report table. */
    data class Row(
        val screen: String,
        val mode: String,
        val theme: String,
        val viewId: String,
        val viewClass: String,
        val text: String,
        val fg: Int,
        val bg: Int,
        val ratio: Double,
        val threshold: Double,
        val kind: String,
        val disabled: Boolean,
        /**
         * The host could not resolve this row's background: Robolectric substitutes opaque
         * white for the theme reference inside the platform `Button` fill (ripple → inset →
         * gradient), while the device paints `?android:attr/colorButtonNormal`. The ratio is
         * reported for triage but is fiction, so it never counts as a violation.
         *
         * Only `android.widget.Button` may be UNMEASURED — every other view with an
         * unresolved background is a failure (see [Violation.KIND_UNRESOLVED_BACKGROUND]).
         */
        val unmeasured: Boolean = false,
    ) {
        val passes: Boolean get() = ratio >= threshold

        /** Reported, never gated — the number cannot be trusted. */
        val gated: Boolean get() = !unmeasured
    }

    /** A row below its threshold, or a row whose background the host could not resolve. */
    data class Violation(
        val screen: String,
        val mode: String,
        val theme: String,
        val viewId: String,
        val textSnippet: String,
        val fgHex: String,
        val bgHex: String,
        val ratio: Double,
        val threshold: Double,
        val kind: String,
    ) {
        companion object {
            /**
             * The background could not be resolved from the view's drawables on a view that
             * is NOT a platform `Button`. Unlike the button fill this is not a known host
             * artefact, so it fails the gate regardless of the computed ratio.
             */
            const val KIND_UNRESOLVED_BACKGROUND = "unresolved-bg"
        }
    }

    /**
     * [textViews] counts the text views that were actually measured (rows), i.e. the proof
     * that the walk matched something. [note] carries a screen-level remark such as
     * "no content view — nothing to audit"; [screen]/[mode]/[theme] keep that screen in the
     * summary even when it produced no row at all.
     */
    data class AuditResult(
        val screen: String,
        val mode: String,
        val theme: String,
        val textViews: Int,
        val violations: List<Violation>,
        val rows: List<Row> = emptyList(),
        val note: String? = null,
        /** False for a screen that legitimately shows no text (e.g. a permission request). */
        val expectsText: Boolean = true,
    )

    /**
     * Measures every visible, non-empty text view under [root].
     *
     * Skipped: `GONE`/`INVISIBLE` views (own or inherited), zero-size rects (not laid out),
     * and views that paint no text at all (empty [TextView] without a hint).
     */
    fun audit(root: View, screen: String, mode: String, theme: String): AuditResult {
        val rows = mutableListOf<Row>()
        walk(root) { view ->
            if (view !is TextView) return@walk
            if (!isEffectivelyVisible(view)) return@walk
            if (view.width <= 0 || view.height <= 0) return@walk

            val candidates = ViewColors.textCandidates(view)
            if (candidates.isEmpty()) return@walk

            val background = ViewColors.effectiveBackground(view)
            val viewAlpha = ViewColors.effectiveAlpha(view)
            val scaledDensity = view.resources.displayMetrics.density *
                view.resources.configuration.fontScale
            val large = Contrast.isLargeText(view.textSize, AuditFormat.isBold(view), scaledDensity)
            val disabled = !view.isEnabled
            val threshold = Contrast.thresholdFor(large, disabled)
            val id = AuditFormat.viewIdName(view)
            val viewClass = view.javaClass.name
            // The one host artefact this audit excuses: the platform Button fill, whose theme
            // reference Robolectric cannot evaluate. Anywhere else an unresolved background
            // is a real defect and fails the gate (see `violations` below).
            val unresolved = ViewColors.isUnresolvableButtonFill(view)
            val unmeasured = unresolved && view is Button

            for (candidate in candidates) {
                // The colour a user sees: the resolved colour, faded by the container chain.
                val faded = withAlpha(
                    candidate.color,
                    (Color.alpha(candidate.color) * viewAlpha).toInt().coerceIn(0, 255)
                )
                val visibleFg = Contrast.compositeOver(faded, background)
                val shown = if (candidate.kind == ViewColors.KIND_HINT) view.hint?.toString().orEmpty()
                else view.text?.toString().orEmpty()
                rows += Row(
                    screen = screen,
                    mode = mode,
                    theme = theme,
                    viewId = id,
                    viewClass = viewClass,
                    text = AuditFormat.snippet(shown),
                    fg = candidate.color,
                    bg = background,
                    ratio = Contrast.contrastRatio(visibleFg, background),
                    threshold = threshold,
                    kind = if (unresolved && !unmeasured) Violation.KIND_UNRESOLVED_BACKGROUND
                    else candidate.kind,
                    disabled = disabled,
                    unmeasured = unmeasured,
                )
            }
        }
        // Two ways to violate: a measured row below its threshold, and an unresolved
        // background on anything that is not a platform Button (ratio-independent).
        val violations = rows.filter {
            it.kind == Violation.KIND_UNRESOLVED_BACKGROUND || (!it.passes && it.gated)
        }.map {
            Violation(
                screen = it.screen,
                mode = it.mode,
                theme = it.theme,
                viewId = it.viewId,
                textSnippet = it.text,
                fgHex = Contrast.hex(it.fg),
                bgHex = Contrast.hex(it.bg),
                ratio = it.ratio,
                threshold = it.threshold,
                kind = it.kind,
            )
        }
        return AuditResult(
            screen = screen,
            mode = mode,
            theme = theme,
            textViews = rows.size,
            violations = violations,
            rows = rows,
        )
    }

    /**
     * Fixed-width table, worst ratio first, one row per measured text view, followed by a
     * per-screen summary. [notes] are appended verbatim (panel backgrounds, screen remarks).
     */
    fun formatTable(results: List<AuditResult>, notes: List<String> = emptyList()): String {
        val rows = results.flatMap { it.rows }.sortedBy { it.ratio }
        val header = listOf(
            "screen", "mode", "theme", "id", "text", "fg", "bg", "ratio", "thr", "verdict"
        )
        val body = rows.map {
            listOf(
                it.screen,
                it.mode,
                it.theme,
                it.viewId,
                it.text,
                Contrast.hex(it.fg),
                Contrast.hex(it.bg),
                Contrast.formatRatio(it.ratio),
                Contrast.formatThreshold(it.threshold),
                when {
                    it.unmeasured -> "UNMEASURED"
                    it.passes -> "PASS"
                    else -> "FAIL"
                },
            )
        }
        val widths = header.indices.map { column ->
            // The text column is the only unbounded one; AuditFormat.snippet() already caps it.
            (body.map { it[column] } + header[column]).maxOf { it.length }
        }
        val out = StringBuilder()
        out.appendLine("# WCAG contrast audit — enforced gate (#161)")
        out.appendLine("# ratio = composited foreground vs. composited background, WCAG 2.1")
        out.appendLine("# threshold = 3.0 for large/disabled text, else 4.5")
        out.appendLine("# rows: ${rows.size}, failing: ${rows.count { !it.passes && it.gated }}," +
            " unmeasured: ${rows.count { it.unmeasured }}")
        out.appendLine("# UNMEASURED = the host could not resolve the background: Robolectric")
        out.appendLine("#   substitutes opaque white for the theme reference inside the platform")
        out.appendLine("#   Button fill (ripple -> inset -> gradient), while the device paints")
        out.appendLine("#   ?android:attr/colorButtonNormal. Only android.widget.Button may be")
        out.appendLine("#   UNMEASURED; covered by the pixel test #162.")
        out.appendLine()
        out.appendLine(formatLine(header, widths))
        out.appendLine(formatLine(widths.map { "-".repeat(it) }, widths))
        body.forEach { out.appendLine(formatLine(it, widths)) }

        out.appendLine()
        out.appendLine("## Per-screen counts")
        val screenHeader =
            listOf("screen", "mode", "theme", "textviews", "failing", "unmeasured", "worst", "note")
        val screenBody = results.map {
            listOf(
                screenOf(it),
                modeOf(it),
                themeOf(it),
                it.textViews.toString(),
                it.violations.size.toString(),
                it.rows.count { row -> row.unmeasured }.toString(),
                it.rows.filter { row -> row.gated }.minOfOrNull { row -> row.ratio }
                    ?.let { r -> Contrast.formatRatio(r) } ?: "-",
                it.note.orEmpty(),
            )
        }
        val screenWidths = screenHeader.indices.map { column ->
            (screenBody.map { it[column] } + screenHeader[column]).maxOf { it.length }
        }
        out.appendLine(formatLine(screenHeader, screenWidths))
        out.appendLine(formatLine(screenWidths.map { "-".repeat(it) }, screenWidths))
        screenBody.forEach { out.appendLine(formatLine(it, screenWidths)) }

        if (notes.isNotEmpty()) {
            out.appendLine()
            out.appendLine("## Panel backgrounds")
            notes.forEach { out.appendLine("  $it") }
        }

        // The UNMEASURED rows grouped by widget class — the audit excuses exactly one class,
        // so this section is also the proof that the excusing rule held.
        val unmeasured = rows.filter { it.unmeasured }
        if (unmeasured.isNotEmpty()) {
            out.appendLine()
            out.appendLine("## Unmeasured rows (host limitation, covered by the pixel test #162)")
            unmeasured.groupBy { it.viewClass }.toSortedMap().forEach { (viewClass, group) ->
                val themes = group.groupingBy { it.theme }.eachCount()
                    .entries.joinToString(", ") { "${it.key}=${it.value}" }
                out.appendLine("  $viewClass  rows=${group.size}  ($themes)")
            }
        }
        return out.toString()
    }

    private fun screenOf(result: AuditResult): String = result.screen

    private fun modeOf(result: AuditResult): String = result.mode

    private fun themeOf(result: AuditResult): String = result.theme

    private fun formatLine(cells: List<String>, widths: List<Int>): String =
        cells.mapIndexed { index, cell -> cell.padEnd(widths[index]) }.joinToString(" | ")

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }

    private fun isEffectivelyVisible(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or (alpha shl 24)
}
