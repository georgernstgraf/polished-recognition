package com.georgernstgraf.polishedrecognition.config

/**
 * Maps a Whisper `language` field to a human-readable display name.
 *
 * Providers disagree on the format: GROQ returns the full name ("German"),
 * faster-whisper-based gateways return the ISO 639-1 code ("de").
 * Both resolve to "German"; anything unrecognized falls back to
 * capitalized words. The code table is the complete Whisper language list
 * (openai/whisper tokenizer.py, LANGUAGES).
 */
object LanguageMapper {

    private val CODE_TO_NAME = mapOf(
        "en" to "english",
        "zh" to "chinese",
        "de" to "german",
        "es" to "spanish",
        "ru" to "russian",
        "ko" to "korean",
        "fr" to "french",
        "ja" to "japanese",
        "pt" to "portuguese",
        "tr" to "turkish",
        "pl" to "polish",
        "ca" to "catalan",
        "nl" to "dutch",
        "ar" to "arabic",
        "sv" to "swedish",
        "it" to "italian",
        "id" to "indonesian",
        "hi" to "hindi",
        "fi" to "finnish",
        "vi" to "vietnamese",
        "he" to "hebrew",
        "uk" to "ukrainian",
        "el" to "greek",
        "ms" to "malay",
        "cs" to "czech",
        "ro" to "romanian",
        "da" to "danish",
        "hu" to "hungarian",
        "ta" to "tamil",
        "no" to "norwegian",
        "th" to "thai",
        "ur" to "urdu",
        "hr" to "croatian",
        "bg" to "bulgarian",
        "lt" to "lithuanian",
        "la" to "latin",
        "mi" to "maori",
        "ml" to "malayalam",
        "cy" to "welsh",
        "sk" to "slovak",
        "te" to "telugu",
        "fa" to "persian",
        "lv" to "latvian",
        "bn" to "bengali",
        "sr" to "serbian",
        "az" to "azerbaijani",
        "sl" to "slovenian",
        "kn" to "kannada",
        "et" to "estonian",
        "mk" to "macedonian",
        "br" to "breton",
        "eu" to "basque",
        "is" to "icelandic",
        "hy" to "armenian",
        "ne" to "nepali",
        "mn" to "mongolian",
        "bs" to "bosnian",
        "kk" to "kazakh",
        "sq" to "albanian",
        "sw" to "swahili",
        "gl" to "galician",
        "mr" to "marathi",
        "pa" to "punjabi",
        "si" to "sinhala",
        "km" to "khmer",
        "sn" to "shona",
        "yo" to "yoruba",
        "so" to "somali",
        "af" to "afrikaans",
        "oc" to "occitan",
        "ka" to "georgian",
        "be" to "belarusian",
        "tg" to "tajik",
        "sd" to "sindhi",
        "gu" to "gujarati",
        "am" to "amharic",
        "yi" to "yiddish",
        "lo" to "lao",
        "uz" to "uzbek",
        "fo" to "faroese",
        "ht" to "haitian creole",
        "ps" to "pashto",
        "tk" to "turkmen",
        "nn" to "nynorsk",
        "mt" to "maltese",
        "sa" to "sanskrit",
        "lb" to "luxembourgish",
        "my" to "myanmar",
        "bo" to "tibetan",
        "tl" to "tagalog",
        "mg" to "malagasy",
        "as" to "assamese",
        "tt" to "tatar",
        "haw" to "hawaiian",
        "ln" to "lingala",
        "ha" to "hausa",
        "ba" to "bashkir",
        "jw" to "javanese",
        "su" to "sundanese",
        "yue" to "cantonese"
    )

    private val NAME_SET = CODE_TO_NAME.values.toSet()

    fun toDisplayName(raw: String?): String {
        val v = raw?.trim() ?: return "Unknown"
        CODE_TO_NAME[v.lowercase()]?.let { return capitalizeWords(it) }
        if (v.lowercase() in NAME_SET) return capitalizeWords(v.lowercase())
        return capitalizeWords(v)
    }

    private fun capitalizeWords(s: String): String =
        s.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}
