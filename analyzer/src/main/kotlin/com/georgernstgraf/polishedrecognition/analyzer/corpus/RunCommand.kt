package com.georgernstgraf.polishedrecognition.analyzer.corpus

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.analyzer.analysis.Analysis
import com.georgernstgraf.polishedrecognition.analyzer.analysis.Stats
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import com.georgernstgraf.polishedrecognition.audio.PcmSource
import com.georgernstgraf.polishedrecognition.audio.SearchSpan
import com.georgernstgraf.polishedrecognition.audio.SeamPolicy
import com.georgernstgraf.polishedrecognition.audio.WavChunker
import com.georgernstgraf.polishedrecognition.audio.WavPcm
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.georgernstgraf.polishedrecognition.pipeline.AudioEvidenceStore
import com.georgernstgraf.polishedrecognition.pipeline.FragmentTranscriber
import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.georgernstgraf.polishedrecognition.pipeline.SessionBundle
import com.georgernstgraf.polishedrecognition.pipeline.SttRequestRunner
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * `run` — corpus mode (#146): the ex-HarnessActivity capability on the PC.
 * For every corpus WAV: `WavPcm` → file-backed [PcmSource] → the REAL
 * [FragmentPreparer] (pinned knobs, defaulting to the frozen production
 * config) → the REAL [FragmentTranscriber] (prompt carry, trim, stitch
 * evidence) → [SttRequestRunner] against a configurable OpenAI-compatible
 * STT endpoint → a full-context reference pass (≤300-s slices, the #120
 * rule) → the canonical `result.json` into `results/<run-id>/`.
 *
 * Invariants (PITFALLS, binding):
 * - STRICTLY SERIAL requests (C=1 — gregor serializes; the transcriber's
 *   worker is serial by construction, the reference pass runs after drain).
 * - Never upload >300 s: fragment uploads are ~1 fragment + 1 s pre-roll;
 *   reference slices go through [WavChunker] (cap 300 s).
 * - STT credentials only via CLI option / env (`ANALYZER_STT_TOKEN`) —
 *   never hardcoded, never committed.
 * - Resume-safe: a sample whose `result.json` already reports success is
 *   skipped, so interrupted runs continue where they stopped.
 */
object RunCommand {

    /** The local whisper server (gregor, large-v3, faster-whisper) — strictly serial. */
    const val DEFAULT_STT_URL = "http://10.8.0.16:11437/v1/"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun run(args: Args) {
        val corpusName = args.opt("corpus") ?: Corpus.names().singleOrNull()
            ?: throw CorpusException(
                "no --corpus given and corpora/ holds ${Corpus.names().size} candidates — name one"
            )
        val corpus = Corpus.locate(corpusName)
        val runId = args.optOr("run-id", defaultRunId())
        val outDir = corpus.resultsDir(runId)

        val problems = corpus.verifyWavs()
        if (problems.isNotEmpty()) {
            problems.forEach { System.err.println("integrity: $it") }
            throw CorpusException("manifest sha256 mismatch — refusing to run on corrupt material")
        }

        val config = sttConfig(args)
        val pipeline = SttPipeline(config)
        val knobs = Knobs.fromArgs(args)
        val only = args.opt("only")
        val withReference = !args.flag("no-reference")

        val wavs = corpus.wavs().filter { only == null || it.file.path.contains(only) }
        if (wavs.isEmpty()) throw CorpusException("no WAVs matched in corpus '$corpusName'")

        println("run-id: $runId -> ${outDir.path}")
        println("STT: ${config.baseUrl} model=${config.model} (strictly serial C=1)")
        println("knobs: $knobs")
        println("samples: ${wavs.size}" + (if (only != null) " (filtered by '$only')" else ""))

        var done = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        for (wav in wavs) {
            val out = sampleDir(outDir, wav)
            val marker = File(out, "result.json")
            if (marker.isFile && readSuccess(marker)) {
                skipped++
                continue
            }
            print("[${done + skipped + 1}/${wavs.size}] ${wav.file.name} ... ")
            System.out.flush()
            try {
                val summary = runOne(corpusName, runId, pipeline, knobs, wav, out, withReference)
                done++
                println(summary)
            } catch (e: Exception) {
                failures.add("${wav.file.path}: ${e.message}")
                println("FAILED: ${e.message}")
                writeFailure(out, wav, e, knobs)
            }
        }
        println()
        println("run $runId: $done succeeded, $skipped skipped (resume), ${failures.size} failed")
        failures.forEach { println("  FAILED: $it") }
        if (failures.isNotEmpty()) exitProcess(1)
    }

    fun defaultRunId(): String =
        "clean-baseline-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

    fun sampleDir(outDir: File, wav: CorpusWav): File =
        File(outDir, wav.className + "__" + wav.file.nameWithoutExtension)

    private fun readSuccess(resultFile: File): Boolean = try {
        val obj = Gson().fromJson(resultFile.readText(), Map::class.java)
        obj?.get("success") == true
    } catch (_: Exception) {
        false
    }

    private fun writeFailure(out: File, wav: CorpusWav, e: Exception, knobs: Knobs) {
        try {
            out.mkdirs()
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val record = linkedMapOf<String, Any?>(
                "formatVersion" to 1,
                "corpus" to wav.className,
                "class" to wav.className,
                "file" to wav.file.path,
                "knobs" to knobs.asMap(),
                "success" to false,
                "error" to (e.message ?: e.javaClass.simpleName),
                "stack" to sw.toString()
            )
            File(out, "result.json").writeText(gson.toJson(record))
        } catch (_: Exception) {
        }
    }

    private fun sttConfig(args: Args): SttProviderConfig {
        val url = args.optOr("stt-url", System.getenv("ANALYZER_STT_URL") ?: DEFAULT_STT_URL)
        val token = args.opt("stt-token")
            ?: System.getenv("ANALYZER_STT_TOKEN")
            ?: throw IllegalArgumentException(
                "no STT token: pass --stt-token or set ANALYZER_STT_TOKEN (never commit one)"
            )
        val model = args.optOr("model", "large-v3")
        return SttProviderConfig(
            id = "analyzer",
            displayName = "analyzer-cli",
            baseUrl = url,
            apiToken = token,
            model = model
        )
    }

    /** The pinned knobs of one run (CLI-overridable for sweeps). */
    data class Knobs(
        val axisK: Double,
        val axisC: Double,
        val qualityMin: Double,
        val marginKMs: Int,
        val searchSpan: SearchSpan,
        val policy: SeamPolicy,
        val fragmentSeconds: Double,
        val searchSeconds: Double,
        val preRollSeconds: Double,
        val promptChars: Int
    ) {
        fun asMap(): Map<String, Any?> = linkedMapOf(
            "axisK" to axisK,
            "axisC" to axisC,
            "qualityMin" to qualityMin,
            "marginKMs" to marginKMs,
            "searchSpan" to if (searchSpan == SearchSpan.LATE_HALF) "lateHalf" else "fullWindow",
            "policy" to when (policy) {
                SeamPolicy.Q_GATE -> "qgate"
                SeamPolicy.ALL -> "all"
                SeamPolicy.FORCED_ONLY -> "forced_only"
                SeamPolicy.OFF -> "off"
            },
            "fragmentSeconds" to fragmentSeconds,
            "searchSeconds" to searchSeconds,
            "preRollSeconds" to preRollSeconds,
            "promptChars" to promptChars
        )

        override fun toString(): String = asMap().entries.joinToString(" ") { (k, v) -> "$k=$v" }

        companion object {
            /**
             * The FROZEN production config: the #126/#128 provisional knob
             * defaults (#129's winners already adopted where decided),
             * fragment size 21 s (the production default), search 2 s (the
             * default forward window).
             */
            fun production(): Knobs = Knobs(
                axisK = FragmentPreparer.DEFAULT_AXIS_K,
                axisC = FragmentPreparer.DEFAULT_AXIS_C,
                qualityMin = FragmentPreparer.DEFAULT_QUALITY_MIN,
                marginKMs = FragmentPreparer.DEFAULT_MARGIN_K_MS,
                searchSpan = FragmentPreparer.DEFAULT_SEARCH_SPAN,
                policy = SeamPolicy.Q_GATE,
                fragmentSeconds = FragmentPreparer.FRAGMENT_SECONDS,
                searchSeconds = FragmentPreparer.DEFAULT_SEARCH_SECONDS,
                preRollSeconds = FragmentPreparer.PRE_ROLL_SECONDS,
                promptChars = FragmentTranscriber.DEFAULT_PROMPT_MAX_CHARS
            )

            fun fromArgs(args: Args): Knobs {
                val base = production()
                return Knobs(
                    axisK = args.optDouble("axis-k", base.axisK),
                    axisC = args.optDouble("axis-c", base.axisC),
                    qualityMin = args.optDouble("quality-min", base.qualityMin),
                    marginKMs = args.optInt("margin-k-ms", base.marginKMs),
                    searchSpan = when (args.optOr("search-span", "lateHalf")) {
                        "lateHalf" -> SearchSpan.LATE_HALF
                        "fullWindow" -> SearchSpan.FULL_WINDOW
                        else -> throw IllegalArgumentException("--search-span must be lateHalf|fullWindow")
                    },
                    policy = when (args.optOr("policy", "qgate")) {
                        "qgate" -> SeamPolicy.Q_GATE
                        "all" -> SeamPolicy.ALL
                        "forced_only", "forced" -> SeamPolicy.FORCED_ONLY
                        "off" -> SeamPolicy.OFF
                        else -> throw IllegalArgumentException("--policy must be qgate|all|forced_only|off")
                    },
                    fragmentSeconds = args.optDouble("fragment-seconds", base.fragmentSeconds),
                    searchSeconds = args.optDouble("search-seconds", base.searchSeconds),
                    preRollSeconds = args.optDouble("pre-roll-seconds", base.preRollSeconds),
                    promptChars = args.optInt("prompt-chars", base.promptChars)
                )
            }
        }
    }

    /** One wired STT endpoint: the same Retrofit-cache pattern the app uses. */
    class SttPipeline(private val config: SttProviderConfig) {

        private val api: OpenAiSttApiService = Retrofit.Builder()
            .baseUrl(if (config.baseUrl.endsWith("/")) config.baseUrl else "${config.baseUrl}/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.MINUTES)
                    .writeTimeout(30, TimeUnit.MINUTES)
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiSttApiService::class.java)

        val runner = SttRequestRunner(getSttApi = { api })
        val providerConfig: SttProviderConfig get() = config
    }

    /**
     * Runs ONE corpus WAV through the real production pipeline and writes
     * the canonical result. Returns a one-line summary.
     */
    fun runOne(
        corpusName: String,
        runId: String,
        pipeline: SttPipeline,
        knobs: Knobs,
        wav: CorpusWav,
        outDir: File,
        withReference: Boolean
    ): String {
        val startedAtMs = System.currentTimeMillis()
        outDir.mkdirs()
        val stem = wav.file.nameWithoutExtension
        val sessionId = stem + "-" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val sessionDir = File(outDir, "session")
        val logger = RotatingJsonLogger(File(outDir, "logs"))
        val bundle = SessionBundle(sessionId = sessionId)
        bundle.startedAt = startedAtMs
        bundle.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = pipeline.providerConfig.baseUrl,
            sttModel = pipeline.providerConfig.model,
            llmBaseUrl = null,
            llmModel = null,
            rawMode = true,
            compressAudio = false
        )

        // --- the real fragment pipeline ----------------------------------
        val pcm = WavPcm.extract(wav.file.readBytes())
        val pcmSource = object : PcmSource {
            override fun pcmSize(): Long = pcm.size.toLong()
            override fun copyPcmRange(start: Long, end: Long): ByteArray =
                pcm.copyOfRange(start.toInt(), end.toInt())
        }
        // wavMode: the analyzer has no platform Opus encoder — WAV fragments
        // are the analyzer's fragment format (the transcription path runs
        // regardless of compress_audio in production too).
        val preparer = FragmentPreparer(
            pcm = pcmSource,
            transcoder = throwingTranscoder(),
            sessionDir = sessionDir,
            sampleRate = 16_000,
            fragmentBytes = (knobs.fragmentSeconds * FragmentPreparer.PCM_BYTES_PER_SECOND).toInt(),
            wavMode = true,
            searchBytes = (knobs.searchSeconds * FragmentPreparer.PCM_BYTES_PER_SECOND).toInt(),
            preRollBytes = (knobs.preRollSeconds * FragmentPreparer.PCM_BYTES_PER_SECOND).toInt(),
            seamPolicy = knobs.policy,
            axisK = knobs.axisK,
            axisC = knobs.axisC,
            marginKMs = knobs.marginKMs,
            searchSpan = knobs.searchSpan,
            qualityMin = knobs.qualityMin,
            bundle = bundle,
            logger = logger
        )
        val transcriber = FragmentTranscriber(
            sttRunner = pipeline.runner,
            configProvider = { pipeline.providerConfig },
            scope = CoroutineScope(Dispatchers.IO),
            logger = logger,
            promptMaxChars = knobs.promptChars,
            seamEvidence = true,
            // Forensic retention: the composed uploads (ground truth at the
            // provider) stay in the run dir — no eviction, no gate. NOTE the
            // horizon must stay overflow-safe: evict() multiplies the
            // seconds by 1000, so Long.MAX_VALUE/2 would wrap NEGATIVE and
            // delete every session dir on the first write.
            audioEvidenceStore = AudioEvidenceStore(
                rootDir = outDir,
                retentionSeconds = { Long.MAX_VALUE / 2000 }
            ),
            audioEvidenceEnabled = { true },
            bundle = bundle
        )
        preparer.onFragmentCommitted = { index, file, preRoll, seamForced, stitched ->
            transcriber.offer(
                FragmentTranscriber.CommittedFragment(index, file, preRoll, seamForced, stitched)
            )
        }
        preparer.encodeAvailableFragments()
        preparer.prepareTailSync()
        preparer.stopWorker()
        val drained = runBlocking { transcriber.drain() }
        if (drained.failedIndex != null) {
            throw IllegalStateException(
                "fragment ${drained.failedIndex} failed: ${drained.failure?.message}"
            )
        }
        val joinText = drained.text

        // --- the full-context reference pass (≤300-s slices, #120) --------
        val references = mutableListOf<String>()
        if (withReference) {
            // WavChunker parses via the STRICT WavReader — corpus WAVs may
            // carry ffmpeg's extra chunks, so re-wrap the raw PCM into one
            // canonical 44-byte-header WAV first.
            val canonical = WavWriter.write(pcm, 16_000)
            val slices = WavChunker.chunk(canonical)
            slices.forEachIndexed { idx, slice ->
                val sliceFile = File(sessionDir, "reference_%03d.wav".format(idx + 1))
                sliceFile.writeBytes(slice)
                val response = runBlocking {
                    pipeline.runner.run(
                        audioFile = sliceFile,
                        config = pipeline.providerConfig,
                        chunk = idx + 1,
                        chunkCount = slices.size
                    )
                }
                runCatching { sliceFile.delete() }
                response.fold(
                    onSuccess = { references.add(it.text) },
                    onFailure = { throw IllegalStateException("reference slice ${idx + 1} failed: ${it.message}") }
                )
            }
        }
        val referenceText = references.joinToString(" ").trim()

        // --- forensic bundle (the app's own shape, zero drift) ------------
        bundle.endedAt = System.currentTimeMillis()
        bundle.result = SessionBundle.ResultRecord(
            rawFullText = joinText,
            finalText = null,
            deliveredAt = System.currentTimeMillis(),
            deliveryKind = "analyzer",
            error = null
        )
        File(outDir, "session.json").writeText(bundle.write())

        // --- canonical analysis -------------------------------------------
        val evidence = bundle.fragments.values.map { f ->
            Analysis.FragmentEvidence(
                index = f.index,
                text = f.sttTrimmed,
                seamForced = f.seamForced,
                stitched = f.stitched,
                q = f.quality?.get("Q") as? Double,
                echoTokens = f.echoTokens
            )
        }
        val analysis = Analysis.analyze(evidence, joinText, referenceText)

        val result = buildResult(corpusName, runId, wav, knobs, pipeline, sessionId, analysis,
            joinText, referenceText, references, startedAtMs, bundle.endedAt ?: 0L)
        File(outDir, "result.json").writeText(gson.toJson(result))
        return "frags=${analysis.fragments} join=${analysis.join} ref=${analysis.reference} " +
            "ratio=${analysis.ratio} extra=${analysis.extra} missing=${analysis.missing} " +
            "uncleanSeams=${analysis.seamsUnclean}/${analysis.seams}"
    }

    private fun throwingTranscoder() = object : AudioTranscoder {
        override fun transcode(wav: ByteArray, outFile: File): File =
            throw IllegalStateException("analyzer runs wavMode — the transcoder must never be called")
    }

    private fun buildResult(
        corpusName: String,
        runId: String,
        wav: CorpusWav,
        knobs: Knobs,
        pipeline: SttPipeline,
        sessionId: String,
        analysis: Analysis.Analysis,
        joinText: String,
        referenceText: String,
        referenceSlices: List<String>,
        startedAtMs: Long,
        endedAtMs: Long
    ): Map<String, Any?> = linkedMapOf(
        "formatVersion" to 1,
        "runId" to runId,
        "corpus" to corpusName,
        "class" to wav.className,
        "file" to wav.file.path,
        "sha256" to wav.sha256,
        "sessionId" to sessionId,
        "durationSeconds" to wav.seconds,
        "startedAtMs" to startedAtMs,
        "endedAtMs" to endedAtMs,
        "knobs" to knobs.asMap(),
        "stt" to linkedMapOf(
            "baseUrl" to pipeline.providerConfig.baseUrl,
            "model" to pipeline.providerConfig.model
        ),
        "success" to true,
        "error" to null,
        "join" to analysis.join,
        "reference" to analysis.reference,
        "joinLoops" to analysis.joinLoops,
        "referenceLoops" to analysis.referenceLoops,
        "ratio" to analysis.ratio,
        "reliable" to analysis.reliable,
        "extra" to analysis.extra,
        "missing" to analysis.missing,
        "err100w" to (if (analysis.reliable) {
            Stats.round2(
                (analysis.extra + analysis.missing) / maxOf(1, analysis.reference).toDouble() * 100
            )
        } else null),
        "fragments" to analysis.perFragment.map {
            linkedMapOf(
                "index" to it.index,
                "words" to it.words,
                "seamForced" to it.forced,
                "echo" to it.echo,
                "extra" to it.extra,
                "missing" to it.missing
            )
        },
        "seams" to analysis.perSeam.map {
            linkedMapOf(
                "seam" to it.seam,
                "q" to it.q,
                "stitched" to it.stitched,
                "overlap" to it.overlap,
                "split" to it.split,
                "unclean" to it.unclean
            )
        },
        "q" to linkedMapOf(
            "median" to analysis.qMedian,
            "p25" to analysis.qP25,
            "p75" to analysis.qP75,
            "stitchedMedian" to analysis.qStitchedMedian,
            "bareMedian" to analysis.qBareMedian,
            "uncleanMedian" to analysis.qUncleanMedian,
            "cleanMedian" to analysis.qCleanMedian,
            "spearman" to analysis.qSpearman,
            "trimFalsePositives" to analysis.trimFalsePositives,
            "seamOverlapTotal" to analysis.seamOverlapTotal,
            "seamsUnclean" to analysis.seamsUnclean,
            "wordSplits" to analysis.wordSplits
        ),
        "joinText" to joinText,
        "referenceText" to referenceText,
        "referenceSlices" to referenceSlices
    )
}
