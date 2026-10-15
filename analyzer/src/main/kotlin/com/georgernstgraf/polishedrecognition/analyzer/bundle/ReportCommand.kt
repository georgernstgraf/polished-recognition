package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.analyzer.corpus.Corpus
import java.io.File

/**
 * `report` — provider-performance aggregation (#146, the #144c core):
 * aggregates many `session.json` bundles into a per-provider table —
 * sessions, fragments, audio seconds transcribed, stitched/forced seam
 * counts, STT failures and pre-roll coverage. Runs over a bundles dir or a
 * corpus's `bundles/`.
 */
object ReportCommand {

    fun run(args: Args) {
        val dir = args.opt("corpus")?.let { Corpus.locate(it).bundlesDir() }
            ?: args.positionals.firstOrNull()?.let { File(it) }
            ?: throw IllegalArgumentException("report needs a bundles dir or --corpus NAME")
        val bundles = BundleIo.loadAll(dir)
        if (bundles.isEmpty()) {
            println("no session.json under ${dir.path}")
            return
        }

        data class Agg(
            var sessions: Int = 0,
            var fragments: Int = 0,
            var audioMs: Long = 0,
            var preRollMs: Long = 0,
            var stitched: Int = 0,
            var forced: Int = 0,
            var failures: Int = 0,
            var uploads: Int = 0
        )

        val byProvider = LinkedHashMap<String, Agg>()
        for ((_, b) in bundles) {
            val key = "${b.provider?.sttBaseUrl ?: "?"} model=${b.provider?.sttModel ?: "?"}"
            val agg = byProvider.getOrPut(key) { Agg() }
            agg.sessions++
            for (f in b.fragments) {
                agg.fragments++
                BundleIo.durationMs(f)?.let { agg.audioMs += it }
                f.preRollMs?.let { agg.preRollMs += it }
                f.uploadFile?.let { agg.uploads++ }
                if (f.stitched == true) agg.stitched++
                if (f.seamForced == true) agg.forced++
                if (f.sttError != null) agg.failures++
            }
        }

        println("provider performance over ${bundles.size} sessions in ${dir.path}")
        println()
        println(
            "%-42s %8s %6s %9s %8s %7s %6s %6s %8s".format(
                "provider", "sessions", "frags", "audio(s)", "preroll", "stitch", "forced", "fail", "uploads"
            )
        )
        for ((key, a) in byProvider) {
            println(
                "%-42s %8d %6d %9.1f %8.1f %7d %6d %6d %8d".format(
                    key,
                    a.sessions,
                    a.fragments,
                    a.audioMs / 1000.0,
                    a.preRollMs / 1000.0,
                    a.stitched,
                    a.forced,
                    a.failures,
                    a.uploads
                )
            )
        }
        val totalFrags = byProvider.values.sumOf { it.fragments }
        val totalFailures = byProvider.values.sumOf { it.failures }
        println()
        println("total: $totalFrags fragments, $totalFailures failures")
        if (totalFailures > 0) {
            println("sessions with failures:")
            for ((file, b) in bundles) {
                val failed = b.fragments.filter { it.sttError != null }
                if (failed.isNotEmpty()) {
                    println(
                        "  ${b.sessionId ?: file.parentFile?.name}: " +
                            failed.joinToString(", ") { "frag ${it.index}: ${it.sttError}" }
                    )
                }
            }
        }
    }
}
