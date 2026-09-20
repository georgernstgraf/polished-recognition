package com.georgernstgraf.polishedrecognition.service

/**
 * Pure word-deletion policy for the IME delete-word button (#90): given the
 * text before the cursor, returns how many chars one tap deletes — trailing
 * whitespace plus the preceding whitespace-delimited token (punctuation
 * stays glued to its token, so "hello," goes in one tap). Empty input
 * deletes nothing. Whitespace test matches [InsertionSpacingPolicy]
 * (space, tab, newline, NBSP).
 */
object DeleteWordPolicy {

    fun charsToDelete(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && isSep(before[i - 1])) i--
        while (i > 0 && !isSep(before[i - 1])) i--
        return before.length - i
    }

    private fun isSep(c: Char): Boolean =
        Character.isWhitespace(c) || Character.isSpaceChar(c)
}
