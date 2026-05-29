package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
            .edit().clear().commit()
        store = SettingsStore(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `save and load SttProviderConfig round-trips all fields`() {
        val config = SttProviderConfig(
            displayName = "Test STT",
            baseUrl = "https://example.com/v1/",
            apiToken = "test-token",
            model = "test-model"
        )
        store.sttProvider = config
        val loaded = store.sttProvider
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.displayName).isEqualTo("Test STT")
        assertThat(loaded.baseUrl).isEqualTo("https://example.com/v1/")
        assertThat(loaded.apiToken).isEqualTo("test-token")
        assertThat(loaded.model).isEqualTo("test-model")
    }

    @Test
    fun `save and load LlmProviderConfig round-trips all fields`() {
        val config = LlmProviderConfig(
            displayName = "Test LLM",
            baseUrl = "https://example.com/v1/",
            apiToken = "llm-token",
            model = "gpt-4"
        )
        store.llmProvider = config
        val loaded = store.llmProvider
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.displayName).isEqualTo("Test LLM")
        assertThat(loaded.baseUrl).isEqualTo("https://example.com/v1/")
        assertThat(loaded.apiToken).isEqualTo("llm-token")
        assertThat(loaded.model).isEqualTo("gpt-4")
    }

    @Test
    fun `rawMode defaults to false`() {
        assertThat(store.rawMode).isFalse()
    }

    @Test
    fun `rawMode save and load true`() {
        store.rawMode = true
        assertThat(store.rawMode).isTrue()
    }

    @Test
    fun `targetLanguage defaults to null`() {
        assertThat(store.targetLanguage).isNull()
    }

    @Test
    fun `targetLanguage save and load`() {
        store.targetLanguage = "German"
        assertThat(store.targetLanguage).isEqualTo("German")
    }

    @Test
    fun `set and get STT model list`() {
        val models = listOf("model-a", "model-b")
        store.setSttModelList(models)
        val loaded = store.getSttModelList()
        assertThat(loaded).containsExactly("model-a", "model-b").inOrder()
    }

    @Test
    fun `getSttModelList returns empty before save`() {
        assertThat(store.getSttModelList()).isEmpty()
    }

    @Test
    fun `set and get LLM model list`() {
        val models = listOf("x", "y", "z")
        store.setLlmModelList(models)
        assertThat(store.getLlmModelList()).containsExactly("x", "y", "z").inOrder()
    }

    @Test
    fun `sttProvider null removes key`() {
        store.sttProvider = SttProviderConfig(displayName = "A", baseUrl = "url", apiToken = "tok", model = "mod")
        store.sttProvider = null
        assertThat(store.sttProvider).isNull()
    }

    @Test
    fun `prompt template fields save and load`() {
        store.systemPrompt = "sys"
        store.userPromptTemplate = "user {{text}}"
        store.translatePromptTemplate = "tl {{target_language}}"

        assertThat(store.systemPrompt).isEqualTo("sys")
        assertThat(store.userPromptTemplate).isEqualTo("user {{text}}")
        assertThat(store.translatePromptTemplate).isEqualTo("tl {{target_language}}")
    }

    @Test
    fun `new SettingsStore reads previously saved data`() {
        store.sttProvider = SttProviderConfig(displayName = "X", baseUrl = "url", apiToken = "tok", model = "mod")

        val fresh = SettingsStore(RuntimeEnvironment.getApplication())
        assertThat(fresh.sttProvider!!.displayName).isEqualTo("X")
    }
}
