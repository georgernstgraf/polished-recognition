package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * #169 (epic #166 D1): the launcher entry moved from Settings to the
 * Workbench. Exactly one activity may answer MAIN/LAUNCHER — two would show
 * two app icons, none would break the launcher.
 */
class LauncherEntryTest {

    private fun manifest(): String =
        File(moduleDir(), "src/main/AndroidManifest.xml").readText(Charsets.UTF_8)

    @Test
    fun `exactly one activity is the launcher entry`() {
        val text = manifest()

        assertThat(Regex("android\\.intent\\.action\\.MAIN").findAll(text).count()).isEqualTo(1)
        assertThat(Regex("android\\.intent\\.category\\.LAUNCHER").findAll(text).count()).isEqualTo(1)
    }

    @Test
    fun `the launcher entry is the workbench, settings stays reachable but unexported`() {
        val text = manifest()

        assertThat(text).contains("android:name=\".ui.WorkbenchActivity\"")
        assertThat(text).contains("android:name=\".ui.SettingsActivity\"")
        assertThat(text).contains("android:exported=\"false\"\n            android:theme=\"@style/Theme.PolishedRecognition.Plain\" />")
    }

    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res/xml").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate the app module from " + File(".").absolutePath)
    }
}
