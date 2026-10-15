package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #142: the audio-evidence TIME-based retention — copy semantics (never
 * move, never throw) plus the two eviction forces: the retention HORIZON
 * (the user's 0→50-years setting; 0 = off) and the invisible storage
 * safety valve (free space below the floor evicts oldest-first, the newest
 * session always survives). Session age comes from the #132 timestamped
 * dir-name prefix (`yyyyMMdd-HHmm-<8hex>`, LOCAL time), falling back to
 * the dir mtime.
 */
class AudioEvidenceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    /** Injectable clock: distinct increasing stamps order the retention. */
    private var fakeNow = 0L

    @Before
    fun setUp() {
        root = tmp.newFolder("audio")
        fakeNow = System.currentTimeMillis()
    }

    private fun newStore(
        retentionSeconds: Long = 0L,
        minFreeBytes: Long = 0L // 0 = the valve fires only below zero — never
    ): AudioEvidenceStore = AudioEvidenceStore(
        root,
        retentionSeconds = { retentionSeconds },
        minFreeBytes = minFreeBytes,
        now = { fakeNow }
    )

    /** Timestamped session id (the #132 format) [hoursBefore] hours before the clock. */
    private fun sessionId(hoursBefore: Long = 0L): String {
        val d = java.text.SimpleDateFormat("yyyyMMdd-HHmm")
            .format(java.util.Date(fakeNow - hoursBefore * 3_600_000L))
        return "%s-%04x".format(d, 0xABCD + hoursBefore)
    }

    /** Writes one session evidence file. */
    private fun retainSession(store: AudioEvidenceStore, sessionId: String, bytes: ByteArray) {
        assertThat(store.retainBytes(sessionId, "session.json", bytes)).isTrue()
    }

    @Test
    fun `retainBytes writes the file under the session dir`() {
        val store = newStore(retentionSeconds = 31_536_000)
        store.retainBytes("s1", "session.json", byteArrayOf(1, 2, 3))

        val written = File(File(root, "s1"), "session.json")
        assertThat(written.isFile).isTrue()
        assertThat(written.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        // the tmp staging file never leaks into the evidence
        assertThat(File(File(root, "s1"), "session.json.tmp").exists()).isFalse()
    }

    @Test
    fun `retainFile copies content under a nested relative path`() {
        val source = tmp.newFile("upload_000001.wav").apply { writeBytes(byteArrayOf(7, 8)) }
        val store = newStore(retentionSeconds = 31_536_000)

        assertThat(store.retainFile("s1", source, "uploads/upload_000001.wav")).isTrue()
        assertThat(File(File(root, "s1"), "uploads/upload_000001.wav").readBytes())
            .isEqualTo(byteArrayOf(7, 8))
        // COPY, never move: the source survives
        assertThat(source.isFile).isTrue()
    }

    @Test
    fun `retainFile returns false for a missing source`() {
        val store = newStore()
        assertThat(store.retainFile("s1", File(tmp.root, "nope.wav"), "uploads/nope.wav")).isFalse()
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `retention 0 clears every session (nothing is retained)`() {
        // the owner slid the slider to "No retention": stragglers from the
        // on-period are cleared entirely — no newest exception
        val store = newStore(retentionSeconds = 0)
        retainSession(store, sessionId(hoursBefore = 120), byteArrayOf(1))
        retainSession(store, sessionId(hoursBefore = 6), byteArrayOf(2))
        retainSession(store, sessionId(), byteArrayOf(3))

        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `sessions older than the horizon are evicted on write`() {
        val store = newStore(retentionSeconds = 86_400) // 1 day
        // three sessions: 120 h (5 d), 6 h and now — only the first
        // exceeds the 1-day horizon
        retainSession(store, sessionId(hoursBefore = 120), byteArrayOf(1))
        retainSession(store, sessionId(hoursBefore = 6), byteArrayOf(2))
        retainSession(store, sessionId(), byteArrayOf(3))

        assertThat(File(root, sessionId(hoursBefore = 120)).exists()).isFalse()
        assertThat(File(root, sessionId(hoursBefore = 6)).isDirectory).isTrue()
        assertThat(File(root, sessionId()).isDirectory).isTrue()
    }

    @Test
    fun `the horizon is read LIVE — a shorter horizon evicts on the next write`() {
        var retention = 31_536_000L // 1 year
        val store = AudioEvidenceStore(root, retentionSeconds = { retention }, now = { fakeNow })
        retainSession(store, sessionId(hoursBefore = 60 * 24), byteArrayOf(1))
        assertThat(File(root, sessionId(hoursBefore = 60 * 24)).isDirectory).isTrue()

        // the owner slides to 30 days
        retention = 2_592_000L
        retainSession(store, sessionId(), byteArrayOf(2))

        assertThat(File(root, sessionId(hoursBefore = 60 * 24)).exists()).isFalse()
    }

    @Test
    fun `space valve evicts oldest-first but never the newest session`() {
        val store = newStore(retentionSeconds = 31_536_000, minFreeBytes = Long.MAX_VALUE)
        // four sessions: the valve must leave ONLY the newest
        retainSession(store, sessionId(hoursBefore = 30 * 24), byteArrayOf(1))
        retainSession(store, sessionId(hoursBefore = 20 * 24), byteArrayOf(2))
        retainSession(store, sessionId(hoursBefore = 10 * 24), byteArrayOf(3))
        retainSession(store, sessionId(), byteArrayOf(4))

        val remaining = root.listFiles().orEmpty().map { it.name }
        assertThat(remaining).containsExactly(sessionId())
    }

    @Test
    fun `dirs without a timestamp prefix are dated by their mtime`() {
        // legacy bare-UUID dirs (pre-#132): the mtime is the age fallback
        val oldBare = File(root, "bare-uuid-dir").apply {
            mkdirs()
            File(this, "session.json").writeBytes(byteArrayOf(1))
            setLastModified(fakeNow - 10 * 86_400_000L)
        }
        val freshBare = File(root, "fresh-uuid-dir").apply { mkdirs() }
        freshBare.setLastModified(fakeNow)
        val store = newStore(retentionSeconds = 86_400) // 1 day

        store.evict()

        // the old bare dir exceeds the horizon (mtime fallback) → evicted
        assertThat(oldBare.exists()).isFalse()
        // the fresh one is within the horizon → survives
        assertThat(freshBare.isDirectory).isTrue()
    }

    @Test
    fun `re-retaining a session overwrites its files in place`() {
        val store = newStore(retentionSeconds = 31_536_000)
        retainSession(store, "s1", byteArrayOf(1))
        retainSession(store, "s1", byteArrayOf(2, 2))

        val files = File(root, "s1").listFiles().orEmpty().map { it.name }
        assertThat(files).containsExactly("session.json")
        assertThat(File(root, "s1/session.json").readBytes()).isEqualTo(byteArrayOf(2, 2))
    }

    @Test
    fun `a failed copy leaves no partial session dir behind`() {
        val store = newStore(retentionSeconds = 31_536_000)
        // a directory as the copy TARGET's write source: retainFile on a dir
        assertThat(store.retainFile("s1", tmp.newFolder("not_a_file"), "uploads/x")).isFalse()
        assertThat(File(root, "s1").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `evict() is callable directly (app start) without any write`() {
        val store = newStore(retentionSeconds = 86_400)
        val dir = File(root, sessionId(hoursBefore = 10 * 24)).apply { mkdirs() }
        File(dir, "session.json").writeBytes(byteArrayOf(1))

        store.evict()

        assertThat(dir.exists()).isFalse()
    }
}
