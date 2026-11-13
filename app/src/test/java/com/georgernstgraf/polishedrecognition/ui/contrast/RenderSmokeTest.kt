package com.georgernstgraf.polishedrecognition.ui.contrast

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * #162 — render smoke test: the text must be VISIBLE PIXELS, not merely a correctly coloured
 * view in the tree.
 *
 * #161 walks the view tree and compares resolved colours. That cannot see text which is
 * clipped, zero-sized, covered by a sibling, faded by `alpha = 0` on a parent, or painted
 * under the navigation bar (#157) — in all those cases the colours are right and nothing is
 * on screen. This test draws the real screen into a bitmap and measures the pixels.
 *
 * **Opt-in.** `-Pcontrast.render=true` sets the `contrast.render` system property (see
 * `app/build.gradle.kts`). Without it every test here is SKIPPED, never failed: native
 * rasterisation costs a few seconds and must not sit in the required suite.
 *
 * **Determinism.** The decor is measured and laid out at a fixed 1080 × 2400 px, so the
 * bitmap never depends on the host window. The IME bar is `wrap_content`, so it is measured
 * with an AT_MOST height and laid out at its own measured height.
 *
 * **What a box must show**, per visible non-empty text view:
 *  1. at least [MIN_CLUSTERS] distinguishable luminance clusters — a single flat colour means
 *     the text did not render at all;
 *  2. a maximum pixel contrast of at least the #161 WCAG floor (4.5 normal / 3.0 large /
 *     3.0 disabled).
 *
 * The synthetic tests at the bottom are the proof that the detector detects: each one breaks
 * a text view in exactly one way and expects a flag, plus one healthy tree that must NOT be
 * flagged (no false positive).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderSmokeTest {

    @Before
    fun setUp() {
        ContrastMatrix.clearPrefs()
    }

    @After
    fun tearDown() {
        ContrastMatrix.clearPrefs()
    }

    // ---------------------------------------------------------------- real screens

    @Test
    @Config(qualifiers = "notnight")
    fun `light mode - every text view paints visible pixels`() {
        assumeTrue(System.getProperty("contrast.render") == "true")
        assertScreens(ContrastMatrix.MODE_LIGHT)
    }

    @Test
    @Config(qualifiers = "night")
    fun `night mode - every text view paints visible pixels`() {
        assumeTrue(System.getProperty("contrast.render") == "true")
        assertScreens(ContrastMatrix.MODE_NIGHT)
    }

    // ---------------------------------------------------------------- synthetic self-tests

    /** `alpha = 0` on the text view itself: the view is there, its pixels are not. */
    @Test
    @Config(qualifiers = "notnight")
    fun `synthetic - alpha 0 on the text view is detected`() {
        assumeTrue(System.getProperty("contrast.render") == "true")
        val context = syntheticContext()
        val tree = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        tree.addView(syntheticText(context).apply { alpha = 0f }, matchWidth())
        val sample = renderAndMeasure(tree).single()
        assertFlagged(sample, "alpha=0 on the text view")
    }

    /** `alpha = 0` on the PARENT: the text view itself is untouched, the container fades it. */
    @Test
    @Config(qualifiers = "notnight")
    fun `synthetic - alpha 0 on the parent is detected`() {
        assumeTrue(System.getProperty("contrast.render") == "true")
        val context = syntheticContext()
        val tree = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        val parent = FrameLayout(context).apply { alpha = 0f }
        parent.addView(syntheticText(context), matchWidth())
        tree.addView(parent, matchWidth())
        val sample = renderAndMeasure(tree).single()
        assertFlagged(sample, "alpha=0 on the parent")
    }

    /** `height = 0`: laid out, visible, non-empty text — and not a single pixel tall. */
    @Test
    @Config(qualifiers = "notnight")
    fun `synthetic - zero height is detected`() {
        assumeTrue(System.getProperty("contrast.render") == "true")
        val context = syntheticContext()
        val tree = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        tree.addView(syntheticText(context), matchWidth(height = 0))
        val sample = renderAndMeasure(tree).single()
        assertFlagged(sample, "height=0")
    }

    /** An opaque sibling drawn over the text: the colours are fine, the pixels are the sibling's. */
    @Test
    @Config(qualifiers = "notnight")
    fun `synthetic - a sibling covering the text is detected`() {
        assumeTrue(System.getProperty("contrast.render") == "true")
        val context = syntheticContext()
        val tree = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        tree.addView(syntheticText(context), matchWidth())
        tree.addView(
            View(context).apply { setBackgroundColor(Color.WHITE) },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val sample = renderAndMeasure(tree).single()
        assertFlagged(sample, "sibling drawn over the text")
    }

    /** The control: a healthy text view must pass — otherwise the detector is worthless. */
    @Test
    @Config(qualifiers = "notnight")
    fun `synthetic - a visible text view is not flagged`() {
        assumeTrue(System.getProperty("contrast.render") == "true")
        val context = syntheticContext()
        val tree = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        tree.addView(syntheticText(context), matchWidth())
        val sample = renderAndMeasure(tree).single()

        assertWithMessage("a healthy text view must not be flagged: ${sample.line()}")
            .that(sample.problem).isNull()
        assertWithMessage("the text must rasterise into >= $MIN_CLUSTERS clusters: ${sample.line()}")
            .that(sample.clusters).isAtLeast(MIN_CLUSTERS)
        assertWithMessage("black on white must reach the WCAG floor: ${sample.line()}")
            .that(sample.maxRatio).isAtLeast(sample.threshold)
    }

    // ---------------------------------------------------------------- the real screens

    private fun assertScreens(mode: String) {
        val themeRes = ContrastMatrix.themeRes(mode, ContrastMatrix.THEME_APP)
        val screens = ContrastMatrix.screens(themeRes, RENDERED_SCREENS)

        val samples = mutableListOf<Sample>()
        val perScreen = mutableListOf<String>()

        for (screen in screens) {
            val root = screen.root
            if (root == null) {
                perScreen += "${screen.name}: nothing to render (${screen.note})"
                continue
            }
            // The decor, not the content view: the window background is part of what the
            // user sees, and a text view drawn over it needs it as its backdrop.
            val decor = root.rootView
            layoutForRender(decor, screen.name == ContrastMatrix.SCREEN_IME)
            val bitmap = render(decor)
            val backdrop = backdropOf(decor)
            val measured = measure(screen.name, mode, decor, bitmap, backdrop)
            samples += measured
            val visible = measured.count { it.measured }
            perScreen += "${screen.name}: ${decor.width}x${decor.height}px, " +
                "$visible/${measured.size} text views measured"
        }

        assertWithMessage("no screen was rendered for $mode").that(screens).isNotEmpty()

        val failures = samples.filter { !it.ok }
        assertWithMessage(buildString {
            appendLine("$mode: ${failures.size} text view(s) do not paint visible text")
            failures.forEach { appendLine("  ${it.line()}") }
            appendLine("measured: ${samples.count { it.measured }}/${samples.size}")
            perScreen.forEach { appendLine("  $it") }
        }).that(failures).isEmpty()

        // The walk must have matched something on every rendered screen — a screen that
        // silently produces no box at all would make this test vacuous.
        screens.filter { it.root != null }.forEach { screen ->
            val boxes = samples.filter { it.screen == screen.name }
            assertWithMessage("${screen.name} [$mode] produced no text view at all")
                .that(boxes).isNotEmpty()
            assertWithMessage("${screen.name} [$mode] rendered no measurable text box")
                .that(boxes.count { it.measured }).isGreaterThan(0)
        }

        writeReport(mode, samples, perScreen)
    }

    // ---------------------------------------------------------------- rendering

    /**
     * Fixed geometry: 1080 × 2400 px with EXACTLY specs, so the bitmap cannot depend on the
     * host window. The IME bar is `wrap_content` — an EXACTLY height would stretch it to a
     * full screen it never occupies on a device.
     */
    private fun layoutForRender(decor: View, wrapHeight: Boolean) {
        val width = View.MeasureSpec.makeMeasureSpec(RENDER_WIDTH, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(
            RENDER_HEIGHT,
            if (wrapHeight) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY
        )
        decor.measure(width, height)
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
    }

    private fun render(root: View): Bitmap {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * What the window shows behind the content — the colour transparent pixels are composited
     * over before the luminance arithmetic.
     *
     * `?android:attr/windowBackground` is NOT always a colour: the platform Dialog theme points
     * it at a DRAWABLE (`@drawable/screen_background_light`, id `0x01080290`), and
     * [ViewColors.themeWindowBackgroundColor] then throws `Resources$NotFoundException` from
     * `TypedArray.getColorStateList`. Fall back to `?android:attr/colorBackground`, which is a
     * colour in every Material theme.
     */
    private fun backdropOf(decor: View): Int {
        val fromTheme = try {
            ViewColors.themeWindowBackgroundColor(decor.context)
        } catch (_: Resources.NotFoundException) {
            ViewColors.themeBackgroundColor(decor.context)
        }
        if (Contrast.alpha(fromTheme) != 0) return fromTheme
        return ViewColors.drawableColor(decor.background, decor.drawableState) ?: Color.WHITE
    }

    // ---------------------------------------------------------------- pixel measurement

    /**
     * One text view's pixel verdict. [measured] is false when the box lies entirely outside
     * the rendered window (scrollable content below the fold) — reported, not gated.
     */
    private data class Sample(
        val screen: String,
        val mode: String,
        val viewId: String,
        val text: String,
        val box: String,
        val clusters: Int,
        val maxRatio: Double,
        val threshold: Double,
        val measured: Boolean,
        val problem: String?,
    ) {
        val ok: Boolean get() = problem == null

        fun line(): String = String.format(
            Locale.ROOT,
            "%s [%s] %s box=%s clusters=%d maxRatio=%.2f threshold=%.1f%s",
            screen, mode, viewId, box, clusters, maxRatio, threshold,
            when {
                problem != null -> " PROBLEM: $problem"
                !measured -> " (outside the rendered window)"
                else -> " OK"
            }
        )
    }

    private fun measure(
        screen: String,
        mode: String,
        root: View,
        bitmap: Bitmap,
        backdrop: Int,
    ): List<Sample> {
        val samples = mutableListOf<Sample>()
        walk(root, 0, 0, true) { view, x, y, visible ->
            if (view !is TextView || !visible) return@walk
            val shown = view.text?.toString().orEmpty()
            if (shown.isEmpty()) return@walk

            val id = viewIdName(view)
            val threshold = thresholdFor(view)
            val box = "($x,$y)-(${x + view.width},${y + view.height})"

            if (view.width <= 0 || view.height <= 0) {
                samples += Sample(
                    screen, mode, id, snippet(shown), box, 0, 1.0, threshold,
                    measured = false,
                    problem = "zero-size ${view.width}x${view.height}px",
                )
                return@walk
            }

            val left = maxOf(x, 0)
            val top = maxOf(y, 0)
            val right = minOf(x + view.width, bitmap.width)
            val bottom = minOf(y + view.height, bitmap.height)
            if (right <= left || bottom <= top) {
                samples += Sample(
                    screen, mode, id, snippet(shown), box, 0, 1.0, threshold,
                    measured = false, problem = null,
                )
                return@walk
            }

            val pixels = samplePixels(bitmap, left, top, right, bottom, backdrop)
            samples += Sample(
                screen = screen,
                mode = mode,
                viewId = id,
                text = snippet(shown),
                box = box,
                clusters = pixels.clusters,
                maxRatio = pixels.maxRatio,
                threshold = threshold,
                measured = true,
                problem = when {
                    pixels.clusters < MIN_CLUSTERS ->
                        "only ${pixels.clusters} luminance cluster(s) - the text did not render"
                    pixels.maxRatio < threshold ->
                        String.format(
                            Locale.ROOT,
                            "max contrast %.2f is below the %.1f floor",
                            pixels.maxRatio, threshold
                        )
                    else -> null
                },
            )
        }
        return samples
    }

    private class PixelStats(val clusters: Int, val maxRatio: Double)

    /** Per-test memo of [Contrast.relativeLuminance] — see [luminanceOf]. */
    private val luminanceCache = HashMap<Int, Double>()

    /**
     * Reads the box and returns its cluster count and its MAX pixel contrast.
     *
     * Transparent pixels are composited over [backdrop] first — that is what the device shows
     * behind the window, and it keeps the luminance arithmetic on opaque colours.
     *
     * "Max contrast" is the ratio between the lightest and the darkest pixel of the box, i.e.
     * the best contrast the box offers at all. A box that paints nothing is flat, so it scores
     * exactly 1.0 and fails both checks.
     */
    private fun samplePixels(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        backdrop: Int,
    ): PixelStats {
        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        val buckets = IntArray(CLUSTER_BUCKETS)
        var darkest = 0
        var lightest = 0
        var darkestLum = Double.MAX_VALUE
        var lightestLum = -1.0

        for (pixel in pixels) {
            val opaque =
                if (Contrast.alpha(pixel) == 255) pixel else Contrast.compositeOver(pixel, backdrop)
            val luminance = luminanceOf(opaque)
            buckets[(luminance * CLUSTER_BUCKETS).toInt().coerceIn(0, CLUSTER_BUCKETS - 1)]++
            if (luminance < darkestLum) {
                darkestLum = luminance
                darkest = opaque
            }
            if (luminance > lightestLum) {
                lightestLum = luminance
                lightest = opaque
            }
        }

        // A cluster must hold a real share of the box: antialiasing spreads a glyph edge over
        // dozens of intermediate luminances, each far below this floor.
        val minShare = maxOf(1, pixels.size / CLUSTER_SHARE_DIVISOR)
        return PixelStats(
            clusters = buckets.count { it >= minShare },
            maxRatio = Contrast.contrastRatio(lightest, darkest),
        )
    }

    /**
     * [Contrast.relativeLuminance] memoised per colour: a rendered box holds a few hundred
     * distinct colours (ink, background, antialiasing) but tens of thousands of pixels, and
     * the three `pow()` calls per pixel dominated the runtime of this class.
     */
    private fun luminanceOf(argb: Int): Double =
        luminanceCache.getOrPut(argb) { Contrast.relativeLuminance(argb) }

    // ---------------------------------------------------------------- synthetic trees

    private fun syntheticContext(): Context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        android.R.style.Theme_Material_Light
    )

    private fun syntheticText(context: Context): TextView = TextView(context).apply {
        text = SYNTHETIC_TEXT
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.WHITE)
        textSize = 14f
        setPadding(8, 8, 8, 8)
    }

    private fun matchWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun renderAndMeasure(tree: FrameLayout): List<Sample> {
        // EXACTLY on both axes: the zero-height case must still produce a bitmap to measure.
        tree.measure(
            View.MeasureSpec.makeMeasureSpec(SYNTHETIC_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SYNTHETIC_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        tree.layout(0, 0, tree.measuredWidth, tree.measuredHeight)
        return measure("synthetic", "light", tree, render(tree), Color.WHITE)
    }

    private fun assertFlagged(sample: Sample, what: String) {
        assertWithMessage("$what must be detected as invisible text: ${sample.line()}")
            .that(sample.ok).isFalse()
    }

    // ---------------------------------------------------------------- helpers

    private fun walk(
        view: View,
        x: Int,
        y: Int,
        visible: Boolean,
        visit: (View, Int, Int, Boolean) -> Unit,
    ) {
        val shown = visible && view.visibility == View.VISIBLE
        visit(view, x, y, shown)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                // dispatchDraw translates by -scrollX/-scrollY before drawing the children.
                walk(
                    child,
                    x + child.left - view.scrollX,
                    y + child.top - view.scrollY,
                    shown,
                    visit,
                )
            }
        }
    }

    private fun thresholdFor(view: TextView): Double {
        val scaledDensity = view.resources.displayMetrics.density *
            view.resources.configuration.fontScale
        val bold = view.typeface?.isBold == true || view.paint.isFakeBoldText
        return Contrast.thresholdFor(
            Contrast.isLargeText(view.textSize, bold, scaledDensity),
            !view.isEnabled,
        )
    }

    private fun viewIdName(view: View): String {
        if (view.id == View.NO_ID) return view.javaClass.simpleName
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Resources.NotFoundException) {
            view.javaClass.simpleName
        }
    }

    private fun snippet(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= MAX_SNIPPET) flat else flat.take(MAX_SNIPPET - 2) + ".."
    }

    /**
     * `build/render-smoke-report-<mode>.txt` — the per-box evidence. Written per mode so the
     * five determinism runs can be diffed byte for byte.
     */
    private fun writeReport(mode: String, samples: List<Sample>, perScreen: List<String>) {
        val dir = File("build")
        dir.mkdirs()
        val text = buildString {
            appendLine("# generated by RenderSmokeTest (#162)")
            appendLine("# geometry: $RENDER_WIDTH x $RENDER_HEIGHT px, EXACTLY specs (IME: AT_MOST height)")
            appendLine("# mode: $mode, theme: ${ContrastMatrix.THEME_APP}")
            appendLine("# cluster buckets: $CLUSTER_BUCKETS, min share: 1/${CLUSTER_SHARE_DIVISOR} of the box")
            appendLine("# maxRatio = contrast between the lightest and the darkest pixel of the box")
            appendLine()
            perScreen.forEach { appendLine(it) }
            appendLine()
            samples.sortedWith(compareBy({ it.maxRatio }, { it.viewId })).forEach {
                appendLine(it.line())
            }
        }
        File(dir, "render-smoke-report-$mode.txt").writeText(text)
    }

    private companion object {
        /** The rendered window: a 1080 × 2400 px phone, independent of the host window. */
        const val RENDER_WIDTH = 1080
        const val RENDER_HEIGHT = 2400

        const val SYNTHETIC_WIDTH = 320
        const val SYNTHETIC_HEIGHT = 160
        const val SYNTHETIC_TEXT = "Synthetic text"

        /** Text plus background — one flat colour means nothing was painted. */
        const val MIN_CLUSTERS = 2

        /** 32 luminance buckets; a cluster must hold at least 1/1000 of the box. */
        const val CLUSTER_BUCKETS = 32
        const val CLUSTER_SHARE_DIVISOR = 1000

        const val MAX_SNIPPET = 30

        /**
         * The three screens this issue names. SettingsHintActivity (a second dialog) and
         * MicrophonePermissionActivity (no content view) stay with the #161 contrast matrix:
         * they add no pixel coverage this test needs, and building them would cost another
         * activity launch per mode.
         */
        val RENDERED_SCREENS = setOf(
            ContrastMatrix.SCREEN_SETTINGS,
            ContrastMatrix.SCREEN_CRASH,
            ContrastMatrix.SCREEN_IME,
        )
    }
}
