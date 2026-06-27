package com.georgernstgraf.polishedrecognition.pipeline

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PromptStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val defaults: Map<String, String> = loadDefaults(context)

    fun get(key: String): String {
        if (key == KEY_USER) return defaults[KEY_USER] ?: throw IllegalArgumentException("Missing prompt key: $key")
        val stored = prefs.getString(key, null)
        return stored ?: defaults[key] ?: throw IllegalArgumentException("Missing prompt key: $key")
    }

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun restoreDefault(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun restoreAllDefaults() {
        prefs.edit().clear().apply()
    }

    val systemPrompt: String get() = get(KEY_SYSTEM)
    val userPromptTemplate: String get() = get(KEY_USER)
    val targetLanguageClauseTemplate: String get() = get(KEY_TRANSLATE)

    companion object {
        const val KEY_SYSTEM = "system"
        const val KEY_USER = "user"
        const val KEY_TRANSLATE = "translate"
        private const val PREFS_NAME = "polished_recognition_prompts"
    }

    private fun loadDefaults(context: Context): Map<String, String> {
        val json = context.assets.open("prompts.json").bufferedReader().use { it.readText() }
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(json, type)
            ?: error("prompts.json parsed to null")
    }
}
