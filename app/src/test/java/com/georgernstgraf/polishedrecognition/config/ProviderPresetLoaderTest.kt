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

    // ---- #153: the OpenCode endpoints (Console pay-as-you-go + Go) -----------

    @Test
    fun `findLlmPreset OpenCode has the Console inference base_url`() {
        val preset = loader.findLlmPreset("OpenCode")
        assertThat(preset).isNotNull()
        assertThat(preset!!.base_url).isEqualTo("https://opencode.ai/inference/openai/v1/")
        assertThat(preset.default_model).isEqualTo("glm-5.3-flash")
    }

    @Test
    fun `findLlmPreset OpenCode Go has the Go inference base_url`() {
        val preset = loader.findLlmPreset("OpenCode Go")
        assertThat(preset).isNotNull()
        assertThat(preset!!.base_url).isEqualTo("https://opencode.ai/inference/go/openai/v1/")
        assertThat(preset.default_model).isEqualTo("glm-5.3-flash")
    }

    @Test
    fun `findSttPreset GROQ Whisper has base_url`() {
        val preset = loader.findSttPreset("GROQ Whisper")
        assertThat(preset).isNotNull()
        assertThat(preset!!.base_url).isEqualTo("https://api.groq.com/openai/v1/")
    }

    @Test
    fun `findLlmPreset OpenRouter has base_url`() {
        val preset = loader.findLlmPreset("OpenRouter")
        assertThat(preset).isNotNull()
        assertThat(preset!!.base_url).isEqualTo("https://openrouter.ai/api/v1/")
    }

    @Test
    fun `findLlmPreset Cortecs has base_url`() {
        val preset = loader.findLlmPreset("Cortecs")
        assertThat(preset).isNotNull()
        assertThat(preset!!.base_url).isEqualTo("https://api.cortecs.ai/v1/")
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
