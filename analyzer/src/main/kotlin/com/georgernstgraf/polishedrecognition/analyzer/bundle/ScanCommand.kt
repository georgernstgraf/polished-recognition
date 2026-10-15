package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.analyzer.corpus.Corpus
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import java.io.File

/**
 * `scan` — bundle mode (#146, the #144a/b analyzer core): parses every
 * `session.json` under a bundles directory (or a corpus's `bundles/`) with
 * the bundle shape the REAL :core [com.georgernstgraf.polishedrecognition
 * .pipeline.SessionBundle] writes, and produces:
 *
 * 1. a tabular inspection of every session (fragments, duration, stitched /
 *    forced seams, failures, provider),
 * 2. the **Q-scan** — every seam of every session with its
 *    `stitched` / `seamForced` / Q value, flagging seams below
 *    [qThreshold]. This is the #129 natural-find detector: real dictations
 *    whose seams fall below the provisional gate tell us where the real
 *    `qualityMin` boundary sits.
 */
object ScanCommand {

    fun run(args: Args) {
        val dir = resolveDir(args)
        val qThreshold = args.optDouble("q-threshold", FragmentPreparer.DEFAULT_QUALITY_MIN)
        val detail = args.opt("detail")

        val bundles = BundleIo.loadAll(dir)
        if (bundles.isEmpty()) {
            println("no session.json under ${dir.path}")
            return
        }

        // --- tabular inspection ------------------------------------------
        println("sessions in ${dir.path}: ${bundles.size}")
        println()
        println(
            "%-28s %5s %8s %8s %6s %6s %5s %s".format(
                "session", "frags", "secs", "provider", "strchd", "frccd", "fail", "model"
            )
        )
        val totals = mutableMapOf<String, Int>()
        for ((file, b) in bundles) {
            val frags = b.fragments
            val stitched = frags.count { it.stitched == true }
            val forced = frags.count { it.seamForced == true }
            val failures = frags.count { it.sttError != null }
            val seconds = frags.maxOfOrNull { it.endMs ?: 0L }?.let { "%.1f".format(it / 1000.0) } ?: "-"
            val provider = b.provider?.sttBaseUrl?.takeLast(24) ?: "-"
            val model = b.provider?.sttModel ?: "-"
            println(
                "%-28s %5d %8s %8s %6d %6d %5d %s".format(
                    b.sessionId ?: file.parentFile?.name ?: "?",
                    frags.size, seconds, provider, stitched, forced, failures, model
                )
            )
            totals["fragments"] = (totals["fragments"] ?: 0) + frags.size
            totals["stitched"] = (totals["stitched"] ?: 0) + stitched
            totals["forced"] = (totals["forced"] ?: 0) + forced
            totals["failures"] = (totals["failures"] ?: 0) + failures
        }
        println()
        println(
            "totals: ${totals["fragments"]} fragments, ${totals["stitched"]} stitched, " +
                "${totals["forced"]} forced, ${totals["failures"]} STT failures"
        )

        // --- the Q-scan (#129 natural-find detector) ----------------------
        println()
        println("Q-scan (threshold $qThreshold — seams below the gate EXPECT a stitch):")
        println(
            "%-28s %4s %9s %8s %6s %s".format("session", "seam", "Q", "stitched", "forced", "flag")
        )
        var flagged = 0
        for ((_, b) in bundles) {
            val id = b.sessionId ?: "?"
            if (detail != null && !id.contains(detail)) continue
            for (f in b.fragments) {
                if (f.index == 0) continue // fragment 0 has no seam
                val q = BundleIo.q(f)
                if (q == null) continue // unmeasured cut (tail) — not a Q candidate
                val flags = mutableListOf<String>()
                if (q < qThreshold) flags.add("LOWQ")
                if (f.stitched == true && q >= qThreshold) flags.add("STITCHED-BUT-CLEAN")
                if (f.seamForced == true) flags.add("forced")
                if (f.sttError != null) flags.add("sttError")
                if (flags.isEmpty()) continue
                flagged++
                println(
                    "%-28s %4d %9.2f %8s %6s %s".format(
                        id, f.index, q,
                        (f.stitched ?: false).toString(),
                        (f.seamForced ?: false).toString(),
                        flags.joinToString(",")
                    )
                )
            }
        }
        if (flagged == 0) println("  (no seams below the threshold — the corpus is too clean to discriminate)")
        else println("  $flagged flagged seams — the #129 natural finds")

        // --- per-session detail mode --------------------------------------
        if (detail != null) {
            val match = bundles.firstOrNull { (it.second.sessionId ?: "").contains(detail) } ?: return
            println()
            println("detail: ${match.second.sessionId}")
            println(
                "%4s %8s %8s %8s %6s %5s %s".format(
                    "frag", "startMs", "endMs", "Q", "strchd", "echo", "text"
                )
            )
            for (f in match.second.fragments) {
                val text = (f.sttTrimmed ?: f.sttRaw ?: "").take(60)
                println(
                    "%4d %8s %8s %8s %6s %5d %s".format(
                        f.index,
                        f.startMs?.toString() ?: "-",
                        f.endMs?.toString() ?: "-",
                        BundleIo.q(f)?.let { "%.2f".format(it) } ?: "-",
                        (f.stitched ?: false).toString(),
                        f.echoTokens ?: 0,
                        text
                    )
                )
            }
        }
    }

    private fun resolveDir(args: Args): File {
        args.opt("corpus")?.let { return Corpus.locate(it).bundlesDir() }
        val positional = args.positionals.firstOrNull()
            ?: throw IllegalArgumentException("scan needs a bundles dir or --corpus NAME")
        val f = File(positional)
        return if (f.isDirectory) f
        else throw IllegalArgumentException("not a directory: ${f.path}")
    }
}
