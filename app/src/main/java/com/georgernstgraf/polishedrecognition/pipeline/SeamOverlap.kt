package com.georgernstgraf.polishedrecognition.pipeline

/**
 * Seam overlap detection (#122): how much of a fragment's transcript head
 * repeats the previous fragment's transcript tail. A value > 0 at a seam is
 * the fingerprint of the round-2 symptom — a word straddling a forced cut,
 * transcribed by both fragments. It is also the evidence that distinguishes a
 * *surviving echo* (untrimmed pre-roll/prompt text) from a legitimate
 * repetition.
 *
 * Pure and token-based (`[\p{L}\p{N}]+`, lowercased) so the metric is stable
 * across punctuation/casing differences.
 */
object SeamOverlap {

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Word-ish tokens, lowercased. */
    fun tokens(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList()
        else TOKEN.findAll(text).map { it.value.lowercase() }.toList()

    /** Word count of a transcript (blank → 0). */
    fun wordCount(text: String?): Int = tokens(text).size

    /**
     * Length of the longest token run that is a SUFFIX of [previous] and a
     * PREFIX of [next]; 0 when they do not share one.
     */
    fun longestTokenOverlap(previous: String?, next: String?): Int {
        val a = tokens(previous)
        val b = tokens(next)
        if (a.isEmpty() || b.isEmpty()) return 0
        for (k in minOf(a.size, b.size) downTo 1) {
            if (a.subList(a.size - k, a.size) == b.subList(0, k)) return k
        }
        return 0
    }

    /** The overlapping token run itself (the repeated text), or null. */
    fun overlapText(previous: String?, next: String?): String? {
        val k = longestTokenOverlap(previous, next)
        if (k == 0) return null
        return tokens(previous).takeLast(k).joinToString(" ")
    }
}
