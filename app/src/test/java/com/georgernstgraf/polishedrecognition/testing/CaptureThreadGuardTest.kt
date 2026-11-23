package com.georgernstgraf.polishedrecognition.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * #198: the guard itself — an uncaught background throwable must become a
 * FAILING TEST with the throwable attached, never a dead fork, and the app's
 * handler must be back in place afterwards.
 */
class CaptureThreadGuardTest {

    @Test
    fun `a background throwable becomes a readable failure`() {
        val appHandler = Thread.getDefaultUncaughtExceptionHandler()
        CaptureThreadGuard.install()
        try {
            val thrown = Thread { throw IllegalStateException("boom-198") }.apply {
                name = "guard-test-thread"
                start()
                join()
            }
            assertThat(thrown.isAlive).isFalse()

            val failure = assertThrows(AssertionError::class.java) {
                CaptureThreadGuard.restoreAndFail()
            }
            assertThat(failure).hasMessageThat().contains("guard-test-thread")
            assertThat(failure).hasMessageThat().contains("boom-198")
            assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
            assertThat((failure.cause as IllegalStateException).message).isEqualTo("boom-198")
        } finally {
            CaptureThreadGuard.restore() // idempotent when restoreAndFail already ran
        }
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameInstanceAs(appHandler)
    }

    @Test
    fun `a clean test restores the app handler without failing`() {
        val appHandler = Thread.getDefaultUncaughtExceptionHandler()
        CaptureThreadGuard.install()

        CaptureThreadGuard.restoreAndFail()

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameInstanceAs(appHandler)
    }
}
