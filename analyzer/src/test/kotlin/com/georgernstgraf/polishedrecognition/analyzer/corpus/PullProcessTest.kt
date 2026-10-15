package com.georgernstgraf.polishedrecognition.analyzer.corpus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for `PullCommand.run`'s process handling (2026-10-15, found
 * live on the A03 pull): the pre-fix code called `waitFor` BEFORE reading the
 * stream, so any output larger than the OS pipe buffer (~64 KB) deadlocked —
 * the child blocked on write, never exited, and the wait burned its whole
 * timeout. Small outputs (adb's `ls` listings) sailed through, which is why
 * the first pull only crashed on the 356-KB session.wav.
 */
class PullProcessTest {

    @Test
    fun `output larger than the pipe buffer does not deadlock`() {
        // ~267 KB of base64 — far beyond the ~64 KB pipe buffer. With the
        // pre-fix waitFor-before-read ordering this hits the (short) timeout
        // and throws; with the drain-first fix it returns the full output.
        val out = PullCommand.run(
            ProcessBuilder("sh", "-c", "head -c 200000 /dev/zero | base64"),
            timeoutSec = 10,
        )
        // base64 of 200000 zero bytes = ceil(200000/3)*4 = 266668 chars (+ newlines)
        assertTrue("expected > 200 KB of output, got ${out.size}", out.size > 200_000)
    }

    @Test
    fun `a hanging process hits the configured timeout and throws`() {
        try {
            PullCommand.run(
                ProcessBuilder("sh", "-c", "sleep 5 && echo never"),
                timeoutSec = 1,
            )
            throw AssertionError("expected CorpusException for the hanging process")
        } catch (e: CorpusException) {
            assertTrue("expected a timeout message, got: ${e.message}", e.message!!.contains("timed out"))
        }
    }

    @Test
    fun `exit codes still propagate on small outputs`() {
        val out = PullCommand.run(
            ProcessBuilder("sh", "-c", "echo hello"),
            timeoutSec = 10,
        )
        assertEquals("hello\n", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `nonzero exit still throws`() {
        try {
            PullCommand.run(
                ProcessBuilder("sh", "-c", "echo boom >&2; exit 3"),
                timeoutSec = 10,
            )
            throw AssertionError("expected CorpusException for exit code 3")
        } catch (e: CorpusException) {
            assertTrue("expected the exit-code message, got: ${e.message}", e.message!!.contains("failed (3)"))
        }
    }
}
