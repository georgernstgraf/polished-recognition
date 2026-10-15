package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.google.gson.Gson
import java.io.File

/**
 * Read-side DTOs for the forensic session bundle (`session.json`, #142) —
 * the analyzer parses device bundles AND its own corpus-mode run outputs.
 *
 * Zero-drift guarantee: the WRITE side is the REAL `:core` [com.georgernst
 * graf.polishedrecognition.pipeline.SessionBundle] (same class the device
 * runs); these DTOs mirror its serialized shape and a round-trip regression
 * test (`SessionBundleRoundTripTest`) proves they stay in sync — a field
 * added to `SessionBundle` without a mirror fails the suite.
 *
 * All fields are nullable with defaults so the generated no-arg constructor
 * lets Gson parse partial/legacy bundles (Gson omits nulls on write).
 *
 * NOTE: Gson in this repo predates 2.8.6 — never `JsonParser.parseString`
 * (PITFALLS); `Gson().fromJson` only.
 */
class BundleDto(
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val provider: ProviderDto? = null,
    val llm: LlmDto? = null,
    val result: ResultDto? = null,
    /** Sorted JSON array of per-fragment records (the BundleJson shape). */
    val fragments: List<FragmentDto> = emptyList()
)

class ProviderDto(
    val sttBaseUrl: String? = null,
    val sttModel: String? = null,
    val llmBaseUrl: String? = null,
    val llmModel: String? = null,
    val rawMode: Boolean? = null,
    val compressAudio: Boolean? = null
)

class LlmDto(
    val rawMode: Boolean? = null,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val response: String? = null,
    val error: String? = null
)

class ResultDto(
    val rawFullText: String? = null,
    val finalText: String? = null,
    val deliveredAt: Long? = null,
    val deliveryKind: String? = null,
    val error: String? = null
)

class FragmentDto(
    val index: Int = 0,
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** The upload file's NAME inside `uploads/` (or the bare fragment's name). */
    val uploadFile: String? = null,
    val preRollMs: Long? = null,
    /** START-seam decision (#128): did this fragment upload with a pre-roll + prompt? */
    val stitched: Boolean? = null,
    /** START-seam evidence (#122): was the boundary a forced (hard) cut? */
    val seamForced: Boolean? = null,
    /**
     * START-seam quality block (#126): `M`, `min`, `max`, `X`, `Q`,
     * `centroidMs` — present only when a search span was actually measured.
     */
    val quality: Map<String, Double?>? = null,
    val sttRaw: String? = null,
    val sttTrimmed: String? = null,
    val echoTokens: Int? = null,
    val prompt: String? = null,
    /** #148: wall-clock (epoch ms) of the successful attempt's send. */
    val sttSentAt: Long? = null,
    /** #148: wall-clock (epoch ms) when the successful response arrived. */
    val sttReceivedAt: Long? = null,
    /** #148: the composed upload's size in bytes. */
    val uploadBytes: Long? = null,
    val sttError: String? = null
)

class BundleException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BundleIo {

    private val gson = Gson()

    /** Parses one `session.json` (tolerant: missing fields stay null). */
    fun load(file: File): BundleDto = parse(file.readText())

    fun parse(json: String): BundleDto = try {
        gson.fromJson(json, BundleDto::class.java)
    } catch (e: Exception) {
        throw BundleException("session.json not parseable: ${e.message}", e)
    }

    /**
     * Every `session.json` directly under [dir] (one session dir per child
     * directory), sorted by session id. Missing files / unparseable ones are
     * reported, not fatal.
     */
    fun loadAll(dir: File): List<Pair<File, BundleDto>> {
        val out = mutableListOf<Pair<File, BundleDto>>()
        for (child in dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()) {
            val f = File(child, "session.json")
            if (f.isFile) out.add(f to load(f))
        }
        return out
    }

    /** The seam quality Q of one fragment record (null when unmeasured). */
    fun q(fragment: FragmentDto): Double? = fragment.quality?.get("Q")

    /** Fragment audio duration in ms (from the boundaries), or null. */
    fun durationMs(fragment: FragmentDto): Long? {
        val start = fragment.startMs ?: return null
        val end = fragment.endMs ?: return null
        return (end - start).coerceAtLeast(0)
    }

    /** #148: request duration in ms (successful attempt's wall-clock span), or null. */
    fun requestMs(fragment: FragmentDto): Long? {
        val sent = fragment.sttSentAt ?: return null
        val received = fragment.sttReceivedAt ?: return null
        return (received - sent).coerceAtLeast(0)
    }

    /**
     * #148: the real-time factor of one fragment — request duration vs audio
     * duration (`RTF < 1` means faster than realtime; the pipeline is serial,
     * so this is the streaming budget). Null without timing or boundaries.
     */
    fun realTimeFactor(fragment: FragmentDto): Double? {
        val req = requestMs(fragment)?.toDouble() ?: return null
        val audio = durationMs(fragment)?.toDouble() ?: return null
        if (audio <= 0.0) return null
        return req / audio
    }
}
