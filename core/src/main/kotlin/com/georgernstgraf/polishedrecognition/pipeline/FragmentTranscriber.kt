package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.AudioDuration
import com.georgernstgraf.polishedrecognition.audio.SeamOverlap
import com.georgernstgraf.polishedrecognition.audio.WavAssembler
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Live fragment transcription (#116, the successor of #64's parallel-uploads
 * idea): consumes the fragments the [com.georgernstgraf.polishedrecognition
 * .audio.FragmentPreparer] commits DURING dictation and runs one STT request
 * per fragment through [SttRequestRunner] (retry ×3, transient-only), so at
 * stop-tap only the tail fragment remains. Stop→raw ≈ 1.2 s worst case
 * against the measured gregor profile at the 21-s default fragment size,
 * <1 s against GROQ.
 *
 * Serial by construction (C=1) — PERMANENT (owner verdict 2026-10-10, #116):
 * even the slowest provider (gregor) runs ~25× realtime, so parallelism has
 * no provider to help anywhere; and the prompt carry-over + echo trim need
 * fragment i−1's transcript at fragment i's upload time, which the serial
 * worker guarantees. Concurrency would break seam conditioning for zero gain.
 *
 * Ordering: transcripts accumulate in fragment order regardless of offer
 * order ([ConcurrentSkipListMap] keyed by fragment index); the join
 * semantics are exactly the pipeline's single-space join. A finally-failed
 * fragment (after the runner's retries) is recorded per index and does not
 * block later fragments; the lowest-order failure surfaces in [Drained].
 *
 * Failure handling (owner decision): a fragment failure fails the session in
 * polish mode (lowest-order failed fragment named, as the batch path does);
 * in RAW mode the partial joined text is still delivered — the Raw toggle is
 * the user's rescue path. Transcripts stay cached in memory, so a retry
 * after a parked failure re-drains instantly without re-transcribing.
 *
 * **#183 — the AUTH latch:** a fragment that fails with
 * [SttFailure.AUTH] (401/403) sets [authBlocked]; every later fragment of
 * the session is recorded as failed WITHOUT a request, and its composed
 * upload still reaches the journal, so nothing is lost and the provider is
 * not hammered. [resetFailures] clears the latch, because a new run may carry
 * the token the user just fixed.
 *
 * **Acoustic pre-roll (#117 round 2):** each fragment's UPLOAD is composed
 * as `preRoll + fragment` byte-concatenation (OGG chaining makes that a
 * valid stream — the fragment FILES stay gapless for chunk assembly). The
 * pre-roll gives the decoder ~1 s of real acoustic seam context and
 * protects quiet onsets from a server-side VAD; its text re-appears at the
 * transcript start (an ACOUSTIC echo — it occurs even when the text prompt
 * is disabled) and is trimmed by conservative token-matching against the
 * previous fragment's known transcript tail BEFORE the transcript is
 * stored (an untrimmed echo would re-condition the next fragment's prompt
 * and known tail, compounding per seam). Trim evidence goes into the
 * dedicated `stt-trim.json` stream.
 *
 * **#128 stitching gate:** WHICH seams get the pre-roll (+ the prompt) is
 * decided by the preparer ([FragmentPreparer.seamStitchedAt] — under the
 * production Q-gate: seam quality `Q < qualityMin`) and arrives per
 * fragment as [CommittedFragment.stitched]; the prompt below is applied
 * exactly on that flag, so prompt, pre-roll and trim share ONE decision.
 */
class FragmentTranscriber(
    private val sttRunner: SttRequestRunner,
    private val configProvider: () -> SttProviderConfig?,
    private val scope: CoroutineScope,
    /**
     * #116 (owner decision 2026-11-12): fires with the AUDIO LENGTH of the
     * fragment that is about to be transcribed — emitted at request start, so
     * the stage line always describes the running request. It used to carry
     * the *backlog* ([pendingAudioSeconds]), which the user read as a
     * countdown: the measured backlog was median 10.7 s / p90 22.0 s while
     * the real stop→raw wait was median 1.65 s (RTF 0.07).
     */
    private val onFragmentTranscribing: ((audioSeconds: Int) -> Unit)? = null,
    /** adb-readable trim evidence (`stt-trim.json`), null in unit tests. */
    private val logger: RotatingJsonLogger? = null,
    /**
     * Whisper prompt window (#117 / #122): characters of the previous
     * transcript tail passed as `prompt`. Seam-local by default — the prompt
     * and the acoustic pre-roll now cover the same span; the round-1
     * `takeLast(800)` vastly exceeded the pre-roll and could make the decoder
     * emit prompt text not present in the audio (a dup source). The window is
     * snapped to a word boundary so the prompt never starts mid-word. The old
     * 800-char behaviour stays reachable as a study knob (#122).
     */
    private val promptMaxChars: Int = DEFAULT_PROMPT_MAX_CHARS,
    /**
     * #122: when true, every fragment's transcript evidence is appended to
     * `stt-fragment.json` — index, words, chars, language, seam type, the
     * actual prompt text, echo-trim tokens and the seam-overlap metric. Off
     * in production (the analyzer study turns it on): the array is rewritten
     * per fragment, which is cheap for a study but unnecessary for daily use.
     */
    private val seamEvidence: Boolean = false,
    /**
     * #127 audio-evidence retention: when non-null AND [audioEvidenceEnabled]
     * returns true, every composed `upload_<i>.<ext>` file is COPIED into
     * the session's evidence dir (`files/audio/<sessionId>/uploads/`) right
     * before its `finally` deletion — the post-mortem sees exactly what the
     * server saw, pre-roll included. Copy failures are best-effort `Log.w`
     * and never touch the fragment's own flow. `null` in unit tests.
     */
    private val audioEvidenceStore: AudioEvidenceStore? = null,
    /** Gate for the #127 evidence copy: the Settings toggle, wired by the controller. */
    private val audioEvidenceEnabled: () -> Boolean = { false },
    /**
     * #142: the session's forensic manifest — the transcriber records each
     * fragment's upload (what was REALLY sent), pre-roll length, raw + trimmed
     * STT text, echo-trim tokens and the conditioning prompt here as they
     * happen. `null` in tests without a bundle.
     */
    private val bundle: SessionBundle? = null,
    /**
     * #182: the durable fragment journal. When non-null AND [sessionId] is
     * set, each fragment's composed upload is COPIED into
     * `filesDir/journal/<sid>/` with a PENDING record BEFORE its first upload
     * attempt, and the record is flipped once the transcript is stored — so a
     * kill, reboot or flat battery leaves the session recoverable, without
     * depending on the #127/#142 retention slider.
     *
     * Best-effort by design: a journal failure is logged and never fails the
     * fragment (the same rule the #142 evidence copy follows). `null` in unit
     * tests.
     */
    private val journal: FragmentJournal? = null,
    /** #182: the journal's session key. `null` disables journaling. */
    private val sessionId: String? = null
) {

    data class CommittedFragment(
        val index: Int,
        val file: File,
        /** Pre-roll side file (#117 round 2), null when absent. */
        val preRoll: File? = null,
        /**
         * #122: was the boundary at this fragment's start a forced (hard)
         * cut? Evidence only (the `stt-fragment.json` `seamForced` field).
         */
        val seamForced: Boolean = true,
        /**
         * #128: was the boundary at this fragment's start STITCHED — the
         * single seam decision made by the preparer under the active
         * [com.georgernstgraf.polishedrecognition.audio.SeamPolicy] (under
         * the production Q-gate: the seam's silence quality Q fell below
         * `qualityMin`). The text prompt is applied EXACTLY then, and the
         * pre-roll composition (hence the echo trim) keys on the same
         * seam's pre-roll file — the two can never disagree.
         */
        val stitched: Boolean = true
    )

    /**
     * The ordered STT result of a drained queue — shaped for
     * [TranscriptionPipeline.finishTranscription]. `failedIndex`/`failure`
     * name the lowest-order failed fragment when any.
     */
    data class Drained(
        val text: String,
        val language: String?,
        val languageProbability: Float?,
        val chunkCount: Int,
        val chunkLengths: List<Int>,
        val failedIndex: Int? = null,
        val failure: SttRequestRunner.SttRequestException? = null
    )

    private sealed interface WorkerItem {
        data class Fragment(val fragment: CommittedFragment, val durationMs: Long) : WorkerItem
        object CheckIdle : WorkerItem
    }

    companion object {
        /**
         * Default prompt window (#122): ~12 words of seam-local context,
         * aligned with the 1-s acoustic pre-roll span. Replaces the round-1
         * `takeLast(800)` (~200 tokens), which predated the pre-roll.
         */
        const val DEFAULT_PROMPT_MAX_CHARS = 96

        /** Echo-trim match window (#117 round 2): at most 8 tokens of head. */
        const val MAX_ECHO_TOKENS = EchoTrim.MAX_ECHO_TOKENS

        /**
         * #183: the failure text a fragment carries when its session's token
         * was already rejected — it never reached the network, so there is no
         * HTTP code to report. The class (`AUTH`) is what the UI acts on.
         */
        const val AUTH_BLOCKED_MESSAGE = "STT token rejected — no further attempts in this session"

        private const val TAG = "FragmentTranscriber"
    }

    private val gson = Gson()
    private val channel = Channel<WorkerItem>(Channel.UNLIMITED)
    private val transcripts = ConcurrentSkipListMap<Int, SttResponse>()
    private val failures = ConcurrentSkipListMap<Int, SttRequestRunner.SttRequestException>()

    /** Every fragment ever offered, by index — [resetFailures] re-queues from it. */
    private val offered = ConcurrentHashMap<Int, CommittedFragment>()

    /** Indices queued but not yet consumed — membership guard + drain check. */
    private val queued = ConcurrentHashMap.newKeySet<Int>()

    private val pendingAudioMs = AtomicLong()

    /**
     * Set when a provider rejects the Whisper `prompt` field with HTTP 400
     * (#117): the current request is retried without the prompt once, and
     * every later fragment of the session skips it. Falls back gracefully
     * on servers without prompt support.
     */
    @Volatile private var promptDisabled = false

    @Volatile private var worker: Job? = null

    @Volatile private var pendingDrain: CompletableDeferred<Unit>? = null

    /**
     * #183: set once a fragment fails with [SttFailure.AUTH]. Every LATER
     * fragment of this session is recorded as failed WITHOUT a request — the
     * "stop hammering" half of the 401 storm a mis-migrated endpoint produced
     * (five 401s in one session). Cleared by [resetFailures] (a new Send
     * re-reads the token from Settings) and by [cancel].
     */
    @Volatile private var authBlocked = false

    /**
     * Set by [cancel] — the ONE place the channel is closed. [drain] must not
     * wait for a pending-drain signal that no worker will ever complete.
     * Owned here instead of asking `channel.isClosedForSend`: that query is
     * `@DelicateCoroutinesApi` (racy by contract, and a known migration
     * hazard), while a closed channel only ever comes from [cancel].
     */
    @Volatile private var closed = false

    /**
     * Enqueues a committed fragment. Idempotent per index (a re-committed
     * fragment after a WAV-fallback rebuild is ignored when its transcript
     * or failure already exists). Adds the fragment's audio duration to the
     * pending-drain figure the stage line displays.
     */
    fun offer(fragment: CommittedFragment) {
        val durationMs = AudioDuration.estimateMs(fragment.file) ?: 0L
        synchronized(this) {
            if (transcripts.containsKey(fragment.index) || failures.containsKey(fragment.index)) return
            if (!queued.add(fragment.index)) return
        }
        pendingAudioMs.addAndGet(durationMs)
        offered[fragment.index] = fragment
        channel.trySend(WorkerItem.Fragment(fragment, durationMs))
    }

    /** Starts the single serial worker. Idempotent. */
    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (item in channel) {
                when (item) {
                    is WorkerItem.Fragment -> {
                        // #116: report THIS fragment's audio length at request
                        // start (never the backlog — see onFragmentTranscribing).
                        onFragmentTranscribing?.invoke(((item.durationMs + 999) / 1000).toInt())
                        try {
                            process(item.fragment)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // The worker must survive an unexpected failure —
                            // the fragment is recorded as failed instead.
                            failures.putIfAbsent(
                                item.fragment.index,
                                SttRequestRunner.SttRequestException(e.message ?: e.javaClass.simpleName)
                            )
                        }
                        pendingAudioMs.addAndGet(-item.durationMs)
                    }
                    WorkerItem.CheckIdle -> Unit
                }
                checkIdle()
            }
        }
    }

    /**
     * Waits until every committed fragment is processed (success or final
     * failure) and returns the ordered result. Must be called when no more
     * fragments will arrive (the preparer's encoder is stopped before the
     * tail is committed) — later offers belong to the NEXT session.
     */
    suspend fun drain(): Drained {
        start()
        if (closed) return snapshot()
        val signal = CompletableDeferred<Unit>()
        pendingDrain = signal
        channel.trySend(WorkerItem.CheckIdle)
        signal.await()
        return snapshot()
    }

    /**
     * Audio seconds still awaiting transcription (the BACKLOG — logs and
     * evidence only; never rendered as a duration on the stage line, #116).
     */
    fun pendingAudioSeconds(): Int = ((pendingAudioMs.get() + 999) / 1000).toInt()

    /**
     * Re-queues the finally-failed fragments for a fresh attempt (retry
     * after a parked failure, #116): they are still committed on disk, and a
     * transient outage may have recovered since. Cached successes are never
     * re-transcribed. Called at the start of a transcription run.
     */
    fun resetFailures() {
        val failed = failures.keys.toList()
        failures.clear()
        // #183: a new run may carry a NEW token (Settings was open in
        // between), so the AUTH latch must not survive a Send tap.
        authBlocked = false
        failed.forEach { index -> offered[index]?.let { offer(it) } }
    }

    /**
     * Stops the worker and forgets everything — in-flight STT requests are
     * abandoned (they belong to a discarded session), transcripts dropped.
     *
     * #113 amendment: returns the accumulated transcripts (a copy) instead
     * of silently voiding them, so a cancel with paid-for work can salvage
     * the text (see [VoiceSessionController.cancel]); `null` when nothing
     * was transcribed yet. Same for [Drained] shape as [snapshot] but
     * WITHOUT the drain wait — in-flight fragments are simply not counted.
     */
    fun cancel(): Drained? {
        closed = true
        channel.close()
        worker?.cancel()
        worker = null
        val drained = if (transcripts.isEmpty()) null else snapshot()
        transcripts.clear()
        failures.clear()
        offered.clear()
        queued.clear()
        seamEvidenceRecords.clear()
        pendingAudioMs.set(0)
        promptDisabled = false
        authBlocked = false
        pendingDrain?.cancel()
        pendingDrain = null
        return drained
    }

    private suspend fun process(fragment: CommittedFragment) {
        queued.remove(fragment.index)
        if (transcripts.containsKey(fragment.index) || failures.containsKey(fragment.index)) return
        val config = configProvider() ?: run {
            failures[fragment.index] = SttRequestRunner.SttRequestException("STT provider not configured")
            return
        }
        // Prompt carry-over (#117): the previous fragment's transcript tail
        // conditions Whisper across the seam, so the fragment start is not
        // contextless (the main cold-start word-drop cause). Whisper does
        // NOT echo the prompt into the output — no join-side dedup needed.
        // Serial processing (C=1) in commit order guarantees the previous
        // fragment's transcript exists here (a re-queued failed fragment
        // after resetFailures may have none — then no prompt is sent).
        // #128: the prompt is gated on the SAME seam decision as the
        // pre-roll (`stitched`, made by the preparer under the active
        // SeamPolicy) — prompt and pre-roll can never disagree.
        val prompt = if (promptDisabled || !fragment.stitched) null
        else promptTail(transcripts[fragment.index - 1]?.text)
        // Acoustic pre-roll (#117 round 2): the upload is composed ONCE (the
        // echoed pre-roll is acoustic — it would appear on either attempt),
        // so BOTH attempts below upload the SAME file, which is deleted
        // afterwards. A 400 retry on the bare fragment would silently drop
        // the pre-roll and change the audio between attempts.
        val upload = composeUpload(fragment)
        // the size is captured BEFORE the finally-delete — by the time the
        // outcome fold runs, a composed upload is already gone (length() = 0)
        val uploadBytes = upload.length()
        // #182 write-ahead durability: the composed upload + a PENDING record
        // land on disk BEFORE the first attempt, so a kill mid-request still
        // leaves the audio (and, after a success, the text) recoverable.
        journalPending(fragment, upload, prompt)
        // #148: wall-clock around EACH attempt — the pair that survives is
        // the SUCCESSFUL attempt's (a 400-retry below resets both, so the
        // failed attempt's latency never contaminates the recorded one).
        // The bracket includes the runner's internal retry loop: it IS the
        // request duration as the app saw it.
        var sttSentAt: Long? = null
        var sttReceivedAt: Long? = null
        val result = try {
            if (authBlocked) {
                // #183: the token was already rejected in this session — no
                // request. The fragment still runs the normal failure fold
                // (journal + bundle + failure list), so its audio and the
                // reason survive exactly like a request that failed.
                Result.failure(
                    SttRequestRunner.SttRequestException(
                        AUTH_BLOCKED_MESSAGE,
                        httpCode = null,
                        failure = SttFailure.AUTH
                    )
                )
            } else {
                withContext(Dispatchers.IO) {
                    sttSentAt = System.currentTimeMillis()
                    sttRunner.run(
                        audioFile = upload,
                        config = config,
                        chunk = fragment.index + 1,
                        prompt = prompt,
                        // #184: the fragment's durable identity on the wire — the
                        // same pair a retry repeats, so a server can dedupe on
                        // session_id:fragment_index.
                        sessionId = sessionId,
                        fragmentIndex = fragment.index
                    )
                }.also { sttReceivedAt = System.currentTimeMillis() }.let { first ->
                    // A provider without prompt support rejects the field with 400:
                    // retry THIS fragment without it and skip prompts for the rest
                    // of the session (#117 graceful fallback).
                    if (first.isFailure && prompt != null &&
                        (first.exceptionOrNull() as? SttRequestRunner.SttRequestException)?.httpCode == 400
                    ) {
                        promptDisabled = true
                        withContext(Dispatchers.IO) {
                            sttSentAt = System.currentTimeMillis()
                            sttRunner.run(
                                audioFile = upload,
                                config = config,
                                chunk = fragment.index + 1,
                                // #184: the prompt-rejection retry is the SAME
                                // fragment — same identity, or a server would see
                                // two fragments.
                                sessionId = sessionId,
                                fragmentIndex = fragment.index
                            )
                        }.also { sttReceivedAt = System.currentTimeMillis() }
                    } else first
                }
            }
        } finally {
            // the composed upload is single-use (the bare fragment file is
            // owned by the preparer and must survive). #127: the evidence
            // copy runs BEFORE the delete so a post-mortem sees exactly
            // what the server saw; a cancelled worker skips the copy.
            // #142 FIX: the copy runs for BARE uploads too — before, an
            // upload without a pre-roll (WAV mode, compose failure) was
            // never retained at all (the copy was skipped when the upload
            // IS the fragment file), losing the only audio record.
            try {
                retainUploadEvidence(upload)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // best-effort by design — never fail a fragment over evidence
            }
            if (upload != fragment.file) upload.delete()
        }
        result.fold(
            onSuccess = { response ->
                // Trim BEFORE storing: the trimmed text feeds the next
                // fragment's prompt AND its known tail — an untrimmed echo
                // would re-condition the decoder on the duplicated words and
                // compound per seam.
                val trim = trimEcho(fragment, response)
                // #123 loop guard: a FORCED cut is the only place the decoder
                // is fed a split word (pre-roll + prompt), so it is the only
                // place a repetition loop can be seeded AND carried into the
                // next prompt. Collapsing here — before the store — keeps the
                // loop out of the prompt tail and out of the joined text.
                val stored = if (fragment.seamForced) {
                    trim.response.copy(text = LoopGuard.collapse(trim.response.text))
                } else {
                    trim.response
                }
                val trimmed = trim.copy(response = stored)
                // #184: a duplicated upload must never duplicate its text.
                // The index-keyed offer gate is the primary guarantee; this
                // putIfAbsent is the second line, so a second success reaching
                // the store by any future path is DETECTED and logged instead
                // of silently doubling the joined text.
                if (transcripts.putIfAbsent(fragment.index, stored) != null) {
                    CoreLog.w(TAG, "duplicate success for fragment ${fragment.index} dropped")
                    return@fold
                }
                recordSeamEvidence(fragment, trimmed, prompt)
                // #182: the transcript is on disk before the session can end.
                journalOutcome(fragment.index, sttRaw = response.text, sttTrimmed = stored.text, error = null)
                // #142: the forensic record — what was REALLY uploaded, the
                // untrimmed response, the trim outcome and the prompt sent.
                bundle?.let { b ->
                    val f = b.fragment(fragment.index)
                    f.uploadFile = upload.name
                    f.uploadBytes = uploadBytes
                    f.preRollMs = fragment.preRoll?.takeIf { it.isFile }
                        ?.let { AudioDuration.estimateMs(it) }
                    f.sttRaw = response.text
                    f.sttTrimmed = stored.text
                    f.echoTokens = trim.echoTokens
                    f.prompt = prompt
                    f.sttSentAt = sttSentAt
                    f.sttReceivedAt = sttReceivedAt
                    f.sttError = null
                }
            },
            onFailure = { e ->
                val ex = e as? SttRequestRunner.SttRequestException
                    ?: SttRequestRunner.SttRequestException(e.message ?: e.javaClass.simpleName)
                failures[fragment.index] = ex
                // #183: a rejected token stops THIS session's requests — the
                // remaining fragments are recorded without one (see process).
                if (ex.failure == SttFailure.AUTH) {
                    if (!authBlocked) CoreLog.w(TAG, "auth rejected (HTTP ${ex.httpCode}) — pausing STT for this session")
                    authBlocked = true
                }
                // #182: KEEP the fragment — the journal stays PENDING so a
                // later retry (or #185's re-run) can pick it up; #183 records
                // WHY it failed and which failures go to the manual path.
                journalOutcome(
                    fragment.index,
                    sttRaw = null,
                    sttTrimmed = null,
                    error = ex.message,
                    failure = ex.failure,
                    httpCode = ex.httpCode
                )
                bundle?.let { b ->
                    val f = b.fragment(fragment.index)
                    f.uploadFile = upload.name
                    f.uploadBytes = uploadBytes
                    f.preRollMs = fragment.preRoll?.takeIf { it.isFile }
                        ?.let { AudioDuration.estimateMs(it) }
                    f.sttError = ex.message
                }
            }
        )
    }

    /**
     * The upload file for a fragment (#117 round 2): with a pre-roll
     * available, the pre-roll and the fragment are combined into
     * `upload_%06d.<ext>` in the session dir, handing the decoder ~1 s of
     * acoustic seam context. Without a pre-roll (fragment 0, failed pre-roll
     * encode, process death) the bare fragment uploads unchanged.
     * Composition failures degrade to the bare fragment too — an upload must
     * never be lost over a missing pre-roll.
     *
     * OGG is byte-concatenated (page chaining is a valid stream); WAV is
     * REBUILT into one canonical header (#119) — a byte-concat would decouple
     * the payload from the first file's declared `data` size and make a
     * decoder read only the pre-roll.
     */
    private fun composeUpload(fragment: CommittedFragment): File {
        val preRoll = fragment.preRoll?.takeIf { it.isFile } ?: return fragment.file
        val ext = fragment.file.name.substringAfterLast('.')
        val upload = File(fragment.file.parentFile, "upload_%06d.$ext".format(fragment.index))
        return try {
            if (ext.equals("wav", ignoreCase = true)) {
                WavAssembler.concat(listOf(preRoll, fragment.file), upload)
            } else {
                FileOutputStream(upload).use { out ->
                    preRoll.inputStream().use { it.copyTo(out, 64 * 1024) }
                    fragment.file.inputStream().use { it.copyTo(out, 64 * 1024) }
                }
            }
            upload
        } catch (_: Throwable) {
            upload.delete()
            fragment.file
        }
    }

    /**
     * #182 write-ahead: persists the fragment's composed upload plus a
     * PENDING record BEFORE the first upload attempt. Best-effort — a journal
     * failure is logged and the fragment proceeds, because durability
     * bookkeeping must never cost a dictation. The IO dispatcher is REQUIRED:
     * the worker coroutine is Main-dispatched and a ~10 MB copy must not run
     * on it.
     */
    private suspend fun journalPending(fragment: CommittedFragment, upload: File, prompt: String?) {
        val journal = journal ?: return
        val sessionId = sessionId ?: return
        val record = FragmentJournal.FragmentRecord(
            index = fragment.index,
            createdAtMs = System.currentTimeMillis(),
            durationMs = AudioDuration.estimateMs(fragment.file),
            preRollMs = fragment.preRoll?.takeIf { it.isFile }?.let { AudioDuration.estimateMs(it) },
            stitched = fragment.stitched,
            seamForced = fragment.seamForced,
            prompt = prompt
        )
        val written = withContext(Dispatchers.IO) { journal.writePending(sessionId, record, upload) }
        if (!written) CoreLog.w(TAG, "journal: write-ahead failed (index=${fragment.index})")
    }

    /**
     * #182 outcome: flips the fragment's record — DONE with its transcript on
     * success, still PENDING (with the error) on failure. Same best-effort
     * rule as [journalPending]. #183 adds the failure class: an AUTH/TRANSIENT
     * failure stays PENDING (the audio is retryable), a PROTOCOL failure is
     * marked NEEDS_MANUAL (a plain retry would break the same way).
     */
    private suspend fun journalOutcome(
        index: Int,
        sttRaw: String?,
        sttTrimmed: String?,
        error: String?,
        failure: SttFailure? = null,
        httpCode: Int? = null
    ) {
        val journal = journal ?: return
        val sessionId = sessionId ?: return
        val written = withContext(Dispatchers.IO) {
            if (error == null) journal.markDone(sessionId, index, sttRaw, sttTrimmed)
            else journal.markFailed(
                sessionId = sessionId,
                index = index,
                error = error,
                needsManual = failure == SttFailure.PROTOCOL,
                failureClass = failure?.name,
                httpCode = httpCode
            )
        }
        if (!written) CoreLog.w(TAG, "journal: outcome write failed (index=$index)")
    }

    /**
     * #127: copies the composed upload into the session's evidence dir
     * (`uploads/<name>`) before its deletion. The session id is the upload's
     * parent dir name — the fragment session dir (`cacheDir/fragments/<sid>`)
     * is where [composeUpload] writes, so no extra identity is threaded
     * through. The IO dispatcher is REQUIRED: the worker coroutine is
     * Main-dispatched and a ~10 MB WAV copy must not run on it.
     */
    private suspend fun retainUploadEvidence(upload: File) {
        val store = audioEvidenceStore ?: return
        if (!audioEvidenceEnabled()) return
        val sessionId = upload.parentFile?.name ?: return
        val copied = withContext(Dispatchers.IO) {
            store.retainFile(sessionId, upload, "uploads/${upload.name}")
        }
        if (!copied) CoreLog.w(TAG, "audio evidence: upload copy failed (${upload.name})")
    }

    /**
     * Trims the echoed pre-roll from a fragment's transcript (#117 round 2).
     * The pre-roll is ACOUSTIC — its words re-appear at the transcript start
     * even when the text prompt is disabled, so the trim runs independent of
     * [promptDisabled] and only when the upload actually carried a pre-roll
     * (no pre-roll → no echo → trimming could only eat real repetitions).
     *
     * Matching is deliberately conservative against the previous fragment's
     * known tail: the head token must equal the tail token, at most 1
     * mismatch is tolerated for k ≥ 2, and NO match keeps everything —
     * duplication beats loss (the LLM polish can dedupe, lost words are
     * gone).
     */
    private fun trimEcho(fragment: CommittedFragment, response: SttResponse): TrimResult {
        val uploadWithPreRoll = fragment.preRoll?.isFile == true
        if (!uploadWithPreRoll) return TrimResult(response, 0, false)
        val knownTail = transcripts[fragment.index - 1]?.text
        val text = response.text
        if (knownTail.isNullOrBlank() || text.isBlank()) return TrimResult(response, 0, uploadWithPreRoll)
        // #185: the rule lives in EchoTrim — the recovery re-run calls the same
        // function against the journal's stored transcripts.
        val echo = EchoTrim.trim(text = text, knownTail = knownTail, hadPreRoll = uploadWithPreRoll)
        logger?.log(
            "stt-trim",
            gson.toJson(
                mapOf(
                    "fragment" to fragment.index,
                    "echoTokens" to echo.echoTokens,
                    "uploadWithPreRoll" to uploadWithPreRoll
                )
            )
        )
        if (echo.echoTokens == 0) return TrimResult(response, 0, uploadWithPreRoll)
        return TrimResult(response.copy(text = echo.text), echo.echoTokens, uploadWithPreRoll)
    }

    /** Echo-trim outcome of one fragment (#117 round 2 / #122 evidence). */
    private data class TrimResult(
        val response: SttResponse,
        val echoTokens: Int,
        val uploadWithPreRoll: Boolean
    )

    /** Per-fragment seam evidence, keyed by index; written as one JSON array (#122). */
    private val seamEvidenceRecords = ConcurrentSkipListMap<Int, String>()

    /**
     * Appends this fragment's seam evidence (#122) and rewrites the whole
     * `stt-fragment.json` array. The array form is deliberate: every other
     * evidence stream is a single record rotated 9 deep, which would keep only
     * the last 9 of ~40-80 fragments — useless for a seam study. Gated by
     * [seamEvidence] so production pays nothing.
     */
    private fun recordSeamEvidence(fragment: CommittedFragment, trim: TrimResult, prompt: String?) {
        if (!seamEvidence) return
        val text = trim.response.text
        val previous = transcripts[fragment.index - 1]?.text
        val durationMs = AudioDuration.estimateMs(fragment.file)
        val record = mapOf(
            "fragment" to fragment.index,
            "pcmBytes" to durationMs?.let { it * 32 }, // 16 kHz · 16 bit · mono
            "words" to SeamOverlap.wordCount(text),
            "chars" to text.length,
            "language" to trim.response.language,
            "languageProbability" to trim.response.languageProbability,
            "seamForced" to fragment.seamForced,
            "uploadWithPreRoll" to trim.uploadWithPreRoll,
            "promptChars" to (prompt?.length ?: 0),
            "prompt" to prompt,
            "echoTokens" to trim.echoTokens,
            "seamOverlapTokens" to SeamOverlap.longestTokenOverlap(previous, text),
            "seamOverlapText" to SeamOverlap.overlapText(previous, text),
            "text" to text
        )
        seamEvidenceRecords[fragment.index] = gson.toJson(record)
        logger?.log("stt-fragment", "[" + seamEvidenceRecords.values.joinToString(",\n") + "]")
    }

    /**
     * Seam-local prompt (#122): the last [promptMaxChars] characters of the
     * previous transcript, SNAPPED FORWARD to a word boundary so the prompt
     * never starts mid-word (the old `takeLast` cut at an arbitrary character
     * offset, despite the doc-comment claim). A short transcript passes
     * through unchanged; a single word longer than the window is returned
     * as-is (nothing to snap to).
     */
    private fun promptTail(text: String?): String? {
        if (promptMaxChars <= 0) return null // #122 study knob: prompt disabled
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (trimmed.length <= promptMaxChars) return trimmed
        val window = trimmed.takeLast(promptMaxChars)
        val firstBoundary = window.indexOfFirst { it.isWhitespace() }
        if (firstBoundary < 0) return window
        return window.substring(firstBoundary + 1).trimStart()
    }

    private fun checkIdle() {
        val signal = pendingDrain ?: return
        if (queued.isEmpty()) {
            pendingDrain = null
            signal.complete(Unit)
        }
    }

    private fun snapshot(): Drained {
        val lowestFailure = failures.firstEntry()
        val maxIndex = (transcripts.keys + failures.keys).maxOrNull()
        return Drained(
            text = transcripts.values.map { it.text }.filter { it.isNotBlank() }.joinToString(" ").trim(),
            language = transcripts.values.firstOrNull { !it.language.isNullOrBlank() }?.language,
            languageProbability = transcripts.values.firstOrNull { !it.language.isNullOrBlank() }?.languageProbability,
            chunkCount = (maxIndex ?: -1) + 1,
            chunkLengths = transcripts.values.map { it.text.length },
            failedIndex = lowestFailure?.key,
            failure = lowestFailure?.value
        )
    }
}
