package com.georgernstgraf.polishedrecognition.testing

import java.util.concurrent.atomic.AtomicReference

/**
 * #198: makes an uncaught background exception a READABLE failure instead of
 * a dead test fork.
 *
 * `PolishedRecognitionApp.onCreate` installs a JVM-global
 * `Thread.setDefaultUncaughtExceptionHandler` that ends in
 * `Process.killProcess` + `System.exit(2)`. Robolectric creates the
 * Application inside the test JVM, so the app's handler is the test JVM's
 * handler: ONE throwable on any background thread (a capture thread writing
 * its spool, the fragment worker reading it) killed the whole fork with
 * `finished with non-zero exit value 2` — no failing test, no stack trace
 * (`#198`).
 *
 * A test class that lets a background thread of the audio pipeline run
 * installs this guard in `@Before` — AFTER `RuntimeEnvironment.getApplication()`,
 * so it replaces the app's handler rather than being chained behind it — and
 * calls [restoreAndFail] at the END of `@After`, after the take has been torn
 * down. The throwable is then attached to a real test failure.
 *
 * Never install without restoring: the JVM-global handler outlives the test.
 */
object CaptureThreadGuard {

    private class Captured(val threadName: String, val throwable: Throwable)

    private val captured = AtomicReference<Captured?>(null)
    private var previous: Thread.UncaughtExceptionHandler? = null
    private var installed = false

    /** The capturing handler — the app's `System.exit(2)` path is bypassed. */
    val handler = Thread.UncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
        captured.compareAndSet(null, Captured(thread.name, throwable))
    }

    /** Replaces the default handler; remembers the one to restore. */
    fun install() {
        check(!installed) { "CaptureThreadGuard is already installed" }
        previous = Thread.getDefaultUncaughtExceptionHandler()
        captured.set(null)
        Thread.setDefaultUncaughtExceptionHandler(handler)
        installed = true
    }

    /** Puts the previous (the app's) handler back. Idempotent. */
    fun restore() {
        if (!installed) return
        Thread.setDefaultUncaughtExceptionHandler(previous)
        previous = null
        installed = false
    }

    /**
     * Restores the app's handler and fails the test when a background thread
     * threw meanwhile — with the throwable as the failure's cause, so the
     * trace names the thread and the code, not "exit value 2".
     */
    fun restoreAndFail() {
        restore()
        val seen = captured.getAndSet(null) ?: return
        throw AssertionError(
            "uncaught exception on thread '${seen.threadName}': ${seen.throwable} — " +
                "on a device the app's crash handler would have killed the process " +
                "mid-dictation (#198)",
            seen.throwable
        )
    }
}
