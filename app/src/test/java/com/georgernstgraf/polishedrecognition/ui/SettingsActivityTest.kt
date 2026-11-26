package com.georgernstgraf.polishedrecognition.ui

import android.widget.Button
import android.widget.AutoCompleteTextView
import android.widget.TextView
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
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * #212: the Settings OVERVIEW tests — category navigation, the draft +
 * validate-and-apply back-exit (success and failure), the write-through
 * language section and the absence of any Save button.
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
        SettingsDraft.clear()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        logFile().delete()
        SettingsDraft.clear()
    }

    private fun launch(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun <T : android.app.Activity> launch(activity: Class<T>): T =
        Robolectric.buildActivity(activity).setup().get()

    /** Seeds a valid provider pair so only the check under test can fire. */
    private fun seedProviders() {
        prefs().edit()
            .putString("stt_provider", STT_SEED)
            .putString("llm_provider", LLM_SEED)
            .commit()
    }

    @Test
    fun `category buttons launch the four sub-screens in order`() {
        val activity = launch()

        activity.findViewById<Button>(R.id.category_providers).performClick()
        assertThat(Shadows.shadowOf(activity).nextStartedActivity.component?.className)
            .isEqualTo(ProvidersActivity::class.java.name)
        activity.findViewById<Button>(R.id.category_prompts).performClick()
        assertThat(Shadows.shadowOf(activity).nextStartedActivity.component?.className)
            .isEqualTo(PromptsActivity::class.java.name)
        activity.findViewById<Button>(R.id.category_output).performClick()
        assertThat(Shadows.shadowOf(activity).nextStartedActivity.component?.className)
            .isEqualTo(OutputControlActivity::class.java.name)
        activity.findViewById<Button>(R.id.category_advanced).performClick()
        assertThat(Shadows.shadowOf(activity).nextStartedActivity.component?.className)
            .isEqualTo(AdvancedActivity::class.java.name)
    }

    @Test
    fun `no save buttons exist on the overview or any sub-screen`() {
        // The Save affordance is GONE (#212): leaving Settings saves via the
        // draft, so no screen may carry a save-labelled button any more.
        val screens = listOf(
            SettingsActivity::class.java,
            ProvidersActivity::class.java,
            PromptsActivity::class.java,
            OutputControlActivity::class.java,
            AdvancedActivity::class.java,
        )
        screens.forEach { activityClass ->
            val activity = launch(activityClass)
            val saveButtons = ArrayList<Button>()
            collectButtons(activity.findViewById(android.R.id.content), saveButtons)
            saveButtons.forEach {
                assertThat(it.text.toString().lowercase()).doesNotContain("save")
            }
        }
    }

    private fun collectButtons(view: android.view.View, out: MutableList<Button>) {
        if (view is Button) out += view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), out)
        }
    }

    @Test
    fun `backExit with no draft changes toasts saved and applies nothing`() {
        ShadowToast.reset()
        val activity = launch()
        activity.handleBackExit()

        assertThat(activity.isFinishing).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(RuntimeEnvironment.getApplication().getString(R.string.saved))
        // Nothing was materialised: no provider config appeared from thin air.
        assertThat(prefs().getString("stt_provider", null)).isNull()
        assertThat(prefs().getString("llm_provider", null)).isNull()
        assertThat(logFile().exists()).isFalse()
    }

    @Test
    fun `backExit with an INVALID stored state but no draft still toasts saved`() {
        // The draft-isEmpty shortcut wins: validation only runs over a draft.
        seedProviders()
        store.setSttModels("https://seed.example/v1", listOf("other-model"))
        try {
            ShadowToast.reset()
            val activity = launch()
            activity.handleBackExit()

            assertThat(activity.isFinishing).isTrue()
            assertThat(ShadowToast.getTextOfLatestToast())
                .isEqualTo(RuntimeEnvironment.getApplication().getString(R.string.saved))
            assertThat(logFile().exists()).isFalse()
        } finally {
            store.setSttModels("https://seed.example/v1", emptyList())
        }
    }

    @Test
    fun `backExit applies the draft and toasts saved`() {
        seedProviders()
        val advanced = launch(AdvancedActivity::class.java)
        advanced.findViewById<android.widget.EditText>(R.id.workbench_fifo_size).setText("4")
        assertThat(SettingsDraft.workbenchFifoSize).isEqualTo("4")

        ShadowToast.reset()
        val overview = launch()
        overview.handleBackExit()

        assertThat(overview.isFinishing).isTrue()
        assertThat(store.workbenchFifoSize).isEqualTo(4)
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(RuntimeEnvironment.getApplication().getString(R.string.saved))
        assertThat(logFile().exists()).isFalse()
    }

    @Test
    fun `backExit failure names the check, finishes anyway and keeps the failing value`() {
        seedProviders()
        val output = launch(OutputControlActivity::class.java)
        output.findViewById<android.widget.EditText>(R.id.wrap_width).setText("5")
        val advanced = launch(AdvancedActivity::class.java)
        advanced.findViewById<android.widget.EditText>(R.id.workbench_fifo_size).setText("4")

        ShadowToast.reset()
        val overview = launch()
        overview.handleBackExit()

        // The screen still closes — leaving Settings never traps the user (#212).
        assertThat(overview.isFinishing).isTrue()
        val toast = ShadowToast.getTextOfLatestToast()
        assertThat(toast).contains("Not saved")
        assertThat(toast).contains("wrap-width")
        assertThat(logFile().readText()).contains("\"check\":\"wrap-width\"")
        // The failing field keeps its previous persisted value…
        assertThat(store.wrapWidth).isEqualTo(SettingsStore.DEFAULT_WRAP_WIDTH)
        // …while all other pending changes applied.
        assertThat(store.workbenchFifoSize).isEqualTo(4)
        // A failed check never deletes the stored provider config (#150 lesson):
        // the seed config's model is still there (its token may have migrated
        // into the secrets file on the first read — the #101 behavior).
        assertThat(prefs().getString("stt_provider", null)).contains("seed-model")
    }

    @Test
    fun `language dropdown label tap still commits write-through`() {
        val activity = launch()
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.target_language)
        val adapter = dropdown.adapter
        assertThat(adapter.count).isAtLeast(2)
        val row = adapter.getView(1, null, dropdown.parent as android.view.ViewGroup)
        row.findViewById<TextView>(R.id.language_name).performClick()

        assertThat(store.targetLanguage).isEqualTo(adapter.getItem(1) as String)
        assertThat(dropdown.text.toString()).isEqualTo(adapter.getItem(1) as String)
    }

    private companion object {
        const val STT_SEED =
            """{"displayName":"seed","baseUrl":"https://seed.example/v1","apiToken":"t","model":"seed-model"}"""
        const val LLM_SEED =
            """{"displayName":"seedLlm","baseUrl":"https://seedllm.example/v1","apiToken":"t","model":"seed-llm-model"}"""
    }
}
