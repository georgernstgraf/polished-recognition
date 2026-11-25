package com.georgernstgraf.polishedrecognition.ui

import com.georgernstgraf.polishedrecognition.dictation.Dictation
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #188: the ring's merge rule — stored dictations and unfinished journal
 * sessions as ONE list, ascending by the page's last change.
 */
class WorkbenchPageTest {

    private fun stored(id: String, updatedAtMs: Long) = Dictation(
        id = id,
        raw = "text of $id",
        createdAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs
    )

    private fun leftover(sessionId: String, startedAtMs: Long?, hasAudio: Boolean = true) =
        WorkbenchPage.Leftover(
            summary = FragmentJournal.SessionSummary(
                sessionId = sessionId,
                startedAtMs = startedAtMs,
                completed = false,
                fragmentCount = 1,
                doneCount = 0,
                pendingCount = 1,
                needsManualCount = 0,
                durationMs = 7_000L,
                totalBytes = 64L,
                failureClass = "AUTH",
                failureHttpCode = 401
            ),
            hasAudio = hasAudio
        )

    @Test
    fun `both kinds of page interleave by their change time`() {
        val merged = WorkbenchPages.merge(
            dictations = listOf(stored("a", 1_000L), stored("c", 3_000L)),
            leftovers = listOf(leftover("s-b", 2_000L))
        )

        assertThat(merged.map { WorkbenchPages.idOf(it) }).containsExactly("a", "s-b", "c").inOrder()
    }

    @Test
    fun `a leftover older than every dictation sits at the front`() {
        val merged = WorkbenchPages.merge(
            dictations = listOf(stored("a", 10_000L), stored("b", 20_000L)),
            leftovers = listOf(leftover("s-old", 1_000L))
        )

        assertThat(WorkbenchPages.idOf(merged.first())).isEqualTo("s-old")
        assertThat(WorkbenchPages.storedCount(merged)).isEqualTo(2)
    }

    @Test
    fun `a leftover without an instant sorts last`() {
        val merged = WorkbenchPages.merge(
            dictations = listOf(stored("a", 1_000L), stored("b", 2_000L)),
            leftovers = listOf(leftover("s-undated", null))
        )

        assertThat(WorkbenchPages.idOf(merged.last())).isEqualTo("s-undated")
        assertThat(WorkbenchPages.newestLeftoverIndex(merged)).isEqualTo(2)
    }

    @Test
    fun `the newest page can be a leftover`() {
        val merged = WorkbenchPages.merge(
            dictations = listOf(stored("a", 1_000L)),
            leftovers = listOf(leftover("s-new", 5_000L))
        )

        val newest = merged.last()
        assertThat(newest).isInstanceOf(WorkbenchPage.Leftover::class.java)
        assertThat(WorkbenchPages.idOf(newest)).isEqualTo("s-new")
        assertThat(WorkbenchPages.newestLeftoverIndex(merged)).isEqualTo(1)
    }

    @Test
    fun `equal timestamps keep a total order that does not depend on the input order`() {
        val first = WorkbenchPages.merge(
            dictations = listOf(stored("b", 5_000L), stored("a", 5_000L)),
            leftovers = listOf(leftover("s-m", 5_000L))
        )
        val second = WorkbenchPages.merge(
            dictations = listOf(stored("a", 5_000L), stored("b", 5_000L)),
            leftovers = listOf(leftover("s-m", 5_000L))
        )

        assertThat(first.map { WorkbenchPages.idOf(it) })
            .containsExactly("a", "b", "s-m").inOrder()
        assertThat(second.map { WorkbenchPages.idOf(it) })
            .isEqualTo(first.map { WorkbenchPages.idOf(it) })
    }

    @Test
    fun `an empty journal leaves the stored ring untouched`() {
        val dictations = listOf(stored("a", 1_000L), stored("b", 2_000L))

        val merged = WorkbenchPages.merge(dictations, emptyList())

        assertThat(merged.map { WorkbenchPages.idOf(it) }).containsExactly("a", "b").inOrder()
        assertThat(WorkbenchPages.storedCount(merged)).isEqualTo(2)
        assertThat(WorkbenchPages.newestLeftoverIndex(merged)).isNull()
    }
}
