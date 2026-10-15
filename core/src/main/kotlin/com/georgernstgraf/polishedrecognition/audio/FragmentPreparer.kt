package com.georgernstgraf.polishedrecognition.audio

import com.georgernstgraf.polishedrecognition.pipeline.CoreOs
import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.georgernstgraf.polishedrecognition.pipeline.SessionBundle
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Encodes the live recording into small OGG fragments WHILE the user is
 * still dictating (#115, owner design): every ~[FRAGMENT_SECONDS] of
 * accumulated PCM is transcoded in its own MediaCodec/Muxer run and
 * **committed** to the session directory with a manifest entry.
 *
 * Why fragments, not chunks: a mid-stream-cut OGG is undecodable (the
 * OpusHead/OpusTags header pages live at the stream start), but OGG
 * **chaining** (RFC 3533) is a native format feature — simply concatenating
 * self-contained fragment files produces a valid chained stream every
 * decoder (ffmpeg/Whisper) plays through, with a per-link encoder pre-skip
 * "tick" (~6.5 ms) at each seam. An upload chunk (hard-capped at 300 s, #120)
 * is therefore assembled at stop time by byte-concatenating its fragments —
 * no custom muxer involved.
 *
 * **Silence-quality-aligned boundaries (#117 → #126):** the fixed 7-s byte
 * cuts split words at the seams (the owner observed consistent word drops),
 * and each fragment's transcript started cold. The cut point is therefore
 * * MOVED into silence: the nominal boundary stays at [FRAGMENT_SECONDS],
 * but the encoder waits for the forward search window and hands the
 * **search span** — by default the window's LATE half
 * `[nominalEnd + search/2, nominalEnd + search]` (owner decision #126,
 * "immer in der zweiten Hälfte des Windows") — to [SilenceQuality]: a
 * per-fragment adaptive axis `X = k·M + c`, a normalized deficit curve,
 * candidates as contiguous below-axis regions, the cut at the AREA CENTROID
 * of the best deficit-area candidate Q, clamped to a K-ms margin from the
 * candidate's over-axis edges. The stream stays GAPLESS: fragment *i+1*
 * starts exactly where *i* ended, so the assembled chunks still reproduce
 * the original PCM byte-for-byte. No candidate (min ≈ M — continuous
 * speech, or a whisper room under the absolute anchor) → best-effort forced
 * cut at the QUIETEST frame of the span (the #124 semantics, now the only
 * forced path, Q = 0). Because the metric's M spans the search span (which
 * ends at the window end), the decision ALWAYS waits for the full window —
 * the #117 early-commit-on-found-silence is gone; every fragment commits
 * at ≤ S+search bytes of audio, which was the old worst case anyway.
 *
 * **#128 stitching gate — the cut is best-effort, the stitch is the
 * guarantee:** whether a seam gets the pre-roll (+ the text prompt and the
 * echo trim in the transcriber) is a QUALITY decision, not a cut-type
 * decision. Under [SeamPolicy.Q_GATE] (the production default) a seam
 * stitches iff its Q < [qualityMin]: a solid pause (Q ≥ qualityMin) needs
 * no manufactured contact; a shallow/whisper seam or a forced cut (Q = 0)
 * gets the full safety net. The decision is made HERE, once per seam
 * ([seamStitchedAt]), and flows to the transcriber through
 * [Committed.stitched] and the commit hook — pre-roll, prompt and trim can
 * never disagree. The old cut-type rule survives verbatim inside
 * [SeamPolicy.FORCED_ONLY] as a frozen study arm (#129).
 *
 * **Acoustic pre-roll (#117 round 2):** the text prompt conditions only the
 * decoder — it cannot add acoustic context, and a server-side VAD can trim
 * quiet onsets near the clip edge. Every fragment ≥ 1 therefore ALSO gets a
 * `preroll_%06d.<ext>` side file: the last [PRE_ROLL_SECONDS] of PCM before
 * its start, in the SAME format as the fragments. The fragment FILES stay
 * gapless (chunk assembly still reproduces the original PCM byte-for-byte) —
 * the pre-roll exists only to be prepended to the fragment's UPLOAD
 * ([com.georgernstgraf.polishedrecognition.pipeline.FragmentTranscriber]
 * byte-concatenates `preRoll + fragment` into the upload; OGG chaining makes
 * that a valid stream). The echoed pre-roll text is trimmed from the
 * transcript by token-matching against the previous fragment's known
 * transcript tail. A failed pre-roll encode degrades to `null` and must
 * NEVER trigger the session-level WAV fallback — the upload simply omits it.
 *
 * The commit-every-step architecture (owner request): the PCM source is
 * append-only, so encoded fragments stay valid for the lifetime of the
 * session — a pipeline failure + retry reuses them all (a retry after a
 * slow STT endpoint pays ZERO re-encode), a resumed recording only adds
 * fragments. This survives process death via the session id persisted in
 * the controller's snapshot meta. Fragment boundaries are recorded in the
 * manifest ([Entry.end]) so a restored preparer continues at the exact
 * last cut — legacy manifests (without offsets) are discarded and the
 * prefix re-encoded (the PCM is fully available; costs seconds).
 *
 * Encoder-failure fallback: if the Opus transcode fails (e.g. no encoder on
 * the ROM), the whole session switches to WAV fragments — the already
 * encoded OGG fragments are discarded and re-encoded as WAV from the still
 * fully available PCM prefix, and chunks are then assembled as WAV files.
 * Never mixed-format chunks. In [wavMode] the session writes WAV fragments
 * from the start (#116): the live transcription path runs regardless of
 * `compress_audio`, which only selects the fragment format.
 */
class FragmentPreparer(
    private val pcm: PcmSource,
    private val transcoder: AudioTranscoder,
    private val sessionDir: File,
    private val sampleRate: Int = 16_000,
    private val fragmentBytes: Int = DEFAULT_FRAGMENT_BYTES,
    chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
    chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
    /**
     * WAV-fragment mode (#116): skip the Opus transcoder entirely and write
     * plain WAV fragments — the live transcription path runs regardless of
     * `compress_audio`; the setting only selects the fragment format. No
     * MediaCodec needed, robust on ROMs without an Opus encoder.
     */
    private val wavMode: Boolean = false,
    /**
     * Forward silence-search window in PCM bytes (#117). Tests inject small
     * values: 0 reproduces the legacy fixed-offset cuts exactly.
     */
    private val searchBytes: Int = DEFAULT_SEARCH_BYTES,
    /**
     * Acoustic pre-roll length in PCM bytes (#117 round 2): the tail of the
     * previous fragment re-sent ahead of this one IN THE UPLOAD ONLY (the
     * fragment files stay gapless). Tests inject 0 (off), mirroring
     * [searchBytes].
     */
    private val preRollBytes: Int = DEFAULT_PRE_ROLL_BYTES,
    /**
     * Seam mechanism policy (#122/#128): whether a boundary is STITCHED
     * (pre-roll composed + prompt sent + echo trim armed), decided per seam
     * by [seamStitchedAt]. [SeamPolicy.Q_GATE] is the production default
     * (stitch iff the seam's Q < [qualityMin]); ALL/FORCED_ONLY/OFF keep
     * their exact pre-#128 semantics as frozen study arms (#129).
     */
    private val seamPolicy: SeamPolicy = SeamPolicy.ALL,
    /**
     * #124: on a full search window with NO qualifying silence run, fall back
     * to the quietest 10-ms frame in the window's LATE half. **INERT since
     * #126:** the silence-quality metric ALWAYS finds a best-effort cut (the
     * quietest span frame is its built-in no-candidate path), so there is no
     * "hard cut at the nominal boundary" variant left to disable. The
     * parameter stays accepted so the analyzer/controller call sites keep
     * compiling — its rework is #129's job; do not rewire its semantics.
     */
    private val energyFallbackEnabled: Boolean = true,
    /**
     * #126 silence-quality axis: the multiplicative term k in
     * `X = k·M + c` — gain-invariant separation (scales with the room/AGC
     * together with M). **PROVISIONAL default** — the #129 research pass on
     * real audio determines the real value.
     */
    private val axisK: Double = DEFAULT_AXIS_K,
    /**
     * #126 silence-quality axis: the absolute anchor c in `X = k·M + c`,
     * in 16-bit amplitude units — the only absolute element (whisper/
     * quiet-room: an axis threading under everything forces the best-effort
     * path, arming stitching). **PROVISIONAL default** — #129 decides.
     */
    private val axisC: Double = DEFAULT_AXIS_C,
    /**
     * #126: the cut's minimum distance from the best candidate's over-axis
     * edges, in milliseconds (quantized to 10-ms frames).
     * **PROVISIONAL default** — #129 decides.
     */
    private val marginKMs: Int = DEFAULT_MARGIN_K_MS,
    /**
     * #126: which part of the forward search window the metric measures —
     * the LATE half (production default, owner decision) or the full window
     * (the #129 study arm).
     */
    private val searchSpan: SearchSpan = DEFAULT_SEARCH_SPAN,
    /**
     * #128 stitching gate: the minimum silence quality a seam must reach to
     * stay BARE under [SeamPolicy.Q_GATE] — stitch iff `Q < qualityMin`
     * (strict; Q == qualityMin is good enough). 10-ms frame units, i.e. Q
     * ≈ the minimum pause duration in frames. **PROVISIONAL default**
     * ([DEFAULT_QUALITY_MIN]) — the #126 fixtures bracket it (solid pause
     * Q ≈ 40 → bare, whisper threading Q ≈ 2.3 → stitch); the #129
     * research pass on real audio determines the real value. Study-only
     * knob — no Settings UI.
     */
    private val qualityMin: Double = DEFAULT_QUALITY_MIN,
    private val pollIntervalMs: Long = 500,
    /**
     * #142: the session's forensic manifest — the preparer records each
     * fragment's boundaries + its START-seam decision/quality block here at
     * commit time (mirroring the `prepare` stream, which stays the live
     * debug view). `null` in tests without a bundle.
     */
    private val bundle: SessionBundle? = null,
    private val logger: RotatingJsonLogger? = null
) {

    /** `end` = PCM byte offset AFTER this fragment (#117) — 0 marks legacy. */
    data class Entry(
        val name: String,
        val bytes: Long,
        val end: Long,
        /**
         * Did this fragment's END cut come from a qualifying silence
         * candidate (#126: a below-axis deficit region was found, i.e.
         * `Q > 0` — the interim `silenceAligned` mapping; under the #128
         * Q-gate it only drives the frozen `FORCED_ONLY` A/B arm) or was it
         * a forced best-effort cut (false)? Legacy manifests parse as
         * `false` (= forced). The field name is kept so pre-#126 manifests
         * keep parsing unchanged.
         */
        val silenceAligned: Boolean = false,
        /**
         * #128: the #126 metric's Q of this fragment's END cut — the seam
         * quality the Q-gate compares against [qualityMin] when the NEXT
         * fragment's stitch decision is made (`seamStitchedAt(index + 1)`).
         * `null` when no search span was measured (the stop-tap tail, the
         * `searchBytes = 0` legacy path, pre-#128 manifests) — the gate
         * treats null as stitch (the conservative direction). Gson omits
         * nulls, so legacy manifests keep parsing unchanged.
         */
        val seamQ: Double? = null
    )
    data class ManifestState(val fallbackToWav: Boolean, val fragments: List<Entry>)

    /**
     * (index, file, preRoll, seamForced, stitched) of one committed
     * fragment (#122/#128).
     */
    data class Committed(
        val index: Int,
        val file: File,
        val preRoll: File?,
        /** Was the boundary at this fragment's start a forced (hard) cut? */
        val seamForced: Boolean,
        /**
         * #128: was the boundary at this fragment's start STITCHED under
         * the active [SeamPolicy] (pre-roll composed for THIS fragment +
         * prompt + echo trim)? The single seam decision — the transcriber
         * keys both its prompt and (via the pre-roll file) its trim on it.
         */
        val stitched: Boolean
    )

    private val gson = Gson()

    // CopyOnWriteArrayList: mutated by the encoder thread (add on commit,
    // clear on fallback/prune), read concurrently by [committedFragments]
    // from the controller coroutine (#116) — snapshot iteration without locks.
    private val entries = CopyOnWriteArrayList<Entry>()
    private var fallbackToWav = false
    private var nextFragment = 0

    /**
     * Per-fragment commit hook (#116): fired (best-effort, on the encoder
     * thread) after a fragment is committed to disk + manifest — the live
     * transcription worker consumes fragments through this as they close.
     * `index` is the fragment's position in the stream, `file` the committed
     * OGG/WAV fragment file, `preRoll` the acoustic pre-roll side file
     * (#117 round 2; `null` for fragment 0, a failed pre-roll encode, or a
     * seam the active [SeamPolicy] left bare), `seamForced` whether the
     * boundary at this fragment's start was a forced cut (#122 evidence),
     * and `stitched` the #128 seam decision for this fragment's start
     * boundary (pre-roll + prompt + echo trim armed).
     */
    var onFragmentCommitted:
            ((index: Int, file: File, preRoll: File?, seamForced: Boolean, stitched: Boolean) -> Unit)? = null

    /**
     * Upload chunk cap in PCM bytes (#117): the smaller of the byte limit
     * (25 MB WAV) and the duration limit (300 s = 9.6 MB of 16-kHz mono
     * 16-bit PCM, #120 — the time cap binds and the byte cap can only lower
     * it further). Chunks accumulate fragments until adding the next would
     * exceed this — with variable fragment lengths a count-based grouping
     * could overshoot the cap, and the Phase 2 auto-sizer may settle on
     * fragment sizes different from the default, so the cap must be
     * enforced per accumulated byte, not per fragment count.
     */
    private val chunkMaxPcmBytes = minOf(
        chunkMaxBytes.toLong(),
        (chunkMaxSeconds * sampleRate * 2).toLong()
    )

    @Volatile private var running = false
    private var workerThread: Thread? = null

    init {
        sessionDir.mkdirs()
        recover()
        // WAV mode is a session property: force the flag AFTER recover so a
        // stale OGG manifest can never mix formats into a WAV session.
        if (wavMode) fallbackToWav = true
    }

    /** Fragment size in PCM bytes (21 s · 16 kHz · 16 bit · mono, #117 round 2). */
    companion object {
        /** PCM byte rate: 16 kHz · 16 bit · mono. */
        const val PCM_BYTES_PER_SECOND = 32_000
        const val FRAGMENT_SECONDS = 21.0
        const val DEFAULT_FRAGMENT_BYTES = (FRAGMENT_SECONDS * PCM_BYTES_PER_SECOND).toInt()

        /** Default forward silence-search window (#117): 2 s. */
        const val DEFAULT_SEARCH_SECONDS = 2.0
        val DEFAULT_SEARCH_BYTES = (DEFAULT_SEARCH_SECONDS * 32_000).toInt()

        /**
         * #126 provisional axis defaults — PLACEHOLDERS until the #129
         * research pass on real audio picks the real values (study knobs,
         * no Settings UI).
         */
        const val DEFAULT_AXIS_K = 0.65
        const val DEFAULT_AXIS_C = 100.0
        const val DEFAULT_MARGIN_K_MS = 150
        val DEFAULT_SEARCH_SPAN = SearchSpan.LATE_HALF

        /**
         * #128 provisional stitching-gate threshold — PLACEHOLDER until the
         * #129 research pass on real audio picks the real value (study
         * knob, no Settings UI). The #126 fixtures bracket it: a solid
         * pause reads Q ≈ 40 (bare), the whisper-threading regime Q ≈ 2.3
         * (stitch).
         */
        const val DEFAULT_QUALITY_MIN = 10.0

        /** Acoustic pre-roll (#117 round 2): 1 s of the previous fragment. */
        const val PRE_ROLL_SECONDS = 1.0
        val DEFAULT_PRE_ROLL_BYTES = (PRE_ROLL_SECONDS * 32_000).toInt()
        private const val MANIFEST = "manifest.json"
    }

    /**
     * Rebuilds state from a previous preparation of the same session dir:
     * the manifest's longest valid contiguous prefix (file present with the
     * recorded size) becomes the resume point; orphan files beyond it are
     * removed so a stale crash can never poison the stream.
     *
     * Fragment boundaries must be known to continue the stream gaplessly
     * (#117): a manifest without `end` offsets (pre-#117) is discarded
     * wholesale — the PCM prefix is fully available and re-encoding it
     * costs seconds, while a wrong boundary would corrupt every chunk.
     */
    private fun recover() {
        val manifest = File(sessionDir, MANIFEST)
        if (!manifest.isFile) return
        try {
            val state = gson.fromJson(manifest.readText(), ManifestState::class.java) ?: return
            fallbackToWav = state.fallbackToWav
            val legacy = state.fragments.any { it.end <= 0L }
            if (legacy) {
                sessionDir.listFiles()?.forEach { file ->
                    if (file.name != MANIFEST) file.delete()
                }
                fallbackToWav = wavMode
                return
            }
            var count = 0
            for (entry in state.fragments) {
                val file = File(sessionDir, entry.name)
                if (file.isFile && file.length() == entry.bytes && entry.end <= pcm.pcmSize()) {
                    count++
                } else break
            }
            nextFragment = count
            // The pre-roll side files are NOT manifest entries (#117 round 2)
            // — they must survive the orphan cleanup exactly like their
            // fragments, or a restored session loses its seam context.
            val kept = state.fragments.take(count)
            val keep = kept.map { it.name }.toSet() +
                kept.map { it.name.replaceFirst("frag_", "preroll_") }.toSet()
            sessionDir.listFiles()?.forEach { file ->
                if (file.name != MANIFEST && file.name !in keep) file.delete()
            }
            entries.clear()
            entries.addAll(kept)
        } catch (_: Throwable) {
        }
    }

    /** PCM offset where the next fragment begins: the last committed end (#117). */
    private fun nextFragmentStart(): Long =
        entries.lastOrNull()?.end?.takeIf { it > 0 } ?: (nextFragment.toLong() * fragmentBytes)

    /** PCM bytes fragment [index] spans, derived from the committed boundaries. */
    private fun fragmentPcmBytes(index: Int): Long {
        val end = entries[index].end
        val start = if (index == 0) 0L else entries[index - 1].end
        return (end - start).coerceAtLeast(0L)
    }

    /**
     * Starts the background worker on a DEDICATED daemon thread: the worker
     * loop runs (potentially forever, until [stopWorker]) — a shared
     * single-thread executor would deadlock the NEXT session's
     * [stopWorker] behind an abandoned predecessor's never-ending future
     * (found in the #116 test suite). Idempotent.
     */
    fun startWorker() {
        if (running) return
        running = true
        workerThread = Thread {
            try {
                // #146: was the platform Process.setThreadPriority(...) call
                // inline — routed through the CoreOs hook (identical on
                // device; no-op in :core/:analyzer). The platform Process
                // backend installed at app start performs the real call.
                CoreOs.setThreadPriority(CoreOs.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {
            }
            while (running) {
                val before = nextFragment
                encodeAvailableFragments()
                if (nextFragment == before) {
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            name = "FragmentPreparer"
            isDaemon = true
            start()
        }
    }

    /** Signals the worker to stop and waits for the in-flight fragment. */
    fun stopWorker() {
        running = false
        workerThread?.let {
            it.interrupt()
            runCatching { it.join() }
        }
        workerThread = null
    }

    /**
     * Encodes every fragment whose cut point is DECIDABLE (#117/#126): a
     * fragment commits once the FULL search window is available — the
     * silence-quality metric's M spans the search span, which ends at the
     * window end, so the decision cannot be made earlier (the #117
     * early-commit-on-found-silence is gone). The metric then ALWAYS
     * returns a best-effort cut: the best candidate's margin-clamped area
     * centroid (silence-aligned), or the quietest span frame when no
     * candidate exists (forced). With [searchBytes] = 0 this degenerates
     * to the legacy fixed-offset behavior.
     */
    fun encodeAvailableFragments() {
        while (true) {
            val start = nextFragmentStart()
            val nominalEnd = start + fragmentBytes
            val total = pcm.pcmSize()
            if (total < nominalEnd) return
            val searchEnd = nominalEnd + searchBytes.toLong()
            val windowEnd = minOf(total, searchEnd)
            if (searchBytes <= 0) {
                // legacy fixed-offset cuts (#117 tests, searchBytes = 0)
                encodeFragment(nextFragment, start, nominalEnd, silenceAligned = false)
                nextFragment++
                continue
            }
            if (windowEnd < searchEnd) {
                // M spans the search span, which ends at the window end —
                // wait for the full window rather than deciding on a
                // partial span
                return
            }
            // #126: measure the search span (late half by default, the
            // owner decision; the full window is the #129 A/B arm) on the
            // high-passed copy and cut at the best candidate's
            // margin-clamped area centroid — best-effort even with no
            // candidate (the quietest span frame, tagged FORCED).
            val spanStart = when (searchSpan) {
                SearchSpan.LATE_HALF -> nominalEnd + searchBytes.toLong() / 2
                SearchSpan.FULL_WINDOW -> nominalEnd
            }
            val result = SilenceQuality.evaluate(
                pcm.copyPcmRange(spanStart, searchEnd),
                sampleRate,
                axisK,
                axisC,
                marginFrames = marginKMs / 10
            )
            if (result == null) {
                // span below one analysis frame — hard cut at the nominal
                // boundary (nothing was measured)
                encodeFragment(nextFragment, start, nominalEnd, silenceAligned = false)
            } else {
                encodeFragment(
                    nextFragment, start, spanStart + result.cutOffset,
                    silenceAligned = result.candidateFound, metric = result,
                    metricSpanStart = spanStart
                )
            }
            nextFragment++
        }
    }

    /**
     * Synchronously encodes everything not yet committed: first any full
     * fragments that became available, then the trailing partial fragment
     * (which is the LAST fragment — no seam follows it, so the remainder is
     * taken whole). Called by the controller after the capture stopped —
     * even at the 21-s default fragment size this is fast work in the
     * normal case (the live worker already consumed every full fragment).
     */
    fun prepareTailSync() {
        encodeAvailableFragments()
        val start = nextFragmentStart()
        val total = pcm.pcmSize()
        if (total > start) {
            // The tail is NOT a searched cut — it is taken whole (#122 keeps
            // the stop-tap tail free of any minimum), so it counts as forced.
            encodeFragment(nextFragment, start, total, silenceAligned = false)
            nextFragment++
        }
    }

    /**
     * Concatenates the committed fragments into upload chunks: fragments
     * accumulate until the next one would exceed the PCM-byte cap
     * ([chunkMaxPcmBytes], #117) — duration/byte limits hold for ANY
     * fragment size, which the Phase 2 auto-sized fragments require.
     * Writes into the session dir. Returns [] when nothing was encoded at
     * all — callers fall back to the single-file path.
     */
    fun assembleChunks(): List<File> {
        if (entries.isEmpty()) return emptyList()
        val ext = if (fallbackToWav) "wav" else "ogg"
        val chunks = mutableListOf<File>()
        var index = 0
        var chunkNo = 1
        while (index < entries.size) {
            var acc = 0L
            var groupEnd = index
            while (groupEnd < entries.size) {
                val fragBytes = fragmentPcmBytes(groupEnd)
                if (groupEnd > index && acc + fragBytes > chunkMaxPcmBytes) break
                acc += fragBytes
                groupEnd++
            }
            val target = File(sessionDir, "recording_%d.%s".format(chunkNo, ext))
            if (fallbackToWav) {
                // WAV must be REBUILT, not byte-concatenated (#119): the first
                // fragment's header would otherwise cap the decoded duration at
                // one fragment.
                val sources = (index until groupEnd).map { File(sessionDir, entries[it].name) }
                WavAssembler.concat(sources, target)
            } else {
                // OGG page chaining makes a byte-concat a valid stream.
                FileOutputStream(target).use { out ->
                    for (i in index until groupEnd) {
                        File(sessionDir, entries[i].name).inputStream().use { input ->
                            input.copyTo(out, 64 * 1024)
                        }
                    }
                }
            }
            chunks.add(target)
            index = groupEnd
            chunkNo++
        }
        return chunks
    }

    /** Discards all committed fragments (flush/cancel — the PCM they encoded is gone). */
    fun prune() {
        entries.clear()
        fallbackToWav = wavMode
        nextFragment = 0
        sessionDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * (index, file, preRoll, seamForced, stitched) of every committed
     * fragment, in commit order (#116/#122/#128). The pre-roll is derived
     * from the fragment's name and is `null` when its file is absent
     * (fragment 0, a failed pre-roll encode, a policy-bare seam, or a
     * legacy session). Safe to call while the encoder thread runs — COW
     * snapshot semantics.
     */
    fun committedFragments(): List<Committed> =
        entries.mapIndexed { index, entry ->
            val file = File(sessionDir, entry.name)
            val preRoll = File(sessionDir, entry.name.replaceFirst("frag_", "preroll_"))
            Committed(index, file, preRoll.takeIf { it.isFile }, seamForcedAt(index), seamStitchedAt(index))
        }

    /** Was the boundary at fragment [index]'s start a forced (hard) cut? (#122) */
    private fun seamForcedAt(index: Int): Boolean =
        index > 0 && entries.getOrNull(index - 1)?.silenceAligned != true

    /**
     * #128 stitching gate — THE seam decision, evaluated identically for
     * the pre-roll composition ([writePreRoll]), the commit hook /
     * [committedFragments] (the transcriber's prompt gate) and the
     * `prepare.json` `stitched` evidence field: was the boundary at
     * fragment [index]'s start STITCHED under the active [seamPolicy]?
     *
     * - [SeamPolicy.Q_GATE] (production): stitch iff the seam's Q (the
     *   previous entry's [Entry.seamQ]) is `null` (legacy/unmeasured cut —
     *   the conservative direction) or `< qualityMin` (strict: Q ==
     *   qualityMin is a good-enough pause, bare). The forced path's Q = 0
     *   always stitches.
     * - [SeamPolicy.FORCED_ONLY] (frozen A/B arm, exact pre-#128 rule):
     *   stitch iff the previous cut was forced (`silenceAligned == false`).
     * - [SeamPolicy.ALL] / [SeamPolicy.OFF]: always / never.
     *
     * Fragment 0 has no predecessor → never stitched. The decision is
     * deterministic in the append-only entry list, so every consumer
     * evaluates the same value.
     */
    private fun seamStitchedAt(index: Int): Boolean {
        if (index <= 0) return false
        val previous = entries.getOrNull(index - 1)
        return when (seamPolicy) {
            SeamPolicy.ALL -> true
            SeamPolicy.OFF -> false
            SeamPolicy.FORCED_ONLY -> previous?.silenceAligned != true
            SeamPolicy.Q_GATE -> {
                val q = previous?.seamQ
                q == null || q < qualityMin
            }
        }
    }

    private fun encodeFragment(
        index: Int,
        start: Long,
        end: Long,
        silenceAligned: Boolean,
        metric: SilenceQuality.Result? = null,
        metricSpanStart: Long = 0L
    ) {
        if (end <= start) return
        val data = pcm.copyPcmRange(start, end)
        val started = System.currentTimeMillis()
        var preRoll: File? = null

        if (!fallbackToWav && !wavMode) {
            val target = File(sessionDir, "frag_%06d.ogg".format(index))
            try {
                transcoder.transcode(WavWriter.write(data, sampleRate), target)
                entries.add(Entry(target.name, target.length(), end, silenceAligned, metric?.quality))
            } catch (e: Exception) {
                // Session-level fallback: OGG is impossible on this ROM —
                // discard the OGG fragments and rebuild the WHOLE prefix as
                // WAV (the PCM prefix is immutable and fully available),
                // then continue in WAV mode below for this very fragment.
                // The committed boundaries + cut flags + seam qualities
                // (#117/#122/#128) are captured BEFORE the clear so the
                // rebuild reproduces all three.
                val boundaries = entries.map { it.end }
                val flags = entries.map { it.silenceAligned }
                val seamQs = entries.map { it.seamQ }
                fallbackToWav = true
                entries.clear()
                sessionDir.listFiles()?.forEach { file ->
                    if (file.name != MANIFEST) file.delete()
                }
                for (i in 0 until index) {
                    val s = if (i == 0) 0L else boundaries[i - 1].takeIf { it > 0 }
                        ?: (i.toLong() * fragmentBytes)
                    val e = boundaries.getOrNull(i)?.takeIf { it > 0 }
                        ?: (s + fragmentBytes)
                    val rebuilt = writeWavFragment(i, pcm.copyPcmRange(s, e))
                    // The rebuild's delete sweep wiped the pre-rolls — they
                    // are re-created for every rebuilt fragment (#117 round 2).
                    writePreRoll(i, s, ogg = false)
                    entries.add(
                        Entry(rebuilt.name, rebuilt.length(), e, flags.getOrNull(i) ?: false, seamQs.getOrNull(i))
                    )
                }
                logger?.log(
                    "prepare",
                    gson.toJson(mapOf("fallback" to true, "fragment" to index, "error" to (e.message ?: "")))
                )
            }
        }

        val file = if (fallbackToWav) writeWavFragment(index, data) else {
            // OGG path already appended its entry above; the pre-roll encode
            // happens BEFORE the manifest write so a crash can never leave a
            // committed fragment without the pre-roll it advertised. A failed
            // pre-roll encode yields `null` — it must NOT trigger the
            // session-level WAV fallback.
            preRoll = writePreRoll(index, start, ogg = true)
            writeManifest()
            logFragment(index, data.size, end, metric, metricSpanStart, start, started)
            notifyCommitted(index, "frag_%06d.ogg".format(index), preRoll, seamForcedAt(index), seamStitchedAt(index))
            return
        }
        preRoll = writePreRoll(index, start, ogg = false)
        entries.add(Entry(file.name, file.length(), end, silenceAligned, metric?.quality))
        writeManifest()
        logFragment(index, data.size, end, metric, metricSpanStart, start, started)
        notifyCommitted(index, file.name, preRoll, seamForcedAt(index), seamStitchedAt(index))
    }

    private fun notifyCommitted(
        index: Int,
        name: String,
        preRoll: File?,
        seamForced: Boolean,
        stitched: Boolean
    ) {
        try {
            onFragmentCommitted?.invoke(index, File(sessionDir, name), preRoll, seamForced, stitched)
        } catch (_: Throwable) {
        }
    }

    /**
     * Writes fragment [index]'s acoustic pre-roll (#117 round 2): the
     * [preRollBytes] of PCM immediately before the fragment's start (clamped
     * at the stream start), encoded in the SAME format as the fragments.
     * Fragment 0 has no predecessor → `null`. ANY failure → `null`: a
     * missing pre-roll degrades gracefully (bare-fragment upload) and a
     * pre-roll transcode failure must NEVER trigger the session-level WAV
     * fallback — this is why the OGG transcode here is isolated from the
     * fragment encode's own catch block.
     */
    private fun writePreRoll(index: Int, start: Long, ogg: Boolean): File? {
        if (index == 0 || preRollBytes <= 0) return null
        // #128 stitching gate: the pre-roll is composed iff the seam at
        // this fragment's start was STITCHED under the active policy —
        // under Q_GATE that is `seam Q < qualityMin` (a solid pause stays
        // bare; a shallow/whisper seam or a forced cut gets the overlap).
        // The SAME decision drives the transcriber's prompt and the
        // `stitched` evidence, via [seamStitchedAt].
        if (!seamStitchedAt(index)) return null
        return try {
            val from = (start - preRollBytes).coerceAtLeast(0L)
            if (from >= start) return null
            val data = pcm.copyPcmRange(from, start)
            val target = if (ogg) File(sessionDir, "preroll_%06d.ogg".format(index))
            else File(sessionDir, "preroll_%06d.wav".format(index))
            if (ogg) transcoder.transcode(WavWriter.write(data, sampleRate), target)
            else target.writeBytes(WavWriter.write(data, sampleRate))
            target
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Fragment evidence (#117/#126/#128): the cadence fields stay unchanged
     * and the old `silenceAligned` flag is REPLACED by the #126 contract
     * block — `M`, `min` (the P5-ish floor stat, not a single-frame min),
     * `max`, `X`, `Q`, `centroidMs` (the cut position relative to the
     * FRAGMENT start; the margin-clamped centroid — what the analyzer needs)
     * and `stitched` (#128, real since this issue): the stitch decision for
     * the seam this record DESCRIBES (the fragment's END cut) — whether the
     * NEXT fragment's upload is composed with a pre-roll under the active
     * [SeamPolicy] (`Q < qualityMin` under the production Q-gate). The
     * block is present only when a search span was actually measured (the
     * stop-tap tail and the `searchBytes = 0` legacy path carry none).
     */
    private fun logFragment(
        index: Int,
        bytes: Int,
        end: Long,
        metric: SilenceQuality.Result?,
        metricSpanStart: Long,
        fragmentStart: Long,
        started: Long
    ) {
        val record = linkedMapOf<String, Any?>(
            "fragment" to index,
            "bytes" to bytes,
            "pcmEnd" to end,
            "encodeMs" to (System.currentTimeMillis() - started),
            "mode" to if (fallbackToWav) "wav" else "ogg"
        )
        if (metric != null) {
            record["M"] = metric.meanLevel
            record["min"] = metric.floorLevel
            record["max"] = metric.maxLevel
            record["X"] = metric.axis
            record["Q"] = metric.quality
            record["centroidMs"] = (metricSpanStart + metric.cutOffset - fragmentStart) / 32.0
            record["stitched"] = seamStitchedAt(index + 1)
        }
        // #142: mirror the record into the forensic session manifest — the
        // fragment's boundaries + its START-seam decision, and the END cut's
        // metric block lands on the NEXT fragment (it IS that fragment's
        // start seam: the cut that ends fragment index starts index + 1).
        bundle?.let { b ->
            val own = b.fragment(index)
            own.startMs = fragmentStart / 32L
            own.endMs = end / 32L
            own.stitched = seamStitchedAt(index)
            own.seamForced = seamForcedAt(index)
            if (metric != null) {
                val next = b.fragment(index + 1)
                next.startMs = end / 32L
                next.stitched = seamStitchedAt(index + 1)
                next.seamForced = seamForcedAt(index + 1)
                next.quality = linkedMapOf(
                    "M" to metric.meanLevel,
                    "min" to metric.floorLevel,
                    "max" to metric.maxLevel,
                    "X" to metric.axis,
                    "Q" to metric.quality,
                    "centroidMs" to (metricSpanStart + metric.cutOffset - fragmentStart) / 32.0
                )
            }
        }
        logger?.log("prepare", gson.toJson(record))
    }

    private fun writeWavFragment(index: Int, data: ByteArray): File {
        val target = File(sessionDir, "frag_%06d.wav".format(index))
        target.writeBytes(WavWriter.write(data, sampleRate))
        return target
    }

    private fun writeManifest() {
        try {
            File(sessionDir, MANIFEST).writeText(gson.toJson(ManifestState(fallbackToWav, entries.toList())))
        } catch (_: Throwable) {
        }
    }
}
