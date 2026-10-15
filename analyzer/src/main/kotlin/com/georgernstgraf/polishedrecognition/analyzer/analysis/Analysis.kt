package com.georgernstgraf.polishedrecognition.analyzer.analysis

import com.georgernstgraf.polishedrecognition.audio.SeamOverlap
import com.georgernstgraf.polishedrecognition.pipeline.LoopMask

/**
 * The analysis core — the port of the proven #122/#129 logic from
 * `tools/seam-study/analyze.py` (`analyze()`, `seam_records()`,
 * `word_split_flag()`, `attribute()`), operating on the analyzer's OWN
 * canonical inputs (the analyze.py compatibility is struck — this defines
 * the canonical result fields once, documented in `analyzer/README.md`).
 *
 * Inputs are one [FragmentEvidence] per fragment (the per-fragment forensic
 * record of a run — in corpus mode assembled from the session bundle, in
 * tests hand-built), the fragment JOIN text and the full-context REFERENCE
 * text. The reference is the same-Whisper full-context transcript of the
 * same audio — NOT ground truth (the README's caveats apply verbatim); the
 * RELIABILITY filter and the loop-mask discipline carry over unchanged.
 */
object Analysis {

    /** Reliability window of the join/reference token ratio (#122). */
    const val RELIABLE_LO = 0.80
    const val RELIABLE_HI = 1.25

    /** Sentence-final punctuation for the word-split heuristic (#129). */
    private val SENTENCE_FINAL = setOf('.', '!', '?', '…')

    /**
     * One fragment's forensic evidence (the analyzer's canonical per-fragment
     * input — mirrors what a session bundle records).
     *
     * @property text            the STORED (echo-trimmed) transcript
     * @property seamForced      was the boundary at this fragment's START a forced cut? (null for fragment 0)
     * @property stitched        was this fragment's START seam stitched? (null for fragment 0)
     * @property q               this fragment's START-seam silence quality (null when unmeasured)
     * @property echoTokens      echo-trim token count for this fragment
     * @property deviceOverlapTokens  the ON-DEVICE seam-overlap metric (when available) — cross-check
     */
    data class FragmentEvidence(
        val index: Int,
        val text: String?,
        val seamForced: Boolean? = null,
        val stitched: Boolean? = null,
        val q: Double? = null,
        val echoTokens: Int? = null,
        val deviceOverlapTokens: Int? = null
    )

    /** Per-fragment analysis row (canonical `fragments[]` field). */
    data class FragmentStat(
        val index: Int,
        val words: Int,
        val forced: Boolean?,
        val echo: Int,
        val extra: Int,
        val missing: Int
    )

    /** Per-seam analysis row (canonical `seams[]` field). */
    data class SeamStat(
        val seam: Int,
        val q: Double?,
        val stitched: Boolean?,
        val overlap: Int,
        val split: Boolean,
        val unclean: Boolean,
        /** Device-vs-port seam-overlap cross-check (0 when no device value is known). */
        val tokMirrorMismatch: Boolean
    )

    /** The complete analysis of one run (canonical result body). */
    data class Analysis(
        val fragments: Int,
        val join: Int,
        val reference: Int,
        val joinLoops: Int,
        val referenceLoops: Int,
        val ratio: Double,
        val reliable: Boolean,
        val extra: Int,
        val missing: Int,
        val forcedSeams: Int,
        val silenceSeams: Int,
        val perFragment: List<FragmentStat>,
        val seams: Int,
        val seamsUnclean: Int,
        val seamOverlapTotal: Int,
        val wordSplits: Int,
        val qMedian: Double?,
        val qP25: Double?,
        val qP75: Double?,
        val qStitchedMedian: Double?,
        val qBareMedian: Double?,
        val qUncleanMedian: Double?,
        val qCleanMedian: Double?,
        val qSpearman: Double?,
        val trimFalsePositives: Int,
        val perSeam: List<SeamStat>
    )

    /**
     * WEAK word/sentence-split heuristic (#129, ported verbatim): the
     * previous transcript ends WITHOUT sentence-final punctuation AND the
     * next begins with a lowercase letter. Limits (documented): Whisper
     * transcripts of continuous speech often omit punctuation and start
     * lowercase at CLEAN seams (false positives), and German capitalizes
     * nouns (false negatives) — only a secondary signal next to the overlap.
     */
    fun wordSplitFlag(previous: String?, current: String?): Boolean {
        val prev = (previous ?: "").trimEnd()
        val cur = (current ?: "").trimStart()
        if (prev.isEmpty() || cur.isEmpty()) return false
        return prev.last() !in SENTENCE_FINAL &&
            cur.first().isLetter() && cur.first().isLowerCase()
    }

    /**
     * Per-seam evidence list. Seam i (1..n-1) is the boundary BEFORE fragment
     * i: its Q/stitched come from fragment i's START-seam record (in the
     * session bundle that block is stored on fragment i; in the legacy
     * prepare_all.json format it was the record of fragment i-1 — same cut),
     * its cleanliness from the adjacent TRIMMED transcripts.
     */
    fun seamRecords(frags: List<FragmentEvidence>): List<SeamStat> {
        val out = mutableListOf<SeamStat>()
        for (i in 1 until frags.size) {
            val prev = frags[i - 1]
            val cur = frags[i]
            val overlap = SeamOverlap.longestTokenOverlap(prev.text, cur.text)
            val split = wordSplitFlag(prev.text, cur.text)
            out.add(
                SeamStat(
                    seam = i,
                    q = cur.q,
                    stitched = cur.stitched,
                    overlap = overlap,
                    split = split,
                    unclean = overlap > 0 || split,
                    // The on-device seamOverlapTokens field (stt-fragment.json
                    // evidence, when present) must equal the port's value —
                    // the port IS the real Kotlin metric, so a mismatch can
                    // only mean mis-mapped evidence.
                    tokMirrorMismatch = cur.deviceOverlapTokens != null &&
                        cur.deviceOverlapTokens != overlap
                )
            )
        }
        return out
    }

