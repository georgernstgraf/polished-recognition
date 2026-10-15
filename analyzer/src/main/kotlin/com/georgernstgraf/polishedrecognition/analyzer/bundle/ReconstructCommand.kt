package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.analyzer.corpus.Corpus
import com.georgernstgraf.polishedrecognition.analyzer.corpus.CorpusException
import com.georgernstgraf.polishedrecognition.analyzer.corpus.CorpusManifest
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import com.georgernstgraf.polishedrecognition.audio.WavReader
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.pipeline.SessionBundle
import com.google.gson.GsonBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * `reconstruct` (#149): rebuilds a CONTINUOUS session WAV from a post-#142
 * forensic bundle (`uploads/` + `session.json` — the bundles have no
 * `session.wav`), so real daily-driver dictations become corpus material for
 * the #129 fragment-size sweep (SilenceQuality × shrinking fragments) and
 * future sizing studies.
 *
 * Tiling rule: iterate the fragment records in index order; every composed
 * upload (`upload_*`) carries the pre-roll in its head — cut `preRollMs`
 * off so the piece aligns with the PCM-derived `startMs`/`endMs` boundaries.
 *
 * Strictness (owner decision, 2026-10-15): a bundle whose audio is
 * INCOMPLETE is discarded whole — a fragment with `sttError`, a missing
 * upload file, missing boundaries, or a boundary/tolerance violation means
 * the session is not faithfully reconstructable, and corpus material must
 * never be silently glued together. No splicing, no silence-fill.
 *
 * Format handling (the mixed-bundle reality): the audio format is detected
 * PER FRAGMENT from the `uploadFile` extension — never from
 * `provider.compressAudio`, which is a session-start snapshot and is
 * legitimately WRONG in the two designed mixed cases (the OGG→WAV
 * transcoder-fallback rebuild; process-death recovery across a settings
 * flip). WAV pieces are cut pure-Kotlin via the REAL :core WavReader/
 * WavWriter; OGG pieces decode through host ffmpeg (documented host
 * dependency — `compressAudio` bundles need it, think has it).
 *
 * The pre-roll cut keys on the upload's own NAME: `upload_*` is a composed
 * upload (pre-roll in the head), `frag_*` is the degraded bare-fragment
 * fallback (pre-roll absent even though `preRollMs` is recorded) — cutting
 * there would eat real fragment audio, so it never cuts.
 *
 * Output: `corpora/<name>/wavs/<sessionId>.wav` (16 kHz mono s16le) + a
 * manifest entry (class `reconstructed`, sha256, seconds) so `run` consumes
 * it like any corpus WAV. `--dry-run` validates and reports without writing.
 * Corpora stay LOCAL-ONLY (owner privacy decision) — nothing is committed.
 */
object ReconstructCommand {

    /** PCM bytes per ms: 16 kHz · 16 bit · mono (FragmentPreparer's rate). */
    private val BYTES_PER_MS = FragmentPreparer.PCM_BYTES_PER_SECOND / 1000L

    /**
     * Piece-duration tolerance in ms. The boundaries are PCM bytes / 32
     * truncated (±1 ms per edge) and the recorded `preRollMs` is the
     * pre-roll's duration truncated to ms (±1 ms) — so a FAITHFUL piece can
     * deviate by up to ~3 ms without anything being wrong. Beyond that the
     * bundle does not tile and is discarded.
     */
    private const val TOLERANCE_MS = 3L

    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    enum class Kind { RECONSTRUCTED, DISCARDED, SKIPPED }

    /** One bundle's reconstruct outcome (the testable, write-free core). */
    class Result(
        val sessionId: String,
        val kind: Kind,
        /** The reconstructed session PCM (16 kHz mono s16le) — RECONSTRUCTED only. */
        val pcm: ByteArray? = null,
        /** Why the bundle was discarded/skipped — DISCARDED/SKIPPED only. */
        val reason: String? = null,
        val pieces: Int = 0,
        val spanMs: Long = 0,
        /** Largest |piece − boundary span| deviation in ms (reconstruction fidelity). */
        val maxDeltaMs: Double = 0.0,
        val notes: List<String> = emptyList()
    )

    fun run(args: Args) {
        val corpus = Corpus.locate(args.requireOne("corpus"))
        val sessionFilter = args.opt("session")
        val dryRun = args.flag("dry-run")
        val results = reconstructInCorpus(corpus.dir, sessionFilter, dryRun)
        if (results.isEmpty()) {
            println("no session.json bundles under ${corpus.bundlesDir().path}")
            return
        }
        val counts = mutableMapOf<Kind, Int>()
        for (r in results) {
            counts[r.kind] = (counts[r.kind] ?: 0) + 1
            val label = when (r.kind) {
                Kind.RECONSTRUCTED ->
                    "%-9s %d pieces, %.1f s, max |Δ| %.1f ms%s".format(
                        "OK", r.pieces, r.spanMs / 1000.0, r.maxDeltaMs,
                        if (dryRun) " (dry-run)" else ""
                    )
                else -> "%-9s %s".format(r.kind.name, r.reason)
            }
            println("  ${r.sessionId}  $label")
            for (note in r.notes) println("    note: $note")
        }
        println(
            "summary: ${counts[Kind.RECONSTRUCTED] ?: 0} reconstructed, " +
                "${counts[Kind.DISCARDED] ?: 0} discarded, ${counts[Kind.SKIPPED] ?: 0} skipped" +
                (if (dryRun) " (dry-run — nothing written)" else " -> ${File(corpus.dir, "wavs").path}")
        )
    }

    /**
     * Reconstructs every post-#142 bundle in [corpusDir]/bundles/ and —
     * unless [dryRun] — writes `wavs/<sessionId>.wav` + the manifest entry
     * per successful reconstruction. Returns one [Result] per bundle, sorted
     * by session dir name.
     */
    internal fun reconstructInCorpus(
        corpusDir: File,
        sessionFilter: String?,
        dryRun: Boolean
    ): List<Result> {
        if (!File(corpusDir, "manifest.json").isFile) {
            throw CorpusException("no manifest at ${File(corpusDir, "manifest.json").path}")
        }
        val bundles = BundleIo.loadAll(File(corpusDir, "bundles"))
            .filter { (file, b) -> sessionFilter == null || (b.sessionId ?: "").contains(sessionFilter) }
        val results = bundles.map { (file, _) -> reconstructBundle(file.parentFile ?: file) }
        if (dryRun) return results
        for (r in results) {
            if (r.kind != Kind.RECONSTRUCTED || r.pcm == null) continue
            val wavsDir = File(corpusDir, "wavs")
            wavsDir.mkdirs()
            val wav = File(wavsDir, "${sanitize(r.sessionId)}.wav")
            wav.writeBytes(WavWriter.write(r.pcm, 16_000))
            updateManifest(corpusDir, wav, r.spanMs / 1000.0)
        }
        return results
    }

    /**
     * The write-free core: tiles one bundle's `uploads/` via its
     * `session.json` fragment records. Strict validation — any violation
     * returns a DISCARDED result, never partial audio.
     */
    internal fun reconstructBundle(bundleDir: File): Result {
        val sessionFile = File(bundleDir, SessionBundle.FILE_NAME)
        val session = BundleIo.load(sessionFile)
        val id = session.sessionId ?: bundleDir.name
        val uploads = File(bundleDir, "uploads")
        if (!uploads.isDirectory) {
            return Result(id, Kind.SKIPPED, reason = "pre-#142 bundle (no uploads/ — the session.wav era)")
        }
        val fragments = session.fragments.sortedBy { it.index }
        if (fragments.isEmpty()) return Result(id, Kind.DISCARDED, reason = "no fragment records in session.json")

        val notes = mutableListOf<String>()
        try {
            val out = ByteArrayOutputStream()
            var maxDeltaBytes = 0L
            var prevEnd: Long? = null
            for (f in fragments) {
                val start = f.startMs ?: throw Discard("fragment ${f.index}: no startMs recorded")
                val end = f.endMs ?: throw Discard("fragment ${f.index}: no endMs recorded")
                if (end < start) throw Discard("fragment ${f.index}: inverted boundaries ($start..$end)")
                if (f.sttError != null) {
                    throw Discard(
                        "fragment ${f.index}: sttError (${f.sttError}) — audio incomplete, " +
                            "bundle discarded whole (owner decision: no partial reconstruction)"
                    )
                }
                val name = f.uploadFile ?: throw Discard("fragment ${f.index}: no uploadFile recorded")
                val file = File(uploads, name)
                if (!file.isFile) throw Discard("fragment ${f.index}: uploads/$name missing on disk")

                val pcm = when (file.extension.lowercase()) {
                    "wav" -> {
                        val parsed = try {
                            WavReader.read(file.readBytes())
                        } catch (e: IllegalArgumentException) {
                            throw Discard("fragment ${f.index}: uploads/$name not a canonical WAV (${e.message})")
                        }
                        if (parsed.sampleRate != 16_000) {
                            throw Discard("fragment ${f.index}: uploads/$name is ${parsed.sampleRate} Hz, expected 16000")
                        }
                        parsed.data
                    }
                    "ogg", "opus" -> try {
                        decodeToPcm16kMono(file)
                    } catch (e: Discard) {
                        throw e
                    } catch (e: Exception) {
                        throw Discard("fragment ${f.index}: ffmpeg decode of uploads/$name failed (${e.message})")
                    }
                    else -> throw Discard("fragment ${f.index}: unsupported upload format '$name'")
                }

                // The pre-roll cut: ONLY composed uploads carry the head.
                // A bare `frag_*` upload is the degraded-composition fallback —
                // its preRollMs is recorded but its audio does NOT include it.
                val cutBytes = if (name.startsWith("upload_")) {
                    (f.preRollMs ?: 0L) * BYTES_PER_MS
                } else {
                    if ((f.preRollMs ?: 0L) > 0) {
                        notes.add("fragment ${f.index}: bare upload with preRollMs=${f.preRollMs} " +
                            "(degraded composition) — no head cut")
                    }
                    0L
                }
                if (cutBytes > pcm.size) {
                    throw Discard("fragment ${f.index}: pre-roll cut ($cutBytes bytes) exceeds upload PCM (${pcm.size})")
                }
                val piece = pcm.copyOfRange(cutBytes.toInt(), pcm.size)
                val expectedBytes = (end - start) * BYTES_PER_MS
                val delta = piece.size - expectedBytes
                if (abs(delta) > TOLERANCE_MS * BYTES_PER_MS) {
                    throw Discard(
                        "fragment ${f.index}: piece ${piece.size / BYTES_PER_MS} ms ≠ boundary span " +
                            "${end - start} ms (Δ ${delta / BYTES_PER_MS} ms beyond the ±$TOLERANCE_MS ms tolerance)"
                    )
                }
                prevEnd?.let {
                    if (start != it) {
                        throw Discard("fragment ${f.index}: startMs $start ≠ previous endMs $it (gap/overlap)")
                    }
                }
                maxDeltaBytes = maxOf(maxDeltaBytes, abs(delta))
                out.write(piece)
                prevEnd = end
            }

            // compressAudio is a session-start snapshot — an advisory cross-check,
            // never a verdict (the two designed mixed-bundle cases make it wrong).
            session.provider?.compressAudio?.let { compressed ->
                val ogg = fragments.any { it.uploadFile?.endsWith(".ogg", true) ?: false }
                val wav = fragments.any { it.uploadFile?.endsWith(".wav", true) ?: false }
                if (compressed && wav && !ogg) {
                    notes.add("provider.compressAudio=true but WAV uploads present (OGG→WAV fallback rebuild?)")
                } else if (!compressed && ogg) {
                    notes.add("provider.compressAudio=false but OGG uploads present (recovery across a settings flip?)")
                }
            }
            val spanMs = fragments.last().endMs!! - fragments.first().startMs!!
            return Result(
                sessionId = id,
                kind = Kind.RECONSTRUCTED,
                pcm = out.toByteArray(),
                pieces = fragments.size,
                spanMs = spanMs,
                maxDeltaMs = maxDeltaBytes.toDouble() / BYTES_PER_MS,
                notes = notes
            )
        } catch (e: Discard) {
            return Result(id, Kind.DISCARDED, reason = e.message)
        }
    }

    /**
     * Decodes any media file to raw 16 kHz mono s16le PCM via host ffmpeg —
     * the path for OGG/Opus uploads (the device's `compressAudio` format),
     * which Kotlin cannot decode without a new dependency. ffmpeg is a
     * documented host dependency (present on think; CI runners carry it too).
     */
    internal fun decodeToPcm16kMono(file: File): ByteArray {
        val pb = ProcessBuilder(
            "ffmpeg", "-v", "error", "-nostdin", "-i", file.absolutePath,
            "-map", "0:a:0", "-f", "s16le", "-ar", "16000", "-ac", "1", "pipe:1"
        )
            .redirectInput(File("/dev/null"))
        val process = try {
            pb.start()
        } catch (e: Exception) {
            throw Discard("ffmpeg not found on PATH — required to decode OGG uploads (${e.message})")
        }
        val out = ByteArrayOutputStream()
        val err = StringBuilder()
        val outReader = thread(isDaemon = true) { process.inputStream.copyTo(out) }
        val errReader = thread(isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
        }
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw Discard("ffmpeg decode timed out: ${file.name}")
        }
        outReader.join(5000)
        errReader.join(1000)
        if (process.exitValue() != 0) {
            throw Discard("ffmpeg failed (${process.exitValue()}): ${err.trim().take(300)}")
        }
        if (out.size() == 0) throw Discard("ffmpeg decoded no audio: ${file.name}")
        return out.toByteArray()
    }

    /**
     * Adds/updates the `reconstructed` class in the corpus manifest (one
     * entry per WAV, idempotent by path) so `analyzer run` consumes the
     * reconstruction like any corpus WAV.
     */
    private fun updateManifest(corpusDir: File, wav: File, seconds: Double) {
        val manifestFile = File(corpusDir, "manifest.json")
        val manifest = try {
            Corpus.parseManifest(manifestFile.readText())
        } catch (e: Exception) {
            throw CorpusException("manifest not parseable: ${e.message}", e)
        }
        val relPath = "wavs/${wav.name}"
        val classes = manifest.classes.toMutableList()
        val existing = classes.indexOfFirst { it.name == CLASS_NAME }
        val files = (if (existing >= 0) classes[existing].files else emptyList())
            .filter { it.path != relPath }
            .toMutableList()
        files.add(
            CorpusManifest.CorpusFile(
                path = relPath,
                seconds = seconds,
                sha256 = Corpus.sha256(wav)
            )
        )
        val rebuilt = CorpusManifest.CorpusClass(
            name = CLASS_NAME,
            kind = "wav",
            confidence = "high",
            note = "machine-faithful reconstruction from post-#142 bundles (#149): " +
                "uploads/ pre-roll-cut + tiled via session.json boundaries; " +
                "incomplete bundles are discarded whole (owner decision)",
            source = "bundles/",
            files = files.sortedBy { it.path }
        )
        if (existing >= 0) classes[existing] = rebuilt else classes.add(rebuilt)
        val kinds = if ("wav" in manifest.kinds) manifest.kinds else manifest.kinds + "wav"
        manifestFile.writeText(
            prettyGson.toJson(
                CorpusManifest(
                    name = manifest.name,
                    createdAt = manifest.createdAt,
                    description = manifest.description,
                    source = manifest.source,
                    kinds = kinds,
                    audio = manifest.audio,
                    classes = classes
                )
            )
        )
    }

    private fun sanitize(sessionId: String): String =
        sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Internal control-flow signal: the bundle does not tile — discard whole. */
    private class Discard(message: String) : Exception(message)

    /** The manifest class the reconstructions register under. */
    const val CLASS_NAME = "reconstructed"
}
