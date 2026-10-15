package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.analyzer.corpus.Corpus
import java.io.File

/**
 * `report` — provider-performance aggregation (#146, the #144c core):
 * aggregates many `session.json` bundles into a per-provider table —
 * sessions, fragments, audio seconds transcribed, stitched/forced seam
 * counts, STT failures, pre-roll coverage and the #148 per-request STT
 * timing (request latency + real-time factor). Runs over a bundles dir or
 * a corpus's `bundles/`.
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
            var uploads: Int = 0,
            // #148: per-request STT timing over fragments with a successful
            // attempt (mean/max request duration, mean/max real-time factor,
            // mean upload size). Fragment-local accumulators, folded below.
            var requests: Int = 0,
            var requestMsTotal: Long = 0,
            var requestMsMax: Long = 0,
            var rtfTotal: Double = 0.0,
            var rtfMax: Double = 0.0,
            var uploadBytesTotal: Long = 0
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
                BundleIo.requestMs(f)?.let { req ->
                    agg.requests++
                    agg.requestMsTotal += req
                    if (req > agg.requestMsMax) agg.requestMsMax = req
                    BundleIo.realTimeFactor(f)?.let { rtf ->
                        agg.rtfTotal += rtf
                        if (rtf > agg.rtfMax) agg.rtfMax = rtf
                    }
                }
                f.uploadBytes?.let { agg.uploadBytesTotal += it }
            }
        }

        println("provider performance over ${bundles.size} sessions in ${dir.path}")
        println()
        println(
            "%-42s %8s %6s %9s %8s %7s %6s %6s %8s %6s %9s %7s %7s".format(
                "provider", "sessions", "frags", "audio(s)", "preroll", "stitch", "forced", "fail", "uploads",
                "reqs", "req(s)", "RTF", "up(kB)"
            )
        )
        for ((key, a) in byProvider) {
            println(
                "%-42s %8d %6d %9.1f %8.1f %7d %6d %6d %8d %6d %9.2f %7.2f %7.0f".format(
                    key,
                    a.sessions,
                    a.fragments,
                    a.audioMs / 1000.0,
                    a.preRollMs / 1000.0,
                    a.stitched,
                    a.forced,
                    a.failures,
                    a.uploads,
                    a.requests,
                    if (a.requests > 0) a.requestMsTotal / 1000.0 / a.requests else 0.0,
                    if (a.requests > 0) a.rtfTotal / a.requests else 0.0,
                    if (a.uploads > 0) a.uploadBytesTotal / 1024.0 / a.uploads else 0.0
                )
            )
        }
        val timed = byProvider.values.sumOf { it.requests }
        if (timed > 0) {
            val maxReq = byProvider.values.maxOf { it.requestMsMax }
            val maxRtf = byProvider.values.maxOf { it.rtfMax }
            println()
            println(
                "timing (#148, successful attempts): $timed requests, " +
                    "slowest ${maxReq / 1000.0} s, worst RTF %.2f".format(maxRtf)
            )
            println("RTF = request duration / audio duration (serial pipeline: the streaming budget)")
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