    /**
     * Maps the token diff back onto the fragment boundaries: (extra, missing)
     * per fragment, index-aligned to [counts]. Boundary-less tail edits
     * (beyond the last fragment) attribute to the LAST fragment.
     */
    fun attribute(join: List<String>, reference: List<String>, counts: List<Int>): Pair<IntArray, IntArray> {
        val n = counts.size
        val extra = IntArray(n)
        val missing = IntArray(n)
        if (n == 0) return extra to missing
        val bounds = ArrayList<Pair<Int, Int>>(n)
        var acc = 0
        for (c in counts) {
            bounds.add(acc to acc + c)
            acc += c
        }
        fun fragOf(idx: Int): Int {
            for ((fi, range) in bounds.withIndex()) {
                if (idx in range.first until range.second) return fi
            }
            return maxOf(0, n - 1)
        }
        for (op in Diff.opcodes(join, reference)) {
            if (op.tag == DiffTag.EQUAL) continue
            val target = fragOf(minOf(op.i1, maxOf(0, acc - 1)))
            if (op.tag == DiffTag.DELETE || op.tag == DiffTag.REPLACE) extra[target] += op.i2 - op.i1
            if (op.tag == DiffTag.INSERT || op.tag == DiffTag.REPLACE) missing[target] += op.j2 - op.j1
        }
        return extra to missing
    }

    /**
     * The full analysis of one run: loop-masked diff of the fragment join vs
     * the full-context reference, per-fragment attribution, per-seam quality
     * evidence, Q distributions and the trim-false-positive flag — all
     * identical to `analyze.py analyze()` semantics.
     */
    fun analyze(
        frags: List<FragmentEvidence>,
        joinText: String,
        referenceText: String
    ): Analysis {
        val rawJoin = Tokenize.tokens(joinText)
        val rawReference = Tokenize.tokens(referenceText)

        // Loop-mask PER FRAGMENT (preserves fragment boundaries) and the
        // reference GLOBALLY (#122 discipline).
        val fragCompressed = frags.map { LoopMask.compress(Tokenize.tokens(it.text)) }
        val joinLoops = fragCompressed.sumOf { it.loopedTokenCount }
        val join = fragCompressed.flatMap { it.tokens }
        val referenceCompressed = LoopMask.compress(rawReference)
        val counts = fragCompressed.map { it.tokens.size }
        val (extra, missing) = if (frags.isEmpty()) intArrayOf() to intArrayOf()
        else attribute(join, referenceCompressed.tokens, counts)

        val ratio = if (rawReference.isNotEmpty()) rawJoin.size.toDouble() / rawReference.size else 0.0
        val reliable = rawReference.isNotEmpty() &&
            ratio in RELIABLE_LO..RELIABLE_HI && rawJoin.isNotEmpty()

        val perFragment = frags.mapIndexed { i, f ->
            FragmentStat(
                index = i,
                words = Tokenize.tokens(f.text).size,
                forced = if (i > 0) f.seamForced else null,
                echo = f.echoTokens ?: 0,
                extra = if (i < extra.size) extra[i] else 0,
                missing = if (i < missing.size) missing[i] else 0
            )
        }

        val perSeam = if (frags.isEmpty()) emptyList() else seamRecords(frags)
        val qs = perSeam.mapNotNull { it.q }.sorted()
        val qsStitched = perSeam.filter { it.q != null && it.stitched == true }.map { it.q!! }.sorted()
        val qsBare = perSeam.filter { it.q != null && it.stitched == false }.map { it.q!! }.sorted()
        val qsUnclean = perSeam.filter { it.q != null && it.unclean }.map { it.q!! }.sorted()
        val qsClean = perSeam.filter { it.q != null && !it.unclean }.map { it.q!! }.sorted()

        // Trim false-positive heuristic (#129): the echo trim removed tokens
        // from a fragment's head, and the loop-masked diff says at least that
        // many words are MISSING from that fragment's span — the "echo" was
        // real speech. A FLAG for manual review, not a verdict.
        val trimFp = perFragment.count { (it.echo) > 0 && it.missing >= it.echo }
        val qSpear = Stats.spearman(
            perSeam.mapNotNull { it.q },
            perSeam.filter { it.q != null }.map { if (it.unclean) 1.0 else 0.0 }
        )

        return Analysis(
            fragments = frags.size,
            join = rawJoin.size,
            reference = rawReference.size,
            joinLoops = joinLoops,
            referenceLoops = referenceCompressed.loopedTokenCount,
            ratio = Stats.round2(ratio),
            reliable = reliable,
            extra = extra.sum(),
            missing = missing.sum(),
            forcedSeams = frags.count { it.seamForced == true },
            silenceSeams = frags.count { it.index > 0 && it.seamForced != true },
            perFragment = perFragment,
            seams = perSeam.size,
            seamsUnclean = perSeam.count { it.unclean },
            seamOverlapTotal = perSeam.sumOf { it.overlap },
            wordSplits = perSeam.count { it.split },
            qMedian = Stats.median(qs),
            qP25 = Stats.pct(qs, 0.25),
            qP75 = Stats.pct(qs, 0.75),
            qStitchedMedian = Stats.median(qsStitched),
            qBareMedian = Stats.median(qsBare),
            qUncleanMedian = Stats.median(qsUnclean),
            qCleanMedian = Stats.median(qsClean),
            qSpearman = qSpear,
            trimFalsePositives = trimFp,
            perSeam = perSeam
        )
    }
}
