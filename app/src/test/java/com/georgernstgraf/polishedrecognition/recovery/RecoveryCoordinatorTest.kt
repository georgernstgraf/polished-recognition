package com.georgernstgraf.polishedrecognition.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.georgernstgraf.polishedrecognition.dictation.Dictation
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.georgernstgraf.polishedrecognition.pipeline.JournalReplay
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #185: the recovery run — what gets stored, what is deleted (and never the
 * other way round), which sessions are retried automatically, and the
 * once-per-process guard.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var journal: FragmentJournal
    private lateinit var replay: JournalReplay
    private lateinit var pipeline: TranscriptionPipeline
    private lateinit var controller: VoiceSessionController
    private lateinit var dictations: DictationStore
    private lateinit var coordinator: RecoveryCoordinator

    private val outcomes = mutableListOf<RecoveryCoordinator.Outcome>()
    private var clock = 1_700_000_000_000L
    private var activeSession: String? = null

    private val session = "20260101-1200-aaaaaaaa"
    private val other = "20260102-0900-bbbbbbbb"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, DictationStore.DIR_NAME).deleteRecursively()
        journal = FragmentJournal(tmp.newFolder("journal"), now = { clock++ })
        replay = mockk()
        pipeline = mockk()
        controller = mockk(relaxed = true)
        every { controller.state } returns VoiceSessionController.State.IDLE
        every { controller.activeSessionId() } answers { activeSession }
        dictations = DictationStore(context, fifoSize = { 10 })
        coordinator = RecoveryCoordinator(
            journal = journal,
            replay = replay,
            pipeline = pipeline,
            dictationStore = dictations,
            controller = { controller },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined
        )
        coordinator.onFinished = { outcomes += it }
    }

    private fun seed(id: String, failureClass: String? = null) {
        val file = tmp.newFile("$id.wav").apply { writeBytes(ByteArray(64)) }
        journal.writePending(
            id,
            FragmentJournal.FragmentRecord(index = 0, durationMs = 7_000L),
            file
        )
        failureClass?.let {
            journal.markFailed(id, 0, "HTTP 500", failureClass = it, httpCode = 500)
        }
    }

    private fun summaryOf(id: String) = journal.recoverableSessions().first { it.sessionId == id }

    private fun allDictations(): List<Dictation> = dictations.loadAll()

    @Test
    fun `a network failure is retried automatically, a dead token never`() {
        seed(session, failureClass = "TRANSIENT")
        seed(other, failureClass = "AUTH")

        assertThat(coordinator.shouldAutoRecover(summaryOf(session))).isTrue()
        assertThat(coordinator.shouldAutoRecover(summaryOf(other))).isFalse()
    }

    @Test
    fun `an interrupted session and a completed one are treated differently`() {
        seed(session)
        seed(other, failureClass = "TRANSIENT")
        journal.markCompleted(other)

        assertThat(coordinator.shouldAutoRecover(summaryOf(session))).isTrue()
        assertThat(coordinator.shouldAutoRecover(summaryOf(other))).isFalse()
    }

    @Test
    fun `the session the IME still owns is never recovered`() {
        seed(session, failureClass = "TRANSIENT")
        activeSession = session

        assertThat(coordinator.shouldAutoRecover(summaryOf(session))).isFalse()
    }

    @Test
    fun `an automatic attempt is made only once per process`() {
        seed(session, failureClass = "TRANSIENT")
        coEvery { replay.replay(session, any()) } returns
            Result.failure(IllegalStateException("offline"))
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns Result.success("x")

        assertThat(coordinator.startAuto(listOf(summaryOf(session)))).isTrue()
        assertThat(coordinator.shouldAutoRecover(summaryOf(session))).isFalse()
        assertThat(coordinator.startAuto(listOf(summaryOf(session)))).isFalse()
    }

    @Test
    fun `a successful run stores the polished text and deletes the journal session`() {
        seed(session, failureClass = "TRANSIENT")
        coEvery { replay.replay(session, any()) } returns
            Result.success(JournalReplay.ReplayResult("hallo welt", 2, 1))
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.success("Hallo Welt.")

        assertThat(coordinator.startManual(session)).isTrue()

        assertThat(allDictations().map { it.raw }).containsExactly("Hallo Welt.")
        assertThat(journal.sessionDir(session).exists()).isFalse()
        assertThat(outcomes.single().recovered).isEqualTo(1)
        assertThat(coordinator.isRunning()).isFalse()
    }

    @Test
    fun `a failed polish still stores the raw transcript`() {
        seed(session, failureClass = "TRANSIENT")
        coEvery { replay.replay(session, any()) } returns
            Result.success(JournalReplay.ReplayResult("hallo welt", 2, 1))
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(IllegalStateException("LLM post-processing failed: HTTP 500"))

        coordinator.startManual(session)

        assertThat(allDictations().map { it.raw }).containsExactly("hallo welt")
        assertThat(journal.sessionDir(session).exists()).isFalse()
    }

    @Test
    fun `a failed re-run keeps the journal and reports the reason`() {
        seed(session, failureClass = "TRANSIENT")
        coEvery { replay.replay(session, any()) } returns
            Result.failure(IllegalStateException("fragment 0: HTTP 500"))

        coordinator.startManual(session)

        assertThat(allDictations()).isEmpty()
        assertThat(journal.sessionDir(session).exists()).isTrue()
        assertThat(outcomes.single().failures.single().message).contains("HTTP 500")
    }

    @Test
    fun `a second run is refused while one is active`() {
        seed(session, failureClass = "TRANSIENT")
        val gate = CompletableDeferred<Unit>()
        coEvery { replay.replay(session, any()) } coAnswers {
            gate.await()
            Result.success(JournalReplay.ReplayResult("text", 1, 1))
        }
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns Result.success("Text")

        assertThat(coordinator.startManual(session)).isTrue()
        assertThat(coordinator.isRunning()).isTrue()
        assertThat(coordinator.startManual(session)).isFalse()

        gate.complete(Unit)
        assertThat(coordinator.isRunning()).isFalse()
        assertThat(allDictations()).hasSize(1)
    }
}
