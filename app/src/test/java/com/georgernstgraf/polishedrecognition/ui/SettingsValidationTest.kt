package com.georgernstgraf.polishedrecognition.ui

import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #212: the abort-check-name contract of the old `saveAndClose()` — the same
 * checks run in the same order over the draft-or-store effective values, and
 * `apply` skips exactly the entries whose check failed.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsValidationTest {

    private lateinit var store: SettingsStore
    private lateinit var promptStore: PromptStore

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("polished_recognition_settings", 0)

    private fun secrets() = RuntimeEnvironment.getApplication()
        .getSharedPreferences(SettingsStore.SECRETS_PREFS_NAME, 0)

    @Before
    fun setUp() {
        store = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).settingsStore
        promptStore = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).promptStore
        prefs().edit().clear().commit()
        secrets().edit().clear().commit()
        SettingsDraft.clear()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        secrets().edit().clear().commit()
        SettingsDraft.clear()
    }

    private fun seedProviders() {
        prefs().edit()
            .putString("stt_provider", STT_SEED)
            .putString("llm_provider", LLM_SEED)
            .commit()
    }

    @Test
    fun `a fresh draft is empty`() {
        assertThat(SettingsDraft.isEmpty).isTrue()
        SettingsDraft.sttUrl = "https://x/v1"
        assertThat(SettingsDraft.isEmpty).isFalse()
    }

    @Test
    fun `emptySttModel aborts naming the check`() {
        seedProviders()
        SettingsDraft.sttModel = ""
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_STT_MODEL_EMPTY)
    }

    @Test
    fun `emptyLlmModel aborts naming the check`() {
        seedProviders()
        SettingsDraft.llmModel = ""
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_LLM_MODEL_EMPTY)
    }

    @Test
    fun `sttModelOutsideFetchedList aborts naming the check`() {
        seedProviders()
        store.setSttModels("https://seed.example/v1", listOf("only-this-one"))
        try {
            assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
                .contains(SettingsValidation.CHECK_STT_MODEL_NOT_IN_LIST)
        } finally {
            store.setSttModels("https://seed.example/v1", emptyList())
        }
    }

    @Test
    fun `llmModelOutsideFetchedList aborts naming the check`() {
        seedProviders()
        store.setLlmModels("https://seedllm.example/v1", listOf("only-this-one"))
        try {
            assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
                .contains(SettingsValidation.CHECK_LLM_MODEL_NOT_IN_LIST)
        } finally {
            store.setLlmModels("https://seedllm.example/v1", emptyList())
        }
    }

    @Test
    fun `appWrapWidth aborts naming the check`() {
        seedProviders()
        SettingsDraft.pendingAppWrap = "com.example" to "5"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_APP_WRAP_WIDTH)
        // The legacy empty / 0 / >= MIN rule (#100).
        SettingsDraft.pendingAppWrap = "com.example" to ""
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_APP_WRAP_WIDTH)
        SettingsDraft.pendingAppWrap = "com.example" to "0"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_APP_WRAP_WIDTH)
        SettingsDraft.pendingAppWrap = "com.example" to "20"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_APP_WRAP_WIDTH)
    }

    @Test
    fun `invalidWrapWidth aborts naming the check`() {
        seedProviders()
        SettingsDraft.wrapWidth = "5"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_WRAP_WIDTH)
        SettingsDraft.wrapWidth = "0"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_WRAP_WIDTH)
    }

    @Test
    fun `invalidSliceSeconds aborts naming the check`() {
        seedProviders()
        SettingsDraft.sliceAuto = false
        SettingsDraft.sliceSeconds = "2"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_SLICE_SECONDS)
        SettingsDraft.sliceSeconds = "21"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_SLICE_SECONDS)
    }

    @Test
    fun `invalidSearchPercent aborts naming the check`() {
        seedProviders()
        SettingsDraft.searchAuto = false
        SettingsDraft.searchPercent = "90"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_SEARCH_PERCENT)
        SettingsDraft.searchPercent = "30"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_SEARCH_PERCENT)
    }

    @Test
    fun `invalidWorkbenchFifoSize aborts naming the check`() {
        seedProviders()
        SettingsDraft.workbenchFifoSize = "0"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_WORKBENCH_FIFO_SIZE)
        SettingsDraft.workbenchFifoSize = "3"
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_WORKBENCH_FIFO_SIZE)
    }

    @Test
    fun `a fully valid draft passes every check`() {
        seedProviders()
        SettingsDraft.workbenchFifoSize = "5"
        SettingsDraft.wrapWidth = "120"
        SettingsDraft.compressAudio = true
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store)).isEmpty()
    }

    @Test
    fun `apply writes the pending provider config into the SECRETS file only for the token`() {
        seedProviders()
        SettingsDraft.sttUrl = "https://stt.example/v1"
        SettingsDraft.sttModel = "whisper-card"
        SettingsDraft.sttToken = "super-secret-token"

        val context = RuntimeEnvironment.getApplication()
        SettingsValidation.apply(context, SettingsDraft, store, promptStore, emptyList())

        val sttJson = prefs().getString("stt_provider", null)!!
        assertThat(sttJson).contains("whisper-card")
        assertThat(sttJson).contains("https://stt.example/v1")
        // #101: the token never lives in the backed-up settings file.
        assertThat(sttJson).doesNotContain("super-secret-token")
        assertThat(secrets().getString("stt_token", null)).isEqualTo("super-secret-token")
    }

    @Test
    fun `apply skips the failing field and applies the rest`() {
        seedProviders()
        SettingsDraft.workbenchFifoSize = "0" // fails workbench-fifo-size
        SettingsDraft.wrapWidth = "120" // valid

        val failed = SettingsValidation.failedChecks(SettingsDraft, store)
        assertThat(failed).contains(SettingsValidation.CHECK_WORKBENCH_FIFO_SIZE)
        SettingsValidation.apply(RuntimeEnvironment.getApplication(), SettingsDraft, store, promptStore, failed.toSet())

        assertThat(store.wrapWidth).isEqualTo(120)
        assertThat(store.workbenchFifoSize).isEqualTo(3) // the previous persisted value
    }

    @Test
    fun `apply skips a failing provider model but keeps the stored config`() {
        seedProviders()
        store.setSttModels("https://seed.example/v1", listOf("only-this-one"))
        SettingsDraft.sttModel = "wrong-model"
        SettingsDraft.wrapWidth = "120"
        try {
            val failed = SettingsValidation.failedChecks(SettingsDraft, store)
            assertThat(failed).contains(SettingsValidation.CHECK_STT_MODEL_NOT_IN_LIST)
            SettingsValidation.apply(
                RuntimeEnvironment.getApplication(), SettingsDraft, store, promptStore, failed.toSet()
            )
            // The failing entry keeps its previous persisted value (the token
            // may have migrated into the secrets file on the first read — the
            // #101 behavior): the seed model is still there.
            assertThat(prefs().getString("stt_provider", null)).contains("seed-model")
            // ...while the rest applied.
            assertThat(store.wrapWidth).isEqualTo(120)
        } finally {
            store.setSttModels("https://seed.example/v1", emptyList())
        }
    }

    @Test
    fun `apply writes only pending prompts and leaves the store untouched otherwise`() {
        val beforeSystem = promptStore.get(PromptStore.KEY_SYSTEM)
        val beforeTranslate = promptStore.get(PromptStore.KEY_TRANSLATE)
        SettingsDraft.systemPrompt = "edited prompt"
        SettingsValidation.apply(
            RuntimeEnvironment.getApplication(), SettingsDraft, store, promptStore, emptySet()
        )
        assertThat(promptStore.get(PromptStore.KEY_SYSTEM)).isEqualTo("edited prompt")
        assertThat(promptStore.get(PromptStore.KEY_TRANSLATE)).isEqualTo(beforeTranslate)

        SettingsDraft.clear()
        SettingsDraft.targetLanguageClause = "edited clause"
        SettingsValidation.apply(
            RuntimeEnvironment.getApplication(), SettingsDraft, store, promptStore, emptySet()
        )
        assertThat(promptStore.get(PromptStore.KEY_SYSTEM)).isEqualTo("edited prompt")
        assertThat(promptStore.get(PromptStore.KEY_TRANSLATE)).isEqualTo("edited clause")
        assertThat(beforeSystem).isNotEqualTo("edited prompt")
    }

    private companion object {
        const val STT_SEED =
            """{"displayName":"seed","baseUrl":"https://seed.example/v1","apiToken":"t","model":"seed-model"}"""
        const val LLM_SEED =
            """{"displayName":"seedLlm","baseUrl":"https://seedllm.example/v1","apiToken":"t","model":"seed-llm-model"}"""
    }
}
