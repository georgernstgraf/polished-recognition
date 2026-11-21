package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #182: the durable fragment journal — write-ahead ordering, atomic writes,
 * state transitions, and the size cap that must never sacrifice the newest
 * (or the live) session.
 */
class FragmentJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private var clock = 1_700_000_000_000L

    private val session = "20260101-1200-aaaaaaaa"
    private val older = "20251231-0900-11111111"
    private val newer = "20260102-0900-22222222"

    @Before
    fun setUp() {
        root = tmp.newFolder("journal")
    }

    private fun journal(capBytes: Long = FragmentJournal.DEFAULT_CAP_BYTES): FragmentJournal =
        FragmentJournal(root, capBytes = capBytes, now = { clock++ })

    private fun audio(name: String, bytes: Int = 64): File =
        tmp.newFile(name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    private fun record(index: Int, prompt: String? = "prev tail") = FragmentJournal.FragmentRecord(
        index = index,
        durationMs = 7_000L,
        preRollMs = 1_000L,
        stitched = true,
        seamForced = false,
        prompt = prompt
    )

    @Test
    fun `write-ahead lands the audio and a PENDING record before any request`() {
        val j = journal()

        assertThat(j.writePending(session, record(7), audio("upload_000007.wav", 1_000))).isTrue()

        val dir = j.sessionDir(session)
        assertThat(File(dir, FragmentJournal.SESSION_FILE).isFile).isTrue()
        assertThat(File(dir, FragmentJournal.audioName(7, "wav")).length()).isEqualTo(1_000L)
        val r = j.readRecord(session, 7)!!
        assertThat(r.index).isEqualTo(7)
        assertThat(r.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(r.uploadFile).isEqualTo(FragmentJournal.audioName(7, "wav"))
        assertThat(r.uploadBytes).isEqualTo(1_000L)
        assertThat(r.durationMs).isEqualTo(7_000L)
        assertThat(r.preRollMs).isEqualTo(1_000L)
        assertThat(r.stitched).isTrue()
        assertThat(r.seamForced).isFalse()
        assertThat(r.prompt).isEqualTo("prev tail")
        assertThat(r.sttTrimmed).isNull()
    }

    @Test
    fun `markDone stores the trimmed transcript and flips the state`() {
        val j = journal()
        j.writePending(session, record(1), audio("a.wav"))

        assertThat(j.markDone(session, 1, sttRaw = " äh hallo", sttTrimmed = "hallo")).isTrue()

        val r = j.readRecord(session, 1)!!
        assertThat(r.state).isEqualTo(FragmentJournal.State.DONE.name)
        assertThat(r.sttRaw).isEqualTo(" äh hallo")
        assertThat(r.sttTrimmed).isEqualTo("hallo")
        assertThat(r.sttError).isNull()
    }

    @Test
    fun `a final failure keeps the fragment PENDING with its error and its audio`() {
        val j = journal()
        j.writePending(session, record(4), audio("b.wav", 128))

        assertThat(j.markFailed(session, 4, "HTTP 429")).isTrue()

        val r = j.readRecord(session, 4)!!
        assertThat(r.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(r.sttError).isEqualTo("HTTP 429")
        assertThat(File(j.sessionDir(session), r.uploadFile!!).length()).isEqualTo(128L)
    }

    @Test
    fun `a protocol failure can be parked as NEEDS_MANUAL`() {
        val j = journal()
        j.writePending(session, record(4), audio("c.wav"))

        assertThat(j.markFailed(session, 4, "unexpected envelope", needsManual = true)).isTrue()

        assertThat(j.readRecord(session, 4)!!.state).isEqualTo(FragmentJournal.State.NEEDS_MANUAL.name)
    }

    @Test
    fun `the failure class and http code reach the record`() {
        val j = journal()
        j.writePending(session, record(5), audio("d.wav"))

        j.markFailed(session, 5, "HTTP 401", failureClass = "AUTH", httpCode = 401)

        val r = j.readRecord(session, 5)!!
        assertThat(r.failureClass).isEqualTo("AUTH")
        assertThat(r.failureHttpCode).isEqualTo(401)
        // still retryable: AUTH keeps the audio for the next run with a new token
        assertThat(r.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(File(j.sessionDir(session), r.uploadFile!!).isFile).isTrue()
    }

    @Test
    fun `a later success clears the recorded failure`() {
        val j = journal()
        j.writePending(session, record(6), audio("e.wav"))
        j.markFailed(session, 6, "HTTP 503", failureClass = "TRANSIENT", httpCode = 503)

        j.markDone(session, 6, "hallo", "hallo")

        val r = j.readRecord(session, 6)!!
        assertThat(r.state).isEqualTo(FragmentJournal.State.DONE.name)
        assertThat(r.failureClass).isNull()
        assertThat(r.failureHttpCode).isNull()
        assertThat(r.sttError).isNull()
    }

    @Test
    fun `the transcript is written even when the pending record was lost`() {
        val j = journal()
        // a lost journal write must never cost the text — the text is the
        // more valuable half and arrives after the fact
        j.sessionDir(session).mkdirs()

        assertThat(j.markDone(session, 3, sttRaw = "wort", sttTrimmed = "wort")).isTrue()

        val r = j.readRecord(session, 3)!!
        assertThat(r.state).isEqualTo(FragmentJournal.State.DONE.name)
        assertThat(r.sttTrimmed).isEqualTo("wort")
    }

    @Test
    fun `a re-written fragment replaces its own entry and drops stale audio of another format`() {
        val j = journal()
        j.writePending(session, record(1), audio("first.wav"))
        // WAV fallback after a failed transcode: same index, other extension
        j.writePending(session, record(1), audio("second.ogg"))

        val files = j.sessionDir(session).list()!!.sorted()
        assertThat(files).containsExactly(
            FragmentJournal.SESSION_FILE,
            FragmentJournal.recordName(1),
            FragmentJournal.audioName(1, "ogg")
        )
    }

    @Test
    fun `a crash-truncated temp file is not a record`() {
        val j = journal()
        val dir = j.sessionDir(session).apply { mkdirs() }
        File(dir, FragmentJournal.recordName(9) + ".tmp").writeText("{ truncated")

        assertThat(j.readRecord(session, 9)).isNull()
        assertThat(j.readRecord(session, 8)).isNull()
    }

    @Test
    fun `a blank session id or a missing audio file is rejected`() {
        val j = journal()

        assertThat(j.writePending(" ", record(0), audio("x.wav"))).isFalse()
        assertThat(j.writePending(session, record(0), File(tmp.root, "missing.wav"))).isFalse()
        assertThat(j.sessionDir(session).exists()).isFalse()
    }

    @Test
    fun `an unwritable root makes the journal report failure instead of throwing`() {
        val blocked = tmp.newFile("not-a-dir")
        val j = FragmentJournal(blocked)

        assertThat(j.writePending(session, record(0), audio("y.wav"))).isFalse()
        assertThat(j.markDone(session, 0, null, "text")).isFalse()
        assertThat(j.markFailed(session, 0, "boom")).isFalse()
        assertThat(j.prune()).isEqualTo(0)
    }

    @Test
    fun `prune deletes the oldest session first and stops at the cap`() {
        val cap = 1_000L
        val j = journal(capBytes = cap)
        listOf(older, session, newer).forEachIndexed { i, sid ->
            j.writePending(sid, record(0), audio("f$i.wav", 500))
        }

        assertThat(j.prune()).isAtLeast(1)

        assertThat(j.sessionDir(older).exists()).isFalse() // oldest goes first
        assertThat(j.sessionDir(newer).exists()).isTrue() // the newest never does
        assertThat(sizeOf(root)).isAtMost(cap)
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Test
    fun `prune never sacrifices the newest session, however tight the cap`() {
        val j = journal(capBytes = 1)
        j.writePending(older, record(0), audio("g0.wav", 200))
        j.writePending(newer, record(0), audio("g1.wav", 200))

        j.prune()

        assertThat(j.sessionDir(older).exists()).isFalse()
        assertThat(j.sessionDir(newer).exists()).isTrue()
    }

    @Test
    fun `prune keeps the live session even when it is the oldest`() {
        val j = journal(capBytes = 1)
        j.writePending(older, record(0), audio("h0.wav", 200))
        j.writePending(newer, record(0), audio("h1.wav", 200))

        j.prune(keepSessionId = older)

        assertThat(j.sessionDir(older).exists()).isTrue()
    }

    @Test
    fun `deleteSession removes the whole session and tolerates a missing one`() {
        val j = journal()
        j.writePending(session, record(0), audio("d.wav"))

        assertThat(j.deleteSession(session)).isTrue()
        assertThat(j.sessionDir(session).exists()).isFalse()
        assertThat(j.deleteSession(session)).isTrue()
        assertThat(j.deleteSession(" ")).isFalse()
    }

    // --- #185: session state, recovery scan, dismissal -----------------------

    @Test
    fun `a session with an unfinished fragment is recoverable with its reason`() {
        val j = journal()
        j.writePending(session, record(0), audio("r0.wav", 100))
        j.writePending(session, record(1), audio("r1.wav", 100))
        j.markDone(session, 0, "raw", "done text")
        j.markFailed(session, 1, "HTTP 429", failureClass = "TRANSIENT", httpCode = 429)

        val summary = j.recoverableSessions().single()

        assertThat(summary.sessionId).isEqualTo(session)
        assertThat(summary.completed).isFalse()
        assertThat(summary.fragmentCount).isEqualTo(2)
        assertThat(summary.doneCount).isEqualTo(1)
        assertThat(summary.pendingCount).isEqualTo(1)
        assertThat(summary.unfinishedCount).isEqualTo(1)
        assertThat(summary.durationMs).isEqualTo(14_000L)
        assertThat(summary.failureClass).isEqualTo("TRANSIENT")
        assertThat(summary.failureHttpCode).isEqualTo(429)
        assertThat(summary.startedAtMs).isNotNull()
        assertThat(summary.totalBytes).isGreaterThan(0L)
    }

    @Test
    fun `the reason comes from the lowest-index failing fragment`() {
        val j = journal()
        (0..2).forEach { j.writePending(session, record(it), audio("m$it.wav")) }
        j.markFailed(session, 2, "HTTP 401", failureClass = "AUTH", httpCode = 401)
        j.markFailed(session, 1, "HTTP 500", failureClass = "TRANSIENT", httpCode = 500)

        val summary = j.recoverableSessions().single()

        assertThat(summary.failureClass).isEqualTo("TRANSIENT")
        assertThat(summary.failureHttpCode).isEqualTo(500)
    }

    @Test
    fun `a session without a recorded failure is recoverable but carries no class`() {
        val j = journal()
        j.writePending(session, record(0), audio("i.wav"))

        val summary = j.recoverableSessions().single()

        assertThat(summary.failureClass).isNull()
        assertThat(summary.pendingCount).isEqualTo(1)
    }

    @Test
    fun `a completed session is recoverable and flagged as completed`() {
        val j = journal()
        j.writePending(session, record(0), audio("c.wav"))
        j.markDone(session, 0, "raw", "text")

        assertThat(j.markCompleted(session)).isTrue()

        val summary = j.recoverableSessions().single()
        assertThat(summary.completed).isTrue()
        assertThat(summary.unfinishedCount).isEqualTo(0)
    }

    @Test
    fun `markCompleted needs a session marker`() {
        val j = journal()
        assertThat(j.markCompleted("20990101-0000-deadbeef")).isFalse()
    }

    @Test
    fun `a session whose fragments are all done counts as completed even without the marker`() {
        val j = journal()
        j.writePending(session, record(0), audio("z0.wav"))
        j.writePending(session, record(1), audio("z1.wav"))
        j.markDone(session, 0, "r0", "eins")
        j.markDone(session, 1, "r1", "zwei")

        val summary = j.recoverableSessions().single()

        // nothing left to send — the UI must offer the text, not a re-run
        assertThat(summary.completed).isTrue()
        assertThat(summary.unfinishedCount).isEqualTo(0)
    }

    @Test
    fun `a dismissed session records the reason first and keeps only its tombstone`() {
        val j = journal()
        j.writePending(session, record(0), audio("x0.wav", 200))
        j.writePending(session, record(1), audio("x1.wav", 200))

        assertThat(j.markDismissed(session)).isTrue()

        val dir = j.sessionDir(session)
        assertThat(dir.isDirectory).isTrue()
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly(FragmentJournal.SESSION_FILE)
        val marker = j.readSession(session)!!
        assertThat(marker.dismissReason).isEqualTo(FragmentJournal.REASON_USER_DISCARDED)
        assertThat(marker.dismissedAtMs).isNotNull()
        // the audio is gone, so it is no longer a recovery candidate
        assertThat(j.recoverableSessions()).isEmpty()
    }

    @Test
    fun `a dismissed session without a marker is not recorded`() {
        val j = journal()
        assertThat(j.markDismissed("20990101-0000-feedface")).isFalse()
    }

    @Test
    fun `dismissal tombstones are capped by count, newest survive`() {
        val j = journal()
        val ids = (0..FragmentJournal.MAX_TOMBSTONES).map { i ->
            "202601%02d-1200-%08x".format(i % 28 + 1, i)
        }
        ids.forEach { id ->
            j.writePending(id, record(0), audio("t-$id.wav"))
            j.markDismissed(id)
        }

        j.prune()

        val remaining = root.listFiles()!!.filter { it.isDirectory }
        assertThat(remaining.size).isEqualTo(FragmentJournal.MAX_TOMBSTONES)
        // the oldest one went first
        assertThat(remaining.map { it.name }).doesNotContain(ids.first())
    }

    @Test
    fun `a session without any fragment record is debris and never listed`() {
        val j = journal()
        j.writePending(session, record(0), audio("debris.wav"))
        // simulate a crash between mkdirs + marker and the first record
        File(j.sessionDir(session), FragmentJournal.recordName(0)).delete()

        assertThat(j.recoverableSessions()).isEmpty()
    }

    @Test
    fun `readRecords returns the fragments in index order`() {
        val j = journal()
        listOf(3, 1, 2).forEach { j.writePending(session, record(it), audio("o$it.wav")) }

        assertThat(j.readRecords(session).map { it.index }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `recoverable sessions are listed newest first`() {
        val j = journal()
        j.writePending(older, record(0), audio("n0.wav"))
        j.writePending(newer, record(0), audio("n1.wav"))

        assertThat(j.recoverableSessions().map { it.sessionId })
            .containsExactly(newer, older).inOrder()
    }
}
