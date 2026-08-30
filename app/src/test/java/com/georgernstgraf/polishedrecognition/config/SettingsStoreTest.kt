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
    fun `set and get STT models keyed per URL`() {
        store.setSttModels("https://a.example.com/v1", listOf("model-a1", "model-a2"))
        store.setSttModels("https://b.example.com/v1", listOf("model-b1"))
        assertThat(store.getSttModels("https://a.example.com/v1"))
            .containsExactly("model-a1", "model-a2").inOrder()
        assertThat(store.getSttModels("https://b.example.com/v1")).containsExactly("model-b1")
    }

    @Test
    fun `getSttModels returns empty for unknown URL`() {
        assertThat(store.getSttModels("https://none.example.com")).isEmpty()
    }

    @Test
    fun `overwriting URL updates models in place`() {
        store.setSttModels("https://a.example.com/v1", listOf("old"))
        store.setSttModels("https://a.example.com/v1", listOf("new"))
        assertThat(store.getSttModels("https://a.example.com/v1")).containsExactly("new")
        assertThat(store.getSttModels("https://b.example.com/v1")).isEmpty()
    }

    @Test
    fun `set and get LLM models keyed per URL`() {
        store.setLlmModels("https://llm.example.com/v1", listOf("x", "y", "z"))
        assertThat(store.getLlmModels("https://llm.example.com/v1")).containsExactly("x", "y", "z").inOrder()
        assertThat(store.getLlmModels("https://other.example.com")).isEmpty()
    }

    @Test
    fun `stt and llm caches are independent`() {
        store.setSttModels("https://same.example.com/v1", listOf("stt-model"))
        store.setLlmModels("https://same.example.com/v1", listOf("llm-model"))
        assertThat(store.getSttModels("https://same.example.com/v1")).containsExactly("stt-model")
        assertThat(store.getLlmModels("https://same.example.com/v1")).containsExactly("llm-model")
    }

    @Test
    fun `expired cache entry is pruned and returns empty`() {
        store.setSttModels("https://old.example.com/v1", listOf("stale"))
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
        // Age the stored timestamp beyond the 6-week TTL.
        val json = prefs.getString("stt_model_lists", null)!!
        val aged = json.replace("\"timestamp\":", "\"timestamp\":${System.currentTimeMillis() - SettingsStore.MODEL_CACHE_TTL_MS - 1000},\"ignored\":")
        prefs.edit().putString("stt_model_lists", aged).commit()

        assertThat(store.getSttModels("https://old.example.com/v1")).isEmpty()
        // Pruned — a fresh store no longer has it either.
        val fresh = SettingsStore(RuntimeEnvironment.getApplication())
        assertThat(fresh.getSttModels("https://old.example.com/v1")).isEmpty()
    }

    @Test
    fun `fresh cache entry survives read`() {
        store.setSttModels("https://a.example.com/v1", listOf("m"))
        assertThat(store.getSttModels("https://a.example.com/v1")).containsExactly("m")
    }

    @Test
    fun `legacy model list migrates under saved provider baseUrl`() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
        store.sttProvider = SttProviderConfig(displayName = "Groq", baseUrl = "https://api.groq.com/openai/v1", apiToken = "t", model = "whisper-large-v3")
        prefs.edit().putString("stt_model_list", "[\"whisper-large-v3\",\"whisper-large-v3-turbo\"]").commit()

        assertThat(store.getSttModels("https://api.groq.com/openai/v1"))
            .containsExactly("whisper-large-v3", "whisper-large-v3-turbo").inOrder()
        // Legacy key removed after migration.
        assertThat(prefs.getString("stt_model_list", null)).isNull()
    }

    @Test
    fun `legacy model list without saved provider is dropped`() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
        prefs.edit().putString("stt_model_list", "[\"orphan\"]").commit()

        assertThat(store.getSttModels("https://any.example.com")).isEmpty()
        assertThat(prefs.getString("stt_model_list", null)).isNull()
    }

    @Test
    fun `sttProvider null removes key`() {
        store.sttProvider = SttProviderConfig(displayName = "A", baseUrl = "url", apiToken = "tok", model = "mod")
        store.sttProvider = null
        assertThat(store.sttProvider).isNull()
    }

    @Test
    fun `new SettingsStore reads previously saved data`() {
        store.sttProvider = SttProviderConfig(displayName = "X", baseUrl = "url", apiToken = "tok", model = "mod")

        val fresh = SettingsStore(RuntimeEnvironment.getApplication())
        assertThat(fresh.sttProvider!!.displayName).isEqualTo("X")
    }

    @Test
    fun `customLanguages defaults to empty`() {
        assertThat(store.customLanguages).isEmpty()
    }

    @Test
    fun `customLanguages save and load`() {
        val languages = listOf("German", "French", "Italian")
        store.customLanguages = languages
        assertThat(store.customLanguages).containsExactly("German", "French", "Italian").inOrder()
    }

    @Test
    fun `customLanguages round-trips empty list`() {
        store.customLanguages = emptyList()
        assertThat(store.customLanguages).isEmpty()
    }
}
