package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests of the loop-mask port (#146 — verbatim from
 * `tools/seam-study/analyze.py` `loop_spans`/`compress_loops`), including the
 * loop-mask expectations the analyzer's `--selftest` asserts on the #129
 * fixture (`join_loops = 0`, `shadow_loops = 0` on the fixture texts).
 *
 * The full fixture regression (extra/missing/err100w/Q statistics) lives in
 * the :analyzer module where the analysis core was ported.
 */
class LoopMaskTest {

    // --- loopSpans ---

    @Test
    fun `no repetition yields no spans`() {
        assertThat(LoopMask.loopSpans(listOf("a", "b", "c", "a", "b"))).isEmpty()
    }

    @Test
    fun `three repetitions are below the threshold`() {
        assertThat(LoopMask.loopSpans(listOf("x", "y", "x", "y", "x", "y", "z"))).isEmpty()
    }

    @Test
    fun `a single word repeated four times is one span`() {
        assertThat(LoopMask.loopSpans(listOf("x", "w", "w", "w", "w", "y")))
            .isEqualTo(listOf(LoopMask.Span(1, 5)))
    }

    @Test
    fun `a repeated run is consumed maximally`() {
        // 6 identical tokens: the scan consumes all 6 in one span (r grows
        // past minRep while the block keeps matching).
        assertThat(LoopMask.loopSpans(listOf("w", "w", "w", "w", "w", "w")))
            .isEqualTo(listOf(LoopMask.Span(0, 6)))
    }

    @Test
    fun `a two-word phrase repeated four times is one span`() {
        assertThat(
            LoopMask.loopSpans(listOf("a", "b", "a", "b", "a", "b", "a", "b"))
        ).isEqualTo(listOf(LoopMask.Span(0, 8)))
    }

    @Test
    fun `a phrase longer than the maximum is not a loop`() {
        // 9-word phrase repeated twice — beyond maxPhraseLen 8 at any unit
        // length, and no shorter-period loop exists inside.
        val w = (1..9).map { "t$it" } + (1..9).map { "t$it" }
        assertThat(LoopMask.loopSpans(w)).isEmpty()
    }

    @Test
    fun `nested loops of different phrase lengths are all reported`() {
        // "a b a b a b a b c c c c": the 2-word phrase spans 0..8, the
        // single-token c-run spans 8..12.
        assertThat(
            LoopMask.loopSpans(listOf("a", "b", "a", "b", "a", "b", "a", "b", "c", "c", "c", "c"))
        ).containsExactly(LoopMask.Span(0, 8), LoopMask.Span(8, 12))
    }

    @Test
    fun `duplicated spans of different phrase lengths collapse in compress`() {
        // Six identical tokens: L=1 finds (0,6); L=2 finds the same (0,6)
        // (three repetitions of "w w"). compress replaces it exactly once.
        val c = LoopMask.compress(listOf("w", "w", "w", "w", "w", "w"))
        assertThat(c.tokens).isEqualTo(listOf(LoopMask.SENTINEL))
        assertThat(c.loopedTokenCount).isEqualTo(6)
    }

    // --- compress ---

    @Test
    fun `compress without loops returns tokens unchanged`() {
        val tokens = listOf("a", "b", "c")
        val c = LoopMask.compress(tokens)
        assertThat(c.tokens).isSameInstanceAs(tokens)
        assertThat(c.loopedTokenCount).isEqualTo(0)
    }

    @Test
    fun `compress replaces a loop with exactly one sentinel`() {
        val c = LoopMask.compress(listOf("x", "da", "da", "da", "da", "y"))
        assertThat(c.tokens).containsExactly("x", LoopMask.SENTINEL, "y").inOrder()
        assertThat(c.loopedTokenCount).isEqualTo(4)
    }

    @Test
    fun `compress replaces two disjoint loops`() {
        val c = LoopMask.compress(listOf("a", "a", "a", "a", "b", "b", "b", "b"))
        assertThat(c.tokens).containsExactly(LoopMask.SENTINEL, LoopMask.SENTINEL).inOrder()
        assertThat(c.loopedTokenCount).isEqualTo(8)
    }

    @Test
    fun `compress keeps the surrounding tokens in order`() {
        val c = LoopMask.compress(listOf("p", "x", "x", "x", "x", "q", "r", "r", "r", "r", "s"))
        assertThat(c.tokens)
            .containsExactly("p", LoopMask.SENTINEL, "q", LoopMask.SENTINEL, "s").inOrder()
        assertThat(c.loopedTokenCount).isEqualTo(8)
    }

    @Test
    fun `a loop in only one text contributes one error, not a hundred`() {
        // The #122 discipline: join loops, reference does not -> after the
        // mask the diff is exactly one extra token.
        val join = LoopMask.compress(listOf("fix", "go", "go", "go", "go", "go", "go", "go", "go", "go", "end"))
        val ref = listOf("fix", "end")
        val extra = join.tokens.filterNot { it == LoopMask.SENTINEL }.count { it !in ref }
        assertThat(extra).isEqualTo(1)
        assertThat(join.loopedTokenCount).isEqualTo(8)
    }

    @Test
    fun `matching loops on both sides collapse to one aligned token`() {
        val join = LoopMask.compress(listOf("go", "go", "go", "go", "end"))
        val ref = LoopMask.compress(listOf("go", "go", "go", "go", "go", "end"))
        // Both sides become ["<loop>", "end"] -> identical token lists.
        assertThat(join.tokens).isEqualTo(ref.tokens)
    }

    // --- fixture-derived expectations (analyze.py --selftest parity) ---

    @Test
    fun `the 129 fixture join and reference carry no loops`() {
        // The #129 synthetic fixture texts (testdata/quality-fixture). The
        // Python selftest asserts join_loops = 0 and shadow_loops = 0 — the
        // ported mask must agree on the same texts.
        val join = "Hallo Welt ich hab fieber und fieber und schnupfen und " +
            "das ist schlecht. morgen geht es mir besser"
        val reference = "Hallo Welt ich hab fieber und fieber und schnupfen und " +
            "das ist schlecht. ist schlecht morgen geht es mir besser"
        // Analyzer tokenizer: word-ish runs lowercased (matches the Python
        // `toks` on this ASCII fixture).
        val tok = Regex("[\\p{L}\\p{N}]+")
        val joinTokens = tok.findAll(join).map { it.value.lowercase() }.toList()
        val refTokens = tok.findAll(reference).map { it.value.lowercase() }.toList()
        assertThat(LoopMask.compress(joinTokens).loopedTokenCount).isEqualTo(0)
        assertThat(LoopMask.compress(refTokens).loopedTokenCount).isEqualTo(0)
        // The "fieber und" duplication is a seam duplicate, NOT a loop:
        // two repetitions are below the >= 4 threshold.
        assertThat(LoopMask.loopSpans(joinTokens)).isEmpty()
    }

    @Test
    fun `minRep and maxPhraseLen are configurable`() {
        // 3 repetitions of a 2-word phrase pass with minRep = 3.
        val w = listOf("a", "b", "a", "b", "a", "b")
        assertThat(LoopMask.loopSpans(w, minRep = 3)).isEqualTo(listOf(LoopMask.Span(0, 6)))
        // A 5-token phrase repeated 4 times is invisible with maxPhraseLen 4.
        val long = (1..5).map { "t$it" }
        val repeated = long + long + long + long
        assertThat(LoopMask.loopSpans(repeated, maxPhraseLen = 4)).isEmpty()
        assertThat(LoopMask.loopSpans(repeated, maxPhraseLen = 5))
            .isEqualTo(listOf(LoopMask.Span(0, 20)))
    }
}
