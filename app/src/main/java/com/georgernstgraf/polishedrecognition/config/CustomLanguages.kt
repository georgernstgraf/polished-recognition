package com.georgernstgraf.polishedrecognition.config

object CustomLanguages {

    const val NONE_TARGET_LANGUAGE = "None (no translation)"
    const val BUILTIN_LANGUAGE = "English"

    enum class RenameError { EMPTY, DUPLICATE }

    fun displayList(customLanguages: List<String>): List<String> =
        listOf(NONE_TARGET_LANGUAGE, BUILTIN_LANGUAGE) + customLanguages.sorted()

    fun isBuiltIn(name: String): Boolean =
        name == NONE_TARGET_LANGUAGE || name == BUILTIN_LANGUAGE

    fun rename(languages: List<String>, oldName: String, newName: String): List<String> =
        languages.map { if (it == oldName) newName else it }

    fun validateRename(customLanguages: List<String>, oldName: String, newName: String): RenameError? {
        val name = newName.trim()
        if (name.isEmpty()) return RenameError.EMPTY
        if (name == oldName) return null
        val pool = customLanguages + NONE_TARGET_LANGUAGE + BUILTIN_LANGUAGE
        if (pool.any { it.equals(name, ignoreCase = true) && !it.equals(oldName, ignoreCase = true) }) {
            return RenameError.DUPLICATE
        }
        return null
    }

    sealed class CommitResult {
        data class Selected(val language: String) : CommitResult()
        data class Added(val language: String, val newList: List<String>) : CommitResult()
        object Reverted : CommitResult()
    }

    fun commitEdit(customLanguages: List<String>, value: String): CommitResult {
        val name = value.trim()
        if (name.isEmpty()) return CommitResult.Reverted
        val existing = displayList(customLanguages).firstOrNull { it.equals(name, ignoreCase = true) }
        if (existing != null) return CommitResult.Selected(existing)
        return CommitResult.Added(name, customLanguages + name)
    }
}
