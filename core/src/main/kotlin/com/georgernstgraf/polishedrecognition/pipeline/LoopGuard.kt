package com.georgernstgraf.polishedrecognition.pipeline

/**
 * Production loop guard (#123) built on the [LoopMask] detector — the
 * repetition-loop mask ported from the #122 seam study.
 *
 * **When it runs (owner decision 2026-11-20):** only on a fragment produced by
 * a FORCED (hard) cut. Under the production `SeamPolicy.FORCED_ONLY` the
 * decoder gets the seam mechanism — 1 s acoustic pre-roll + transcript prompt
 * — exactly there, so a split word is the one place where a Whisper
 * repetition loop is both likely and propagated into the NEXT fragment's
 * prompt. Silence-aligned cuts upload bare and never reach this guard.
 *
 * **What it does:** every detected loop span (phrase of 1..8 tokens repeated
 * >= 4x, [LoopMask] defaults) is replaced by its FIRST period, keeping the
 * original casing and punctuation of that period. Collapsing rather than
 * truncating is deliberate: a loop at the transcript tail leaves one
 * occurrence and nothing hallucinated behind it, while a loop in the middle
 * keeps the real text that follows. No setting, no extra evidence stream —
 * `session.json` already contrasts `sttRaw` (server answer) with
 * `sttTrimmed` (what was stored) per fragment.
 *
 * Deliberately minimal: the loop fraction in daily dictation is small (a
 * quality-undercut seam is ~6 %, two in a row ~0.4 %, three ~0.02 %), so this
 * must never grow into a policy machine.
 */
object LoopGuard {

    /** Word-ish token pattern — identical to the echo-trim tokenizer (#117). */
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")

    /**
     * Collapses repetition loops in [text] to one occurrence of their phrase.
     * Returns [text] unchanged when no loop is found; an empty/blank input is
     * returned as-is (never produces an empty transcript out of a non-empty
     * one, so the fragment still yields a token).
     */
    fun collapse(
        text: String,
        minRep: Int = LoopMask.DEFAULT_MIN_REPETITIONS,
        maxPhraseLen: Int = LoopMask.DEFAULT_MAX_PHRASE_LEN
    ): String {
        if (text.isBlank()) return text
        val matches = TOKEN_REGEX.findAll(text).toList()
        if (matches.size < minRep) return text
        val tokens = matches.map { it.value.lowercase() }
        val spans = maximalSpans(LoopMask.loopSpans(tokens, minRep, maxPhraseLen))
        if (spans.isEmpty()) return text

        val out = StringBuilder(text.length)
        var pos = 0
        for (span in spans) {
            val first = matches[span.start].range.first
            val last = matches[span.endExclusive - 1].range.last + 1
            val period = periodLen(tokens, span, maxPhraseLen)
            val periodEnd = matches[span.start + period - 1].range.last + 1
            out.append(text, pos, first)
            out.append(text, first, periodEnd)
            pos = last
        }
        out.append(text, pos, text.length)
        return out.toString()
    }

    /**
     * [LoopMask.loopSpans] reports the same run once per matching phrase
     * length and per start position. Overlaps resolve earliest-start-first;
     * at an identical start the LONGEST span wins (the opposite tie-break of
     * [LoopMask.compress], which only needs one sentinel). Skipping a span
     * that starts inside an accepted one mirrors `compress`.
     */
    private fun maximalSpans(spans: List<LoopMask.Span>): List<LoopMask.Span> {
        if (spans.isEmpty()) return spans
        val sorted = spans.sortedWith(compareBy({ it.start }, { -it.endExclusive }))
        val kept = mutableListOf<LoopMask.Span>()
        var pos = 0
        for (span in sorted) {
            if (span.start < pos) continue
            kept.add(span)
            pos = span.endExclusive
        }
        return kept
    }

    /**
     * Shortest phrase length whose repetitions fill the span — a span found at
     * a longer unit length is usually a multiple of a shorter period
     * ("das ist ein test" x4 is also "das ist ein test" x1). Falls back to the
     * whole span (a single period then reproduces it unchanged).
     */
    private fun periodLen(tokens: List<String>, span: LoopMask.Span, maxPhraseLen: Int): Int {
        val total = span.endExclusive - span.start
        for (p in 1..minOf(maxPhraseLen, total)) {
            if (total % p != 0) continue
            val block = tokens.subList(span.start, span.start + p)
            var match = true
            var r = 1
            while (r * p < total) {
                if (tokens.subList(span.start + r * p, span.start + (r + 1) * p) != block) {
                    match = false
                    break
                }
                r++
            }
            if (match) return p
        }
        return total
    }
}
