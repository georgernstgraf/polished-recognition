package com.georgernstgraf.polishedrecognition.analyzer.compare

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.google.gson.Gson
import java.io.File

/**
 * `compare` — cross-corpus comparison (#146, owner decision 2: "does the
 * candidate hold in ALL corpora?"). Aggregates the canonical `result.json`
 * Aggregates the canonical `result.json` files of one or more results
 * directories (either passed explicitly or discovered under
 * `corpora/<name>/results/<run-id>/`):
 *
 * - default: a per corpus+class table — runs, reliable ratio, mean err/100w,
 *   extra/missing sums, unclean-seam counts;
 * - `--by-knob <knob>`: the quality-mode response-curve port (from
 *   `analyze.py quality_modes`) — per knob LEVEL the mean err/100w over
 *   reliable runs, the seam-duplication totals and the unclean-seam counts
 *   (a FLAT curve is the stability-knob signature), plus the coordinate-
 *   descent winner: minimal mean err/100w, tie-break lower seam
 *   duplication, then closer to the provisional default.
 */
object CompareCommand {

    /** Result.json read DTO — mirrors the canonical shape RunCommand writes. */
    class ResultDto(
        val formatVersion: Int? = null,
        val runId: String? = null,
        val corpus: String? = null,
        val `class`: String? = null,
        val file: String? = null,
        val sha256: String? = null,
        val sessionId: String? = null,
        val durationSeconds: Double? = null,
        val knobs: Map<String, Any?>? = null,
        val stt: Map<String, Any?>? = null,
        val success: Boolean? = null,
        val error: String? = null,
        val join: Int? = null,
        val reference: Int? = null,
        val joinLoops: Int? = null,
        val referenceLoops: Int? = null,
        val ratio: Double? = null,
        val reliable: Boolean? = null,
        val extra: Int? = null,
        val missing: Int? = null,
        val err100w: Double? = null,
        val q: Map<String, Any?>? = null
    )

    private val gson = Gson()

    /** The provisional defaults (the #126/#128 knob values) — winners tie-break towards them. */
    val PROVISIONAL_DEFAULTS = mapOf<String, Any?>(
        "axisK" to 0.65,
        "qualityMin" to 10.0,
        "axisC" to 100.0,
        "marginKMs" to 150,
        "searchSpan" to "lateHalf"
    )

    fun run(args: Args) {
        val dirs = resolveDirs(args)
        val rows = dirs.flatMap { loadResults(it) }
        if (rows.isEmpty()) {
            println("no successful result.json under: ${dirs.joinToString(", ") { it.path }}")
            return
        }
        val knob = args.opt("by-knob")
        if (knob != null) {
            qualityModes(rows, knob)
        } else {
            crossCorpusTable(rows)
        }
    }

    private fun resolveDirs(args: Args): List<File> {
        val explicit = args.positionals.map { File(it) }
        if (explicit.isNotEmpty()) {
            explicit.forEach { require(it.isDirectory) { "not a directory: ${it.path}" } }
            return explicit
        }
        // default: every results/<run-id> under every corpus
        val corporaRoot = File("corpora")
        return corporaRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { c -> File(c, "results").listFiles()?.filter { it.isDirectory } ?: emptyList() }
            ?: emptyList()
    }

    private fun loadResults(dir: File): List<ResultDto> {
        val out = mutableListOf<ResultDto>()
        for (sample in dir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val f = File(sample, "result.json")
            if (!f.isFile) continue
            try {
                val dto = gson.fromJson(f.readText(), ResultDto::class.java)
                if (dto.success == true) out.add(dto)
            } catch (_: Exception) {
                // a partially-written result is not fatal to a comparison
            }
        }
        return out
    }

