package com.georgernstgraf.polishedrecognition.api.dto

private val REASONING_OPENING_TAGS = listOf("<think>", "<reasoning>")
private val REASONING_CLOSING_TAGS = listOf("</think>", "</reasoning>")

fun stripReasoning(content: String): String {
    var result = content
    while (true) {
        val openIdx = REASONING_OPENING_TAGS.map { indexOfTag(result, it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull()
            ?: break
        val openTag = REASONING_OPENING_TAGS.first { indexOfTag(result, it, ignoreCase = true) == openIdx }
        val closeIdx = indexOfTag(result, openTagToClosing(openTag), ignoreCase = true, startIndex = openIdx)
        result = if (closeIdx >= 0) {
            result.removeRange(openIdx, closeIdx + openTagToClosing(openTag).length)
        } else {
            result.take(openIdx)
        }
    }
    REASONING_CLOSING_TAGS.forEach { closing ->
        while (true) {
            val idx = indexOfTag(result, closing, ignoreCase = true)
            if (idx < 0) break
            result = result.removeRange(idx, idx + closing.length)
        }
    }
    return result.replace(Regex("\\n{3,}"), "\n\n").trim()
}

private fun openTagToClosing(openTag: String): String = "</${openTag.drop(1)}"

private fun indexOfTag(source: String, tag: String, ignoreCase: Boolean, startIndex: Int = 0): Int =
    source.indexOf(tag, startIndex, ignoreCase = ignoreCase)
