package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.corpus.Corpus
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.pipeline.SessionBundle
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #149: the bundle→session-WAV reconstruction. Synthetic bundles are built
 * with the REAL `:core` shapes — [SessionBundle] writes the `session.json`
 * the device runs, uploads are composed exactly like
 * `FragmentTranscriber.composeUpload` (pre-roll head in a composed
 * `upload_*`, bare fragment fallback under `frag_*`) — and the
 * reconstruction must reproduce the original session PCM.
 */
class ReconstructCommandTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- fixtures ---------------------------------------------------------

    /** One fragment's shape, mirroring what the device actually retains. */
    private class Spec(
        val fragmentMs: Int,
        /** The pre-roll ms the RECORD records (and the upload carries, unless [bare]). */
        val preRollMs: Int = 1000,
        /** Degraded composition: bare `frag_*` upload (NO pre-roll in the audio). */
        val bare: Boolean = false,
        /** OGG/Opus upload (compressAudio on) — decodes via ffmpeg. */
        val ogg: Boolean = false,
        /** STT failure — no upload retained. */
        val sttError: String? = null,
        /** uploadFile recorded but the file missing on disk (evidence copy lost). */
        val omitUpload: Boolean = false,
        /** Override the recorded pre-roll (a lying record → tolerance discard). */
        val preRollRecordedMs: Int? = null,
        /** Shift this fragment's startMs off the previous endMs (gap/overlap). */
        val startShiftMs: Int = 0
    )

    private val ffmpegAvailable: Boolean by lazy {
        try {
            val p = ProcessBuilder("ffmpeg", "-version").start()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Deterministic, non-silent PCM pattern. */
    private fun pattern(i: Int): Byte = ((i * 31 + 7) % 251).toByte()

    /**
     * Builds one post-#142 bundle under `corpus/bundles/<sessionId>/`:
     * `uploads/` + `session.json` (the REAL SessionBundle shape). Returns
     * the ORIGINAL session PCM the fragments were cut from.
     */
    private fun buildSession(corpusDir: File, sessionId: String, specs: List<Spec>): ByteArray {
        val bundleDir = File(corpusDir, "bundles/$sessionId")
        val uploads = File(bundleDir, "uploads").apply { mkdirs() }
        val totalMs = specs.sumOf { it.fragmentMs } + specs.sumOf { it.startShiftMs }
        val sessionPcm = ByteArray(totalMs * 32) { i -> pattern(i) }

        val bundle = SessionBundle(sessionId)
        bundle.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = "http://10.8.0.16:11437/v1/",
            sttModel = "large-v3",
            llmBaseUrl = null, llmModel = null,
            rawMode = false,
            compressAudio = specs.any { it.ogg }
        )
        var startMs = 0L
        for ((i, spec) in specs.withIndex()) {
            val endMs = startMs + spec.fragmentMs
            val f = bundle.fragment(i)
            f.startMs = startMs + spec.startShiftMs
            f.endMs = endMs
            f.stitched = i > 0 && !spec.bare
            f.preRollMs = when {
                i == 0 -> null
                spec.preRollRecordedMs != null -> spec.preRollRecordedMs.toLong()
                else -> spec.preRollMs.toLong()
            }
            if (spec.sttError != null) {
                f.sttError = spec.sttError
            } else if (spec.omitUpload) {
                f.uploadFile = "upload_%06d.wav".format(i)
            } else {
                val startBytes = (startMs + spec.startShiftMs).toInt() * 32
                val endBytes = endMs.toInt() * 32
                val fragPcm = sessionPcm.copyOfRange(startBytes, endBytes)
                val preRollBytes = spec.preRollMs * 32
                // composeUpload semantics: composed upload_* carries the
                // pre-roll head; the degraded fallback keeps the fragment's
                // own name and its bare audio.
                val isComposed = i > 0 && !spec.bare
                val audio = if (isComposed) {
                    sessionPcm.copyOfRange(startBytes - preRollBytes, endBytes)
                } else {
                    fragPcm
                }
                val name = if (isComposed) "upload_%06d.%s".format(i, if (spec.ogg) "ogg" else "wav")
                else "frag_%06d.%s".format(i, if (spec.ogg) "ogg" else "wav")
                val target = File(uploads, name)
                if (spec.ogg) encodeOgg(WavWriter.write(audio, 16_000), target)
                else target.writeBytes(WavWriter.write(audio, 16_000))
                f.uploadFile = name
                f.uploadBytes = target.length()
            }
            startMs = endMs
        }
        File(bundleDir, "session.json").writeText(bundle.write())
        return sessionPcm
    }

    private fun encodeOgg(wavBytes: ByteArray, target: File) {
        val source = File(target.parentFile, target.nameWithoutExtension + "-src.wav")
        source.writeBytes(wavBytes)
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-v", "error", "-nostdin",
            "-i", source.absolutePath, "-c:a", "libopus", "-b:a", "32k", target.absolutePath
        ).start()
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw AssertionError("ffmpeg OGG encode failed")
        }
        source.delete()
    }

    private fun newCorpus(): File {
        val corpusDir = tmp.newFolder("corpus")
        File(corpusDir, "bundles").mkdirs()
        File(corpusDir, "manifest.json").writeText(
            """
            {"name":"test-corpus","createdAt":"2026-10-15","description":"#149 test fixture",
             "kinds":["bundle"],"classes":[]}
            """.trimIndent()
        )
        return corpusDir
    }

    private fun reconstruct(corpusDir: File, session: String? = null, dryRun: Boolean = false) =
        ReconstructCommand.reconstructInCorpus(corpusDir, session, dryRun)

    // --- the happy path ---------------------------------------------------

    @Test
    fun `a contiguous WAV bundle reconstructs the exact session PCM`() {
        val corpus = newCorpus()
        val original = buildSession(
            corpus, "20261015-1015-abcd1234",
            listOf(Spec(5000), Spec(5000), Spec(5000))
        )

        val results = reconstruct(corpus)

        assertThat(results).hasSize(1)
        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.RECONSTRUCTED)
        assertThat(r.pieces).isEqualTo(3)
        assertThat(r.spanMs).isEqualTo(15_000)
        assertThat(r.maxDeltaMs).isWithin(1e-9).of(0.0)
        assertThat(r.pcm!!).hasLength(original.size)
        assertThat(r.pcm.contentEquals(original)).isTrue()
    }

    @Test
    fun `a mixed bundle (OGG prefix, WAV tail) reconstructs via ffmpeg`() {
        assumeTrue(ffmpegAvailable)
        val corpus = newCorpus()
        buildSession(
            corpus, "20261015-1100-mixed0001",
            listOf(Spec(5000, ogg = true), Spec(5000), Spec(5000))
        )

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.RECONSTRUCTED)
        // Opus is lossy — no byte comparison; the TILED LENGTH must hold
        // (decode is granule-exact, the ±3 ms piece tolerance bounds it).
        val expected = 15_000L * 32
        assertThat(r.pcm!!.size.toLong()).isAtLeast(expected - 3 * 32)
        assertThat(r.pcm.size.toLong()).isAtMost(expected + 3 * 32)
        assertThat(r.pcm.any { it != 0.toByte() }).isTrue()
    }

    @Test
    fun `a bare upload with a recorded preRollMs is not cut (degraded composition)`() {
        val corpus = newCorpus()
        val original = buildSession(
            corpus, "20261015-1200-degraded",
            listOf(Spec(5000), Spec(5000), Spec(5000, bare = true))
        )

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.RECONSTRUCTED)
        assertThat(r.notes.any { it.contains("degraded composition") }).isTrue()
        assertThat(r.pcm!!.contentEquals(original)).isTrue()
    }

    // --- the strict gate: incomplete bundles are discarded whole ----------

    @Test
    fun `a bundle with an sttError fragment is discarded whole`() {
        val corpus = newCorpus()
        buildSession(
            corpus, "20261015-1300-fail0001",
            listOf(Spec(5000), Spec(5000, sttError = "HTTP 503 after retries"), Spec(5000))
        )

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.DISCARDED)
        assertThat(r.reason).contains("fragment 1")
        assertThat(r.reason).contains("discarded whole")
        assertThat(File(corpus, "wavs").listFiles().isNullOrEmpty()).isTrue()
    }

    @Test
    fun `a bundle with a missing upload file is discarded whole`() {
        val corpus = newCorpus()
        buildSession(
            corpus, "20261015-1310-lost0001",
            listOf(Spec(5000), Spec(5000, omitUpload = true), Spec(5000))
        )

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.DISCARDED)
        assertThat(r.reason).contains("uploads/upload_000001.wav missing on disk")
        assertThat(File(corpus, "wavs").listFiles().isNullOrEmpty()).isTrue()
    }

    @Test
    fun `a gap between fragments is discarded (no silent glue)`() {
        val corpus = newCorpus()
        buildSession(
            corpus, "20261015-1320-gapped001",
            listOf(Spec(5000), Spec(5000, startShiftMs = 500), Spec(5000))
        )

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.DISCARDED)
        assertThat(r.reason).contains("gap/overlap")
    }

    @Test
    fun `a piece beyond the tolerance is discarded`() {
        val corpus = newCorpus()
        // The record LIES: 2000 ms pre-roll recorded, but the upload carries
        // only 1000 ms — the cut eats 1000 ms of real fragment audio.
        buildSession(
            corpus, "20261015-1330-lying001",
            listOf(Spec(5000), Spec(5000, preRollRecordedMs = 2000))
        )

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.DISCARDED)
        assertThat(r.reason).contains("beyond the ±3 ms tolerance")
    }

    // --- shapes around the command ----------------------------------------

    @Test
    fun `a pre-142 bundle is skipped`() {
        val corpus = newCorpus()
        val legacy = File(corpus, "bundles/20261014-0014-legacy1")
        legacy.mkdirs()
        File(legacy, "session.json").writeText("""{"sessionId":"20261014-0014-legacy1","fragments":[]}""")

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.SKIPPED)
        assertThat(r.reason).contains("pre-#142")
    }

    @Test
    fun `the session filter selects matching bundles only`() {
        val corpus = newCorpus()
        buildSession(corpus, "20261015-1400-match01", listOf(Spec(5000)))
        buildSession(corpus, "20261015-1401-match02", listOf(Spec(5000)))
        buildSession(corpus, "20261015-1402-other1", listOf(Spec(5000)))

        val results = reconstruct(corpus, session = "match")

        assertThat(results).hasSize(2)
        assertThat(results.map { it.sessionId }).containsExactly(
            "20261015-1400-match01", "20261015-1401-match02"
        ).inOrder()
    }

    @Test
    fun `a full reconstruction lands in wavs plus the manifest idempotently`() {
        val corpus = newCorpus()
        buildSession(corpus, "20261015-1500-manifst1", listOf(Spec(5000), Spec(5000), Spec(5000)))

        val first = reconstruct(corpus)
        assertThat(first.single().kind).isEqualTo(ReconstructCommand.Kind.RECONSTRUCTED)

        val wav = File(corpus, "wavs/20261015-1500-manifst1.wav")
        assertThat(wav.isFile).isTrue()
        val manifest = Corpus.parseManifest(File(corpus, "manifest.json").readText())
        val cls = manifest.classes.single { it.name == ReconstructCommand.CLASS_NAME }
        val entry = cls.files.single()
        assertThat(entry.path).isEqualTo("wavs/20261015-1500-manifst1.wav")
        assertThat(entry.seconds).isWithin(1e-9).of(15.0)
        assertThat(entry.sha256).isEqualTo(Corpus.sha256(wav))
        assertThat(manifest.kinds).contains("wav")

        // Idempotent rerun: same path, still exactly one entry.
        reconstruct(corpus)
        val again = Corpus.parseManifest(File(corpus, "manifest.json").readText())
        assertThat(again.classes.single { it.name == ReconstructCommand.CLASS_NAME }.files).hasSize(1)
    }

    @Test
    fun `dry-run validates but writes nothing`() {
        val corpus = newCorpus()
        buildSession(corpus, "20261015-1600-dryrun001", listOf(Spec(5000), Spec(5000)))

        val results = reconstruct(corpus, dryRun = true)

        assertThat(results.single().kind).isEqualTo(ReconstructCommand.Kind.RECONSTRUCTED)
        assertThat(File(corpus, "wavs").listFiles().isNullOrEmpty()).isTrue()
        val manifest = Corpus.parseManifest(File(corpus, "manifest.json").readText())
        assertThat(manifest.classes.none { it.name == ReconstructCommand.CLASS_NAME }).isTrue()
        assertThat(manifest.kinds).doesNotContain("wav")
    }

    @Test
    fun `the compressAudio cross-check flags the designed mixed cases`() {
        val corpus = newCorpus()
        // Built with WAV uploads while the recorded snapshot claims OGG —
        // the OGG→WAV fallback rebuild signature. MUST still reconstruct.
        val bundleDir = File(corpus, "bundles/20261015-1700-snapshot1")
        buildSession(
            corpus, "20261015-1700-snapshot1",
            listOf(Spec(5000), Spec(5000))
        )
        val sessionJson = File(bundleDir, "session.json")
        sessionJson.writeText(
            sessionJson.readText()
                .replace(Regex("\"compressAudio\":\\s*false"), "\"compressAudio\": true")
        )

        val results = reconstruct(corpus)

        val r = results.single()
        assertThat(r.kind).isEqualTo(ReconstructCommand.Kind.RECONSTRUCTED)
        assertThat(r.notes.any { it.contains("OGG→WAV fallback rebuild") }).isTrue()
    }
}
