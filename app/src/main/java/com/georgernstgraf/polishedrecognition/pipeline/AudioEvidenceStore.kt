package com.georgernstgraf.polishedrecognition.pipeline

import java.io.File
import java.io.FileOutputStream

/**
 * Per-session audio-evidence retention (#127): COPIES (never moves) a
 * dictation session's raw material — the full session PCM, the fragment
 * session dir (fragments + pre-rolls + manifest) and the composed
 * `upload_<i>` files — into `<rootDir>/<sessionId>/`, so seam/cut
 * post-mortems run on ground truth instead of inference. The root sits
 * next to `logs/` in the external files dir: the only location adb can
 * read on release devices (`cacheDir` needs `run-as`, which is dead on
 * the owner's OnePlus).
 *
 * Ring buffer: every write touches the session dir's mtime (the injectable
 * [now] clock) and prunes — the NEWEST session always survives (it is the
 * one just written, even when it alone exceeds [maxTotalBytes]); older
 * sessions are deleted oldest-first until at most [maxSessions] remain and
 * their combined size is ≤ [maxTotalBytes]. A 5-min WAV dictation ≈ 10 MB
 * of PCM; fragments + composed uploads roughly triple that in WAV mode,
 * so the byte cap typically binds before the session cap.
 *
 * Pure JVM with an injectable dir + clock (mirrors [RotatingJsonLogger])
 * so the ring-buffer semantics are fully unit-testable. Best-effort by
 * design: every method swallows its errors and reports success — evidence
 * retention must never fail a dictation session (callers log the failure).
 */
class AudioEvidenceStore(
    private val rootDir: File,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    /** Injectable clock (#127): stamps session recency; tests inject a counter. */
    private val now: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** Keep the newest ~3 sessions (#127 issue body). */
        const val DEFAULT_MAX_SESSIONS = 3

        /** ≤ 50 MB total across retained sessions. */
        const val DEFAULT_MAX_TOTAL_BYTES = 50L * 1024 * 1024

        private const val COPY_BUFFER_BYTES = 64 * 1024
    }

    /** The evidence dir of [sessionId] (NOT created by this call). */
    fun sessionDir(sessionId: String): File = File(rootDir, sessionId)

    /**
     * Copies [source] into the session's evidence dir under [relativePath]
     * (e.g. `"uploads/upload_000001.wav"`). `false` when [source] is
     * missing or the copy failed; never throws.
     */
    @Synchronized
    fun retainFile(sessionId: String, source: File, relativePath: String): Boolean =
        if (!source.isFile) false
        else copyInto(sessionId, relativePath) { target ->
            source.inputStream().use { input ->
                FileOutputStream(target).use { input.copyTo(it, COPY_BUFFER_BYTES) }
            }
        }

    /**
     * Writes [bytes] into the session's evidence dir under [relativePath]
     * (the full-session PCM). `false` on failure; never throws.
     */
    @Synchronized
    fun retainBytes(sessionId: String, relativePath: String, bytes: ByteArray): Boolean =
        copyInto(sessionId, relativePath) { it.writeBytes(bytes) }

    /**
     * Copies every FILE of [sourceDir] (non-recursive — fragment session
     * dirs are flat: `frag_*`, `preroll_*`, `manifest.json`) under
     * [relativeDir]. Returns the number of files copied; 0 when the dir is
     * missing or empty. Per-file best-effort: one unreadable file does not
     * stop the rest.
     */
    @Synchronized
    fun retainDir(sessionId: String, sourceDir: File, relativeDir: String): Int {
        val files = try {
            sourceDir.listFiles()?.filter { it.isFile }.orEmpty()
        } catch (_: Throwable) {
            return 0
        }
        var copied = 0
        for (source in files) {
            try {
                if (copyInto(sessionId, "$relativeDir/${source.name}") { target ->
                        source.inputStream().use { input ->
                            FileOutputStream(target).use { input.copyTo(it, COPY_BUFFER_BYTES) }
                        }
                    }) copied++
            } catch (_: Throwable) {
                // best-effort per file — the next retention retries it
            }
        }
        return copied
    }

    /**
     * Writes one evidence file (tmp + rename, so a crashed copy can never
     * leave a HALF-written file that a post-mortem mistakes for ground
     * truth), then touches the session dir's mtime (ring-buffer recency)
     * and prunes. Never throws.
     */
    private fun copyInto(sessionId: String, relativePath: String, write: (File) -> Unit): Boolean {
        val session = sessionDir(sessionId)
        val target = File(session, relativePath)
        val parent = target.parentFile ?: return false
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) return false
            val tmp = File(parent, target.name + ".tmp")
            write(tmp)
            if (!tmp.renameTo(target)) {
                // rename failed (exotic FS / stale handle): rewrite in place
                target.delete()
                write(target)
                tmp.delete()
            }
            if (!target.isFile) return false
            session.setLastModified(now())
            prune()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Ring-buffer prune: the NEWEST session (by dir mtime) always
     * survives; older sessions are deleted oldest-first until at most
     * [maxSessions] remain and their combined size is ≤ [maxTotalBytes].
     * A failed delete is retried by the next prune.
     */
    private fun prune() {
        val sessions = try {
            rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        } catch (_: Throwable) {
            return
        }
        if (sessions.isEmpty()) return
        val ordered = sessions.sortedByDescending { it.lastModified() }
        val newest = ordered.first()
        val kept = mutableListOf(newest)
        var total = dirSize(newest)
        for (candidate in ordered.drop(1)) {
            val size = dirSize(candidate)
            if (kept.size < maxSessions && total + size <= maxTotalBytes) {
                kept.add(candidate)
                total += size
            } else {
                try {
                    candidate.deleteRecursively()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun dirSize(dir: File): Long = try {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) {
        0L
    }
}
