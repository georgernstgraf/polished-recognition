package com.georgernstgraf.polishedrecognition.analyzer.corpus

import com.google.gson.Gson
import java.io.File

/**
 * The corpus convention (#146, owner decision 2 — binding):
 *
 * ```
 * corpora/<name>/
 *   manifest.json      — COMMITTED (documentation-by-example, no secrets)
 *   wavs/              — git-ignored material (16 kHz mono 16-bit PCM WAVs)
 *   bundles/           — git-ignored device bundles (`uploads/` + session.json)
 *   results/<run-id>/  — git-ignored analyzer output (canonical result JSON)
 * ```
 *
 * The manifest GENERALIZES the #122 `tools/seam-study/corpus/samples.json`
 * (per-file class/source/sha256) with the per-class CONFIDENCE + provenance
 * that `REPORT.md` recorded. Corpus #2/#3/#4 need NO code changes: a drop-in
 * directory with a conforming manifest is a first-class corpus.
 */
class CorpusManifest(
    val name: String,
    val createdAt: String? = null,
    val description: String? = null,
    /** Where the material came from and how it was built (the build.sh recipe reference). */
    val source: String? = null,
    /** Material kinds present in this corpus: e.g. ["wav", "bundle"]. */
    val kinds: List<String> = emptyList(),
    /** Audio spec of the WAV material (the pipeline's assumption). */
    val audio: AudioSpec? = null,
    val classes: List<CorpusClass> = emptyList()
) {
    class AudioSpec(val sampleRate: Int? = null, val channels: Int? = null, val encoding: String? = null)

    class CorpusClass(
        val name: String,
        /** Material kind: "wav" | "bundle" | ... */
        val kind: String? = null,
        /** Human confidence from the build report: high | medium-high | medium | low. */
        val confidence: String? = null,
        /** The uncertainty note from the build report (why not higher). */
        val note: String? = null,
        /** Provenance: the original source (file path / recording). */
        val source: String? = null,
        /** Cut start within the source (seconds), where meaningful. */
        val startSeconds: Double? = null,
        val files: List<CorpusFile> = emptyList()
    )

    class CorpusFile(
        /** Path RELATIVE to the corpus dir (e.g. `wavs/de_interview/de_interview_10s.wav`). */
        val path: String,
        val seconds: Double? = null,
        val sha256: String? = null
    )
}

/** One corpus WAV resolved on disk. */
data class CorpusWav(
    val className: String,
    val file: File,
    val seconds: Double?,
    val sha256: String?
)

class CorpusException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Corpus {

    private val gson = Gson()

    /** The corpora root — the repo's `corpora/` directory (CWD-relative). */
    fun root(): File = File("corpora")

    /** Lists the corpus directories under [root] (those carrying a manifest). */
    fun names(root: File = root()): List<String> =
        root.listFiles()?.filter { File(it, "manifest.json").isFile }?.map { it.name }?.sorted()
            ?: emptyList()

    fun locate(name: String, root: File = root()): CorpusDir {
        val dir = File(root, name)
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) {
            throw CorpusException(
                "no manifest at ${manifestFile.path} — corpus '$name' does not exist " +
                    "(available: ${names(root).joinToString(", ").ifEmpty { "<none>" }})"
            )
        }
        val manifest = try {
            gson.fromJson(manifestFile.readText(), CorpusManifest::class.java)
        } catch (e: Exception) {
            throw CorpusException("manifest not parseable: ${e.message}", e)
        }
        return CorpusDir(dir, manifest)
    }

    /** Parses a manifest from bytes (tests, import tooling). */
    fun parseManifest(json: String): CorpusManifest = gson.fromJson(json, CorpusManifest::class.java)

    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A corpus directory + its parsed manifest. */
class CorpusDir(val dir: File, val manifest: CorpusManifest) {

    val name: String get() = manifest.name
    fun resultsDir(runId: String): File = File(dir, "results/$runId")
    fun bundlesDir(): File = File(dir, "bundles")

    /** Resolves the manifest's WAV entries that EXIST on disk. */
    fun wavs(): List<CorpusWav> =
        manifest.classes.filter { it.kind == "wav" || it.kind == null }
            .flatMap { cls ->
                cls.files.mapNotNull { f ->
                    val file = File(dir, f.path)
                    if (file.isFile) CorpusWav(cls.name, file, f.seconds, f.sha256) else null
                }
            }

    /** Verifies every present WAV against its manifest sha256 (nulls skip). */
    fun verifyWavs(): List<String> {
        val problems = mutableListOf<String>()
        for (wav in wavs()) {
            val expected = wav.sha256 ?: continue
            val actual = Corpus.sha256(wav.file)
            if (!actual.equals(expected, ignoreCase = true)) {
                problems.add("${wav.file.name}: sha256 mismatch (manifest $expected, actual $actual)")
            }
        }
        return problems
    }
}
