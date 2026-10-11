package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #127: the audio-evidence ring buffer — copy semantics (never move, never
 * throw) and the prune policy (newest session always survives; oldest
 * deleted on write until the session/byte caps hold).
 */
class AudioEvidenceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    /** Injectable clock: distinct increasing stamps order the ring buffer. */
    private var fakeNow = 0L

    @Before
    fun setUp() {
        root = tmp.newFolder("audio")
        fakeNow = 0L
    }

    private fun newStore(
        maxSessions: Int = AudioEvidenceStore.DEFAULT_MAX_SESSIONS,
        maxTotalBytes: Long = AudioEvidenceStore.DEFAULT_MAX_TOTAL_BYTES
    ): AudioEvidenceStore = AudioEvidenceStore(root, maxSessions, maxTotalBytes, now = { fakeNow })

    /** Writes one session evidence file and advances the clock. */
    private fun retainSession(store: AudioEvidenceStore, sessionId: String, bytes: ByteArray) {
        fakeNow += 1000
        assertThat(store.retainBytes(sessionId, "session.wav", bytes)).isTrue()
    }

    @Test
    fun `retainBytes writes the file under the session dir`() {
        val store = newStore()
        store.retainBytes("s1", "session.wav", byteArrayOf(1, 2, 3))

        val written = File(File(root, "s1"), "session.wav")
        assertThat(written.isFile).isTrue()
        assertThat(written.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        // the tmp staging file never leaks into the evidence
        assertThat(File(File(root, "s1"), "session.wav.tmp").exists()).isFalse()
    }

    @Test
    fun `retainFile copies content under a nested relative path`() {
        val source = tmp.newFile("upload_000001.wav").apply { writeBytes(byteArrayOf(7, 8)) }
        val store = newStore()

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
    fun `retainDir copies the flat session dir contents`() {
        val sessionDir = tmp.newFolder("fragments_testsess").apply {
            File(this, "frag_000000.wav").writeBytes(byteArrayOf(1))
            File(this, "preroll_000001.wav").writeBytes(byteArrayOf(2))
            File(this, "manifest.json").writeText("""{"fragments":[]}""")
        }
        val store = newStore()

        val copied = store.retainDir("testsess", sessionDir, "fragments")

        assertThat(copied).isEqualTo(3)
        val evidence = File(root, "testsess/fragments")
        assertThat(File(evidence, "frag_000000.wav").readBytes()).isEqualTo(byteArrayOf(1))
        assertThat(File(evidence, "preroll_000001.wav").readBytes()).isEqualTo(byteArrayOf(2))
        assertThat(File(evidence, "manifest.json").readText()).isEqualTo("""{"fragments":[]}""")
        // the pipeline's own dir is untouched
        assertThat(File(sessionDir, "manifest.json").isFile).isTrue()
    }

    @Test
    fun `retainDir is zero for a missing dir`() {
        assertThat(newStore().retainDir("s1", File(tmp.root, "missing"), "fragments")).isEqualTo(0)
    }

    @Test
    fun `ring buffer keeps only the newest three sessions`() {
        val store = newStore(maxSessions = 3)
        retainSession(store, "s1", byteArrayOf(1))
        retainSession(store, "s2", byteArrayOf(2))
        retainSession(store, "s3", byteArrayOf(3))
        retainSession(store, "s4", byteArrayOf(4))

        assertThat(File(root, "s1").exists()).isFalse()
        assertThat(File(root, "s2").isDirectory).isTrue()
        assertThat(File(root, "s3").isDirectory).isTrue()
        assertThat(File(root, "s4").isDirectory).isTrue()
    }

    @Test
    fun `ring buffer prunes oldest sessions when the byte cap is exceeded`() {
        // three 10-byte sessions, cap 25 → the oldest must go on the 3rd write
        val store = newStore(maxSessions = 3, maxTotalBytes = 25)
        retainSession(store, "s1", ByteArray(10))
        retainSession(store, "s2", ByteArray(10))
        assertThat(File(root, "s1").isDirectory).isTrue()

        retainSession(store, "s3", ByteArray(10))

        assertThat(File(root, "s1").exists()).isFalse()
        assertThat(File(root, "s2").isDirectory).isTrue()
        assertThat(File(root, "s3").isDirectory).isTrue()
    }

    @Test
    fun `newest session survives even when it alone exceeds the byte cap`() {
        // a single session bigger than the whole cap must still be retained —
        // it is the one just written (a 30-min dictation is ~57 MB of PCM)
        val store = newStore(maxSessions = 3, maxTotalBytes = 25)
        retainSession(store, "big", ByteArray(100))

        assertThat(File(root, "big/session.wav").isFile).isTrue()

        // and a later session evicts the oversized OLD one, not itself
        retainSession(store, "small", ByteArray(10))
        assertThat(File(root, "big").exists()).isFalse()
        assertThat(File(root, "small/session.wav").isFile).isTrue()
    }

    @Test
    fun `re-retaining a session overwrites its files in place`() {
        val store = newStore()
        retainSession(store, "s1", byteArrayOf(1))
        retainSession(store, "s1", byteArrayOf(2, 2))

        val files = File(root, "s1").listFiles().orEmpty().map { it.name }
        assertThat(files).containsExactly("session.wav")
        assertThat(File(root, "s1/session.wav").readBytes()).isEqualTo(byteArrayOf(2, 2))
    }

    @Test
    fun `a failed copy leaves no partial session dir behind`() {
        val store = newStore()
        // a directory as the copy TARGET's write source: retainFile on a dir
        assertThat(store.retainFile("s1", tmp.newFolder("not_a_file"), "uploads/x")).isFalse()
        assertThat(File(root, "s1").listFiles().orEmpty()).isEmpty()
    }
}
