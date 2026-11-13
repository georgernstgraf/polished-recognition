package com.georgernstgraf.polishedrecognition.ui.contrast

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.ui.CrashDialogActivity
import com.georgernstgraf.polishedrecognition.ui.MicrophonePermissionActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsHintActivity
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/**
 * #161: the screen × theme matrix, shared by the light and the night audit test.
 *
 * Holds no assertions — it only builds the screens and hands them to [ContrastAudit].
 * Two theme families are measured per run:
 *
 *  - `app` — `Theme.PolishedRecognition.Plain` (DayNight; the @Config qualifier picks the mode)
 *  - `oem` — `android.R.style.Theme_Material` / `Theme_Material_Light`, whose `textColor*`
 *    attributes are `@color` SELECTORS, i.e. the OxygenOS/ColorOS shape that hid #160
 */
object ContrastMatrix {

    const val MODE_LIGHT = "light"
    const val MODE_NIGHT = "night"

    const val THEME_APP = "app"
    const val THEME_OEM = "oem"

    const val SCREEN_SETTINGS = "SettingsActivity"
    const val SCREEN_CRASH = "CrashDialogActivity"
    const val SCREEN_HINT = "SettingsHintActivity"
    const val SCREEN_IME = "ImeVoiceInput"
    const val SCREEN_MIC = "MicrophonePermissionActivity"

    /** A screen to audit: its root view, or a [note] when it has nothing to measure. */
    data class Screen(
        val name: String,
        val root: View?,
        val note: String? = null,
        val requireText: Boolean = true,
    )

    /** Clears the settings prefs — Robolectric shares them across tests in one sandbox (#142). */
    fun clearPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
            .edit().clear().commit()
    }

    /** The OEM theme of a mode: the dark one has selector-based text colours too. */
    fun oemTheme(mode: String): Int =
        if (mode == MODE_NIGHT) android.R.style.Theme_Material
        else android.R.style.Theme_Material_Light

    fun themeRes(mode: String, theme: String): Int =
        when (theme) {
            THEME_APP -> R.style.Theme_PolishedRecognition_Plain
            else -> oemTheme(mode)
        }

    /** Audits every screen of the matrix for one (mode, theme) pair. */
    fun audits(mode: String, theme: String): List<ContrastAudit.AuditResult> {
        val themeRes = themeRes(mode, theme)
        return screens(themeRes).map { screen ->
            if (screen.root == null) {
                ContrastAudit.AuditResult(
                    screen = screen.name,
                    mode = mode,
                    theme = theme,
                    textViews = 0,
                    violations = emptyList(),
                    note = screen.note,
                    expectsText = screen.requireText,
                )
            } else {
                val result = ContrastAudit.audit(screen.root, screen.name, mode, theme)
                result.copy(note = screen.note, expectsText = screen.requireText)
            }
        }
    }

    /**
     * Builds the five screens under [themeRes]. [names] restricts the build to a subset —
     * the pixel test (#162) renders only three of them and must not pay for launching the
     * two it does not measure.
     */
    fun screens(themeRes: Int, names: Set<String>? = null): List<Screen> {
        val builders: List<Pair<String, () -> Screen>> = listOf(
            SCREEN_SETTINGS to { settings(themeRes) },
            SCREEN_CRASH to { crashDialog(themeRes) },
            SCREEN_HINT to { settingsHint(themeRes) },
            SCREEN_IME to { ime(themeRes) },
            SCREEN_MIC to { microphonePermission(themeRes) },
        )
        return builders
            .filter { names == null || it.first in names }
            .map { it.second() }
    }

    private fun settings(themeRes: Int): Screen {
        val activity = launch(SettingsActivity::class.java, themeRes)
        return Screen(SCREEN_SETTINGS, activity.findViewById(android.R.id.content))
    }

    /** The crash text lives in the AlertDialog's window, not in the activity's content view. */
    private fun crashDialog(themeRes: Int): Screen {
        launch(CrashDialogActivity::class.java, themeRes)
        val decor = ShadowAlertDialog.getLatestAlertDialog()?.window?.decorView
        return Screen(SCREEN_CRASH, decor, note = if (decor == null) NO_DIALOG else null)
    }

    /** Dialog only — the activity never calls setContentView. */
    private fun settingsHint(themeRes: Int): Screen {
        launch(SettingsHintActivity::class.java, themeRes)
        val decor = ShadowAlertDialog.getLatestAlertDialog()?.window?.decorView
        return Screen(SCREEN_HINT, decor, note = if (decor == null) NO_DIALOG else null)
    }

    /**
     * The IME has no activity: inflate the real layout under a themed context and lay it out
     * by hand, because an unattached view has no size and the audit skips zero-size rects.
     */
    private fun ime(themeRes: Int): Screen {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), themeRes)
        val root = LayoutInflater.from(context).inflate(R.layout.ime_voice_input, null)
        val metrics = context.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return Screen(SCREEN_IME, root)
    }

    /**
     * Requests a runtime permission and shows NO content: there is nothing to audit. Recorded
     * explicitly instead of being silently dropped from the matrix.
     */
    private fun microphonePermission(themeRes: Int): Screen {
        launch(MicrophonePermissionActivity::class.java, themeRes)
        return Screen(
            SCREEN_MIC,
            root = null,
            note = "no content view - nothing to audit",
            requireText = false,
        )
    }

    private fun <T : Activity> launch(activityClass: Class<T>, themeRes: Int): T {
        val controller = Robolectric.buildActivity(activityClass)
        controller.get().setTheme(themeRes)
        return controller.setup().get()
    }

    /** The window/panel colours actually resolved per theme — the report's backdrop legend. */
    fun panelNotes(mode: String): List<String> {
        val app = RuntimeEnvironment.getApplication()
        return listOf(THEME_APP, THEME_OEM).map { theme ->
            val context = ContextThemeWrapper(app, themeRes(mode, theme))
            "$mode/$theme  windowBackground=" +
                Contrast.hex(ViewColors.themeWindowBackgroundColor(context)) +
                "  colorBackground=" +
                Contrast.hex(ViewColors.themeBackgroundColor(context)) +
                "  colorBackgroundFloating=" +
                Contrast.hex(ViewColors.themeFloatingBackgroundColor(context)) +
                "  colorButtonNormal=" +
                Contrast.hex(ViewColors.themeButtonNormalColor(context))
        }
    }

    /**
     * Writes the report to `build/contrast-report-<mode>.txt` and rebuilds
     * `build/contrast-report.txt` from whichever mode files exist, so the combined report is
     * complete once both modes have run regardless of their order.
     */
    fun writeReport(mode: String, results: List<ContrastAudit.AuditResult>) {
        val dir = File("build")
        dir.mkdirs()
        val text = buildString {
            appendLine("# generated by ThemeContrastAudit*Test (#161)")
            appendLine("# working dir: ${File(".").absolutePath}")
            appendLine()
            append(ContrastAudit.formatTable(results, panelNotes(mode)))
        }
        File(dir, "contrast-report-$mode.txt").writeText(text)

        val combined = listOf(MODE_LIGHT, MODE_NIGHT)
            .map { File(dir, "contrast-report-$it.txt") }
            .filter { it.exists() }
            .joinToString("\n\n") { it.readText() }
        File(dir, "contrast-report.txt").writeText(combined)
    }

    private const val NO_DIALOG = "no AlertDialog window found"
}
