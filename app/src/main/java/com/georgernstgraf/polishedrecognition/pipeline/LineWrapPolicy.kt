package com.georgernstgraf.polishedrecognition.pipeline

/**
 * Word-wraps the committed transcription output (#81).
 *
 * - `width <= 0` disables wrapping: [text] is returned unchanged.
 * - Existing line breaks are preserved: each paragraph (split on `\n`) is
 *   wrapped independently; empty paragraphs stay empty.
 * - Greedy word wrap: words (split on runs of whitespace) are packed onto
 *   lines of at most [width] characters, joined with single spaces. A single
 *   word longer than [width] occupies its own line unwrapped (never hard-split,
 *   so URLs and long tokens survive intact).
 */
object LineWrapPolicy {

    fun wrap(text: String, width: Int): String {
        if (width <= 0 || text.isEmpty()) return text
        return text.split("\n").joinToString("\n") { wrapParagraph(it, width) }
    }

    private fun wrapParagraph(paragraph: String, width: Int): String {
        val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val lines = mutableListOf<String>()
        var current = StringBuilder(words[0])
        for (word in words.drop(1)) {
            if (current.length + 1 + word.length <= width) {
                current.append(' ').append(word)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        lines.add(current.toString())
        return lines.joinToString("\n")
    }
}
