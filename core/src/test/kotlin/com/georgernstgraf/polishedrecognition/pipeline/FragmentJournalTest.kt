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
}
