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

data class CachedModels(
    val timestamp: Long,
    val models: List<String>
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

    fun setSttModels(baseUrl: String, models: List<String>) {
        setCachedModels(STT_MODEL_LISTS_KEY, baseUrl, models)
    }

    fun getSttModels(baseUrl: String): List<String> = getCachedModels(STT_MODEL_LISTS_KEY, baseUrl)

    fun setLlmModels(baseUrl: String, models: List<String>) {
        setCachedModels(LLM_MODEL_LISTS_KEY, baseUrl, models)
    }

    fun getLlmModels(baseUrl: String): List<String> = getCachedModels(LLM_MODEL_LISTS_KEY, baseUrl)

    private fun setCachedModels(mapKey: String, baseUrl: String, models: List<String>) {
        val cache = readModelCache(mapKey)
        cache[baseUrl] = CachedModels(System.currentTimeMillis(), models)
        prefs.edit().putString(mapKey, gson.toJson(cache)).apply()
    }

    private fun getCachedModels(mapKey: String, baseUrl: String): List<String> {
        migrateLegacyModelList(mapKey)
        val cache = readModelCache(mapKey)
        val entry = cache[baseUrl] ?: return emptyList()
        if (System.currentTimeMillis() - entry.timestamp > MODEL_CACHE_TTL_MS) {
            cache.remove(baseUrl)
            prefs.edit().putString(mapKey, gson.toJson(cache)).apply()
            return emptyList()
        }
        return entry.models
    }

    private fun readModelCache(mapKey: String): MutableMap<String, CachedModels> {
        val json = prefs.getString(mapKey, null) ?: return mutableMapOf()
        return gson.fromJson(json, object : TypeToken<MutableMap<String, CachedModels>>() {}.type)
            ?: mutableMapOf()
    }

    private fun migrateLegacyModelList(mapKey: String) {
        val legacyKey = when (mapKey) {
            STT_MODEL_LISTS_KEY -> STT_MODEL_LIST_KEY
            else -> LLM_MODEL_LIST_KEY
        }
        if (prefs.getString(legacyKey, null) == null) return
        val legacyModels: List<String> =
            gson.fromJson(prefs.getString(legacyKey, null), object : TypeToken<List<String>>() {}.type)
        val baseUrl = when (mapKey) {
            STT_MODEL_LISTS_KEY -> getJson(STT_PROVIDER_KEY, SttProviderConfig::class.java)?.baseUrl
            else -> getJson(LLM_PROVIDER_KEY, LlmProviderConfig::class.java)?.baseUrl
        }
        val cache = readModelCache(mapKey)
        if (baseUrl != null && legacyModels.isNotEmpty()) {
            cache[baseUrl] = CachedModels(System.currentTimeMillis(), legacyModels)
            prefs.edit().putString(mapKey, gson.toJson(cache)).apply()
        }
        prefs.edit().remove(legacyKey).apply()
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
        private const val STT_MODEL_LISTS_KEY = "stt_model_lists"
        private const val LLM_MODEL_LISTS_KEY = "llm_model_lists"
        private const val RAW_MODE_KEY = "raw_mode"
        private const val TARGET_LANGUAGE_KEY = "target_language"
        private const val CUSTOM_LANGUAGES_KEY = "custom_languages"

        /** 6 weeks in milliseconds. */
        const val MODEL_CACHE_TTL_MS = 42L * 24 * 60 * 60 * 1000
    }
}
