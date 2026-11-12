package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.analyzer.analysis.Diff
import com.georgernstgraf.polishedrecognition.analyzer.analysis.DiffTag
import com.georgernstgraf.polishedrecognition.analyzer.analysis.Tokenize
import com.georgernstgraf.polishedrecognition.analyzer.corpus.Corpus
import com.georgernstgraf.polishedrecognition.analyzer.corpus.RunCommand
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import com.georgernstgraf.polishedrecognition.audio.WavReader
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `replay` — the exact re-send of what the DEVICE actually uploaded (#129).
 *
 * The forensic bundle already contains everything the phone sent: the upload
 * bytes (`uploads/<uploadFile>`, composed with the acoustic pre-roll when the
 * seam was stitched), the conditioning prompt (`fragments[i].prompt`), the
 * model and the endpoint (`provider.sttModel`/`provider.sttBaseUrl`) — and the
 * answer it got back (`sttRaw`). Replaying that request through the REAL
 * [com.georgernstgraf.polishedrecognition.pipeline.SttRequestRunner] (same
 * multipart shape: file/model/response_format/prompt) answers the question the
 * #129 Gegenprobe could not: **how much does whisper-large-v3 vary on
 * IDENTICAL input?**
 *
 * That noise floor is the control every text-damage claim needs. Without it,
 * "the seam caused no damage" cannot be distinguished from "the provider
 * re-rolls its own output".
 *
 * `--ab` adds the STITCHING A/B at the seam itself (three arms, same audio):
 *
 * 1. `composed`  — `upload_*` WITH the recorded prompt (exactly what the app
 *    sent for a stitched seam),
 * 2. `bare`      — `frag_*` WITHOUT prompt (exactly what the app would have
 *    sent had the gate decided "bare"),
 * 3. `bare+prompt` — the same bare file WITH the prompt — the arm that
 *    separates the two ingredients the app couples under `stitched`:
 *    acoustic pre-roll vs text conditioning.
 *
 * Results land in `results/<run-id>/replay.json` (LOCAL-ONLY, corpora are
 * git-ignored) so the H1–H4 decision framework of #129 can be computed later
 * without another API run.
 */
object ReplayCommand {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** 16 kHz mono s16le: 32 bytes per millisecond. */
    private val BYTES_PER_MS = FragmentPreparer.PCM_BYTES_PER_SECOND / 1000L

    /** One request of one arm: what was sent and what came back. */
    class Attempt(
        val arm: String,
        val file: String,
        val prompt: String?,
        val text: String? = null,
        val tokens: Int? = null,
        val ms: Long? = null,
        val error: String? = null
    )

    /** One fragment's replay (all arms + all repeats). */
    class FragmentReplay(
        val sessionId: String,
        val index: Int,
        val q: Double?,
        val stitched: Boolean?,
        val recorded: String?,
        val attempts: List<Attempt>
    )

    fun run(args: Args) {
        val bundlesDir = resolveDir(args)
        val sessionFilter = args.opt("session")
        val repeat = args.optInt("repeat", 1).coerceAtLeast(1)
        val withAb = args.flag("ab")
        // `--only-stitched` narrows the corpus-wide A/B to the seams the gate
        // actually reacted to (21 of 918 fragments in the opo corpus) — the
        // cheap way to measure the gate over the WHOLE corpus.
        val onlyStitched = args.flag("only-stitched")
        val limit = args.optInt("limit", Int.MAX_VALUE).coerceAtLeast(1)
        val runId = args.optOr("run-id", "replay-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        val token = args.opt("stt-token")
            ?: System.getenv("ANALYZER_STT_TOKEN")
            ?: throw IllegalArgumentException("no STT token: pass --stt-token or set ANALYZER_STT_TOKEN (never commit one)")
        val bareDir = File(resultsDir(args, runId) ?: File(System.getProperty("java.io.tmpdir")), "bare")
        val modelOverride = args.opt("model")
        val urlOverride = args.opt("stt-url")

        val bundles = BundleIo.loadAll(bundlesDir)
            .filter { (_, b) -> sessionFilter == null || (b.sessionId ?: "").contains(sessionFilter) }
        if (bundles.isEmpty()) {
            println("no session.json under ${bundlesDir.path}")
            return
        }

        // One pipeline per distinct endpoint+model; the bundle records BOTH, so
        // an exact replay needs no flags at all (the overrides exist for the
        // "same audio, different provider" experiment).
        val pipelines = mutableMapOf<String, RunCommand.SttPipeline>()
        fun pipelineFor(url: String, model: String): RunCommand.SttPipeline =
            pipelines.getOrPut("$url|$model") {
                RunCommand.SttPipeline(
                    SttProviderConfig(
                        id = "replay",
                        displayName = "replay",
                        baseUrl = url,
                        apiToken = token,
                        model = model
                    )
                )
            }

        val results = mutableListOf<FragmentReplay>()
        var considered = 0
        var skipped = 0
        for ((file, bundle) in bundles) {
            if (results.size >= limit) break
            val sessionId = bundle.sessionId ?: file.parentFile?.name ?: "?"
            val uploads = File(file.parentFile, "uploads")
            val total = bundle.fragments.size
            for (f in bundle.fragments.sortedBy { it.index }) {
                if (results.size >= limit) break
                considered++
                val recorded = f.sttRaw
                val uploadName = f.uploadFile
                if (onlyStitched && f.stitched != true) {
                    skipped++
                    continue
                }
                if (recorded == null || uploadName == null) {
                    skipped++
                    continue
                }
                val upload = File(uploads, uploadName)
                if (!upload.isFile) {
                    skipped++
                    continue
                }
                val url = urlOverride ?: bundle.provider?.sttBaseUrl
                    ?: throw IllegalArgumentException("bundle $sessionId records no sttBaseUrl — pass --stt-url")
                val model = modelOverride ?: bundle.provider?.sttModel
                    ?: throw IllegalArgumentException("bundle $sessionId records no sttModel — pass --model")
                val pipeline = pipelineFor(url, model)

                val attempts = mutableListOf<Attempt>()
                // armed 1: the recorded request, `repeat` times (noise floor)
                repeat(repeat) { r ->
                    attempts.add(
                        send(pipeline, upload, f.prompt, "recorded${if (repeat > 1) "#${r + 1}" else ""}", f.index, total)
                    )
                }
                if (withAb && f.stitched == true && uploadName.startsWith("upload_")) {
                    // The bundle keeps only what the server SAW (`uploads/`), so
                    // the bare counterfactual is derived from the composed file:
                    // drop the pre-roll head, hold the boundary span.
                    val bare = bareSibling(uploads, uploadName)
                        ?: materializeBare(upload, f.preRollMs ?: 0L, (f.endMs ?: 0L) - (f.startMs ?: 0L), bareDir)
                    if (bare != null) {
                        attempts.add(send(pipeline, bare, null, "bare", f.index, total))
                        attempts.add(send(pipeline, bare, f.prompt, "bare+prompt", f.index, total))
                    } else {
                        attempts.add(Attempt("bare", "-", null, error = "bare arm not derivable"))
                    }
                }
                results.add(FragmentReplay(sessionId, f.index, BundleIo.q(f), f.stitched, recorded, attempts))
            }
        }

        report(results, skipped)
        val outDir = resultsDir(args, runId)
        if (outDir != null) {
            outDir.mkdirs()
            File(outDir, "replay.json").writeText(gson.toJson(results))
            println()
            println("written: ${File(outDir, "replay.json").path}")
        }
    }

    /** `upload_000004.ogg` -> `frag_000004.ogg` (the degraded-composition sibling). */
    internal fun bareName(uploadName: String): String =
        uploadName.replaceFirst("upload_", "frag_")

    /**
     * The bare sibling IF the bundle kept one (it usually does not — `uploads/`
     * holds exactly the files the server saw, and a stitched seam uploaded the
     * composed one).
     */
    private fun bareSibling(uploads: File, uploadName: String): File? =
        File(uploads, bareName(uploadName)).takeIf { it.isFile }

    /**
     * Derives the BARE arm from the composed upload: decode → drop the
     * `preRollMs` head → hold the recorded boundary span → re-encode in the
     * SAME container. WAV is pure-Kotlin ([WavWriter]); OGG re-encodes with
     * ffmpeg's libopus at 24 kbps (the app's `OpusOggTranscoder.DEFAULT_BITRATE`
     * — Android's MediaCodec is not available on the JVM, so this is a
     * documented second-order difference: same audio content, different
     * encoder implementation).
     *
     * Returns null when the composed upload is shorter than the head + span
     * (a record that cannot be reconciled — the A/B is then not measurable).
     */
    internal fun materializeBare(upload: File, preRollMs: Long, spanMs: Long, outDir: File): File? {
        if (spanMs <= 0) return null
        val pcm = try {
            when (upload.extension.lowercase()) {
                "wav" -> WavReader.read(upload.readBytes()).data
                "ogg", "opus" -> ReconstructCommand.decodeToPcm16kMono(upload)
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }
        val from = (preRollMs * BYTES_PER_MS).toInt()
        val to = from + (spanMs * BYTES_PER_MS).toInt()
        if (from < 0 || to > pcm.size) return null
        val piece = pcm.copyOfRange(from, to)
        outDir.mkdirs()
        val out = File(outDir, "bare_${upload.name}")
        return try {
            when (upload.extension.lowercase()) {
                "wav" -> {
                    out.writeBytes(WavWriter.write(piece, 16_000))
                    out
                }
                else -> encodeOgg24k(piece, out)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** OGG/Opus at the app's bitrate via host ffmpeg (documented host dependency). */
    private fun encodeOgg24k(pcm: ByteArray, target: File): File {
        val wav = File(target.parentFile, target.nameWithoutExtension + "-src.wav")
        wav.writeBytes(WavWriter.write(pcm, 16_000))
        try {
            val process = ProcessBuilder(
                "ffmpeg", "-y", "-v", "error", "-nostdin",
                "-i", wav.absolutePath,
                "-map", "0:a:0", "-c:a", "libopus", "-b:a", "24k", "-ar", "16000", "-ac", "1",
                target.absolutePath
            ).redirectInput(File("/dev/null")).start()
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw IllegalStateException("ffmpeg OGG encode failed")
            }
            return target
        } finally {
            wav.delete()
        }
    }

    private fun send(
        pipeline: RunCommand.SttPipeline,
        file: File,
        prompt: String?,
        arm: String,
        index: Int,
        total: Int
    ): Attempt {
        val startedAt = System.currentTimeMillis()
        val result = runBlocking {
            pipeline.runner.run(
                audioFile = file,
                config = pipeline.providerConfig,
                chunk = index + 1,
                chunkCount = total,
                prompt = prompt
            )
        }
        val ms = System.currentTimeMillis() - startedAt
        return result.fold(
            onSuccess = { Attempt(arm, file.name, prompt, it.text, Tokenize.tokens(it.text).size, ms) },
            onFailure = { Attempt(arm, file.name, prompt, null, null, ms, it.message ?: it.javaClass.simpleName) }
        )
    }

    // --- reporting --------------------------------------------------------

    private fun report(results: List<FragmentReplay>, skipped: Int) {
        val recorded = results.mapNotNull { r -> r.attempts.firstOrNull { it.arm.startsWith("recorded") }?.let { r to it } }
        val identical = recorded.count { (r, a) -> a.text != null && a.text.trim() == r.recorded?.trim() }
        val failed = results.sumOf { r -> r.attempts.count { it.error != null } }

        println("replayed ${results.size} fragments (${skipped} skipped: no sttRaw/upload)")
        println()
        println("%-28s %4s %8s %6s %8s %7s".format("session", "frag", "Q", "tokens", "Δtok", "ms"))
        for (r in results) {
            val first = r.attempts.firstOrNull { it.arm.startsWith("recorded") } ?: continue
            val recTokens = Tokenize.tokens(r.recorded).size
            val delta = (first.tokens ?: 0) - recTokens
            println(
                "%-28s %4d %8s %6d %8d %7s".format(
                    r.sessionId, r.index,
                    r.q?.let { "%.2f".format(it) } ?: "-",
                    recTokens, delta, first.ms?.toString() ?: "-"
                )
            )
        }
        println()
        println("identical to the recorded text: $identical/${recorded.size}" +
            (if (failed > 0) "  ($failed request(s) failed)" else ""))
        if (failed > 0) {
            println(
                "  hint: HTTP 400 usually means the endpoint no longer accepts the " +
                    "RECORDED model name (a bundle keeps the model it was sent with) — " +
                    "re-run with --model <current>, or --stt-url <current endpoint>."
            )
        }

        // --- the noise floor (repeat > 1) ---------------------------------
        val repeated = results.filter { r -> r.attempts.count { it.arm.startsWith("recorded") } > 1 }
        if (repeated.isNotEmpty()) {
            val unstable = repeated.count { r ->
                val texts = r.attempts.filter { it.arm.startsWith("recorded") }.mapNotNull { it.text?.trim() }.toSet()
                texts.size > 1
            }
            println("noise floor: ${repeated.size} fragments replayed >1×, $unstable differ between repeats")
            for (r in repeated.filter { r ->
                    r.attempts.filter { it.arm.startsWith("recorded") }.mapNotNull { it.text?.trim() }.toSet().size > 1
                }) {
                println("  ${r.sessionId} frag ${r.index}: " +
                    r.attempts.filter { it.arm.startsWith("recorded") }
                        .joinToString(" | ") { a -> "${a.arm}=${a.tokens ?: "ERR"}tok" })
            }
        }

        // --- the stitching A/B (three arms) -------------------------------
        val ab = results.filter { r -> r.attempts.any { it.arm == "bare" } }
        if (ab.isNotEmpty()) {
            println()
            println("stitching A/B (same audio, three arms):")
            println("%-28s %4s %8s %9s %9s %13s".format("session", "frag", "Q", "composed", "bare", "bare+prompt"))
            var differs = 0
            var promptOnly = 0
            for (r in ab) {
                val composed = r.attempts.firstOrNull { it.arm.startsWith("recorded") }
                val bare = r.attempts.firstOrNull { it.arm == "bare" }
                val barePrompt = r.attempts.firstOrNull { it.arm == "bare+prompt" }
                val same = composed?.text?.trim() == bare?.text?.trim()
                if (!same) differs++
                if (composed?.text?.trim() != barePrompt?.text?.trim()) promptOnly++
                println(
                    "%-28s %4d %8s %9s %9s %13s  %s".format(
                        r.sessionId, r.index,
                        r.q?.let { "%.2f".format(it) } ?: "-",
                        composed?.tokens?.toString() ?: "ERR",
                        bare?.tokens?.toString() ?: "ERR",
                        barePrompt?.tokens?.toString() ?: "ERR",
                        if (same) "same" else "DIFFERS"
                    )
                )
                if (!same) {
                    println("    composed: ${excerpt(composed?.text)}")
                    println("    bare    : ${excerpt(bare?.text)}")
                    val d = diff(bare?.text, composed?.text)
                    println("    bare vs composed: extra=${d.extra} missing=${d.missing}")
                }
                if (composed?.text?.trim() != barePrompt?.text?.trim()) {
                    println("    bare+prompt: ${excerpt(barePrompt?.text)}")
                }
            }
            println()
            println("A/B: ${ab.size} stitched seams, $differs differ between composed and bare, " +
                "$promptOnly differ between composed and bare+prompt")
        }
    }

    private class TextDiff(val extra: Int, val missing: Int)

    private fun diff(a: String?, b: String?): TextDiff {
        val ta = Tokenize.tokens(a)
        val tb = Tokenize.tokens(b)
        var extra = 0
        var missing = 0
        for (op in Diff.opcodes(ta, tb)) {
            when (op.tag) {
                DiffTag.EQUAL -> Unit
                DiffTag.DELETE -> extra += op.i2 - op.i1
                DiffTag.INSERT -> missing += op.j2 - op.j1
                DiffTag.REPLACE -> {
                    extra += op.i2 - op.i1
                    missing += op.j2 - op.j1
                }
            }
        }
        return TextDiff(extra, missing)
    }

    private fun excerpt(text: String?, max: Int = 90): String {
        val t = (text ?: "").replace("\n", " ").trim()
        return if (t.length <= max) t else t.take(max) + "…"
    }

    // --- plumbing ---------------------------------------------------------

    private fun resolveDir(args: Args): File {
        args.opt("corpus")?.let { return Corpus.locate(it).bundlesDir() }
        val positional = args.positionals.firstOrNull()
            ?: throw IllegalArgumentException("replay needs a bundles dir or --corpus NAME")
        val f = File(positional)
        if (!f.isDirectory) throw IllegalArgumentException("not a directory: ${f.path}")
        return f
    }

    /** `results/<run-id>/` next to the bundles when a corpus is named, else null. */
    private fun resultsDir(args: Args, runId: String): File? {
        args.opt("corpus")?.let { return Corpus.locate(it).resultsDir(runId) }
        args.opt("out")?.let { return File(it) }
        return null
    }
}
