package com.georgernstgraf.polishedrecognition.config

object LanguageOptions {

    const val NONE_TARGET_LANGUAGE = CustomLanguages.NONE_TARGET_LANGUAGE

    fun buildLanguageList(customLanguages: List<String>): List<String> =
        listOf(NONE_TARGET_LANGUAGE, "English") + customLanguages.sorted()
}
