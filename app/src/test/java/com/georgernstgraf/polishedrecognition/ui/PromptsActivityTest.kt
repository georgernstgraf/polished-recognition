package com.georgernstgraf.polishedrecognition.ui

import android.widget.Button
import android.widget.EditText
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowToast

/**
 * #212: the PromptsActivity tests — draft-not-store semantics and the reset
 * buttons staying IMMEDIATE actions that clear their draft entry.
 */
@RunWith(RobolectricTestRunner::class)
class PromptsActivityTest {

    private lateinit var promptStore: PromptStore

    @Before
    fun setUp() {
        promptStore = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).promptStore
        SettingsDraft.clear()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        SettingsDraft.clear()
        promptStore.restoreAllDefaults()
    }

    private fun launch(): PromptsActivity =
        Robolectric.buildActivity(PromptsActivity::class.java).setup().get()

    private fun PromptsActivity.field(id: Int): EditText = findViewById(id)

    @Test
    fun `fields are loaded from the prompt store`() {
        val activity = launch()
        assertThat(activity.field(R.id.system_prompt).text.toString())
            .isEqualTo(promptStore.systemPrompt)
        assertThat(activity.field(R.id.target_language_clause).text.toString())
            .isEqualTo(promptStore.targetLanguageClauseTemplate)
    }

    @Test
    fun `edits land in the draft and apply on exit`() {
        val activity = launch()
        activity.field(R.id.system_prompt).setText("custom prompt")

        assertThat(SettingsDraft.systemPrompt).isEqualTo("custom prompt")
        // The sub-screen did not write the store.
        assertThat(promptStore.systemPrompt).isNotEqualTo("custom prompt")

        SettingsValidation.apply(
            RuntimeEnvironment.getApplication(), SettingsDraft,
            (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).settingsStore,
            promptStore, emptySet()
        )
        assertThat(promptStore.systemPrompt).isEqualTo("custom prompt")
    }

    @Test
    fun `reset restores the default immediately and clears the draft entry`() {
        val activity = launch()
        activity.field(R.id.system_prompt).setText("custom prompt")
        assertThat(SettingsDraft.systemPrompt).isEqualTo("custom prompt")

        activity.findViewById<Button>(R.id.reset_system_prompt).performClick()

        // Immediate write (#212 spec) + the stale draft entry is gone.
        assertThat(promptStore.systemPrompt).isNotEqualTo("custom prompt")
        assertThat(promptStore.systemPrompt).isEqualTo(promptStore.get(PromptStore.KEY_SYSTEM))
        assertThat(SettingsDraft.systemPrompt).isNull()
        assertThat(activity.field(R.id.system_prompt).text.toString())
            .isEqualTo(promptStore.systemPrompt)
    }

    @Test
    fun `restore defaults clears both draft entries and shows the confirmation toast`() {
        val activity = launch()
        activity.field(R.id.system_prompt).setText("custom prompt")
        activity.field(R.id.target_language_clause).setText("custom clause")

        activity.findViewById<Button>(R.id.restore_defaults).performClick()

        assertThat(SettingsDraft.systemPrompt).isNull()
        assertThat(SettingsDraft.targetLanguageClause).isNull()
        assertThat(activity.field(R.id.system_prompt).text.toString())
            .isEqualTo(promptStore.get(PromptStore.KEY_SYSTEM))
        assertThat(activity.field(R.id.target_language_clause).text.toString())
            .isEqualTo(promptStore.get(PromptStore.KEY_TRANSLATE))
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(RuntimeEnvironment.getApplication().getString(R.string.saved))
    }
}
