package com.georgernstgraf.polishedrecognition.service

/**
 * Decides how whitespace must be padded around the text committed into the editor
 * (#66): a leading space when no whitespace precedes the cursor (including field
 * start, which some apps report as null), and a trailing space when no whitespace
 * follows (including field end). Whitespace already present in the transcribed
 * text itself suppresses padding so it is never doubled.
 */
object InsertionSpacingPolicy {

    fun apply(text: String, before: CharSequence?, after: CharSequence?): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text)
        val needsLeading = (before.isNullOrEmpty() || !isWhitespace(before.last())) &&
            !isWhitespace(text.first())
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
