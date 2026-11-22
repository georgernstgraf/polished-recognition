package com.georgernstgraf.polishedrecognition.recovery

import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #188: the restore contract — store the text FIRST, delete the journal only
 * when the store really holds it.
 */
class RecoveryRestoreTest {

    /** Records what the restore did, in order. */
    private class FakeTarget(
        private val storedId: String? = "created-1"
    ) : RecoveryRestore.Target {
        val calls = mutableListOf<String>()

        override fun store(text: String): String? {
            calls += "store:$text"
            return storedId
        }

        override fun drop(sessionId: String) {
            calls += "drop:$sessionId"
        }
    }

    private fun record(index: Int, text: String?) =
        FragmentJournal.FragmentRecord(index = index, sttTrimmed = text)

    @Test
    fun `the stored fragments are joined in index order`() {
        val records = listOf(record(0, "erster Teil"), record(1, null), record(2, "dritter Teil"))

        assertThat(RecoveryRestore.textOf(records)).isEqualTo("erster Teil dritter Teil")
    }

    @Test
    fun `the store is written before the journal is dropped`() {
        val target = FakeTarget()

        val outcome = RecoveryRestore.restore(
            "20260101-1200-aaaaaaaa",
            listOf(record(0, "fertiger Text")),
            target
        )

        assertThat(outcome).isEqualTo(RecoveryRestore.Outcome.Restored("created-1"))
        assertThat(target.calls)
            .containsExactly("store:fertiger Text", "drop:20260101-1200-aaaaaaaa").inOrder()
    }

    @Test
    fun `a store that did not keep the text leaves the journal alone`() {
        val target = FakeTarget(storedId = null)

        val outcome = RecoveryRestore.restore(
            "20260101-1200-aaaaaaaa",
            listOf(record(0, "fertiger Text")),
            target
        )

        assertThat(outcome).isEqualTo(RecoveryRestore.Outcome.NotStored)
        assertThat(target.calls).containsExactly("store:fertiger Text")
    }

    @Test
    fun `a session without any stored text is not touched at all`() {
        val target = FakeTarget()

        val outcome = RecoveryRestore.restore(
            "20260101-1200-aaaaaaaa",
            listOf(record(0, null), record(1, "   ")),
            target
        )

        assertThat(outcome).isEqualTo(RecoveryRestore.Outcome.NoText)
        assertThat(target.calls).isEmpty()
    }
}
