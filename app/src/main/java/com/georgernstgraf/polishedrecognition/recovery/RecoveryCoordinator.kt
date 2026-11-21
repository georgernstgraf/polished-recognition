package com.georgernstgraf.polishedrecognition.recovery

import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.georgernstgraf.polishedrecognition.pipeline.JournalReplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #185: the owner of every recovery run — the automatic cleanup when the
 * Workbench is entered and the manual re-run of a single row.
 *
 * ## One run at a time, one attempt per session and process
 *
 * A recovery run is a sequence of network requests; two of them (or one
 * running next to the live dictation) would compete for the same provider.
 * [startAuto]/[startManual] therefore refuse to start while a run is active,
 * and [autoAttempted] remembers the sessions the AUTO path already tried in
 * this process — entering the Workbench again (it is the launcher) must not
 * re-upload a session that just failed. A manual re-run is always allowed.
 *
 * ## Where the result goes
 *
 * The re-run's text takes the same road as a live dictation: the STT text is
 * polished through [TranscriptionPipeline.finishTranscription] with the
 * CURRENT settings (raw mode / LLM / line wrap), appended to the Workbench's
 * [DictationStore], and only then is the journal session deleted. If the
 * polish fails, the RAW text is stored — the transcript is the valuable half,
 * and the journal must never be deleted before the text is safe.
 *
 * ## Yielding to a live dictation
 *
 * [JournalReplay.beforeFragment] is wired by the app to wait while the
 * controller is recording or processing, so a recovery pauses at a fragment
 * boundary and resumes when the live path is done.
 */
class RecoveryCoordinator(
    private val journal: FragmentJournal,
    private val replay: JournalReplay,
    private val pipeline: TranscriptionPipeline,
    private val dictationStore: DictationStore,
    private val controller: () -> VoiceSessionController,
    private val scope: CoroutineScope,
    /** Seams for the tests: production uses the real dispatchers. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    

    /** What one run did — the UI turns this into a Toast. */
    data class Outcome(
        val recovered: Int,
        val failures: List<Failure>,
        val skipped: Int
    ) {
        val attempted: Int get() = recovered + failures.size
        val stillPending: Int get() = attempted - recovered
    }

    data class Failure(val sessionId: String, val message: String)

    private sealed interface Single {
        data class Recovered(val sessionId: String) : Single
        data class Failed(val sessionId: String, val message: String) : Single
        object Skipped : Single
    }

    private val running = AtomicBoolean(false)

    /**
     * Sessions the AUTO path already attempted in THIS process. Deliberately
     * not persisted: a restart is a new chance (the provider may be back), a
     * second entry into the Workbench is not.
     */
    private val autoAttempted: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Called on the main thread after a run; the visible screen decides to Toast. */
    @Volatile
    var onFinished: ((Outcome) -> Unit)? = null

    fun isRunning(): Boolean = running.get()

    /**
     * `true` when the Workbench may retry this session on its own: unfinished,
     * a retryable reason (#183 class), audio still present, not the session the
     * IME is currently holding, and not already attempted in this process.
     */
    fun shouldAutoRecover(summary: FragmentJournal.SessionSummary): Boolean {
        if (summary.completed) return false
        if (summary.unfinishedCount <= 0) return false
        val kind = RecoveryReason.of(false, summary.failureClass)
        if (!RecoveryReason.autoRetryable(kind)) return false
        if (summary.sessionId == controller().activeSessionId()) return false
        return !autoAttempted.contains(summary.sessionId)
    }

    /**
     * The Workbench-entered path. Returns `true` when a run was started;
     * `false` when there was nothing retryable or a run is already active.
     */
    fun startAuto(summaries: List<FragmentJournal.SessionSummary>): Boolean {
        if (running.get()) return false
        val ids = summaries
            .filter { shouldAutoRecover(it) }
            .map { it.sessionId }
        if (ids.isEmpty()) return false
        if (!start(ids)) return false
        // Only a run that actually STARTED counts as the one attempt — a
        // refused start (another run won the race) must stay retryable.
        autoAttempted.addAll(ids)
        return true
    }

    /** The explicit re-run of one row. */
    fun startManual(sessionId: String): Boolean =
        start(listOf(sessionId))

    private fun start(ids: List<String>): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch {
            val recovered = mutableListOf<String>()
            val failed = mutableListOf<Failure>()
            var skipped = 0
            try {
                ids.forEach { id ->
                    // The IME may have started a session for this journal id in
                    // the meantime — never replay a session the live path owns.
                    if (controller().activeSessionId() == id) {
                        skipped++
                        return@forEach
                    }
                    when (val single = recoverOne(id)) {
                        is Single.Recovered -> recovered += single.sessionId
                        is Single.Failed -> failed += Failure(single.sessionId, single.message)
                        Single.Skipped -> skipped++
                    }
                }
            } finally {
                running.set(false)
            }
            withContext(mainDispatcher) {
                onFinished?.invoke(Outcome(recovered.size, failed, skipped))
            }
        }
        return true
    }

    private suspend fun recoverOne(sessionId: String): Single = withContext(ioDispatcher) {
        val replayed = replay.replay(sessionId)
        val raw = replayed.getOrElse { error ->
            return@withContext Single.Failed(
                sessionId,
                error.message ?: error.javaClass.simpleName
            )
        }
        val polished = try {
            pipeline.finishTranscription(
                Result.success(
                    TranscriptionPipeline.SttResult(
                        text = raw.text,
                        chunkCount = raw.fragmentCount.coerceAtLeast(1),
                        chunkLengths = listOf(raw.text.length)
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
        // The raw STT text survives a failed polish — never lose the half that
        // cost the recording.
        val text = polished.getOrElse { raw.text }.trim()
        if (text.isBlank()) {
            return@withContext Single.Failed(sessionId, "empty transcript")
        }
        val created = dictationStore.append(text)
        if (dictationStore.loadAll().none { it.id == created.id }) {
            return@withContext Single.Failed(sessionId, "could not store the transcript")
        }
        // #185: the marker goes before the delete, exactly like the live
        // success path — if the delete fails, the row must not offer a second
        // re-run of a transcript that is already stored.
        journal.markCompleted(sessionId)
        journal.deleteSession(sessionId)
        Single.Recovered(sessionId)
    }
}
