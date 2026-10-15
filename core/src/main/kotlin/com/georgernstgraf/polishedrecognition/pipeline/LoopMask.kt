package com.georgernstgraf.polishedrecognition.pipeline

/**
 * Repetition-loop mask (ported verbatim from the #122 seam-study analyzer,
 * `tools/seam-study/analyze.py` `loop_spans`/`compress_loops`, 2026-10-15
 * into the shared pipeline core as part of #146).
 *
 * **Why this exists (#122 data):** Whisper repetition-loops on repetitive,
 * musical, noisy or disfluent content — it produced ~1528 vs ~146 tokens on
 * reliable study runs, looping in BOTH a fragment join AND a full-context
 * reference of the same audio. Any diff between two transcripts of the same
 * audio MUST compress repetition spans on both sides first, or the metric
 * measures the reference's own loops instead of the actual differences. A
 * loop present in BOTH texts then matches as one token instead of ~100; a
 * loop in only one contributes exactly one error, not a hundred.
 *
 * The #143 removal of the online shadow does NOT remove this hazard: every
 * offline comparison (the #144/:analyzer gregor Gegenprobe) inherits it
 * verbatim. **This object also doubles as the #123 production loop-guard
 * core** — a repeated n-gram detector over transcript tokens — which is why
 * it lives in the pure `:core` pipeline package with no analyzer-specific
 * assumptions. [compress] is the diff-time mask; a future #123 guard can
 * reuse [loopSpans] to truncate a loop BEFORE a transcript is stored or
 * prompted.
 *
 * Semantics (unchanged from the Python original):
 * - A loop span is a phrase of length 1..[DEFAULT_MAX_PHRASE_LEN] tokens
 *   repeated >= [DEFAULT_MIN_REPETITIONS] times CONSECUTIVELY.
 * - Spans are found per phrase length L ascending and per start position,
 *   each maximal run consumed whole (the scan jumps past `r * L` tokens) —
 *   shorter phrase lengths cannot see inside a consumed run at the same L,
 *   but longer lengths may still find larger spans; [compress] resolves
 *   overlaps by earliest-start-first and skips any span starting before the
 *   previous one ended (duplicated/nested spans collapse into one sentinel).
 * - The sentinel token [SENTINEL] is deliberately textual so two compressed
 *   token lists line up on it.
 *
 * Tokenization is the CALLER's concern: this class operates on opaque
 * non-empty tokens (the production transcript tokenizer is
 * `[\p{L}\p{N}]+` lowercased; the analyzer feeds the same shape). The
 * tokenizer itself lives with the callers — no drift-prone duplicate here.
 */
object LoopMask {

    /** The single token a compressed loop span is replaced with. */
    const val SENTINEL = "<loop>"

    /** A phrase must repeat at least this many times to count as a loop. */
    const val DEFAULT_MIN_REPETITIONS = 4

    /** Longest phrase length (in tokens) considered as a loop unit. */
    const val DEFAULT_MAX_PHRASE_LEN = 8

    /** A detected loop span: [first] inclusive, [second] exclusive token index. */
    data class Span(val start: Int, val endExclusive: Int)

    /** Result of [compress]: the masked token list + how many tokens the sentinels replaced. */
    data class Compressed(val tokens: List<String>, val loopedTokenCount: Int)

    /**
     * Spans (start, endExclusive) of a phrase (len 1..[maxPhraseLen])
     * repeating >= [minRep] times, in discovery order (L ascending, then
     * start ascending) — identical to the Python original's list order.
     */
    fun loopSpans(
        tokens: List<String>,
        minRep: Int = DEFAULT_MIN_REPETITIONS,
        maxPhraseLen: Int = DEFAULT_MAX_PHRASE_LEN
    ): List<Span> {
        require(minRep >= 2) { "minRep must be >= 2" }
        require(maxPhraseLen >= 1) { "maxPhraseLen must be >= 1" }
        val n = tokens.size
        val spans = mutableListOf<Span>()
        for (len in 1..maxPhraseLen) {
            var i = 0
            while (i + len * minRep <= n) {
                val block = tokens.subList(i, i + len)
                var allMatch = true
                for (r in 1 until minRep) {
                    if (tokens.subList(i + r * len, i + (r + 1) * len) != block) {
                        allMatch = false
                        break
                    }
                }
                if (allMatch) {
                    var r = minRep
                    while (i + (r + 1) * len <= n &&
                        tokens.subList(i + r * len, i + (r + 1) * len) == block
                    ) {
                        r++
                    }
                    spans.add(Span(i, i + r * len))
                    i += r * len
                } else {
                    i++
                }
            }
        }
        return spans
    }

    /**
     * Replaces every repetition-loop span with a single [SENTINEL] token.
     * Overlapping spans are resolved earliest-start-first; a span starting
     * inside an already-replaced span is skipped (it is part of that loop).
     */
    fun compress(
        tokens: List<String>,
        minRep: Int = DEFAULT_MIN_REPETITIONS,
        maxPhraseLen: Int = DEFAULT_MAX_PHRASE_LEN
    ): Compressed {
        val spans = loopSpans(tokens, minRep, maxPhraseLen)
        if (spans.isEmpty()) return Compressed(tokens, 0)
        val sorted = spans.sortedWith(compareBy({ it.start }, { it.endExclusive }))
        val out = mutableListOf<String>()
        var looped = 0
        var pos = 0
        for (span in sorted) {
            if (span.start < pos) continue
            out.addAll(tokens.subList(pos, span.start))
            out.add(SENTINEL)
            looped += span.endExclusive - span.start
            pos = span.endExclusive
        }
        if (pos < tokens.size) out.addAll(tokens.subList(pos, tokens.size))
        return Compressed(out, looped)
    }
}
