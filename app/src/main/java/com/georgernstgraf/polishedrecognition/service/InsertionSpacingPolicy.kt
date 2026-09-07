package com.georgernstgraf.polishedrecognition.service

/**
 * Decides how whitespace must be padded around the text committed into the editor
 * (#66, refined #73): a leading space when the cursor sits after non-whitespace
 * (letters, punctuation) or when the neighbor cannot be read (null); an empty
 * neighbor — a genuine field start — gets no leading space. A trailing space when
 * no whitespace follows (including field end). Whitespace already present in the
 * transcribed text itself suppresses padding so it is never doubled.
 */
object InsertionSpacingPolicy {

    fun apply(text: String, before: CharSequence?, after: CharSequence?): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text)
        val needsLeading = when {
            isWhitespace(text.first()) -> false
            before == null -> true
            before.isEmpty() -> false
            else -> !isWhitespace(before.last())
        }
        val needsTrailing = (after.isNullOrEmpty() || !isWhitespace(after.first())) &&
            !isWhitespace(text.last())
        if (needsLeading) sb.insert(0, ' ')
        if (needsTrailing) sb.append(' ')
        return sb.toString()
    }

    // Character.isWhitespace() alone misses NBSP (U+00A0); isSpaceChar() covers
    // NBSP and friends but not newline/tab — the union covers both.
    private fun isWhitespace(c: Char): Boolean =
        Character.isWhitespace(c) || Character.isSpaceChar(c)
}
