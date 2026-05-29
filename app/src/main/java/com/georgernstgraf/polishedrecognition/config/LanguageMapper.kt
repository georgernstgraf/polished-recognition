package com.georgernstgraf.polishedrecognition.config

object LanguageMapper {

    private val codeToName = mapOf(
        "en" to "English",
        "de" to "German",
        "fr" to "French",
        "es" to "Spanish",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "ru" to "Russian",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "pl" to "Polish",
        "tr" to "Turkish",
        "sv" to "Swedish",
        "da" to "Danish",
        "no" to "Norwegian",
        "fi" to "Finnish",
        "cs" to "Czech",
        "hu" to "Hungarian",
        "ro" to "Romanian",
        "uk" to "Ukrainian",
        "el" to "Greek",
        "he" to "Hebrew",
        "th" to "Thai",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "ms" to "Malay",
        "ca" to "Catalan"
    )

    fun mapCodeToName(code: String?): String {
        if (code == null) return "unknown"
        val clean = code.lowercase().trim()
        return codeToName[clean] ?: clean
    }

    val supportedLanguages: List<String> = codeToName.values.sorted()
}
