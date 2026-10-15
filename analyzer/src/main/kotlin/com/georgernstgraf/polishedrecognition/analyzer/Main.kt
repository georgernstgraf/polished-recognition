package com.georgernstgraf.polishedrecognition.analyzer

import com.georgernstgraf.polishedrecognition.analyzer.bundle.ReconstructCommand
import com.georgernstgraf.polishedrecognition.analyzer.bundle.ScanCommand
import com.georgernstgraf.polishedrecognition.analyzer.bundle.ReportCommand
import com.georgernstgraf.polishedrecognition.analyzer.compare.CompareCommand
import com.georgernstgraf.polishedrecognition.analyzer.corpus.PullCommand
import com.georgernstgraf.polishedrecognition.analyzer.corpus.RunCommand
import kotlin.system.exitProcess

/**
 * The analyzer CLI (#146): a corpus-first analysis tool compiling the REAL
 * production pipeline (:core). Commands:
 *
 * - `run`      — corpus mode: WAV -> real fragment pipeline -> STT -> canonical result
 * - `scan`     — bundle mode: tabular inspection + the #129 Q-scan over session.json
 * - `report`   — provider-performance aggregation over many session.json
 * - `compare`  — cross-corpus comparison over results directories
 * - `pull`     — adb encapsulation: device bundles -> corpora/<name>/bundles/
 * - `reconstruct` — post-#142 bundles -> continuous session WAVs for the corpus (#149)
 *
 * Dispatch is a plain `when` — no heavyweight arg-parsing dependency (owner
 * decision; see analyzer/README.md for the full command surface).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(2)
    }
    when (args[0]) {
        "run" -> RunCommand.run(Args(args.drop(1)))
        "scan" -> ScanCommand.run(Args(args.drop(1)))
        "report" -> ReportCommand.run(Args(args.drop(1)))
        "compare" -> CompareCommand.run(Args(args.drop(1)))
        "pull" -> PullCommand.run(Args(args.drop(1)))
        "reconstruct" -> ReconstructCommand.run(Args(args.drop(1)))
        "help", "--help", "-h" -> usage()
        else -> {
            System.err.println("unknown command: ${args[0]}")
            usage()
            exitProcess(2)
        }
    }
}

private fun usage() {
    println(
        """
        polished-recognition analyzer (#146)

        usage: analyzer <command> [options]

        commands:
          run      run the real fragment pipeline over a corpus's WAVs
                   (--corpus NAME [--run-id ID] [--only SUBSTR] [--no-reference]
                    [--stt-url URL] [--stt-token TOK] [--model M]
                    [--fragment-seconds S] [--search-seconds S] [--pre-roll-seconds S]
                    [--prompt-chars N] [--policy qgate|all|forced_only|off]
                    [--axis-k K] [--axis-c C] [--quality-min Q] [--margin-k-ms K]
                    [--search-span lateHalf|fullWindow])
          scan     tabular inspection + Q-scan over session.json bundles
                   (<bundles-dir> | --corpus NAME) [--q-threshold Q] [--detail ID]
          report   provider-performance aggregation over session.json bundles
                   (<bundles-dir> | --corpus NAME)
          compare  cross-corpus comparison over results dirs (<dir>...) [--by-knob K]
          pull     pull device bundles into a corpus (--corpus NAME [--serial S])
          reconstruct
                   rebuild continuous session WAVs from post-#142 bundles
                   (--corpus NAME [--session ID] [--dry-run]) — incomplete
                   bundles are discarded whole; OGG uploads decode via ffmpeg

        STT credentials: --stt-token or ANALYZER_STT_TOKEN (never committed);
        endpoint: --stt-url or ANALYZER_STT_URL (default the local whisper
        server, http://10.8.0.16:11437/v1/ — gregor, strictly serial C=1).
        """.trimIndent()
    )
}

/**
 * Minimal argument container: positional arguments in order, `--flag value`
 * pairs, and bare `--flag` booleans. Unknown flags fail loudly (a typo must
 * never silently change a run's semantics).
 */
class Args(args: List<String>) {

    val positionals = mutableListOf<String>()
    private val options = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val name = a.substring(2)
                val next = args.getOrNull(i + 1)
                if (next == null || next.startsWith("--")) {
                    options[name] = ""
                    i++
                } else {
                    options[name] = next
                    i += 2
                }
            } else {
                positionals.add(a)
                i++
            }
        }
    }

    fun opt(name: String): String? = options[name]

    fun optOr(name: String, default: String): String = opt(name) ?: default

    fun optDouble(name: String, default: Double): Double = opt(name)?.toDoubleOrNull() ?: default

    fun optInt(name: String, default: Int): Int = opt(name)?.toIntOrNull() ?: default

    fun flag(name: String): Boolean = options.containsKey(name)

    fun requireOne(name: String): String =
        opt(name) ?: throw IllegalArgumentException("missing required --$name")
}