    private fun crossCorpusTable(rows: List<ResultDto>) {
        data class Group(
            var runs: Int = 0,
            var reliable: Int = 0,
            var errSum: Double = 0.0,
            var extra: Int = 0,
            var missing: Int = 0
        )

        val groups = LinkedHashMap<Pair<String, String>, Group>()
        for (r in rows) {
            val key = (r.corpus ?: "?") to (r.`class` ?: "?")
            val g = groups.getOrPut(key) { Group() }
            g.runs++
            if (r.reliable == true) {
                g.reliable++
                g.errSum += r.err100w ?: 0.0
            }
            g.extra += r.extra ?: 0
            g.missing += r.missing ?: 0
        }
        println("cross-corpus comparison over ${rows.size} successful results")
        println()
        println(
            "%-18s %-22s %5s %5s %9s %6s %6s".format(
                "corpus", "class", "runs", "rel", "err/100w", "extra", "missing"
            )
        )
        for ((key, g) in groups) {
            println(
                "%-18s %-22s %5d %5d %9s %6d %6d".format(
                    key.first, key.second, g.runs, g.reliable,
                    if (g.reliable > 0) "%.2f".format(g.errSum / g.reliable) else "-",
                    g.extra, g.missing
                )
            )
        }
        val perCorpus = groups.entries.groupBy { it.key.first }
        if (perCorpus.size > 1) {
            println()
            println("per corpus (the #129 double-check: the candidate must hold in ALL):")
            for ((corpusName, entries) in perCorpus) {
                val rel = entries.sumOf { it.value.reliable }
                val err = entries.sumOf { it.value.errSum }
                println(
                    "  %-16s rel=%-4d mean err/100w=%s".format(
                        corpusName, rel,
                        if (rel > 0) "%.2f".format(err / rel) else "-"
                    )
                )
            }
        }
    }

    /**
     * The response-curve port (from `analyze.py quality_modes`): per knob
     * level the mean err/100w over reliable runs, the seam-duplication
     * totals (summed overlap) and the unclean-seam count; then the winner.
     */
    private fun qualityModes(rows: List<ResultDto>, knob: String) {
        val byLevel = LinkedHashMap<Any?, MutableList<ResultDto>>()
        for (r in rows) {
            val level = r.knobs?.get(knob) ?: continue
            byLevel.getOrPut(level) { mutableListOf() }.add(r)
        }
        if (byLevel.isEmpty()) {
            println("no results carry knob '$knob'")
            return
        }
        val default = PROVISIONAL_DEFAULTS[knob]
        println("response curve for knob '$knob' (provisional default $default), ${rows.size} results")
        println()
        println("%-12s %5s %4s %9s %6s %8s".format("level", "runs", "rel", "err/100w", "ovl", "unclean"))
        var best: Triple<Double, Int, Double>? = null
        var bestLevel: Any? = null
        for (level in byLevel.keys.sortedWith { a, b -> compareLevels(a, b) }) {
            val rs = byLevel[level]!!
            val rel = rs.filter { it.reliable == true && it.err100w != null }
            val meanErr = if (rel.isNotEmpty()) rel.sumOf { it.err100w!! } / rel.size else null
            val ovl = rs.sumOf { seamOverlapTotal(it) }
            val unclean = rs.sumOf { uncleanCount(it) }
            println(
                "%-12s %5d %4d %9s %6d %8d".format(
                    level.toString(), rs.size, rel.size,
                    meanErr?.let { "%.2f".format(it) } ?: "-", ovl, unclean
                )
            )
            if (rel.isNotEmpty()) {
                val distance = levelDistance(level, default)
                val mean = meanErr!!
                val betterThanBest = best == null ||
                    mean < best.first ||
                    (mean == best.first && (ovl < best.second ||
                        (ovl == best.second && distance < best.third)))
                if (betterThanBest) {
                    best = Triple(mean, ovl, distance)
                    bestLevel = level
                }
            }
        }
        if (bestLevel != null) {
            println()
            println("winner: $knob=$bestLevel")
        } else {
            println()
            println("NO reliable runs; keeping the provisional default $knob=$default")
        }
    }

    private fun seamOverlapTotal(r: ResultDto): Int =
        (r.q?.get("seamOverlapTotal") as? Number)?.toInt() ?: 0

    private fun uncleanCount(r: ResultDto): Int =
        (r.q?.get("seamsUnclean") as? Number)?.toInt() ?: 0

    /** Levels sort numerically when both parse as numbers, else as strings. */
    private fun compareLevels(a: Any?, b: Any?): Int {
        val an = (a as? Number)?.toDouble()
        val bn = (b as? Number)?.toDouble()
        return if (an != null && bn != null) an.compareTo(bn)
        else a.toString().compareTo(b.toString())
    }

    private fun levelDistance(level: Any?, default: Any?): Double {
        val ln = (level as? Number)?.toDouble()
        val dn = (default as? Number)?.toDouble()
        return if (ln != null && dn != null) kotlin.math.abs(ln - dn)
        else if (level == default) 0.0 else 1.0
    }
}
