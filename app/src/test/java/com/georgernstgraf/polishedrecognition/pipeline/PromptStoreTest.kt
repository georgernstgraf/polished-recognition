package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PromptStoreTest {

    private lateinit var store: PromptStore

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_prompts", 0)
            .edit().clear().commit()
        store = PromptStore(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `get system returns default system prompt`() {
        val prompt = store.get(PromptStore.KEY_SYSTEM)
        assertThat(prompt).contains("transcription post-processor")
        assertThat(prompt).contains("Return only the requested output text")
        assertThat(prompt).contains("{{source_language}}")
        assertThat(prompt).contains("{{translate_prompt}}")
        assertThat(prompt).contains("markdown")
    }

    @Test
    fun `get user returns default user prompt template`() {
        val prompt = store.get(PromptStore.KEY_USER)
        assertThat(prompt).contains("{{text}}")
        assertThat(prompt).doesNotContain("{{source_language}}")
        assertThat(prompt).doesNotContain("{{translate_prompt}}")
    }

    @Test
    fun `get translate returns default translate prompt`() {
        val prompt = store.get(PromptStore.KEY_TRANSLATE)
        assertThat(prompt).contains("{{target_language}}")
    }

    @Test
    fun `set and get override default`() {
        store.set(PromptStore.KEY_SYSTEM, "custom system")
        assertThat(store.get(PromptStore.KEY_SYSTEM)).isEqualTo("custom system")
    }

    @Test
    fun `restoreDefault reverts to original`() {
        store.set(PromptStore.KEY_SYSTEM, "custom")
        store.restoreDefault(PromptStore.KEY_SYSTEM)
        assertThat(store.get(PromptStore.KEY_SYSTEM)).contains("transcription post-processor")
    }

    @Test
    fun `restoreAllDefaults clears all custom values`() {
        store.set(PromptStore.KEY_SYSTEM, "custom")
        store.set(PromptStore.KEY_USER, "custom user")
        store.restoreAllDefaults()
        assertThat(store.get(PromptStore.KEY_SYSTEM)).contains("transcription post-processor")
        assertThat(store.get(PromptStore.KEY_USER)).contains("{{text}}")
    }

    @Test
    fun `get nonexistent key throws`() {
        try {
            store.get("nonexistent")
            throw AssertionError("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("Missing prompt key")
        }
    }

    @Test
    fun `custom value persists after store recreation`() {
        store.set(PromptStore.KEY_SYSTEM, "persistent")
        val fresh = PromptStore(RuntimeEnvironment.getApplication())
        assertThat(fresh.get(PromptStore.KEY_SYSTEM)).isEqualTo("persistent")
    }

    @Test
    fun `systemPrompt convenience property matches get`() {
        assertThat(store.systemPrompt).isEqualTo(store.get(PromptStore.KEY_SYSTEM))
        store.set(PromptStore.KEY_SYSTEM, "changed")
        assertThat(store.systemPrompt).isEqualTo("changed")
    }

    @Test
    fun `userPromptTemplate convenience property matches get`() {
        assertThat(store.userPromptTemplate).isEqualTo("{{text}}")
    }

    @Test
    fun `translatePromptTemplate convenience property matches get`() {
        assertThat(store.translatePromptTemplate).contains("{{target_language}}")
    }
}
