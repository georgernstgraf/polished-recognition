package com.georgernstgraf.polishedrecognition.ui

import android.widget.Button
import android.widget.EditText
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * #150: a "Save and Back" that stops at a validation check must never look
 * like "nothing happened". Every early return names the failed check in a
 * toast and the adb-readable rotating log. The abort always happens BEFORE
 * the caller-supplied wrap/fragment fields are validated (they sit at the
 * END of saveAndClose), so the provider config has already been written by
 * then — deliberately NOT persisted by mistake, and irrelevant to #150's
 * "empty after restart": that would need the abort to come even earlier.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {

    private lateinit var app: PolishedRecognitionApp
    private lateinit var store: SettingsStore

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("polished_recognition_settings", 0)

    private fun logFile(): File {
        val root = RuntimeEnvironment.getApplication().getExternalFilesDir(null)
            ?: RuntimeEnvironment.getApplication().filesDir
        return File(File(root, "logs"), "settings.json")
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
        store = app.settingsStore
        // Robolectric's sandbox shares the prefs across test methods (#142 lesson).
        prefs().edit().clear().commit()
        logFile().delete()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        logFile().delete()
    }

    private fun launch(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun SettingsActivity.textField(id: Int): EditText = findViewById(id)

    /** Fills both providers so only the check under test can fire. */
    private fun SettingsActivity.fillValidProviders() {
        textField(R.id.stt_url).setText("https://stt.example/v1")
        textField(R.id.llm_url).setText("https://llm.example/v1")
        textField(R.id.stt_model).setText("whisper-card")
        textField(R.id.llm_model).setText("chat-card")
    }

    private fun SettingsActivity.tapSave() =
        findViewById<Button>(R.id.save_button).performClick()

    /**
     * Runs an abort scenario against a pre-seeded provider config and checks
     * the three observable outcomes: the screen stays open, a toast and a log
     * line name the failed check, and the offending field carries the error.
     */
    private fun assertAborted(
        check: String,
        seed: String,
        errorField: Int,
        configure: SettingsActivity.() -> Unit
    ) {
        prefs().edit().putString("stt_provider", seed).commit()
        val activity = launch()
        ShadowToast.reset()

        activity.configure()
        activity.tapSave()

        assertThat(activity.isFinishing).isFalse()
        val toast = ShadowToast.getTextOfLatestToast()
        assertThat(toast).contains("Not saved")
        assertThat(toast).contains(check)
        assertThat(logFile().readText()).contains("\"check\":\"$check\"")
        assertThat(activity.textField(errorField).error).isNotNull()
        // A save abort never DELETES a stored provider config (#150).
        assertThat(prefs().getString("stt_provider", null)).isNotNull()
    }

    @Test
    fun emptySttModel_abortsNamingTheCheck() {
        assertAborted("stt-model-empty", SEED, R.id.stt_model) {
            fillValidProviders()
            textField(R.id.stt_model).setText("")
        }
    }

    @Test
    fun emptyLlmModel_abortsNamingTheCheck() {
        assertAborted("llm-model-empty", SEED, R.id.llm_model) {
            fillValidProviders()
            textField(R.id.llm_model).setText("")
        }
    }

    @Test
    fun sttModelOutsideFetchedList_abortsNamingTheCheck() {
        store.setSttModels("https://stt.example/v1", listOf("only-this-one"))
        try {
            assertAborted("stt-model-not-in-list", SEED, R.id.stt_model) {
                fillValidProviders()
            }
        } finally {
            store.setSttModels("https://stt.example/v1", emptyList())
        }
    }

    @Test
    fun invalidWrapWidth_abortsNamingTheCheck() {
        assertAborted("wrap-width", SEED, R.id.wrap_width) {
            fillValidProviders()
            textField(R.id.wrap_width).setText("5")
        }
    }

    @Test
    fun invalidFragmentSeconds_abortsNamingTheCheck() {
        assertAborted("fragment-seconds", SEED, R.id.fragment_seconds) {
            fillValidProviders()
            textField(R.id.fragment_seconds).setText("5")
        }
    }

    @Test
    fun validSettings_saveAndClose() {
        val activity = launch()
        activity.fillValidProviders()
        ShadowToast.reset()

        activity.tapSave()

        assertThat(activity.isFinishing).isTrue()
        assertThat(prefs().getString("stt_provider", null)).contains("whisper-card")
        assertThat(prefs().getString("llm_provider", null)).contains("chat-card")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(RuntimeEnvironment.getApplication().getString(R.string.saved))
        assertThat(logFile().exists()).isFalse()
    }

    private companion object {
        const val SEED = """{"displayName":"seed","baseUrl":"https://seed.example/v1","apiToken":"t","model":"seed-model"}"""
    }
}
