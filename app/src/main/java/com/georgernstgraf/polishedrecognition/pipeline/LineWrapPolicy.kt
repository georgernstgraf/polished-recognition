package com.georgernstgraf.polishedrecognition.pipeline

/**
 * Word-wraps the committed transcription output (#81, #103).
 *
 * - `width <= 0` disables wrapping: [text] is returned unchanged.
 * - Existing line breaks are preserved: each paragraph (split on `\n`) is
 *   wrapped independently; empty paragraphs stay empty.
 * - Greedy word wrap: words (split on runs of whitespace) are packed onto
 *   lines of at most [width] characters, joined with single spaces.
 * - Hyphens (`-`, `–`, `—`) are additional break opportunities (#103): a word
 *   may be split **after** a dash, so the dash stays at the end of the line.
 *   A fragment shorter than [MIN_DASH_FRAGMENT] characters on either side of a
 *   dash is merged with its neighbour, so no orphan fragment (`a-` / `-a`)
 *   ends up on its own line. The break point is chosen greedily, i.e. at the
 *   last dash that still fits.
 * - URL-like tokens (`scheme://…`, `www.…`) are never split, even at dashes.
 * - A single unbreakable token longer than [width] occupies its own line
 *   unwrapped (never hard-split, so long tokens survive intact).
 */
object LineWrapPolicy {

    /** Minimum characters on either side of a dash for it to be a break point. */
    private const val MIN_DASH_FRAGMENT = 4

    /** Split after a dash, unless it is a non-breaking hyphen (U+2011). */
    private val DASH_BREAK = Regex("(?<=[-\u2013\u2014])")

    private val URL_LIKE = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*://|www\\.)", RegexOption.IGNORE_CASE)

    /** One packed unit: a word, or a dash-delimited fragment of a word. */
    private data class Piece(val text: String, val spaceBefore: Boolean)

    fun wrap(text: String, width: Int): String {
        if (width <= 0 || text.isEmpty()) return text
        return text.split("\n").joinToString("\n") { wrapParagraph(it, width) }
    }

    private fun wrapParagraph(paragraph: String, width: Int): String {
        val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""

        val pieces = mutableListOf<Piece>()
        for (word in words) {
            chunksFor(word).forEachIndexed { index, chunk ->
                pieces.add(Piece(chunk, spaceBefore = index == 0))
            }
        }

        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            val separator = if (piece.spaceBefore && current.isNotEmpty()) " " else ""
            if (current.isEmpty() || current.length + separator.length + piece.text.length <= width) {
                current.append(separator).append(piece.text)
            } else {
                lines.add(current.toString())
                current.setLength(0)
                current.append(piece.text)
            }
        }
        lines.add(current.toString())
        return lines.joinToString("\n")
    }

    /**
     * Splits [word] into breakable fragments. Returns the word as a single
     * fragment when it is URL-like, contains no dash break point, or collapses
     * to a single fragment after merging short ones.
     */
    private fun chunksFor(word: String): List<String> {
        if (URL_LIKE.containsMatchIn(word)) return listOf(word)
        val raw = word.split(DASH_BREAK).filter { it.isNotEmpty() }
        if (raw.size <= 1) return listOf(word)
        return mergeShortFragments(raw)
    }

    /**
     * Merges fragments shorter than [MIN_DASH_FRAGMENT] with a neighbour, so no
     * orphan fragment can end up on its own line. Short fragments are merged
     * forward; a short tail is merged backward.
     */
    private fun mergeShortFragments(raw: List<String>): List<String> {
        val merged = mutableListOf<String>()
        for (fragment in raw) {
            if (merged.isNotEmpty() && merged.last().length < MIN_DASH_FRAGMENT) {
                merged[merged.size - 1] = merged.last() + fragment
            } else {
                merged.add(fragment)
            }
        }
        if (merged.size > 1 && merged.last().length < MIN_DASH_FRAGMENT) {
            val tail = merged.removeAt(merged.size - 1)
            merged[merged.size - 1] = merged.last() + tail
        }
        return merged
    }
}
