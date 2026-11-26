package com.georgernstgraf.polishedrecognition.api.dto

import com.google.gson.annotations.SerializedName

data class SttResponse(
    @SerializedName("text") val text: String,
    @SerializedName("language") val language: String? = null,
    @SerializedName("language_probability") val languageProbability: Float? = null
)

data class ChatResponse(
    @SerializedName("choices") val choices: List<ChatChoice>
) {
    fun getContent(): String =
        choices.firstOrNull()?.message?.content?.let { stripReasoning(it) } ?: ""
}

data class ChatChoice(
    @SerializedName("message") val message: ChatMessage
)

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    /**
     * #153: the reasoning effort of the model (OpenAI-compatible
     * `reasoning_effort`). `null` serializes to NO member at all — Gson omits
     * nulls — so a provider that never hears about the field is unaffected.
     */
    @SerializedName("reasoning_effort") val reasoningEffort: String? = null
)

data class ModelsResponse(
    @SerializedName("data") val data: List<ModelEntry>
)

data class ModelEntry(
    @SerializedName("id") val id: String,
    @SerializedName("owned_by") val ownedBy: String? = null,
    /**
     * #153: capability extensions some providers send alongside the id —
     * observed on the opencode-go endpoint
     * (`capabilities.reasoning.effort: ["low","high","max"]`). Nullable and
     * additive: a standard OpenAI `/models` body carries none of it, and Gson
     * tolerates every unknown member, so all providers keep parsing.
     */
    @SerializedName("capabilities") val capabilities: ModelCapabilities? = null
)

/**
 * #153: the `capabilities` block of a [ModelEntry]. Only the members this app
 * consumes are declared; everything else the provider sends is ignored.
 */
data class ModelCapabilities(
    @SerializedName("reasoning") val reasoning: ModelReasoningCapabilities? = null
)

/**
 * #153: the reasoning capability of one model — whether it reasons at all and
 * which effort levels the provider communicates for it. The levels are the
 * model's own vocabulary (none/minimal/low/medium/high/xhigh/max have all been
 * observed, per model), never a fixed app-side list.
 */
data class ModelReasoningCapabilities(
    @SerializedName("supported") val supported: Boolean? = null,
    @SerializedName("effort") val effort: List<String>? = null
)
