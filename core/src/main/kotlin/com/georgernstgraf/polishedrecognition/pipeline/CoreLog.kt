package com.georgernstgraf.polishedrecognition.pipeline

/**
 * #146: pluggable logging for :core. The shared JVM module must not import
 * the platform `Log` class, but its classes (e.g. [FragmentTranscriber]) emit
 * the SAME log output the app has always produced — identical tags and
 * priority levels. The app installs the platform Log backend once at startup
 * (`PolishedRecognitionApp.onCreate`); the analyzer CLI (Phase 2) installs
 * whatever stdout backend it wants. Without a backend the calls are no-ops.
 */
object CoreLog {

    /** Minimal log surface; methods mirror the platform Log's semantics. */
    interface Backend {
        fun d(tag: String, message: String)
        fun i(tag: String, message: String)
        fun w(tag: String, message: String)
        fun e(tag: String, message: String, throwable: Throwable? = null)
    }

    @Volatile
    private var backend: Backend? = null

    /** Called once at app startup (or by a CLI) — before any core code logs. */
    fun install(b: Backend?) {
        backend = b
    }

    fun d(tag: String, message: String) {
        backend?.d(tag, message)
    }

    fun i(tag: String, message: String) {
        backend?.i(tag, message)
    }

    fun w(tag: String, message: String) {
        backend?.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        backend?.e(tag, message, throwable)
    }
}
