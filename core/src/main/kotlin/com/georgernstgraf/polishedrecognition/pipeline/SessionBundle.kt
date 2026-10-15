package com.georgernstgraf.polishedrecognition.pipeline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.concurrent.ConcurrentSkipListMap

/**
 * #142: the forensic SESSION MANIFEST — one `session.json` per retained
 * evidence dir (`files/audio/<sessionId>/`), making the dir a complete
 * self-contained record: the actually-uploaded audio (`uploads/`, ground
 * truth at the provider) plus everything the app did with it, with no
 * dependence on the (rotating, shallow) log streams.
 *
 * Contents (owner decisions, Epic #141 — binding):
 * - per fragment: `index`, `startMs`/`endMs` (PCM-derived boundaries),
 *   `uploadFile` (what was REALLY uploaded, pre-roll included),
 *   `preRollMs`, `sttRaw` (the untrimmed Whisper response), `sttTrimmed`,
 *   `echoTokens` (what the trim removed), `prompt` (the conditioning text
 *   sent), `sttError` (when the fragment failed), and the START-seam
 *   quality block `M`/`min`/`max`/`X`/`Q`/`centroidMs` + `stitched`/
 *   `seamForced` (the decision that governed THIS fragment's pre-roll,
 *   prompt and trim).
 * - session level: `sessionId`, `startedAt`/`endedAt` (Wall-Clock),
 *   `provider` (STT + LLM base URL/model, `rawMode`, `compressAudio`),
 *   `llm` (system prompt, user prompt, response — or `rawMode: true` /
 *   the error), `result` (`rawFullText`, `finalText`, `deliveredAt`,
 *   `deliveryKind`: `inserted` | `error`, `error`).
 *
 * The bundle is filled INCREMENTALLY during the session (the preparer
 * records cut metadata at commit time, the transcriber records upload +
 * STT outcomes as they happen) and written once at session end by the
 * controller — on the success AND the failure path, BEFORE `clearPrepared()`
 * wipes the working dirs. Cancel/flush stay deliberate discards (#127): a
 * salvaged session produces NO bundle.
 *
 * Pure JVM + Gson, testable without Android. Records are merged (a later
 * commit of the same fragment overwrites its fields — the WAV-fallback
 * rebuild re-commits fragments), never replaced wholesale.
 */
class SessionBundle(val sessionId: String) {

    /** Per-fragment forensic record (see class KDoc). Mutable merge target. */
    class FragmentRecord(
        val index: Int,
        var startMs: Long? = null,
        var endMs: Long? = null,
        /** The upload file's NAME inside `uploads/` (or the bare fragment's name). */
        var uploadFile: String? = null,
        var preRollMs: Long? = null,
        /** START-seam decision (#128): did this fragment upload with a pre-roll + prompt? */
        var stitched: Boolean? = null,
        /** START-seam evidence (#122): was the boundary a forced (hard) cut? */
        var seamForced: Boolean? = null,
        /**
         * START-seam quality block (#126) — the metric of the cut that
         * produced this fragment: `M`, `min`, `max`, `X`, `Q`, `centroidMs`.
         */
        var quality: Map<String, Any?>? = null,
        /** The UNTRIMMED Whisper response text (what the server returned). */
        var sttRaw: String? = null,
        /** The stored (echo-trimmed) fragment transcript. */
        var sttTrimmed: String? = null,
        var echoTokens: Int? = null,
        /** The Whisper conditioning prompt actually sent (null = none). */
        var prompt: String? = null,
        /** Final STT failure of this fragment (after the runner's retries). */
        var sttError: String? = null
    )

    /** Session-level provider configuration (the state the session ran with). */
    data class ProviderInfo(
        val sttBaseUrl: String?,
        val sttModel: String?,
        val llmBaseUrl: String?,
        val llmModel: String?,
        val rawMode: Boolean,
        val compressAudio: Boolean
    )

    /** The LLM pass outcome (session-level — the polish sees the whole transcript). */
    data class LlmPass(
        val rawMode: Boolean,
        val systemPrompt: String? = null,
        val userPrompt: String? = null,
        val response: String? = null,
        val error: String? = null
    )

    /** The delivery outcome (what the user actually got, and when). */
    data class ResultRecord(
        val rawFullText: String?,
        val finalText: String?,
        val deliveredAt: Long?,
        /** `inserted` (delivered to the IME) | `error` (pipeline failure, parked). */
        val deliveryKind: String?,
        val error: String? = null
    )

    val fragments = ConcurrentSkipListMap<Int, FragmentRecord>()

    var startedAt: Long? = null
    var endedAt: Long? = null
    var provider: ProviderInfo? = null
    var llm: LlmPass? = null
    var result: ResultRecord? = null

    /**
     * The pipeline's last reported outcome — NOT serialized (transient: its
     * content lands in [llm] and [result]). Kept so the controller can
     * compose the `result` record with the raw text when it finalizes.
     */
    @Transient
    var pipelineOutcome: PipelineOutcome? = null
        private set

    /**
     * #142: consumes the pipeline's outcome — the LLM block and the raw text
     * stash. Called by the pipeline itself (its `onOutcome` hook, wired once
     * by the controller) or directly for the fragment-failure path (which
     * returns before the LLM pass ever runs).
     */
    fun recordOutcome(outcome: PipelineOutcome) {
        pipelineOutcome = outcome
        llm = LlmPass(
            rawMode = outcome.rawMode,
            systemPrompt = outcome.systemPrompt,
            userPrompt = outcome.userPrompt,
            response = outcome.llmResponse,
            error = outcome.llmError
        )
    }

    /** Get-or-create the record for [index] — merge semantics, never replaced. */
    fun fragment(index: Int): FragmentRecord =
        fragments.getOrPut(index) { FragmentRecord(index) }

    /** The JSON shape of a bundle — `fragments` as a sorted ARRAY (index order). */
    private data class BundleJson(
        val sessionId: String,
        val startedAt: Long?,
        val endedAt: Long?,
        val provider: ProviderInfo?,
        val llm: LlmPass?,
        val result: ResultRecord?,
        val fragments: List<FragmentRecord>
    )

    private fun toDto(): BundleJson = BundleJson(
        sessionId = sessionId,
        startedAt = startedAt,
        endedAt = endedAt,
        provider = provider,
        llm = llm,
        result = result,
        fragments = fragments.values.toList()
    )

    fun toJson(): String = gson.toJson(toDto())

    fun write(): String = pretty.toJson(toDto())

    companion object {
        /** The evidence file name inside the session dir (#142). */
        const val FILE_NAME = "session.json"

        private val gson = Gson()
        private val pretty = GsonBuilder().setPrettyPrinting().create()
    }
}
