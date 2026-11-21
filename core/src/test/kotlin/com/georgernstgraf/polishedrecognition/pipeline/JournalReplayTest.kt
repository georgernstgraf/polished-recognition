package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #185: the recovery re-run — only the missing fragments go out, byte-identical
 * and with the recorded prompt; stored text is spliced; the journal stays
 * resumable.
 */
class JournalReplayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var journal: FragmentJournal
    private lateinit var runner: SttRequestRunner
    private var clock = 1_700_000_000_000L

    private val session = "20260101-1200-aaaaaaaa"
    private val config = SttProviderConfig(
        displayName = "LAN",
        baseUrl = "http://10.8.0.16:11437/v1/",
        apiToken = "token",
        model = "large-v3"
    )

    private data class Call(
        val file: File,
        val prompt: String?,
        val sessionId: String?,
        val fragmentIndex: Int?
    )

    private val calls = mutableListOf<Call>()

    @Before
    fun setUp() {
        calls.clear()
        root = tmp.newFolder("journal")
        journal = FragmentJournal(root, now = { clock++ })
        runner = mockk()
    }

    private fun audio(name: String, bytes: Int = 96): File =
        tmp.newFile(name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    private fun pending(
        index: Int,
        prompt: String? = "letzte Worte",
        preRollMs: Long? = 1_000L,
        seamForced: Boolean = false,
        file: String = "upload_%06d.wav".format(index)
    ) {
        journal.writePending(
            session,
            FragmentJournal.FragmentRecord(
                index = index,
                durationMs = 7_000L,
                preRollMs = preRollMs,
                stitched = true,
                seamForced = seamForced,
                prompt = prompt
            ),
            audio(file)
        )
    }

    private fun replay(): JournalReplay = JournalReplay(journal, runner, { config })

    private fun answerWith(vararg texts: String) {
        coEvery { runner.run(any(), any(), any(), any(), any(), any(), any()) } answers {
            calls += Call(
                file = firstArg<File>(),
                prompt = arg<String?>(4),
                sessionId = arg<String?>(5),
                fragmentIndex = arg<Int?>(6)
            )
            Result.success(SttResponse(text = texts[calls.size - 1]))
        }
    }

    @Test
    fun `only the missing fragments are sent and the stored text is spliced`() = runBlocking {
        pending(0)
        pending(1)
        pending(2)
        journal.markDone(session, 0, "roh0", "erster")
        answerWith("zweiter", "dritter")

        val result = replay().replay(session).getOrThrow()

        assertThat(calls.map { it.fragmentIndex }).containsExactly(1, 2).inOrder()
        assertThat(result.text).isEqualTo("erster zweiter dritter")
        assertThat(result.fragmentCount).isEqualTo(3)
        assertThat(result.replayedCount).isEqualTo(2)
    }

    @Test
    fun `the replayed audio is the journal upload, byte for byte`() = runBlocking {
        pending(0, file = "upload_000000.wav")
        val original = File(journal.sessionDir(session), "upload_000000.wav").readBytes()
        answerWith("text")

        replay().replay(session).getOrThrow()

        assertThat(calls.single().file.name).isEqualTo("upload_000000.wav")
        assertThat(calls.single().file.readBytes()).isEqualTo(original)
    }

    @Test
    fun `the recorded prompt and the durable identity go out`() = runBlocking {
        pending(3, prompt = "Der Patient klagt")
        answerWith("und Fieber")

        replay().replay(session).getOrThrow()

        assertThat(calls.single().prompt).isEqualTo("Der Patient klagt")
        assertThat(calls.single().sessionId).isEqualTo(session)
        assertThat(calls.single().fragmentIndex).isEqualTo(3)
    }

    @Test
    fun `a successful re-run flips the record to DONE, so a second run sends nothing`() = runBlocking {
        pending(0)
        answerWith("text")

        replay().replay(session).getOrThrow()
        calls.clear()
        val second = replay().replay(session).getOrThrow()

        assertThat(journal.readRecord(session, 0)!!.state).isEqualTo(FragmentJournal.State.DONE.name)
        assertThat(calls).isEmpty()
        assertThat(second.replayedCount).isEqualTo(0)
        assertThat(second.text).isEqualTo("text")
    }

    @Test
    fun `the echoed pre-roll is trimmed against the previous stored transcript`() = runBlocking {
        pending(0, preRollMs = null)
        pending(1)
        journal.markDone(session, 0, "roh", "Der Patient klagt über Schmerzen")
        answerWith("Der Patient klagt über Schmerzen und Fieber")

        replay().replay(session).getOrThrow()

        val record = journal.readRecord(session, 1)!!
        assertThat(record.sttRaw).isEqualTo("Der Patient klagt über Schmerzen und Fieber")
        assertThat(record.sttTrimmed).isEqualTo("und Fieber")
    }

    @Test
    fun `a forced seam collapses a decoder loop before it is stored`() = runBlocking {
        val loop = "das ist ein test"
        pending(0, preRollMs = null)
        pending(1, seamForced = true)
        journal.markDone(session, 0, "roh", "vorher")
        answerWith("$loop $loop $loop $loop und dann noch was")

        replay().replay(session).getOrThrow()

        assertThat(journal.readRecord(session, 1)!!.sttTrimmed).isEqualTo("$loop und dann noch was")
    }

    @Test
    fun `a failure records its class and keeps the journal as it is`() = runBlocking {
        pending(0)
        pending(1)
        coEvery { runner.run(any(), any(), any(), any(), any(), any(), any()) } answers {
            calls += Call(firstArg<File>(), arg<String?>(4), arg<String?>(5), arg<Int?>(6))
            if (calls.size == 1) {
                Result.success(SttResponse(text = "eins"))
            } else {
                Result.failure(
                    SttRequestRunner.SttRequestException("HTTP 401", httpCode = 401, failure = SttFailure.AUTH)
                )
            }
        }

        val result = replay().replay(session)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("fragment 1")
        val failed = journal.readRecord(session, 1)!!
        assertThat(failed.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(failed.failureClass).isEqualTo(SttFailure.AUTH.name)
        assertThat(failed.failureHttpCode).isEqualTo(401)
        // the successful fragment before the failure stays DONE (resumable)
        assertThat(journal.readRecord(session, 0)!!.state).isEqualTo(FragmentJournal.State.DONE.name)
    }

    @Test
    fun `a protocol failure is marked NEEDS_MANUAL`() = runBlocking {
        pending(0)
        coEvery { runner.run(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(
                SttRequestRunner.SttRequestException(
                    "HTTP 200 without a transcript",
                    failure = SttFailure.PROTOCOL
                )
            )

        replay().replay(session)

        assertThat(journal.readRecord(session, 0)!!.state)
            .isEqualTo(FragmentJournal.State.NEEDS_MANUAL.name)
    }

    @Test
    fun `a provider rejecting the prompt is retried once without it`() = runBlocking {
        pending(0, prompt = "alter Text")
        coEvery { runner.run(any(), any(), any(), any(), any(), any(), any()) } answers {
            calls += Call(firstArg<File>(), arg<String?>(4), arg<String?>(5), arg<Int?>(6))
            if (calls.size == 1) {
                Result.failure(SttRequestRunner.SttRequestException("HTTP 400", httpCode = 400))
            } else {
                Result.success(SttResponse(text = "text"))
            }
        }

        replay().replay(session).getOrThrow()

        assertThat(calls.map { it.prompt }).containsExactly("alter Text", null).inOrder()
        assertThat(journal.readRecord(session, 0)!!.sttTrimmed).isEqualTo("text")
    }

    @Test
    fun `a missing audio file fails the run without a request`() = runBlocking {
        pending(0)
        File(journal.sessionDir(session), "upload_000000.wav").delete()

        val result = replay().replay(session)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("journal audio is missing")
        assertThat(calls).isEmpty()
    }

    @Test
    fun `an unconfigured provider fails before touching the journal`() = runBlocking {
        pending(0)

        val result = JournalReplay(journal, runner, { null }).replay(session)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("not configured")
        assertThat(calls).isEmpty()
    }

    @Test
    fun `progress is reported once per fragment`() = runBlocking {
        pending(0)
        pending(1)
        journal.markDone(session, 0, "roh", "eins")
        answerWith("zwei")
        val progress = mutableListOf<JournalReplay.Progress>()

        replay().replay(session) { progress += it }

        assertThat(progress.map { it.processed }).containsExactly(1, 2).inOrder()
        assertThat(progress.map { it.total }.distinct()).containsExactly(2)
        assertThat(progress.size).isEqualTo(2)
    }
}
