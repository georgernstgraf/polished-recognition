package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * #182: the shared session-dir clock (#132 name format) — moved out of
 * `AudioEvidenceStore` so the evidence store and the journal order their
 * session dirs by the same rule.
 */
class SessionDirTimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a session id name is read as LOCAL time`() {
        val dir = File(tmp.root, "20260101-1200-aaaaaaaa").apply { mkdirs() }
        val expected = LocalDateTime.of(2026, 1, 1, 12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        assertThat(SessionDirTime.of(dir)).isEqualTo(expected)
    }

    @Test
    fun `names order by their stamp`() {
        val first = File(tmp.root, "20251231-0900-11111111").apply { mkdirs() }
        val second = File(tmp.root, "20260102-0900-22222222").apply { mkdirs() }

        assertThat(SessionDirTime.of(first)!!).isLessThan(SessionDirTime.of(second)!!)
    }

    @Test
    fun `a legacy dir without a stamp falls back to its mtime`() {
        val dir = tmp.newFolder("5f0c2b1e-legacy-uuid")
        dir.setLastModified(1_600_000_000_000L)

        assertThat(SessionDirTime.of(dir)).isEqualTo(1_600_000_000_000L)
    }

    @Test
    fun `a dir without name or mtime has no age at all`() {
        assertThat(SessionDirTime.of(File(tmp.root, "does-not-exist"))).isNull()
    }
}
