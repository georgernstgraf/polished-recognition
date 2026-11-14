package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

/**
 * #101 — the STATIC half of the token-backup gate.
 *
 * Three things must agree or the privacy guarantee silently disappears:
 *  1. the manifest must wire BOTH backup mechanisms (`fullBackupContent` for
 *     API ≤ 30, `dataExtractionRules` for API 31+),
 *  2. both rule files must exclude `<SettingsStore.SECRETS_PREFS_NAME>.xml`,
 *  3. nothing else may be excluded — a broad exclusion would quietly drop the
 *     restorable configuration this app deliberately keeps in the backup.
 *
 * Pure JUnit, files read from disk (same approach as `XmlColorPolicyTest`): no
 * Robolectric, no resource compaction, and a broken rule file fails the build
 * instead of shipping.
 */
class BackupRulesTest {

    private data class Exclude(val domain: String?, val path: String?)

    @Test
    fun `the manifest wires both backup rule files`() {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml")
            .readText(Charsets.UTF_8)

        assertThat(manifest).contains("android:allowBackup=\"true\"")
        assertThat(manifest).contains("android:fullBackupContent=\"@xml/backup_rules\"")
        assertThat(manifest).contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
    }

    @Test
    fun `the api-30 rule file excludes exactly the secrets file`() {
        val excludes = excludesOf("src/main/res/xml/backup_rules.xml", "exclude")

        assertThat(excludes).containsExactly(
            Exclude("sharedpref", "${SettingsStore.SECRETS_PREFS_NAME}.xml")
        )
    }

    @Test
    fun `api-31 rules exclude the secrets file from cloud backup and device transfer`() {
        val excludes = excludesOf("src/main/res/xml/data_extraction_rules.xml", "exclude")

        // one entry per container: cloud-backup + device-transfer
        assertThat(excludes).hasSize(2)
        assertThat(excludes.toSet()).containsExactly(
            Exclude("sharedpref", "${SettingsStore.SECRETS_PREFS_NAME}.xml")
        )
    }

    @Test
    fun `the secrets file is a distinct file from the backed-up settings`() {
        assertThat(SettingsStore.SECRETS_PREFS_NAME).isEqualTo("polished_recognition_secrets")
        assertThat(SettingsStore.SECRETS_PREFS_NAME).isNotEqualTo("polished_recognition_settings")
        assertThat(SettingsStore.SECRETS_PREFS_NAME).isNotEqualTo("polished_recognition_prompts")
        assertThat(SettingsStore.SECRETS_PREFS_NAME).isNotEqualTo("known_apps")
    }

    private fun excludesOf(relativePath: String, elementName: String): List<Exclude> {
        val found = mutableListOf<Exclude>()
        SAXParserFactory.newInstance().newSAXParser().parse(
            File(moduleDir(), relativePath),
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes
                ) {
                    if (qName == elementName) {
                        found += Exclude(attributes.getValue("domain"), attributes.getValue("path"))
                    }
                }
            }
        )
        return found
    }

    /**
     * The `app` module directory. Unit tests run with the module as working
     * directory, but the walk up keeps the test correct when the host changes
     * that (it looks for `src/main/res`).
     */
    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res/xml").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "cannot locate the app module (src/main/res/xml) from " +
                File(System.getProperty("user.dir") ?: ".").absolutePath
        )
    }
}
