package com.georgernstgraf.polishedrecognition.pipeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import com.georgernstgraf.polishedrecognition.audio.OpusOggTranscoder
import com.georgernstgraf.polishedrecognition.audio.SeamPolicy
import com.georgernstgraf.polishedrecognition.audio.SearchSpan
import com.georgernstgraf.polishedrecognition.audio.WavChunker
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttLatencyProfile
import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class VoiceSessionController(
    context: Context,
    private val pipeline: TranscriptionPipeline,
    private val settings: SettingsStore,
    private val transcoder: AudioTranscoder = OpusOggTranscoder(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Upload-size limit per chunk in bytes (#115). */
    private val chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
    /** Upload-duration limit per chunk in seconds (#115). */
    private val chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
    /** PCM bytes per background-encoded fragment (#115); tests inject huge values. */
    private val fragmentBytes: Int = FragmentPreparer.DEFAULT_FRAGMENT_BYTES,
    /**
     * Forward silence-search window around the nominal fragment boundary
     * (#117); tests inject 0 to reproduce the legacy fixed-offset cuts.
     * #124: when a [fragmentSizeProvider] is wired (production) the effective
     * window grows to `max(fragmentSearchBytes, fragmentBytes)` — see
     * [resolvedSearchBytes].
     */
    private val fragmentSearchBytes: Int = FragmentPreparer.DEFAULT_SEARCH_BYTES,
    /**
     * Acoustic pre-roll prepended to each fragment's UPLOAD (#117 round 2):
     * PCM bytes of the previous fragment re-sent ahead of the current one so
     * Whisper gets real acoustic context at the seam (fragment files stay
     * gapless; the echoed pre-roll text is trimmed by token-matching in
     * [FragmentTranscriber]). Tests inject 0 (off), mirroring
     * [fragmentSearchBytes].
     */
    private val fragmentPreRollBytes: Int = FragmentPreparer.DEFAULT_PRE_ROLL_BYTES,
    /**
     * Seam mechanism policy (#122/#128): how a seam's stitch decision
     * (pre-roll + prompt + echo trim) is made — see
     * [FragmentPreparer.seamStitchedAt]. Production defaults to
     * [SeamPolicy.Q_GATE] (#128: stitch iff the seam's silence quality
     * `Q < fragmentQualityMin` — the cut is best-effort, the stitch is the
     * guarantee); ALL/FORCED_ONLY/OFF keep their exact pre-#128 semantics
     * as frozen study arms (#129) — the `:analyzer run --policy` knob
     * selects the arm per run.
     */
    private val seamPolicy: SeamPolicy = SeamPolicy.Q_GATE,
    /**
     * #124: pass-through of the preparer's quietest-frame forced-cut
     * fallback (see [FragmentPreparer.energyFallbackEnabled]). `true` in
     * production (the frozen config); the analyzer A/Bs it via its
     * knob options. **INERT since #126** — the silence-quality
     * metric always finds a best-effort cut; kept accepted for
     * call-site stability (#129 reworks the knobs).
     */
    private val fragmentEnergyFallback: Boolean = true,
    /**
     * #126 silence-quality axis: the multiplicative term k in `X = k·M + c`
     * (gain-invariant separation). Study-only knob — no Settings UI
     * (the `:analyzer` CLI drives it).
     * **PROVISIONAL default** ([FragmentPreparer.DEFAULT_AXIS_K]) — the
     * #129 research pass on real audio determines the real value.
     */
    private val fragmentAxisK: Double = FragmentPreparer.DEFAULT_AXIS_K,
    /**
     * #126 silence-quality axis: the absolute anchor c in `X = k·M + c`,
     * 16-bit amplitude units (whisper/quiet-room). Study-only knob — no
     * Settings UI (the `:analyzer` CLI drives it).
     * **PROVISIONAL default** — #129 decides.
     */
    private val fragmentAxisC: Double = FragmentPreparer.DEFAULT_AXIS_C,
    /**
     * #126: the cut's minimum distance from the best candidate's over-axis
     * edges, in milliseconds. Study-only knob — no Settings UI
     * (the `:analyzer` CLI drives it).
     * **PROVISIONAL default** — #129 decides.
     */
    private val fragmentMarginKMs: Int = FragmentPreparer.DEFAULT_MARGIN_K_MS,
    /**
     * #126: which part of the forward search window the silence-quality
     * metric measures — the LATE half (production default, owner decision)
     * or the full window (the #129 study arm).
     */
    private val fragmentSearchSpan: SearchSpan = FragmentPreparer.DEFAULT_SEARCH_SPAN,
    /**
     * #128 stitching gate: the minimum silence quality a seam must reach to
     * stay BARE (no pre-roll/prompt/trim) under [SeamPolicy.Q_GATE] —
     * stitch iff `Q < fragmentQualityMin`. Study-only knob — no Settings
     * UI (the `:analyzer` CLI drives it). **PROVISIONAL default**
     * ([FragmentPreparer.DEFAULT_QUALITY_MIN])
     * — the #129 research pass on real audio determines the real value.
     */
    private val fragmentQualityMin: Double = FragmentPreparer.DEFAULT_QUALITY_MIN,
    /**
     * Whisper prompt window in characters (#122 study knob): passed through to
     * [FragmentTranscriber]. `<= 0` disables the prompt. Production keeps the
     * seam-local default.
     */
    private val promptMaxChars: Int = FragmentTranscriber.DEFAULT_PROMPT_MAX_CHARS,
    /**
     * #122: turn on the per-fragment `stt-fragment.json` seam evidence. Off
     * in production; the analyzer study builds its own controller with it on.
     */
    private val seamEvidence: Boolean = false,
    /**
     * Overrides the pipeline's shared [SttRequestRunner] for the live
     * fragment worker (#116); tests inject a fast-backoff runner. `null` in
     * production — the runner comes from the pipeline (same Retrofit cache
     * and evidence logger).
     */
    private val sttRunnerOverride: SttRequestRunner? = null,
    /** adb-readable encode evidence (fragment cadence), null in unit tests. */
    private val logger: RotatingJsonLogger? = null,
    /**
     * #127 audio-evidence retention: when non-null AND [audioEvidenceEnabled]
     * returns true, a FINISHED dictation session (success OR failure) copies
     * its full session PCM (`session.wav`) and its fragment session dir
     * (fragments + pre-rolls + manifest, under `fragments/`) into
     * `<externalFilesDir>/audio/<sessionId>/` — the composed `upload_<i>`
     * files are retained by [FragmentTranscriber] as they are used. The
     * store ring-buffers the evidence (newest ~3 sessions, ≤ 50 MB) and the
     * copies run BEFORE the pipeline deletes its own files, non-destructively
     * — the pipeline's behaviour is unchanged. Copy failures are best-effort
     * `Log.w` and can never fail a session. `null` in unit tests.
     */
    private val audioEvidenceStore: AudioEvidenceStore? = null,
    /** Gate for the #127 evidence retention: the Settings toggle, wired in [com.georgernstgraf.polishedrecognition.PolishedRecognitionApp]. */
    private val audioEvidenceEnabled: () -> Boolean = { false },
    /**
     * Resolves the fragment size for a NEW session in SECONDS (#116 Phase 2):
     * the per-provider auto-sizer or the manual override. `null` (or a null
     * result) falls back to the constructor [fragmentBytes]. Production
     * wires the profile-driven sizer; tests inject explicit [fragmentBytes]
     * and leave this null, so their size expectations stay untouched.
     */
    private val fragmentSizeProvider: (() -> Double?)? = null
) {

    enum class State { IDLE, RECORDING, PAUSED, PROCESSING }

    sealed class Event {
        data class StateChanged(val state: State) : Event()
        data class StageChanged(val stage: TranscriptionPipeline.TranscriptionStage) : Event()
        /**
         * A finished pipeline result (#118). [redelivered] is true when this
         * event came out of the [pendingResult] hold — nobody was bound when
         * it completed (rotation re-delivery, #83) — rather than a live emit.
         * [partial] is non-null for a raw-mode rescue delivery: the delivered
         * text is a join of fragments with one missing (see [PartialInfo]).
         */
        data class Completed(
            val result: Result<String>,
            val redelivered: Boolean = false,
            val partial: PartialInfo? = null,
        ) : Event()

        /**
         * Raw-mode rescue detail (#116/#118): the delivered text joins
         * [chunkCount] fragments with the 1-based fragment [failedIndex]
         * missing, so the text is incomplete by construction.
         */
        data class PartialInfo(val failedIndex: Int, val chunkCount: Int)

        /**
         * A cancel destroyed a session whose fragments were already
         * transcribed (#113 salvage): the joined partial transcript. Emitted
         * BEFORE the terminal [StateChanged] to IDLE; observers can copy it
         * to the clipboard. Never sent when nothing was transcribed.
         */
        data class CancelledWithTranscripts(val text: String) : Event()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    /** #136: capture PCM spools to disk — session RAM is O(1), not O(session). */
    private val recorder = AudioRecorder(File(appContext.cacheDir, CAPTURE_SPOOL))
    private val gson = Gson()

    /** Session-scoped fragment preparation (#115), only with `compress_audio` on. */
    private var preparer: FragmentPreparer? = null
    private var sessionId: String? = null

    /**
     * Live fragment transcription (#116), only with `compress_audio` on:
     * transcribes each committed fragment while the user is still dictating
     * and caches the transcripts for a cheap retry after a parked failure.
     */
    private var transcriber: FragmentTranscriber? = null

    /**
     * #142: the forensic session manifest — filled incrementally by the
     * preparer (cut metadata) and the transcriber (upload + STT outcomes),
     * finalized by the transcribe job (result + LLM pass) and written as
     * `session.json` into the evidence dir. `null` when evidence retention
     * is off (or a legacy flow without a session) — every collector treats
     * null as "no recording wanted".
     */
    private var sessionBundle: SessionBundle? = null

    init {
        // #142: the pipeline reports its per-session outcome (raw text + the
        // LLM pass) into whatever session bundle is CURRENT — the hook is set
        // once per controller and reads the live bundle. In unit tests the
        // pipeline is a mock whose stubbed methods never reach this hook.
        pipeline.onOutcome = { outcome -> sessionBundle?.recordOutcome(outcome) }
    }

    var state: State = State.IDLE
        private set

    private var callback: ((Event) -> Unit)? = null
    private var pendingResult: Event.Completed? = null
    private var accumulatedMs = 0L
    private var segStartMs = 0L
    private var transcribeJob: Job? = null
    /**
     * #118 Phase A: the raw-mode rescue detail for the run currently
     * finishing — set when a finally-failed fragment did not fail the session
     * (raw mode returns the joined partial text). Consumed by [deliver]; reset
     * at the start of every [runTranscription] so it can never leak into a
     * later session.
     */
    private var rescuePartial: Event.PartialInfo? = null
    /**
     * Secondary observers (#82). The primary [callback] slot belongs to the
     * IME forever; a RecognitionService session observes the shared
     * singleton through these without deafening the keyboard. Secondary
     * listeners receive live events only — never held [pendingResult]
     * deliveries, which belong to the primary attach flow.
     */
    private val secondaryListeners = mutableSetOf<(Event) -> Unit>()

    /**
     * Registers a secondary observer without touching the primary callback
     * slot (#82). The observer owns its registration and must call
     * [removeSecondaryListener] when done.
     */
    fun addSecondaryListener(listener: (Event) -> Unit) {
        secondaryListeners += listener
    }

    fun removeSecondaryListener(listener: (Event) -> Unit) {
        secondaryListeners -= listener
    }

    fun start(onEvent: (Event) -> Unit) {
        if (state == State.RECORDING || state == State.PROCESSING) return
        callback = onEvent
        beginRecording()
    }

    /**
     * Starts a session without touching the primary callback slot (#82,
     * service entry point): same guard and recording setup as [start], but
     * the caller observes via its secondary registration instead of
     * becoming primary.
     */
    fun startShared(secondary: (Event) -> Unit) {
        if (state == State.RECORDING || state == State.PROCESSING) return
        addSecondaryListener(secondary)
        beginRecording()
    }

    private fun beginRecording() {
        accumulatedMs = 0L
        segStartMs = System.currentTimeMillis()
        state = State.RECORDING
        // Fragment preparation starts BEFORE the capture so a recorder
        // failure can never leave the session without its preparer (#115).
        startFragmentPreparation(newSession = true)
        recorder.start()
        emit(Event.StateChanged(state))
    }

    /**
     * Fragment preparation lifecycle (#115/#116): a background worker encodes
     * 7-s fragments of the append-only buffer while the user is still
     * dictating, committing each one to `cacheDir/fragments/<sessionId>/`,
     * and the live transcription worker consumes them. Runs REGARDLESS of
     * `compress_audio` (#116, owner decision) — the setting only selects the
     * fragment format (`wavMode`): OGG/Opus when on, plain WAV when off.
     * A new session prunes other sessions' fragment dirs (max one live).
     */
    private fun startFragmentPreparation(newSession: Boolean) {
        val previous = preparer
        if (!newSession && previous != null) {
            // resume path: worker may be stopped after a failed pipeline run
            previous.startWorker()
            attachTranscriber(previous)
            return
        }
        previous?.stopWorker()
        // A new session discards the previous transcripts with the audio (#116).
        transcriber?.cancel()
        transcriber = null
        if (newSession || sessionId == null) {
            sessionId = newSessionId()
            // #142: the forensic manifest — only when retention is on
            sessionBundle = if (audioEvidenceEnabled()) SessionBundle(sessionId!!).apply {
                startedAt = System.currentTimeMillis()
            } else null
            // bound the fragment cache to one session: prune other sessions
            val root = File(appContext.cacheDir, FRAGMENTS_DIR)
            root.mkdirs()
            root.listFiles()?.forEach { if (it.name != sessionId) it.deleteRecursively() }
        }
        val p = buildPreparer()
        preparer = p
        attachTranscriber(p)
        p.startWorker()
    }

    /**
     * Wires the preparer's per-fragment commit hook into the live
     * transcription worker (#116) and reconciles any fragments already
     * committed before the wiring (fresh sessions have none; restored
     * sessions have the manifest prefix recovered by [FragmentPreparer]).
     */
    private fun attachTranscriber(p: FragmentPreparer) {
        val t = transcriber ?: buildTranscriber().also { transcriber = it }
        p.onFragmentCommitted = { index, file, preRoll, seamForced, stitched ->
            t.offer(FragmentTranscriber.CommittedFragment(index, file, preRoll, seamForced, stitched))
        }
        p.committedFragments().forEach { committed ->
            t.offer(
                FragmentTranscriber.CommittedFragment(
                    committed.index, committed.file, committed.preRoll, committed.seamForced, committed.stitched
                )
            )
        }
        t.start()
    }

    private fun buildTranscriber(): FragmentTranscriber = FragmentTranscriber(
        sttRunner = sttRunnerOverride ?: pipeline.sttRequestRunner,
        configProvider = { settings.sttProvider },
        scope = scope,
        onProgress = { pendingSeconds ->
            if (pendingSeconds > 0) {
                emit(Event.StageChanged(
                    TranscriptionPipeline.TranscriptionStage.RequestingSttProgress(pendingSeconds)
                ))
            }
        },
        logger = logger,
        seamEvidence = seamEvidence,
        promptMaxChars = promptMaxChars,
        bundle = sessionBundle,
        audioEvidenceStore = audioEvidenceStore,
        audioEvidenceEnabled = audioEvidenceEnabled
    )

    /**
     * Creates the preparer without starting the worker — used on the
     * restore→send path (#115): a restored PAUSED session goes straight to
     * `stopAndTranscribe` without ever resuming recording, and must still
     * reuse the fragments committed before the process died.
     */
    private fun ensureFragmentPreparer() {
        if (preparer != null) return
        if (sessionId == null) {
            sessionId = newSessionId()
            // #142: restore-path sessions get their manifest too
            sessionBundle = if (audioEvidenceEnabled()) SessionBundle(sessionId!!).apply {
                startedAt = System.currentTimeMillis()
            } else null
        }
        File(appContext.cacheDir, FRAGMENTS_DIR).mkdirs()
        val p = buildPreparer()
        preparer = p
        attachTranscriber(p)
    }

    private fun buildPreparer(): FragmentPreparer = FragmentPreparer(
        pcm = recorder,
        transcoder = transcoder,
        sessionDir = File(File(appContext.cacheDir, FRAGMENTS_DIR).apply { mkdirs() }, sessionId),
        fragmentBytes = resolvedFragmentBytes(),
        wavMode = !settings.compressAudio,
        chunkMaxBytes = chunkMaxBytes,
        chunkMaxSeconds = chunkMaxSeconds,
        searchBytes = resolvedSearchBytes(),
        preRollBytes = fragmentPreRollBytes,
        seamPolicy = seamPolicy,
        energyFallbackEnabled = fragmentEnergyFallback,
        axisK = fragmentAxisK,
        axisC = fragmentAxisC,
        marginKMs = fragmentMarginKMs,
        searchSpan = fragmentSearchSpan,
        qualityMin = fragmentQualityMin,
        bundle = sessionBundle,
        logger = logger
    )

    /**
     * The fragment size in PCM bytes for this session (#116 Phase 2): the
     * auto-sizer's seconds when a [fragmentSizeProvider] is wired, the
     * constructor [fragmentBytes] otherwise (tests). Defensively clamped to
     * the upload cap — a bogus resolved size must never exceed the 300-s
     * chunk limit the preparer's assembly relies on (#120).
     */
    private fun resolvedFragmentBytes(): Int {
        val seconds = fragmentSizeProvider?.invoke()
            ?: return fragmentBytes
        val clamped = seconds.coerceIn(1.0, chunkMaxSeconds)
        return (clamped * FragmentPreparer.PCM_BYTES_PER_SECOND).toInt()
    }

    /**
     * The silence-search window in PCM bytes for this session (#124): with a
     * [fragmentSizeProvider] wired (production — auto-sized fragments), the
     * window GROWS with the fragment size, `max(fragmentSearchBytes,
     * resolvedFragmentBytes())` — a fragment then spans `[S, 2S]` and the
     * silence scan sees a full fragment's worth of audio past the nominal
     * boundary. Without a provider (tests, the analyzer study — explicit
     * injected values) the constructor value keeps its exact semantics; an
     * explicitly injected 0 stays the legacy fixed-offset cut. Invariant:
     * `2S ≤
     * chunkMaxSeconds` holds by construction — the sizer clamps S to
     * [SttLatencyProfile.MAX_FRAGMENT_SECONDS] (60 s) and the chunk cap is
     * 300 s (#120).
     */
    private fun resolvedSearchBytes(): Int = when {
        fragmentSearchBytes <= 0 -> fragmentSearchBytes
        fragmentSizeProvider == null -> fragmentSearchBytes
        else -> maxOf(fragmentSearchBytes, resolvedFragmentBytes())
    }

    /**
     * Drop the session's committed fragments (flush/cancel — audio
     * discarded). Returns the transcriber's accumulated transcripts when the
     * worker already transcribed anything (#113 salvage); the caller decides
     * what to do with them (cancel salvages, flush deliberately ignores).
     */
    private fun discardFragmentSession(): FragmentTranscriber.Drained? {
        // In-flight fragment transcriptions belong to the discarded audio
        // (#116). #113: the transcriber's cancel() hands back the completed
        // transcripts instead of voiding them.
        val drained = transcriber?.cancel()
        transcriber = null
        preparer?.let {
            it.stopWorker()
            it.prune()
            return drained
        }
        // restored-but-not-yet-prepared session: the dir exists on disk only
        sessionId?.let { sid ->
            File(appContext.cacheDir, "$FRAGMENTS_DIR/$sid").deleteRecursively()
        }
        return drained
    }

    /**
     * (Re-)binds the UI callback. A transcription result that completed while
     * no UI was bound (e.g. display rotation during PROCESSING, #83) is held
     * in [pendingResult] and delivered now, so it is committed exactly as if
     * no rotation had happened. The re-emission is tagged
     * `redelivered = true` (#118) so the observer can tell a rotation
     * re-delivery from a live emit.
     */
    fun attach(onEvent: (Event) -> Unit) {
        callback = onEvent
        pendingResult?.let {
            pendingResult = null
            emit(it.copy(redelivered = true))
        }
    }

    fun detach() {
        callback = null
    }

    fun pause() {
        if (state != State.RECORDING) return
        accumulatedMs += System.currentTimeMillis() - segStartMs
        state = State.PAUSED
        recorder.pause()
        // Persist the session while it is safely stopped: if the process dies
        // (observed on Oplus across rotation), the next bind restores it via
        // restore() instead of losing the dictation (#67).
        snapshot()
        emit(Event.StateChanged(state))
    }

    fun resume() {
        if (state != State.PAUSED) return
        segStartMs = System.currentTimeMillis()
        state = State.RECORDING
        recorder.resume()
        // Fragment encoding resumes with the capture (#115); on a restored
        // session there is no preparer yet — it is created here, recovering
        // whatever the manifest already committed before the process died.
        startFragmentPreparation(newSession = false)
        emit(Event.StateChanged(state))
    }

    /**
     * @param callerPackage package of the app being dictated into (#100), used
     *   to resolve the per-app line-wrap width. `null` for callers that cannot
     *   provide one (bound service) → global width.
     */
    fun stopAndTranscribe(callerPackage: String? = null) {
        if (state != State.RECORDING && state != State.PAUSED) return
        if (state == State.RECORDING) {
            accumulatedMs += System.currentTimeMillis() - segStartMs
        }
        segStartMs = 0L
        // Non-destructive stop (#84): the PCM stays SPOOLED ON DISK so a
        // failed pipeline can park the session as PAUSED with audio + timer
        // intact (#136 — RAM no longer holds the session). The spool is
        // discarded (flushBuffer) only after a successful upload below.
        val sessionWav = recorder.stopSessionWavFile()
        // The session is over — its bytes are in hand, so any process-death
        // snapshot is stale from here on.
        clearSnapshot()
        state = State.PROCESSING
        emit(Event.StateChanged(state))

        transcribeJob = scope.launch {
            val result = try {
                runTranscription(sessionWav, callerPackage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // #142: record the outcome + write the forensic session.json
            // (both success and failure — the retry overwrites it with the
            // final outcome) BEFORE clearPrepared() wipes the working dirs.
            // Copy-only for the audio (the transcriber retains each upload
            // as it is used); cancel/flush stay deliberate discards (#127).
            recordSessionOutcome(result)
            finalizeSessionEvidence()
            sessionWav.delete()
            // On success the prepared uploads are consumed; on failure
            // they stay committed so the retry reuses them instead of
            // re-encoding (#115 stage memoization).
            if (result.isSuccess) clearPrepared()
            if (result.isSuccess) {
                recorder.flushBuffer()
                // #138: the transcripts are consumed the moment the result is
                // delivered — drop the transcriber cache + preparer WITHOUT
                // salvage (the return value is deliberately ignored). Before
                // this, a post-delivery `cancel()` (the #133 switch-back
                // destroys the IME → its onDestroy IDLE branch calls
                // `cancel()`) mistook the cached success transcripts for
                // undelivered work and emitted the #113 salvage event after
                // the text was already inserted. The transcriber is lazily
                // rebuilt for the next session.
                discardFragmentSession()
                deliver(result)
                accumulatedMs = 0L
                segStartMs = 0L
                state = State.IDLE
                emit(Event.StateChanged(state))
            } else {
                // Pipeline failure (#84): park as ordinary PAUSED — the audio
                // stays buffered (retry re-sends, resume appends), the timer
                // is preserved, and the snapshot is re-written so process
                // death is covered by restore(). The IME stays visible, shows
                // the failure Toast, and offers send/resume plus editable
                // quick settings — as if the send had never happened.
                Log.w(TAG, "pipeline failed; parking PAUSED: ${result.exceptionOrNull()?.message}")
                // Snapshot FIRST (forced — the state is still PROCESSING
                // here): a PAUSED state visible to any observer must imply
                // the snapshot files exist. The old order (state first) was
                // the #117 flake's file-race mechanism.
                snapshot(force = true)
                state = State.PAUSED
                emit(Event.StateChanged(state))
                deliver(result)
            }
        }
    }

    fun cancel() {
        transcribeJob?.cancel()
        transcribeJob = null
        // #113 salvage: fragments already uploaded + transcribed are paid-for
        // work — publish them (evidence log + observer event) before the
        // teardown voids the session. In-flight fragments are not counted
        // (their transcripts belong to the discarded audio's tail).
        // #138: salvage fires ONLY when a session actually exists. A cancel
        // on an already-IDLE controller (e.g. the IME's onDestroy teardown
        // after #133 switched the IME away post-success) must stay silent —
        // the transcripts of a SUCCESSFULLY delivered session are consumed
        // in the success path above, and any residual cache here is dropped
        // without the event (duplication beats loss never applies: nothing
        // was lost — the text is already in the field).
        val salvage = state != State.IDLE
        val drained = discardFragmentSession()
        if (salvage) drained?.takeIf { it.text.isNotBlank() }?.let { d ->
            logger?.log(
                "stt-text",
                gson.toJson(
                    mapOf(
                        "outcome" to "cancelled",
                        "chunkCount" to d.chunkCount,
                        "chunkLengths" to d.chunkLengths,
                        "language" to d.language,
                        "textLength" to d.text.length,
                        "text" to d.text
                    )
                )
            )
            emit(Event.CancelledWithTranscripts(d.text))
        }
        preparer = null
        sessionId = null
        // #142: the cancelled session is a deliberate discard (#127) — no
        // session.json; the bundle goes so nothing can write into it late.
        sessionBundle = null
        runCatching { recorder.cancel() }
        // #136: the stop WAV of a failed in-flight attempt is garbage now.
        File(appContext.cacheDir, AudioRecorder.SESSION_WAV_NAME).delete()
        clearSnapshot()
        accumulatedMs = 0L
        segStartMs = 0L
        pendingResult = null
        state = State.IDLE
        emit(Event.StateChanged(state))
        callback = null
    }

    /**
     * Discards the recorded audio but keeps the session mode (#86, flush
     * button): RECORDING keeps capturing into a fresh buffer, PAUSED stays
     * paused with an empty buffer and a zeroed timer. Unlike [cancel], the
     * UI callback stays attached and the IME stays open — closing the IME
     * is cancel's job. No-op in IDLE (nothing recorded) and PROCESSING
     * (the upload bytes are already extracted, the pipeline owns them).
     */
    fun flush() {
        if (state != State.RECORDING && state != State.PAUSED) return
        recorder.flushBuffer()
        // The fragments encoded so far referenced the discarded audio (#115).
        discardFragmentSession()
        preparer = null
        if (state == State.RECORDING) startFragmentPreparation(newSession = false)
        clearSnapshot()
        accumulatedMs = 0L
        if (state == State.RECORDING) segStartMs = System.currentTimeMillis()
        emit(Event.StateChanged(state))
    }

    fun recordedDurationMs(): Long {
        val live = if (state == State.RECORDING) System.currentTimeMillis() - segStartMs else 0L
        return accumulatedMs + live
    }

    /**
     * Persists the live session (raw PCM + recorded duration) to `cacheDir`
     * so a dead process can pick it up via [restore]. Best-effort and
     * synchronous: called with the recorder stopped (pause/teardown), never
     * mid-capture. A failure must never break the in-memory session.
     *
     * `force` (#117 flake instrumentation): the pipeline-failure branch calls
     * this while still in PROCESSING — the snapshot must land BEFORE the
     * PAUSED state becomes externally visible, so every observer that sees
     * PAUSED also sees the snapshot files (the old order — state first,
     * snapshot second — let a fast observer race the write and lose the
     * session on a process death in that window).
     */
    fun snapshot(force: Boolean = false) {
        if (!force && state != State.RECORDING && state != State.PAUSED) return
        try {
            val total = accumulatedMs +
                if (state == State.RECORDING) System.currentTimeMillis() - segStartMs else 0L
            // #136: a pure disk copy — the spool never materializes in RAM,
            // however long the dictation.
            recorder.snapshotToFile(File(appContext.cacheDir, SNAP_PCM))
            // The session id ties the snapshot to its committed fragment dir
            // (#115): after process death the restored session reuses the
            // already-encoded fragments instead of re-encoding everything.
            File(appContext.cacheDir, SNAP_META).writeText(
                gson.toJson(SessionMeta(total, sessionId))
            )
            lastSnapshotError = null
        } catch (e: Throwable) {
            // Best-effort: snapshotting must never break the live session.
            // The swallow is VISIBLE (flake instrumentation, #117 watch
            // item): a silently missing `session.meta` cost two debug
            // rounds in the test suite. (#136: the copy is disk-to-disk —
            // the old OOM-on-absurd-buffers failure class is gone.)
            lastSnapshotError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "session snapshot failed (state=$state, sessionId=$sessionId)", e)
        }
    }

    /** Last [snapshot] failure cause (#117 flake instrumentation), null when the last snapshot succeeded. */
    @Volatile var lastSnapshotError: String? = null
        private set

    /** Persisted session snapshot metadata (JSON since #115; was a bare duration). */
    private data class SessionMeta(val durationMs: Long, val sessionId: String?)

    private fun parseSnapshotMeta(raw: String): SessionMeta? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(text, Map::class.java) as? Map<String, Any>
            if (map != null && map.containsKey("durationMs")) {
                SessionMeta(
                    durationMs = (map["durationMs"] as Double).toLong(),
                    sessionId = map["sessionId"] as String?
                )
            } else {
                // legacy format: bare duration
                text.toLongOrNull()?.let { SessionMeta(it, null) }
            }
        } catch (_: Throwable) {
            text.toLongOrNull()?.let { SessionMeta(it, null) }
        }
    }

    /**
     * Restores a snapshot left by a dead process and parks the session as
     * PAUSED (resume appends to the restored audio, the timer continues from
     * the saved duration). Returns true when a snapshot was consumed; the
     * files are deleted so only the first bind after death restores. Emits
     * `StateChanged(PAUSED)` when a UI is already attached.
     */
    fun restore(): Boolean {
        if (state != State.IDLE) return false
        try {
            val meta = File(appContext.cacheDir, SNAP_META)
            val pcmFile = File(appContext.cacheDir, SNAP_PCM)
            if (!meta.exists() || !pcmFile.exists()) return false
            val parsed = parseSnapshotMeta(meta.readText()) ?: return false
            // #136: the snapshot is adopted as the capture spool (renamed
            // into place) — a restored session lives on disk, not in RAM.
            recorder.restoreFromFile(pcmFile)
            meta.delete()
            pcmFile.delete()
            accumulatedMs = parsed.durationMs.coerceAtLeast(0L)
            segStartMs = 0L
            sessionId = parsed.sessionId
            // #142: the restored session gets its manifest — the original
            // bundle died with the process; `startedAt` becomes the restore
            // moment (the original Wall-Clock start is unrecoverable).
            sessionBundle = if (parsed.sessionId != null && audioEvidenceEnabled()) {
                SessionBundle(parsed.sessionId!!).apply { startedAt = System.currentTimeMillis() }
            } else null
            // Fragment state is recovered lazily: the preparer comes alive
            // on resume()/stopAndTranscribe() and picks the manifest up.
            preparer = null
            state = State.PAUSED
            emit(Event.StateChanged(state))
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun clearSnapshot() {
        try {
            File(appContext.cacheDir, SNAP_PCM).delete()
            File(appContext.cacheDir, SNAP_META).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * Runs the transcription for a stopped session (#116). With the fragment
     * pipeline active, the live worker has already transcribed the committed
     * fragments DURING dictation — only the tail fragment is encoded now
     * (fast even at the 21-s default size) and the ordered transcripts
     * drain. The single
     * full-context LLM pass then runs over the joined raw text (owner
     * decision: the polish always sees the transcript in one piece).
     *
     * Fragment failure semantics: the lowest-order finally-failed fragment
     * fails the session — EXCEPT in raw mode, where the partial joined text
     * is still delivered (the Raw toggle is the user's rescue path when the
     * polish pass fails, and it must not require a clean STT run). Without
     * fragments (compress off, or an empty recording) the legacy batch path
     * applies unchanged.
     */
    private suspend fun runTranscription(sessionWav: File, callerPackage: String?): Result<String> {
        rescuePartial = null
        ensureFragmentPreparer()
        val p = preparer
        val t = transcriber
        if (p != null && t != null) {
            p.stopWorker()
            // A retry re-attempts the finally-failed fragments (#116); the
            // retry attempt sees fresh network conditions.
            t.resetFailures()
            emit(Event.StageChanged(TranscriptionPipeline.TranscriptionStage.CompressingAudio))
            withContext(Dispatchers.IO) { p.prepareTailSync() }
            emit(Event.StageChanged(
                TranscriptionPipeline.TranscriptionStage.RequestingSttProgress(t.pendingAudioSeconds())
            ))
            val drained = t.drain()
            if (drained.chunkCount > 0) {
                drained.failure?.let { failure ->
                    val failedIndex = drained.failedIndex!! + 1
                    val where = "fragment $failedIndex/${drained.chunkCount}"
                    if (!settings.rawMode) {
                        // #142: the session failed at STT — record the partial
                        // join as the raw text (the LLM pass never ran)
                        sessionBundle?.recordOutcome(
                            // #146: PipelineOutcome is a top-level class in :core now.
                            PipelineOutcome(rawText = drained.text, rawMode = settings.rawMode)
                        )
                        return Result.failure(Exception("$where: ${failure.message}"))
                    }
                    rescuePartial = Event.PartialInfo(failedIndex, drained.chunkCount)
                    logger?.log(
                        "prepare",
                        gson.toJson(
                            mapOf(
                                "rawRescue" to true,
                                "failed" to where,
                                "failedIndex" to failedIndex,
                                "chunkCount" to drained.chunkCount,
                                "error" to failure.message
                            )
                        )
                    )
                }
                return pipeline.finishTranscription(
                    Result.success(drained.toSttResult()),
                    callerPackage
                ) { stage -> emit(Event.StageChanged(stage)) }
            }
            // empty recording → legacy single-file path below
        }

        val files = prepareAudioFiles(sessionWav)
        return pipeline.transcribe(files, callerPackage) { stage ->
            emit(Event.StageChanged(stage))
        }
    }

    private fun FragmentTranscriber.Drained.toSttResult() = TranscriptionPipeline.SttResult(
        text = text,
        language = language,
        languageProbability = languageProbability,
        chunkCount = chunkCount,
        chunkLengths = chunkLengths
    )


    /**
     * Prepares the recording(s) for upload. With `compress_audio` enabled the
     * WAV is transcoded to Ogg/Opus; any transcoder failure falls back to the
     * original WAV so a recording is never lost over transcoding.
     *
     * Chunking (#115, port of aitranscribe's `chunk_audio`): uploads beyond
     * the STT limits (25 MB / 300 s, #120) are split at sample boundaries
     * BEFORE compression — an Ogg is never split (impossible without
     * re-encode), each chunk is compressed (or falls back to WAV)
     * individually.
     * Recordings within both limits keep the legacy single-file names
     * `recording.{wav,ogg}`.
     *
     * **Stage memoization (#115, owner request after the first on-device
     * long dictation):** every prepared upload file is committed under
     * `cacheDir/prepared/<sha256-of-wav>/` with a `manifest.json`. A failed
     * pipeline parks the session as PAUSED (the ↺ bar) and a retry recomputes
     * only the hash — matching prepared files are REUSED instead of being
     * re-encoded (a 3×400 s Opus transcode costs minutes of CPU; re-doing it
     * on every retry was pure waste). Files are reused only when the PCM is
     * byte-identical (resume appends change the hash → rebuild, which is
     * correct since chunk boundaries shift). The prepared dir is deleted
     * after a successful pipeline run and pruned when a different recording
     * is prepared, so at most one session's uploads ever sit in cacheDir.
     */
    private suspend fun prepareAudioFiles(sessionWav: File): List<File> {
        // Fragment path (#115/#116): the background worker has already encoded
        // 21-s fragments during the dictation — only the trailing partial
        // fragment and the chained-OGG assembly remain, both fast.
        // Reached only for empty recordings now (the live path handles
        // everything else); kept as the legacy safety net.
        ensureFragmentPreparer()
        val p = preparer
        if (p != null) {
            p.stopWorker()
            emit(Event.StageChanged(TranscriptionPipeline.TranscriptionStage.CompressingAudio))
            val files = withContext(Dispatchers.IO) {
                p.prepareTailSync()
                p.assembleChunks()
            }
            if (files.isNotEmpty()) return files
            // empty recording → legacy single-file path below
        }

        // #136: the legacy path below is reached only for EMPTY recordings
        // (the fragment path above handles everything else from disk) — the
        // session WAV is therefore tiny (a bare 44-byte header) and reading
        // it into RAM is safe. The spool file means a non-empty recording
        // never needs this branch.
        val wav = withContext(Dispatchers.IO) { sessionWav.readBytes() }
        val prepared = preparedDirFor(wav)
        if (prepared.reused) {
            Log.i(TAG, "Prepared upload reused from cache: ${prepared.dir.name} (${prepared.manifest.size} files)")
            return prepared.manifest.map { File(prepared.dir, it.name) }
        }

        val chunks = try {
            WavChunker.chunk(wav, chunkMaxBytes, chunkMaxSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "Chunk planning failed; using the full recording for upload", e)
            listOf(wav)
        }

        if (chunks.size <= 1) {
            val file = prepareSingleAudioFile(wav, prepared.dir)
            prepared.writeManifest(listOf(file))
            return listOf(file)
        }

        Log.w(
            TAG,
            "Recording exceeds upload limits, splitting into ${chunks.size} chunks " +
                "(largest ${chunks.maxOf { it.size } / 1024} kB)"
        )
        if (settings.compressAudio) {
            emit(Event.StageChanged(TranscriptionPipeline.TranscriptionStage.CompressingAudio))
        }
        val files = mutableListOf<File>()
        chunks.forEachIndexed { index, chunkWav ->
            files.add(prepareChunkFile(chunkWav, index, chunks.size, prepared.dir))
        }
        prepared.writeManifest(files)
        return files
    }

    private suspend fun prepareChunkFile(chunkWav: ByteArray, index: Int, total: Int, dir: File): File {
        val target = File(dir, "recording_%d.%s".format(index + 1, if (settings.compressAudio) "ogg" else "wav"))
        if (!settings.compressAudio) {
            return target.apply { writeBytes(chunkWav) }
        }
        return withContext(Dispatchers.IO) {
            try {
                val ogg = transcoder.transcode(chunkWav, target)
                Log.i(TAG, "Chunk %d/%d compressed: wav=%dB ogg=%dB".format(index + 1, total, chunkWav.size, ogg.length()))
                ogg
            } catch (e: Exception) {
                Log.w(TAG, "Chunk %d/%d Opus transcoding failed, falling back to WAV".format(index + 1, total), e)
                File(dir, "recording_%d.wav".format(index + 1)).apply { writeBytes(chunkWav) }
            }
        }
    }

    private suspend fun prepareSingleAudioFile(wav: ByteArray, dir: File): File {
        if (!settings.compressAudio) return writeWavFile(wav, dir)
        emit(Event.StageChanged(TranscriptionPipeline.TranscriptionStage.CompressingAudio))
        return try {
            withContext(Dispatchers.IO) {
                val ogg = transcoder.transcode(wav, File(dir, "recording.ogg"))
                Log.i(TAG, "Audio compressed: wav=${wav.size}B ogg=${ogg.length()}B")
                ogg
            }
        } catch (e: Exception) {
            Log.w(TAG, "Opus transcoding failed, falling back to WAV", e)
            writeWavFile(wav, dir)
        }
    }

    private fun writeWavFile(wav: ByteArray, dir: File): File =
        File(dir, "recording.wav").apply { writeBytes(wav) }

    /** Upload manifest entry: file name + size, validated against disk on reuse. */
    private data class ManifestEntry(val name: String, val bytes: Long)

    /** A committed preparation: its directory, the upload manifest, and whether it was reused. */
    private class Prepared(
        val dir: File,
        val manifest: List<ManifestEntry>,
        val reused: Boolean
    ) {
        fun writeManifest(files: List<File>) {
            val entries = files.map { ManifestEntry(it.name, it.length()) }
            try {
                File(dir, MANIFEST).writeText(
                    com.google.gson.Gson().toJson(mapOf("files" to entries.map { mapOf("name" to it.name, "bytes" to it.bytes) }))
                )
            } catch (_: Throwable) {
            }
        }

        companion object {
            const val MANIFEST = "manifest.json"
        }
    }

    /**
     * Returns the prepared dir for `wav`: reused (manifest valid, all files
     * present with the recorded sizes) when the PCM is byte-identical to a
     * previous preparation, freshly created (previous dirs pruned) otherwise.
     */
    private fun preparedDirFor(wav: ByteArray): Prepared {
        val key = try {
            sha256Hex(wav).substring(0, 16)
        } catch (_: Throwable) {
            "fallback${System.currentTimeMillis()}"
        }
        val root = File(appContext.cacheDir, PREPARED_DIR)
        val dir = File(root, key)
        if (dir.isDirectory) {
            val entries = readManifest(dir)
            if (entries != null && entries.all { File(dir, it.name).let { f -> f.exists() && f.length() == it.bytes } }) {
                return Prepared(dir, entries, reused = true)
            }
            // stale or broken: rebuild in place
            dir.deleteRecursively()
        } else {
            // bound the cache to one session: prune other recordings' dirs
            root.listFiles()?.forEach { if (it.name != key) it.deleteRecursively() }
        }
        dir.mkdirs()
        return Prepared(dir, emptyList(), reused = false)
    }

    private fun readManifest(dir: File): List<ManifestEntry>? = try {
        val raw = File(dir, Prepared.MANIFEST).readText()
        @Suppress("UNCHECKED_CAST")
        val parsed = com.google.gson.Gson().fromJson(raw, Map::class.java) as Map<String, Any>
        (parsed["files"] as List<Map<String, Any>>).map { entry ->
            ManifestEntry(entry["name"] as String, (entry["bytes"] as Double).toLong())
        }
    } catch (_: Throwable) {
        null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * #142: records the session outcome into the forensic manifest — the
     * provider configuration (the state the session ran with), the raw
     * full text (from the pipeline outcome; the partial join when the STT
     * failed before the LLM pass), the final delivered text, the delivery
     * kind (`inserted` | `error`) and the Wall-Clock delivery time. Called
     * on the success AND the failure path; a later retry overwrites.
     */
    private fun recordSessionOutcome(result: Result<String>) {
        val bundle = sessionBundle ?: return
        val now = System.currentTimeMillis()
        bundle.endedAt = now
        val outcome = bundle.pipelineOutcome
        bundle.result = SessionBundle.ResultRecord(
            rawFullText = outcome?.rawText,
            finalText = result.getOrNull(),
            deliveredAt = now,
            deliveryKind = if (result.isSuccess) "inserted" else "error",
            error = result.exceptionOrNull()?.message
        )
        bundle.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = settings.sttProvider?.baseUrl,
            sttModel = settings.sttProvider?.model,
            llmBaseUrl = settings.llmProvider?.baseUrl,
            llmModel = settings.llmProvider?.model,
            rawMode = settings.rawMode,
            compressAudio = settings.compressAudio
        )
    }

    /**
     * #142: writes the forensic manifest as `session.json` into the
     * session's evidence dir (uploads/ is retained per-fragment by the
     * transcriber as it works). Best-effort — evidence retention can never
     * fail a session. A retention turn-OFF mid-session skips the write
     * (deliberate discard).
     */
    private suspend fun finalizeSessionEvidence() {
        val store = audioEvidenceStore ?: return
        val sid = sessionId ?: return
        val bundle = sessionBundle ?: return
        if (!audioEvidenceEnabled()) return
        try {
            val bytes = bundle.write().toByteArray(Charsets.UTF_8)
            withContext(Dispatchers.IO) {
                if (!store.retainBytes(sid, SessionBundle.FILE_NAME, bytes)) {
                    Log.w(TAG, "audio evidence: session.json write failed for $sid")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "audio evidence: session manifest write failed for $sid", e)
        }
    }

    /** Deletes the prepared-upload dirs after a successful pipeline run. */
    private fun clearPrepared() {
        try {
            File(appContext.cacheDir, PREPARED_DIR).deleteRecursively()
            File(appContext.cacheDir, FRAGMENTS_DIR).deleteRecursively()
        } catch (_: Throwable) {
        }
    }

    /**
     * Delivers a finished pipeline result to whichever consumer is attached
     * (#82). Both the primary (IME) callback and any secondary observers get
     * the live `Completed` event; only when nobody is listening is the result
     * held in [pendingResult] for the next primary [attach] (rotation, #83).
     * A raw-mode rescue partial (see [PartialInfo]) is carried on the event so
     * the observer can apply a delivery policy instead of committing it
     * blindly (#118).
     *
     * A secondary-only consumer — the bound `RecognitionService` — must be
     * served here too: otherwise its session falls through to
     * `StateChanged(IDLE)` and is reported to the caller as `ERROR_CLIENT`
     * even though the pipeline produced a good result.
     */
    private fun deliver(result: Result<String>) {
        // Consume-once: the rescue detail belongs to this delivery only.
        val partial = rescuePartial.also { rescuePartial = null }
        val completed = Event.Completed(result, partial = partial)
        if (callback != null || secondaryListeners.isNotEmpty()) {
            emit(completed)
        } else {
            pendingResult = completed
        }
    }

    private fun emit(event: Event) {
        val cb = callback
        // Snapshot: a listener removed between post and delivery must not
        // fire — re-read inside the posted block too.
        if (cb == null && secondaryListeners.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb?.invoke(event)
            secondaryListeners.toList().forEach { it.invoke(event) }
        } else {
            val posted = mainHandler.post {
                cb?.invoke(event)
                secondaryListeners.toList().forEach { it.invoke(event) }
            }
            // Flake instrumentation (#117 watch item): a rejected post
            // silently DROPS the event — the missing-`Completed` flake shape.
            // Never fatal (best-effort delivery), but never invisible either.
            if (!posted) {
                Log.w(TAG, "event dropped: main looper rejected the post ($event)")
            }
        }
    }

    companion object {
        private const val TAG = "VoiceSessionController"
        private const val SNAP_PCM = "session.pcm"
        private const val SNAP_META = "session.meta"
        private const val PREPARED_DIR = "prepared"
        private const val FRAGMENTS_DIR = "fragments"
        /** #136: the capture spool — PCM never accumulates in RAM. */
        private const val CAPTURE_SPOOL = "recording.pcm"
    }
}

/**
 * #132: session IDs carry a LOCAL-time prefix (`20261012-1415-abcd1234`)
 * so a retained audio-evidence dir (`files/audio/<sessionId>/`, #127) is
 * recognisable by dictation date/time at a glance — the owner analyses
 * retained evidence days later and remembers when he dictated, not the
 * UUID. The 8-hex UUID suffix keeps uniqueness within the same minute
 * and keeps every existing consumer (dir names, logs, pull tooling)
 * working: the id is still one path/JSON-safe token. Top-level + internal
 * so the format is unit-testable without driving a full session.
 */
internal fun newSessionId(now: Date = Date()): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)
    return "$stamp-${UUID.randomUUID().toString().substringBefore('-')}"
}
