package com.georgernstgraf.polishedrecognition.dictation

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class DictationStoreTest {

    private lateinit var context: Context
    private var clock = 1_000L
    private var seq = 0

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, DictationStore.DIR_NAME).deleteRecursively()
        clock = 1_000L
        seq = 0
    }

    private fun store(cap: Int = 3) = DictationStore(
        context,
        fifoSize = { cap },
        now = { clock },
        newId = { "20261119-1200-${(++seq).toString().padStart(8, '0')}" }
    )

    private fun dir() = File(context.filesDir, DictationStore.DIR_NAME)

    @Test
    fun `append writes one file per dictation and loadAll returns oldest first`() {
        val store = store()
        store.append("first")
        clock += 1_000
        store.append("second")

        assertThat(store.loadAll().map { it.raw }).containsExactly("first", "second").inOrder()
        assertThat(dir().listFiles()!!.count { it.name.endsWith(".json") }).isEqualTo(2)
    }

    @Test
    fun `append prunes the fifo to the configured depth, oldest first`() {
        val store = store(cap = 3)
        repeat(5) {
            store.append("dictation-$it")
            clock += 1_000
        }

        assertThat(store.loadAll().map { it.raw })
            .containsExactly("dictation-2", "dictation-3", "dictation-4").inOrder()
        assertThat(dir().listFiles()!!.count { it.name.endsWith(".json") }).isEqualTo(3)
    }

    @Test
    fun `a temp file never survives a successful write`() {
        store().append("text")

        assertThat(dir().listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun `an unparseable file is skipped and the rest survive`() {
        val store = store()
        store.append("kept")
        File(dir(), "20261119-1201-deadbeef.json").writeText("{not json", Charsets.UTF_8)

        assertThat(store.loadAll().map { it.raw }).containsExactly("kept")
    }

    @Test
    fun `a second store instance sees the persisted dictations`() {
        store().append("survives")

        val reopened = store()
        assertThat(reopened.loadAll().map { it.raw }).containsExactly("survives")
    }

    @Test
    fun `updateRaw discards the polished transcript`() {
        val store = store()
        val dictation = store.append("raw")
        store.updatePolished(dictation.id, "polished")

        val updated = store.updateRaw(dictation.id, "raw, edited")

        assertThat(updated!!.raw).isEqualTo("raw, edited")
        assertThat(updated.polished).isNull()
        assertThat(store.loadAll().single().polished).isNull()
    }

    @Test
    fun `updatePolished keeps the raw text and overwrites the single slot`() {
        val store = store()
        val dictation = store.append("raw")

        store.updatePolished(dictation.id, "first polish")
        store.updatePolished(dictation.id, "second polish")

        val reloaded = store.loadAll().single()
        assertThat(reloaded.raw).isEqualTo("raw")
        assertThat(reloaded.polished).isEqualTo("second polish")
    }

    @Test
    fun `updating an unknown id is a no-op`() {
        val store = store()
        store.append("x")

        assertThat(store.updateRaw("missing", "y")).isNull()
        assertThat(store.loadAll().map { it.raw }).containsExactly("x")
    }

    @Test
    fun `pruneToSize keeps the newest dictations when the cap shrinks`() {
        val store = store(cap = 5)
        repeat(4) {
            store.append("d-$it")
            clock += 1_000
        }

        val shrunk = store(cap = 2)
        shrunk.pruneToSize()

        assertThat(shrunk.loadAll().map { it.raw }).containsExactly("d-2", "d-3").inOrder()
    }

    @Test
    fun `a cap below one still keeps the newest dictation`() {
        val store = store(cap = 0)
        store.append("a")
        clock += 1_000
        store.append("b")

        assertThat(store.loadAll().map { it.raw }).containsExactly("b")
    }

    @Test
    fun `clear removes every dictation`() {
        val store = store()
        store.append("a")
        store.append("b")

        store.clear()

        assertThat(store.loadAll()).isEmpty()
        assertThat(dir().listFiles()!!.filter { it.name.endsWith(".json") }).isEmpty()
    }

    @Test
    fun `a dictation id is local-time prefixed and path safe`() {
        val id = newDictationId(Date(1_700_000_000_000L), Random(7))

        val prefix = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(1_700_000_000_000L))
        assertThat(id).matches("$prefix-[0-9a-f]{8}")
        assertThat(id).doesNotContain("/")
    }
}
