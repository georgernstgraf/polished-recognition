package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderPresetLoaderTest {

    private val loader = ProviderPresetLoader(RuntimeEnvironment.getApplication())

    @Test
    fun `sttPresetNames returns GROQ Whisper and OpenAI Whisper`() {
        val names = loader.sttPresetNames()
        assertThat(names).contains("GROQ Whisper")
        assertThat(names).contains("OpenAI Whisper")
    }

    @Test
    fun `llmPresetNames contains key providers`() {
        val names = loader.llmPresetNames()
        assertThat(names).contains("OpenAI")
        assertThat(names).contains("OpenRouter")
        assertThat(names).contains("GROQ")
        assertThat(names).contains("Ollama (local)")
        assertThat(names).contains("LM Studio (local)")
    }

    @Test
    fun `findSttPreset GROQ Whisper has base_url and models`() {
        val preset = loader.findSttPreset("GROQ Whisper")
        assertThat(preset).isNotNull()
        assertThat(preset!!.base_url).isEqualTo("https://api.groq.com/openai/v1/")
        assertThat(preset.models).contains("whisper-large-v3-turbo")
    }

    @Test
    fun `findLlmPreset OpenRouter has base_url and models`() {
        val preset = loader.findLlmPreset("OpenRouter")
        assertThat(preset).isNotNull()
        assertThat(preset!!.base_url).isEqualTo("https://openrouter.ai/api/v1/")
        assertThat(preset.models).isNotEmpty()
    }

    @Test
    fun `findSttPreset nonexistent returns null`() {
        assertThat(loader.findSttPreset("NoSuchProvider")).isNull()
    }

    @Test
    fun `findLlmPreset nonexistent returns null`() {
        assertThat(loader.findLlmPreset("NoSuchProvider")).isNull()
    }
}
