package com.georgernstgraf.polishedrecognition.analyzer.analysis

import com.georgernstgraf.polishedrecognition.audio.SeamOverlap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The analyzer tokenizer — the port of `analyze.py`'s `TOK`/`toks()`
 * (`[\w\u00c0-\u024f]+`, lowercased). Java's `\w` is ASCII-only, so the
 * Kotlin pattern is `[\p{L}\p{N}_]+`: Unicode letters + digits + underscore.
 * On the #129 fixture (ASCII) the two are token-for-token identical; for
 * exotic scripts the classes are a documented approximation (this is the
 * analyze.py-compat-STRUCK replacement — the canonical format is defined
 * once, here, and the #122/#129 fixtures only test the LOGIC).
 */
object Tokenize {

    val TOKEN = Regex("[\\p{L}\\p{N}_]+")

    /** Word-ish tokens, lowercased. Blank/null → empty. */
    fun tokens(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList()
        else TOKEN.findAll(text).map { it.value.lowercase() }.toList()
}

/** diff opcode tags mirroring difflib's `get_opcodes` tags. */
enum class DiffTag { EQUAL, DELETE, INSERT, REPLACE }

/** One diff opcode: [i1, i2) in the join, [j1, j2) in the reference. */
data class Opcode(val tag: DiffTag, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

/**
 * LCS-based diff producing difflib-compatible opcodes for the analyzer's
 * purpose. Python's `SequenceMatcher(autojunk=False)` computes the same
 * edit scripts on realistic inputs; WHERE the two may decompose a block
 * differently (replace vs delete+insert), the sums per fragment are
 * IDENTICAL (a replace(i1,i2,j1,j2) contributes the same i2-i1 extra and
 * j2-j1 missing as its delete+insert decomposition) — the attribution only
 * consumes those two totals per opcode.
 */
object Diff {

    /** LCS-length table driven opcodes. O(n·m) — token lists are ≤ a few thousand. */
    fun opcodes(a: List<String>, b: List<String>): List<Opcode> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        // lcs[i][j] = LCS length of a[i..] vs b[j..]
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val ops = mutableListOf<Opcode>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[i] == b[j] -> {
                    ops.add(Opcode(DiffTag.EQUAL, i, i + 1, j, j + 1))
                    i++; j++
                }
                // a exhausted → the rest of b is pure insertion
                i == n -> {
                    ops.add(Opcode(DiffTag.INSERT, i, i, j, j + 1))
                    j++
                }
                // b exhausted → the rest of a is pure deletion
                j == m -> {
                    ops.add(Opcode(DiffTag.DELETE, i, i + 1, j, j))
                    i++
                }
                // both sides left: prefer the side that keeps the LCS longer
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    ops.add(Opcode(DiffTag.DELETE, i, i + 1, j, j))
                    i++
                }
                else -> {
                    ops.add(Opcode(DiffTag.INSERT, i, i, j, j + 1))
                    j++
                }
            }
        }
        return ops
    }
}

/**
 * Statistics helpers — the ports of `analyze.py`'s `pct`/`spearman`/`med`.
 * Deterministic, no external deps: ceil-index percentiles on a pre-sorted
 * list, average-rank Spearman correlation.
 */
object Stats {

    /** Ceil-index percentile on a PRE-SORTED list; null for an empty list. */
    fun pct(sortedValues: List<Double>, p: Double): Double? {
        if (sortedValues.isEmpty()) return null
        val i = maxOf(0, ceil(p * sortedValues.size).toInt() - 1)
        return sortedValues[minOf(i, sortedValues.size - 1)]
    }

    /** Median; null for an empty list (even counts: mean of the two middle values). */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun ranks(v: List<Double>): DoubleArray {
        val order = v.withIndex().sortedBy { it.value }.map { it.index }
        val r = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
            for (t in i..j) r[order[t]] = (i + j) / 2.0 + 1
            i = j + 1
        }
        return r
    }

    /**
     * Spearman rank correlation; null for < 2 points, 0.0 on ties
     * (undefined), rounded to 3 decimals like the Python original.
     */
    fun spearman(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size < 2) return null
        val a = ranks(xs)
        val b = ranks(ys)
        val n = a.size
        val ma = a.average()
        val mb = b.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (k in 0 until n) {
            cov += (a[k] - ma) * (b[k] - mb)
            va += (a[k] - ma) * (a[k] - ma)
            vb += (b[k] - mb) * (b[k] - mb)
        }
        if (va == 0.0 || vb == 0.0) return 0.0
        return (cov / kotlin.math.sqrt(va * vb) * 1000).roundToInt() / 1000.0
    }

    fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0
}
