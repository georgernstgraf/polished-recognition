package com.georgernstgraf.polishedrecognition.ui

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

/**
 * #212: the ProvidersActivity tests — field ids, draft-not-prefs semantics,
 * the preset overwrite behavior and the token secret-file separation.
 */
@RunWith(RobolectricTestRunner::class)
class ProvidersActivityTest {

    private lateinit var store: SettingsStore
    private lateinit var presets: com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("polished_recognition_settings", 0)

    @Before
    fun setUp() {
        store = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).settingsStore
        presets = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).providerPresetLoader
        prefs().edit().clear().commit()
        SettingsDraft.clear()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        SettingsDraft.clear()
    }

    private fun launch(): ProvidersActivity =
        Robolectric.buildActivity(ProvidersActivity::class.java).setup().get()

    private fun ProvidersActivity.field(id: Int): EditText = findViewById(id)

    @Test
    fun `fields are loaded from the stored provider config`() {
        prefs().edit().putString("stt_provider", STT_SEED).commit()
        val activity = launch()

        assertThat(activity.field(R.id.stt_url).text.toString()).isEqualTo("https://seed.example/v1")
        assertThat(activity.field(R.id.stt_token).text.toString()).isEqualTo("t")
        assertThat(activity.field(R.id.stt_model).text.toString()).isEqualTo("seed-model")
    }

    @Test
    fun `edits land in the draft and never in the prefs`() {
        prefs().edit().putString("stt_provider", STT_SEED).commit()
        val activity = launch()
        // Reading the config once may MIGRATE a legacy in-JSON token into the
        // secrets file (the #101 behavior) — compare against the post-read
        // JSON, which is the stable state the edits must not touch.
        val beforeEdits = prefs().getString("stt_provider", null)!!

        activity.field(R.id.stt_url).setText("https://changed.example/v1")
        activity.field(R.id.stt_model).setText("changed-model")
        activity.field(R.id.stt_token).setText("changed-token")

        assertThat(SettingsDraft.sttUrl).isEqualTo("https://changed.example/v1")
        assertThat(SettingsDraft.sttModel).isEqualTo("changed-model")
        assertThat(SettingsDraft.sttToken).isEqualTo("changed-token")
        // The store was NOT written by the sub-screen (only the apply does).
        assertThat(prefs().getString("stt_provider", null)).isEqualTo(beforeEdits)
    }

    @Test
    fun `a pending draft value wins over the store on entry`() {
        prefs().edit().putString("stt_provider", STT_SEED).commit()
        SettingsDraft.sttUrl = "https://other.example/v1"
        val activity = launch()

        assertThat(activity.field(R.id.stt_url).text.toString()).isEqualTo("https://other.example/v1")
    }

    @Test
    fun `no provider config and no draft shows empty fields`() {
        val activity = launch()
        assertThat(activity.field(R.id.stt_url).text.toString()).isEmpty()
        assertThat(activity.field(R.id.llm_url).text.toString()).isEmpty()
        assertThat(SettingsDraft.isEmpty).isTrue()
    }

    @Test
    fun `selecting a preset overwrites the URL and clears the model`() {
        prefs().edit().putString("stt_provider", STT_SEED).commit()
        val activity = launch()
        val presetName = presets.sttPresetNames().first()
        val position = (0 until activity.findViewById<android.widget.AutoCompleteTextView>(R.id.stt_provider).adapter.count)
            .first { activity.findViewById<android.widget.AutoCompleteTextView>(R.id.stt_provider).adapter.getItem(it) == presetName }

        activity.findViewById<android.widget.AutoCompleteTextView>(R.id.stt_provider)
            .onItemClickListener!!.onItemClick(null, null, position, position.toLong())

        assertThat(activity.field(R.id.stt_url).text.toString())
            .isEqualTo(presets.findSttPreset(presetName)!!.base_url)
        assertThat(SettingsDraft.sttProviderName).isEqualTo(presetName)
        // The model field was cleared with the provider switch (old behavior).
        assertThat(activity.field(R.id.stt_model).text.toString()).isEmpty()
        assertThat(SettingsDraft.sttModel).isEmpty()
    }

    private companion object {
        const val STT_SEED =
            """{"displayName":"seed","baseUrl":"https://seed.example/v1","apiToken":"t","model":"seed-model"}"""
    }
}
