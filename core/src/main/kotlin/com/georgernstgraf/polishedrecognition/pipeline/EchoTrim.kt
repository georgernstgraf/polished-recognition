package com.georgernstgraf.polishedrecognition.pipeline

/**
 * #117 round 2: trims the ECHOED pre-roll from a fragment's transcript.
 *
 * The pre-roll is ACOUSTIC — its words re-appear at the transcript start even
 * when the text prompt is disabled, so the trim runs independent of the prompt
 * and only when the upload actually carried a pre-roll (no pre-roll → no echo
 * → trimming could only eat real repetitions).
 *
 * Matching is deliberately conservative against the previous fragment's known
 * tail: the head token must equal the tail token, at most 1 mismatch is
 * tolerated for k ≥ 2, and NO match keeps everything — duplication beats loss
 * (the LLM polish can dedupe, lost words are gone).
 *
 * ## #185: why this is its own class
 *
 * The recovery re-run replays a journal fragment byte-identically and must
 * trim its echo against the previous STORED transcript exactly like the live
 * worker does. Extracting the algorithm keeps that one rule in one place;
 * [FragmentTranscriber] delegates here and its own tests stay the regression
 * proof for the live path.
 *
 * Pure function: same input, same output, no state, no IO.
 */
object EchoTrim {

    /**
     * Echo-trim match window (#117 round 2): at most 8 tokens of head. Also the
     * live worker's constant — never introduce a second value.
     */
    const val MAX_ECHO_TOKENS = 8

    /** Word-ish token pattern (letters + digits only). */
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")

    /** [text] without its leading echo; [echoTokens] = how many were dropped. */
    data class Result(val text: String, val echoTokens: Int)

    /**
     * Trims the echo of [knownTail] (the previous fragment's transcript) from
     * the start of [text]. [hadPreRoll] `false` returns [text] unchanged —
     * there was no duplicate audio, so a match would be a real repetition.
     */
    fun trim(text: String, knownTail: String?, hadPreRoll: Boolean): Result {
        if (!hadPreRoll) return Result(text, 0)
        if (knownTail.isNullOrBlank() || text.isBlank()) return Result(text, 0)
        val known = TOKEN_REGEX.findAll(knownTail).map { it.value.lowercase() }.toList()
        val fresh = TOKEN_REGEX.findAll(text)
            .map { it.value.lowercase() to it.range.first }
            .toList()
        var echoTokens = 0
        for (k in minOf(MAX_ECHO_TOKENS, fresh.size) downTo 1) {
            if (known.size < k) continue
            var mismatches = 0
            var matched = true
            for (j in 0 until k) {
                val freshToken = fresh[j].first
                val knownToken = known[known.size - k + j]
                if (freshToken == knownToken) continue
                if (j == 0) { matched = false; break } // head[0] == tail[0] required
                if (++mismatches > 1) { matched = false; break }
            }
            if (matched) {
                echoTokens = k
                break
            }
        }
        if (echoTokens == 0) return Result(text, 0)
        // Drop the first echoTokens tokens; the text is rebuilt from the NEXT
        // token's offset so leading punctuation/whitespace goes too (a
        // full-echo fragment trims to empty).
        val trimmed = if (echoTokens >= fresh.size) "" else text.substring(fresh[echoTokens].second)
        return Result(trimmed, echoTokens)
    }
}
