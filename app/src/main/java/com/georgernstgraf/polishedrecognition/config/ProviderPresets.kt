package com.georgernstgraf.polishedrecognition.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ProviderPreset(
    val name: String,
    val base_url: String,
    val models: List<String>,
    val default_model: String? = null
)

data class ProviderPresets(
    val stt_presets: List<ProviderPreset>,
    val llm_presets: List<ProviderPreset>
)

class ProviderPresetLoader(context: Context) {

    val presets: ProviderPresets by lazy {
        val json = context.assets.open("provider_presets.json").bufferedReader().use { it.readText() }
        Gson().fromJson(json, ProviderPresets::class.java)
    }

    val sttPresets: List<ProviderPreset> get() = presets.stt_presets
    val llmPresets: List<ProviderPreset> get() = presets.llm_presets

    fun findSttPreset(name: String): ProviderPreset? = sttPresets.find { it.name == name }
    fun findLlmPreset(name: String): ProviderPreset? = llmPresets.find { it.name == name }

    fun sttPresetNames(): List<String> = sttPresets.map { it.name }
    fun llmPresetNames(): List<String> = llmPresets.map { it.name }
}
