package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.AudioDuration
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
 * Serial by construction (C=1): gregor strictly serializes requests
 * (measured, #64), so a single in-flight request is optimal there, and
 * trivial ordering is worth more than parallelism anywhere else — Phase 2's
 * concurrency auto-detection may raise C later, but C=1 ships first.
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
 */
class FragmentTranscriber(
    private val sttRunner: SttRequestRunner,
    private val configProvider: () -> SttProviderConfig?,
    private val scope: CoroutineScope,
    private val onProgress: ((pendingAudioSeconds: Int) -> Unit)? = null,
    /** adb-readable trim evidence (`stt-trim.json`), null in unit tests. */
    private val logger: RotatingJsonLogger? = null
) {

    data class CommittedFragment(
        val index: Int,
        val file: File,
        /** Pre-roll side file (#117 round 2), null when absent. */
        val preRoll: File? = null
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
        /** Prompt carry-over budget (#117): ~200 Whisper tokens of text. */
        const val PROMPT_MAX_CHARS = 800

        /** Echo-trim match window (#117 round 2): at most 8 tokens of head. */
        const val MAX_ECHO_TOKENS = 8

        /** Word-ish token pattern for the echo match (#117 round 2). */
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
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
                        onProgress?.invoke(pendingAudioSeconds())
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
        if (channel.isClosedForSend) return snapshot()
        val signal = CompletableDeferred<Unit>()
        pendingDrain = signal
        channel.trySend(WorkerItem.CheckIdle)
        signal.await()
        return snapshot()
    }

    /** Audio seconds still awaiting transcription (drain-progress figure). */
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
        failed.forEach { index -> offered[index]?.let { offer(it) } }
    }

    /**
     * Stops the worker and forgets everything — in-flight STT requests are
     * abandoned (they belong to a discarded session), transcripts dropped.
     */
    fun cancel() {
        channel.close()
        worker?.cancel()
        worker = null
        transcripts.clear()
        failures.clear()
        offered.clear()
        queued.clear()
        pendingAudioMs.set(0)
        promptDisabled = false
        pendingDrain?.cancel()
        pendingDrain = null
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
        val prompt = if (promptDisabled) null else promptTail(transcripts[fragment.index - 1]?.text)
        // Acoustic pre-roll (#117 round 2): the upload is composed ONCE (the
        // echoed pre-roll is acoustic — it would appear on either attempt),
        // so BOTH attempts below upload the SAME file, which is deleted
        // afterwards. A 400 retry on the bare fragment would silently drop
        // the pre-roll and change the audio between attempts.
        val upload = composeUpload(fragment)
        val result = try {
            withContext(Dispatchers.IO) {
                sttRunner.run(
                    audioFile = upload,
                    config = config,
                    chunk = fragment.index + 1,
                    prompt = prompt
                )
            }.let { first ->
                // A provider without prompt support rejects the field with 400:
                // retry THIS fragment without it and skip prompts for the rest
                // of the session (#117 graceful fallback).
                if (first.isFailure && prompt != null &&
                    (first.exceptionOrNull() as? SttRequestRunner.SttRequestException)?.httpCode == 400
                ) {
                    promptDisabled = true
                    withContext(Dispatchers.IO) {
                        sttRunner.run(
                            audioFile = upload,
                            config = config,
                            chunk = fragment.index + 1
                        )
                    }
                } else first
            }
        } finally {
            // the composed upload is single-use (the bare fragment file is
            // owned by the preparer and must survive)
            if (upload != fragment.file) upload.delete()
        }
        result.fold(
            onSuccess = { response ->
                // Trim BEFORE storing: the trimmed text feeds the next
                // fragment's prompt AND its known tail — an untrimmed echo
                // would re-condition the decoder on the duplicated words and
                // compound per seam.
                transcripts[fragment.index] = trimEcho(fragment, response)
            },
            onFailure = { e ->
                val ex = e as? SttRequestRunner.SttRequestException
                    ?: SttRequestRunner.SttRequestException(e.message ?: e.javaClass.simpleName)
                failures[fragment.index] = ex
            }
        )
    }

    /**
     * The upload file for a fragment (#117 round 2): with a pre-roll
     * available, `preRoll + fragment` byte-concatenated into
     * `upload_%06d.<ext>` in the session dir — a chained OGG (or the
     * chunk-assembly WAV pattern) that hands the decoder ~1 s of acoustic
     * seam context. Without a pre-roll (fragment 0, failed pre-roll encode,
     * process death) the bare fragment uploads unchanged. Composition
     * failures degrade to the bare fragment too — an upload must never be
     * lost over a missing pre-roll.
     */
    private fun composeUpload(fragment: CommittedFragment): File {
        val preRoll = fragment.preRoll?.takeIf { it.isFile } ?: return fragment.file
        val ext = fragment.file.name.substringAfterLast('.')
        val upload = File(fragment.file.parentFile, "upload_%06d.$ext".format(fragment.index))
        return try {
            FileOutputStream(upload).use { out ->
                preRoll.inputStream().use { it.copyTo(out, 64 * 1024) }
                fragment.file.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
            upload
        } catch (_: Throwable) {
            upload.delete()
            fragment.file
        }
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
    private fun trimEcho(fragment: CommittedFragment, response: SttResponse): SttResponse {
        val uploadWithPreRoll = fragment.preRoll?.isFile == true
        if (!uploadWithPreRoll) return response
        val knownTail = transcripts[fragment.index - 1]?.text
        val text = response.text
        if (knownTail.isNullOrBlank() || text.isBlank()) return response
        val known = TOKEN_REGEX.findAll(knownTail).map { it.value.lowercase() }.toList()
        val fresh = TOKEN_REGEX.findAll(text)
            .map { it.value.lowercase() to it.range.first }
            .toList()
        var echoTokens = 0
        for (k in minOf(MAX_ECHO_TOKENS, fresh.size) downTo 1) {
            if (known.size < k) continue
            var mismatches = 0
            var matched = true
            for (j in 0 until k) {
                val freshToken = fresh[j].first
                val knownToken = known[known.size - k + j]
                if (freshToken == knownToken) continue
                if (j == 0) { matched = false; break } // head[0] == tail[0] required
                if (++mismatches > 1) { matched = false; break }
            }
            if (matched) {
                echoTokens = k
                break
            }
        }
        logger?.log(
            "stt-trim",
            gson.toJson(
                mapOf(
                    "fragment" to fragment.index,
                    "echoTokens" to echoTokens,
                    "uploadWithPreRoll" to uploadWithPreRoll
                )
            )
        )
        if (echoTokens == 0) return response
        // Drop the first echoTokens tokens; the text is rebuilt from the
        // NEXT token's offset so leading punctuation/whitespace goes too
        // (a full-echo fragment trims to empty).
        val trimmed = if (echoTokens >= fresh.size) "" else text.substring(fresh[echoTokens].second)
        return response.copy(text = trimmed)
    }

    /**
     * ~200 Whisper tokens ≈ 800 characters of transcript; the tail carries
     * the immediate acoustic/linguistic context of the seam. Leading
     * whitespace is dropped so the prompt starts on a word.
     */
    private fun promptTail(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text.trim().takeLast(PROMPT_MAX_CHARS)
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
