package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.AudioDuration
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Live fragment transcription (#116, the successor of #64's parallel-uploads
 * idea): consumes the fragments the [com.georgernstgraf.polishedrecognition
 * .audio.FragmentPreparer] commits DURING dictation and runs one STT request
 * per fragment through [SttRequestRunner] (retry ×3, transient-only), so at
 * stop-tap only the tail fragment remains. Stop→raw ≈ 1.1–1.3 s against the
 * measured gregor profile, <1 s against GROQ.
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
 */
class FragmentTranscriber(
    private val sttRunner: SttRequestRunner,
    private val configProvider: () -> SttProviderConfig?,
    private val scope: CoroutineScope,
    private val onProgress: ((pendingAudioSeconds: Int) -> Unit)? = null
) {

    data class CommittedFragment(val index: Int, val file: File)

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

    private val channel = Channel<WorkerItem>(Channel.UNLIMITED)
    private val transcripts = ConcurrentSkipListMap<Int, SttResponse>()
    private val failures = ConcurrentSkipListMap<Int, SttRequestRunner.SttRequestException>()

    /** Every fragment ever offered, by index — [resetFailures] re-queues from it. */
    private val offered = ConcurrentHashMap<Int, CommittedFragment>()

    /** Indices queued but not yet consumed — membership guard + drain check. */
    private val queued = ConcurrentHashMap.newKeySet<Int>()

    private val pendingAudioMs = AtomicLong()

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
        val result = withContext(Dispatchers.IO) {
            sttRunner.run(fragment.file, config, chunk = fragment.index + 1)
        }
        result.fold(
            onSuccess = { transcripts[fragment.index] = it },
            onFailure = { e ->
                val ex = e as? SttRequestRunner.SttRequestException
                    ?: SttRequestRunner.SttRequestException(e.message ?: e.javaClass.simpleName)
                failures[fragment.index] = ex
            }
        )
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
