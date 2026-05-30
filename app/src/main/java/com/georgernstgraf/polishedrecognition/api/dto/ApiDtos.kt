package com.georgernstgraf.polishedrecognition.api.dto

import com.google.gson.annotations.SerializedName

data class SttResponse(
    @SerializedName("text") val text: String,
    @SerializedName("language") val language: String? = null
)

data class ChatResponse(
    @SerializedName("choices") val choices: List<ChatChoice>
) {
    fun getContent(): String = choices.firstOrNull()?.message?.content ?: ""
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
    @SerializedName("max_tokens") val maxTokens: Int? = null
)

data class ModelsResponse(
    @SerializedName("data") val data: List<ModelEntry>
)

data class ModelEntry(
    @SerializedName("id") val id: String,
    @SerializedName("owned_by") val ownedBy: String? = null
)
