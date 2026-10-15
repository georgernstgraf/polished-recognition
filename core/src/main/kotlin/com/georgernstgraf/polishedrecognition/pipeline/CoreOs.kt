package com.georgernstgraf.polishedrecognition.pipeline

/**
 * #146: pluggable OS-thread hooks for :core — the same pattern as [CoreLog].
 * The shared JVM module must not import the platform's `Process` API, but its
 * worker threads keep the priorities the app has always set (e.g.
 * [FragmentPreparer]'s background-priority worker). The app installs the
 * platform Process backend once at startup (`PolishedRecognitionApp`
 * .onCreate, next to CoreLog); the analyzer CLI (Phase 2) stays on the
 * no-op default. Without a backend every call is a no-op.
 */
object CoreOs {

    /** Minimal OS-hook surface; mirrors the platform Process semantics. */
    interface Backend {
        fun setThreadPriority(priority: Int)
    }

    /**
     * The platform's `THREAD_PRIORITY_BACKGROUND` (the value the moved code
     * passed before the extraction; `:core` cannot read the platform
     * constant).
     */
    const val THREAD_PRIORITY_BACKGROUND = 10

    @Volatile
    private var backend: Backend? = null

    /** Called once at app startup (or by a CLI) — before workers spin up. */
    fun install(b: Backend?) {
        backend = b
    }

    fun setThreadPriority(priority: Int) {
        backend?.setThreadPriority(priority)
    }
}
