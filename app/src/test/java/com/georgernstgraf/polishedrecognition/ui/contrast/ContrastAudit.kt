package com.georgernstgraf.polishedrecognition.ui.contrast

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * #161: walks a laid-out view tree and measures the WCAG contrast of every painted text.
 *
 * REPORT MODE: this file measures and formats, it never asserts. The measured table is
 * the input for the next wave (#162 pixels, #163 colour pairs).
 */
object ContrastAudit {

    /** One measured text view — the row of the report table. */
    data class Row(
        val screen: String,
        val mode: String,
        val theme: String,
        val viewId: String,
        val text: String,
        val fg: Int,
        val bg: Int,
        val ratio: Double,
        val threshold: Double,
        val kind: String,
        val disabled: Boolean,
        /**
         * The host could not measure this row: in night mode Robolectric resolves a Material
         * button's background to opaque white (its 9-patch/tint chain has no colour of its
         * own, and no `backgroundTintList` is exposed), so white text lands on white. The
         * device paints `?android:attr/colorButtonNormal` there instead. Reported, never
         * asserted, never counted as a violation.
         */
        val suspect: Boolean = false,
    ) {
        val passes: Boolean get() = ratio >= threshold
    }

    /** A row below its threshold. */
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
    )

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
            val large = Contrast.isLargeText(view.textSize, isBold(view), scaledDensity)
            val disabled = !view.isEnabled
            val threshold = Contrast.thresholdFor(large, disabled)
            val id = viewIdName(view)

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
                    text = snippet(shown),
                    fg = candidate.color,
                    bg = background,
                    ratio = Contrast.contrastRatio(visibleFg, background),
                    threshold = threshold,
                    kind = candidate.kind,
                    disabled = disabled,
                    suspect = isSuspect(view, background),
                )
            }
        }
        val violations = rows.filter { !it.passes && !it.suspect }.map {
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
                String.format(java.util.Locale.ROOT, "%.2f", it.ratio),
                String.format(java.util.Locale.ROOT, "%.1f", it.threshold),
                when {
                    it.suspect -> "SUSPECT"
                    it.passes -> "PASS"
                    else -> "FAIL"
                },
            )
        }
        val widths = header.indices.map { column ->
            // The text column is the only unbounded one; snippet() already caps it.
            (body.map { it[column] } + header[column]).maxOf { it.length }
        }
        val out = StringBuilder()
        out.appendLine("# WCAG contrast audit — report mode (#161)")
        out.appendLine("# ratio = composited foreground vs. composited background, WCAG 2.1")
        out.appendLine("# threshold = 3.0 for large/disabled text, else 4.5")
        out.appendLine("# rows: ${rows.size}, failing: ${rows.count { !it.passes && !it.suspect }}," +
            " suspect: ${rows.count { it.suspect }}")
        out.appendLine("# SUSPECT = not measurable on the host: in night mode Robolectric resolves a")
        out.appendLine("#   Material button background to opaque white (no colour of its own, no")
        out.appendLine("#   backgroundTintList), so white text lands on white. The device paints")
        out.appendLine("#   ?android:attr/colorButtonNormal there - see the panel-background legend.")
        out.appendLine()
        out.appendLine(formatLine(header, widths))
        out.appendLine(formatLine(widths.map { "-".repeat(it) }, widths))
        body.forEach { out.appendLine(formatLine(it, widths)) }

        out.appendLine()
        out.appendLine("## Per-screen counts")
        val screenHeader =
            listOf("screen", "mode", "theme", "textviews", "failing", "suspect", "worst", "note")
        val screenBody = results.map {
            listOf(
                screenOf(it),
                modeOf(it),
                themeOf(it),
                it.textViews.toString(),
                it.violations.size.toString(),
                it.rows.count { row -> row.suspect }.toString(),
                it.rows.filter { row -> !row.suspect }.minOfOrNull { row -> row.ratio }
                    ?.let { r -> String.format(java.util.Locale.ROOT, "%.2f", r) } ?: "-",
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

    private fun isBold(tv: TextView): Boolean =
        tv.typeface?.isBold == true || tv.paint.isFakeBoldText

    /**
     * A row the host cannot measure: in night mode NO real surface is pure white, so an
     * opaque white background is Robolectric's stand-in for the Material button fill.
     */
    private fun isSuspect(view: View, background: Int): Boolean {
        val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return night && background == Color.WHITE
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or (alpha shl 24)

    private fun snippet(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= MAX_SNIPPET) flat else flat.take(MAX_SNIPPET - 2) + ".."
    }

    private fun viewIdName(view: View): String {
        if (view.id == View.NO_ID) return view.javaClass.simpleName
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Resources.NotFoundException) {
            view.javaClass.simpleName
        }
    }

    private const val MAX_SNIPPET = 30
}
