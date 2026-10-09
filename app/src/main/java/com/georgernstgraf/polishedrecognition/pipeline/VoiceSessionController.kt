package com.georgernstgraf.polishedrecognition.pipeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import com.georgernstgraf.polishedrecognition.audio.OpusOggTranscoder
import com.georgernstgraf.polishedrecognition.audio.WavChunker
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
     */
    private val fragmentSearchBytes: Int = FragmentPreparer.DEFAULT_SEARCH_BYTES,
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
     * Gate for the #117 shadow comparison: when it returns true, a
     * successfully drained fragment session additionally transcribes the
     * FULL recording in one pass after the result is delivered and logs
     * both texts side by side into `stt-shadow.json` — the objective seam
     * quality evidence (joined fragment transcripts vs full-context STT of
     * the same audio). Deliberately fire-and-forget: it must never delay
     * the result, fail the session, or pollute the Phase 2 measurement
     * streams (its requests run with evidence off). Production wires a
     * LAN-provider gate — cloud providers would pay real money twice.
     */
    private val shadowSttEnabled: () -> Boolean = { false }
) {

    enum class State { IDLE, RECORDING, PAUSED, PROCESSING }

    sealed class Event {
        data class StateChanged(val state: State) : Event()
        data class StageChanged(val stage: TranscriptionPipeline.TranscriptionStage) : Event()
        data class Completed(val result: Result<String>) : Event()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder = AudioRecorder()
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

    var state: State = State.IDLE
        private set

    private var callback: ((Event) -> Unit)? = null
    private var pendingResult: Result<String>? = null
    private var accumulatedMs = 0L
    private var segStartMs = 0L
    private var transcribeJob: Job? = null
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
            sessionId = UUID.randomUUID().toString()
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
        p.onFragmentCommitted = { index, file ->
            t.offer(FragmentTranscriber.CommittedFragment(index, file))
        }
        p.committedFragments().forEach { (index, file) ->
            t.offer(FragmentTranscriber.CommittedFragment(index, file))
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
        }
    )

    /**
     * Creates the preparer without starting the worker — used on the
     * restore→send path (#115): a restored PAUSED session goes straight to
     * `stopAndTranscribe` without ever resuming recording, and must still
     * reuse the fragments committed before the process died.
     */
    private fun ensureFragmentPreparer() {
        if (preparer != null) return
        if (sessionId == null) sessionId = UUID.randomUUID().toString()
        File(appContext.cacheDir, FRAGMENTS_DIR).mkdirs()
        val p = buildPreparer()
        preparer = p
        attachTranscriber(p)
    }

    private fun buildPreparer(): FragmentPreparer = FragmentPreparer(
        pcm = recorder,
        transcoder = transcoder,
        sessionDir = File(File(appContext.cacheDir, FRAGMENTS_DIR).apply { mkdirs() }, sessionId),
        fragmentBytes = fragmentBytes,
        wavMode = !settings.compressAudio,
        chunkMaxBytes = chunkMaxBytes,
        chunkMaxSeconds = chunkMaxSeconds,
        searchBytes = fragmentSearchBytes,
        logger = logger
    )

    /** Drop the session's committed fragments (flush/cancel — audio discarded). */
    private fun discardFragmentSession() {
        // In-flight fragment transcriptions belong to the discarded audio (#116).
        transcriber?.cancel()
        transcriber = null
        preparer?.let {
            it.stopWorker()
            it.prune()
            return
        }
        // restored-but-not-yet-prepared session: the dir exists on disk only
        sessionId?.let { sid ->
            File(appContext.cacheDir, "$FRAGMENTS_DIR/$sid").deleteRecursively()
        }
    }

    /**
     * (Re-)binds the UI callback. A transcription result that completed while
     * no UI was bound (e.g. display rotation during PROCESSING, #83) is held
     * in [pendingResult] and delivered now, so it is committed exactly as if
     * no rotation had happened.
     */
    fun attach(onEvent: (Event) -> Unit) {
        callback = onEvent
        pendingResult?.let {
            pendingResult = null
            emit(Event.Completed(it))
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
        // Non-destructive stop (#84): the PCM stays buffered so a failed
        // pipeline can park the session as PAUSED with audio + timer intact.
        // The buffer is discarded (flushBuffer) only after a successful
        // upload below.
        val wav = recorder.stopPreservingBuffer()
        // The session is over — its bytes are in hand, so any process-death
        // snapshot is stale from here on.
        clearSnapshot()
        state = State.PROCESSING
        emit(Event.StateChanged(state))

        transcribeJob = scope.launch {
            val result = try {
                runTranscription(wav, callerPackage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // On success the prepared uploads are consumed; on failure
            // they stay committed so the retry reuses them instead of
            // re-encoding (#115 stage memoization).
            if (result.isSuccess) clearPrepared()
            if (result.isSuccess) {
                recorder.flushBuffer()
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
                state = State.PAUSED
                snapshot()
                emit(Event.StateChanged(state))
                deliver(result)
            }
        }
    }

    fun cancel() {
        transcribeJob?.cancel()
        transcribeJob = null
        discardFragmentSession()
        preparer = null
        sessionId = null
        runCatching { recorder.cancel() }
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
     */
    fun snapshot() {
        if (state != State.RECORDING && state != State.PAUSED) return
        try {
            val total = accumulatedMs +
                if (state == State.RECORDING) System.currentTimeMillis() - segStartMs else 0L
            File(appContext.cacheDir, SNAP_PCM).writeBytes(recorder.snapshotPcm())
            // The session id ties the snapshot to its committed fragment dir
            // (#115): after process death the restored session reuses the
            // already-encoded fragments instead of re-encoding everything.
            File(appContext.cacheDir, SNAP_META).writeText(
                gson.toJson(SessionMeta(total, sessionId))
            )
        } catch (_: Throwable) {
            // Best-effort: snapshotting must never break the live session —
            // this explicitly includes OutOfMemoryError on absurdly large
            // buffers, not just ordinary I/O failures.
        }
    }

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
            recorder.restorePcm(pcmFile.readBytes())
            meta.delete()
            pcmFile.delete()
            accumulatedMs = parsed.durationMs.coerceAtLeast(0L)
            segStartMs = 0L
            sessionId = parsed.sessionId
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
     * (sub-second) and the ordered transcripts drain. The single
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
    private suspend fun runTranscription(wav: ByteArray, callerPackage: String?): Result<String> {
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
                    val where = "fragment ${drained.failedIndex!! + 1}/${drained.chunkCount}"
                    if (!settings.rawMode) {
                        return Result.failure(Exception("$where: ${failure.message}"))
                    }
                    logger?.log(
                        "prepare",
                        gson.toJson(mapOf("rawRescue" to true, "failed" to where, "error" to failure.message))
                    )
                }
                // Shadow comparison (#117): assemble the full recording into
                // a DEDICATED dir — `clearPrepared()` deletes the session dir
                // on success, and the main-looper-queued shadow coroutine
                // only reads its files later (the assembly race was found in
                // the first on-device verify). The coroutine deletes the dir
                // when it is done. The gate decision is logged as a breadcrumb
                // so a silently-absent shadow record is diagnosable on-device.
                val shadowGate = shadowSttEnabled()
                logger?.log(
                    "stt-shadow",
                    gson.toJson(mapOf("event" to "gate", "enabled" to shadowGate, "chunkCount" to drained.chunkCount))
                )
                if (shadowGate) {
                    val shadowFiles = withContext(Dispatchers.IO) {
                        p.assembleChunks(File(appContext.cacheDir, SHADOW_DIR).apply { deleteRecursively(); mkdirs() })
                    }
                    if (shadowFiles.isNotEmpty()) launchShadowStt(shadowFiles, drained.text)
                }
                return pipeline.finishTranscription(
                    Result.success(drained.toSttResult()),
                    callerPackage
                ) { stage -> emit(Event.StageChanged(stage)) }
            }
            // empty recording → legacy single-file path below
        }

        val files = prepareAudioFiles(wav)
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
     * Fire-and-forget full-context STT of the same recording the live
     * fragment worker transcribed (#117): joins the per-chunk texts and
     * logs them next to the joined fragment transcripts in
     * `stt-shadow.json`. Runs in its OWN coroutine so the delivered result
     * is never delayed. The STT requests run with evidence off so
     * `stt-upload`/`stt-latency` (the Phase 2 profile input) stay
     * unpolluted. Every failure — including a crash of the shadow body —
     * is logged into the stream: a silently absent record cost a
     * diagnose round in the first on-device verify.
     */
    private fun launchShadowStt(files: List<File>, fragmentText: String) {
        scope.launch {
            try {
                val config = settings.sttProvider
                if (config == null) {
                    logger?.log("stt-shadow", gson.toJson(mapOf("error" to "STT provider not configured")))
                    return@launch
                }
                val parts = mutableListOf<String>()
                var error: String? = null
                for ((index, file) in files.withIndex()) {
                    val result = pipeline.sttRequestRunner.run(
                        audioFile = file,
                        config = config,
                        chunk = index + 1,
                        chunkCount = files.size,
                        evidence = false
                    )
                    val body = result.getOrNull()
                    if (body == null) {
                        error = result.exceptionOrNull()?.message ?: "unknown error"
                        break
                    }
                    parts.add(body.text)
                }
                val fullText = parts.filter { it.isNotBlank() }.joinToString(" ").trim()
                logger?.log(
                    "stt-shadow",
                    gson.toJson(
                        mapOf(
                            "chunks" to files.size,
                            "fragmentChars" to fragmentText.length,
                            "fullChars" to fullText.length,
                            "fragmentText" to fragmentText,
                            "fullText" to fullText,
                            "error" to error
                        )
                    )
                )
            } catch (crashed: Throwable) {
                // pure diagnostics — never a pipeline failure, but NEVER silent
                logger?.log(
                    "stt-shadow",
                    gson.toJson(mapOf("error" to "shadow crashed: ${crashed.message}"))
                )
            } finally {
                // the assembled shadow chunks are single-use
                try {
                    File(appContext.cacheDir, SHADOW_DIR).deleteRecursively()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Prepares the recording(s) for upload. With `compress_audio` enabled the
     * WAV is transcoded to Ogg/Opus; any transcoder failure falls back to the
     * original WAV so a recording is never lost over transcoding.
     *
     * Chunking (#115, port of aitranscribe's `chunk_audio`): uploads beyond
     * the STT limits (25 MB / 600 s) are split at sample boundaries BEFORE
     * compression — an Ogg is never split (impossible without re-encode),
     * each chunk is compressed (or falls back to WAV) individually.
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
    private suspend fun prepareAudioFiles(wav: ByteArray): List<File> {
        // Fragment path (#115/#116): the background worker has already encoded
        // 7-s fragments during the dictation — only the trailing partial
        // fragment and the chained-OGG assembly remain, both sub-second.
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
     *
     * A secondary-only consumer — the bound `RecognitionService` — must be
     * served here too: otherwise its session falls through to
     * `StateChanged(IDLE)` and is reported to the caller as `ERROR_CLIENT`
     * even though the pipeline produced a good result.
     */
    private fun deliver(result: Result<String>) {
        if (callback != null || secondaryListeners.isNotEmpty()) {
            emit(Event.Completed(result))
        } else {
            pendingResult = result
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
            mainHandler.post {
                cb?.invoke(event)
                secondaryListeners.toList().forEach { it.invoke(event) }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceSessionController"
        private const val SNAP_PCM = "session.pcm"
        private const val SNAP_META = "session.meta"
        private const val PREPARED_DIR = "prepared"
        private const val FRAGMENTS_DIR = "fragments"
        /** Owns the shadow comparison's assembled chunks (#117) — deleted after use. */
        private const val SHADOW_DIR = "shadow"
    }
}
