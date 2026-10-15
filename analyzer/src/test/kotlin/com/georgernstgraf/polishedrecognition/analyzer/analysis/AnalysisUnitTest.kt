package com.georgernstgraf.polishedrecognition.analyzer.analysis

import com.georgernstgraf.polishedrecognition.analyzer.analysis.Analysis.FragmentEvidence
import com.georgernstgraf.polishedrecognition.analyzer.analysis.Analysis.wordSplitFlag
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnalysisUnitTest {

    // --- wordSplitFlag (the #129 heuristic, ported verbatim) ---

    @Test
    fun `word split fires on lowercase continuation after unpunctuated end`() {
        assertThat(wordSplitFlag("das ist gut", "und weiter geht es")).isTrue()
    }

    @Test
    fun `word split stays silent after sentence-final punctuation`() {
        assertThat(wordSplitFlag("Das ist gut.", "Und weiter geht es")).isFalse()
    }

    @Test
    fun `word split stays silent on uppercase continuation`() {
        assertThat(wordSplitFlag("das ist gut", "Weiter geht es")).isFalse()
    }

    @Test
    fun `word split stays silent on empty sides`() {
        assertThat(wordSplitFlag(null, "weiter")).isFalse()
        assertThat(wordSplitFlag("vorher", null)).isFalse()
        assertThat(wordSplitFlag("", "")).isFalse()
    }

    // --- attribute (the fragment-boundary diff attribution) ---

    @Test
    fun `missing tokens attribute to their fragment span`() {
        // join: [a b] [c] — reference: [a b] [x c] (one missing token at frag 1)
        val (e, m) = Analysis.attribute(
            join = listOf("a", "b", "c"),
            reference = listOf("a", "b", "x", "c"),
            counts = listOf(2, 1)
        )
        assertThat(e.sum()).isEqualTo(0)
        assertThat(m.toList()).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `the loop mask runs before attribution so a loop is one extra token`() {
        // join loops ("go" x5), reference does not: the compressed join is
        // ["a", "<loop>", "b"] — exactly ONE extra token, not five.
        val r = Analysis.analyze(
            listOf(
                FragmentEvidence(index = 0, text = "a go go go go go"),
                FragmentEvidence(index = 1, text = "b")
            ),
            joinText = "a go go go go go",
            referenceText = "a b"
        )
        assertThat(r.missing).isEqualTo(0)
        assertThat(r.extra).isEqualTo(1)
        assertThat(r.perFragment[0].extra).isEqualTo(1) // the loop token sits in fragment 0's span
        assertThat(r.joinLoops).isEqualTo(5)
    }

    @Test
    fun `edits beyond the last fragment attribute to it`() {
        val (e, m) = Analysis.attribute(
            join = listOf("a"),
            reference = listOf("a", "x", "y"),
            counts = listOf(1, 1)
        )
        assertThat(e.sum()).isEqualTo(0)
        assertThat(m.sum()).isEqualTo(2)
        assertThat(m[1]).isEqualTo(2)
    }

    @Test
    fun `replace blocks contribute both extra and missing`() {
        val (e, m) = Analysis.attribute(
            join = listOf("a", "x", "y", "c"),
            reference = listOf("a", "p", "q", "c"),
            counts = listOf(2, 2)
        )
        assertThat(e.sum()).isEqualTo(2)
        assertThat(m.sum()).isEqualTo(2)
    }

    // --- Stats (the pct/median/spearman ports) ---

    @Test
    fun `pct is the ceil-index percentile on a pre-sorted list`() {
        assertThat(Stats.pct(emptyList(), 0.25)).isNull()
        assertThat(Stats.pct(listOf(2.0, 40.0), 0.25)).isEqualTo(2.0)
        assertThat(Stats.pct(listOf(2.0, 40.0), 0.75)).isEqualTo(40.0)
        assertThat(Stats.pct(listOf(1.0, 2.0, 3.0, 4.0), 0.5)).isEqualTo(2.0)
        assertThat(Stats.pct(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.5)).isEqualTo(3.0)
    }

    @Test
    fun `median averages the two middle values on even counts`() {
        assertThat(Stats.median(emptyList())).isNull()
        assertThat(Stats.median(listOf(2.0, 40.0))).isEqualTo(21.0)
        assertThat(Stats.median(listOf(1.0, 2.0, 3.0))).isEqualTo(2.0)
    }

    @Test
    fun `spearman is null below two points and zero on ties`() {
        assertThat(Stats.spearman(listOf(1.0), listOf(1.0))).isNull()
        assertThat(Stats.spearman(emptyList(), emptyList())).isNull()
        // constant series → undefined → 0.0
        assertThat(Stats.spearman(listOf(1.0, 2.0, 3.0), listOf(5.0, 5.0, 5.0))).isEqualTo(0.0)
    }

    @Test
    fun `spearman is a perfect negative correlation on the fixture pattern`() {
        // Q high ↔ clean, Q low ↔ unclean → −1.0 (the qualityMin decision input)
        assertThat(
            Stats.spearman(listOf(2.0, 40.0), listOf(1.0, 0.0))
        ).isEqualTo(-1.0)
    }

    @Test
    fun `spearman handles ties with average ranks`() {
        // xs [1,2,2,3]: ranks [1, 2.5, 2.5, 4]; ys strictly increasing [1,2,3,4]: [1,2,3,4]
        val v = Stats.spearman(listOf(1.0, 2.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0, 4.0))!!
        assertThat(v).isGreaterThan(0.85)
        assertThat(v).isLessThan(1.0)
    }

    // --- seamRecords (the per-seam evidence assembly) ---

    @Test
    fun `seam records come from the fragment's own start-seam block`() {
        val frags = listOf(
            FragmentEvidence(index = 0, text = "hallo welt"),
            FragmentEvidence(index = 1, text = "welt hier", q = 2.0, stitched = true),
            FragmentEvidence(index = 2, text = "Das ist gut.", q = 40.0, stitched = false)
        )
        val seams = Analysis.seamRecords(frags)
        assertThat(seams).hasSize(2)
        assertThat(seams[0].q).isEqualTo(2.0)
        assertThat(seams[0].stitched).isTrue()
        assertThat(seams[0].unclean).isTrue() // "welt" overlaps both sides
        assertThat(seams[1].q).isEqualTo(40.0)
        assertThat(seams[1].stitched).isFalse()
        assertThat(seams[1].unclean).isFalse()
    }

    @Test
    fun `the tok-mirror cross-check flags drifted device values`() {
        val frags = listOf(
            FragmentEvidence(index = 0, text = "hallo welt"),
            FragmentEvidence(
                index = 1, text = "welt hier", q = 2.0, stitched = true,
                deviceOverlapTokens = 7
            )
        )
        val seams = Analysis.seamRecords(frags)
        assertThat(seams[0].tokMirrorMismatch).isTrue()
    }
}
