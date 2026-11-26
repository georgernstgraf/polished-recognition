package com.georgernstgraf.polishedrecognition.api.dto

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #153: the reasoning effort rides the chat request as the OpenAI-compatible
 * `reasoning_effort` member — and only when one is set: `null` serializes to
 * NO member at all, so a provider that does not know the field is byte-for-
 * byte unaffected.
 */
class ChatRequestJsonTest {

    private val gson = Gson()

    @Test
    fun `reasoning_effort is sent when set`() {
        val json = gson.toJson(
            ChatRequest(
                model = "glm-5.3",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                reasoningEffort = "high"
            )
        )
        val member = JsonParser().parse(json).asJsonObject.get("reasoning_effort")
        assertThat(member?.asString).isEqualTo("high")
    }

    @Test
    fun `reasoning_effort is omitted when null`() {
        val json = gson.toJson(
            ChatRequest(
                model = "glm-5.3",
                messages = listOf(ChatMessage(role = "user", content = "hi"))
            )
        )
        assertThat(JsonParser().parse(json).asJsonObject.has("reasoning_effort")).isFalse()
    }

    @Test
    fun `ModelEntry parses the reasoning capability extension`() {
        // Verbatim shape from the opencode-go /models response (#153 probe).
        val json = """
            {"id":"deepseek-v4.1-flash","owned_by":"opencode",
             "capabilities":{"reasoning":{"supported":true,"effort":["low","high","max"]}}}
        """.trimIndent()
        val entry = gson.fromJson(json, ModelEntry::class.java)
        assertThat(entry.capabilities!!.reasoning!!.supported).isTrue()
        assertThat(entry.capabilities.reasoning.effort).containsExactly("low", "high", "max").inOrder()
    }

    @Test
    fun `ModelEntry without a capabilities block parses as null`() {
        // The standard OpenAI /models body — no extension members.
        val entry = gson.fromJson("""{"id":"m","owned_by":"x"}""", ModelEntry::class.java)
        assertThat(entry.capabilities).isNull()
    }
}
