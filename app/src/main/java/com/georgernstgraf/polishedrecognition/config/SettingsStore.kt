package com.georgernstgraf.polishedrecognition.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SttProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val apiToken: String,
    val model: String
)

data class LlmProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val apiToken: String,
    val model: String
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    var sttProvider: SttProviderConfig?
        get() = getJson(STT_PROVIDER_KEY, SttProviderConfig::class.java)
        set(value) = setJson(STT_PROVIDER_KEY, value)

    var llmProvider: LlmProviderConfig?
        get() = getJson(LLM_PROVIDER_KEY, LlmProviderConfig::class.java)
        set(value) = setJson(LLM_PROVIDER_KEY, value)

    var rawMode: Boolean
        get() = prefs.getBoolean(RAW_MODE_KEY, false)
        set(value) = prefs.edit().putBoolean(RAW_MODE_KEY, value).apply()

    var targetLanguage: String?
        get() = prefs.getString(TARGET_LANGUAGE_KEY, null)
        set(value) = prefs.edit().putString(TARGET_LANGUAGE_KEY, value).apply()

    var customLanguages: List<String>
        get() = prefs.getString(CUSTOM_LANGUAGES_KEY, null)?.let {
            gson.fromJson(it, object : TypeToken<List<String>>() {}.type)
        } ?: emptyList()
        set(value) = prefs.edit().putString(CUSTOM_LANGUAGES_KEY, gson.toJson(value)).apply()

    fun setSttModelList(models: List<String>) {
        prefs.edit().putString(STT_MODEL_LIST_KEY, gson.toJson(models)).apply()
    }

    fun getSttModelList(): List<String> {
        val json = prefs.getString(STT_MODEL_LIST_KEY, null) ?: return emptyList()
        return gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
    }

    fun setLlmModelList(models: List<String>) {
        prefs.edit().putString(LLM_MODEL_LIST_KEY, gson.toJson(models)).apply()
    }

    fun getLlmModelList(): List<String> {
        val json = prefs.getString(LLM_MODEL_LIST_KEY, null) ?: return emptyList()
        return gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
    }

    private fun <T> getJson(key: String, clazz: Class<T>): T? {
        val json = prefs.getString(key, null) ?: return null
        return gson.fromJson(json, clazz)
    }

    private fun setJson(key: String, value: Any?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, gson.toJson(value)).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "polished_recognition_settings"
        private const val STT_PROVIDER_KEY = "stt_provider"
        private const val LLM_PROVIDER_KEY = "llm_provider"
        private const val STT_MODEL_LIST_KEY = "stt_model_list"
        private const val LLM_MODEL_LIST_KEY = "llm_model_list"
        private const val RAW_MODE_KEY = "raw_mode"
        private const val TARGET_LANGUAGE_KEY = "target_language"
        private const val CUSTOM_LANGUAGES_KEY = "custom_languages"
    }
}
