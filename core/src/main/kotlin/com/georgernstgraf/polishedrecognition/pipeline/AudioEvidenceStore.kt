package com.georgernstgraf.polishedrecognition.pipeline

import java.io.File
import java.io.FileOutputStream

/**
 * Per-session audio-evidence retention (#127/#142): COPIES (never moves)
 * the forensic bundle — the composed `uploads/` audio (ground truth at the
 * provider) and the `session.json` manifest — into `<rootDir>/<sessionId>/`,
 * so seam/cut post-mortems run on ground truth instead of inference. The
 * root sits next to `logs/` in the external files dir: the only location
 * adb can read on release devices (`cacheDir` needs `run-as`, which is dead
 * on the owner's OnePlus).
 *
 * #142 — time-based eviction (replaces the #132 build-type ring buffer):
 * the retention horizon is a USER setting (the 0→50-years log slider),
 * supplied live via [retentionSeconds]. Every write (and every explicit
 * [evict]) deletes session dirs older than the horizon and enforces the
 * invisible STORAGE SAFETY VALVE: when the evidence root's free space
 * falls below [minFreeBytes], the OLDEST sessions are deleted until the
 * floor is restored — the NEWEST session always survives (it is the one
 * just written). "50 years" therefore means "effectively never by age",
 * with the valve as the last-resort guard the user never sees.
 *
 * Session age comes from the dir name (#132: `yyyyMMdd-HHmm-<8hex>` LOCAL
 * time) when parseable, from the dir mtime otherwise — legacy bare-UUID
 * dirs (pre-#132) are dated by their last-modified stamp.
 *
 * Pure JVM with an injectable dir + clock (mirrors [RotatingJsonLogger]) so
 * the eviction semantics are fully unit-testable. Best-effort by design:
 * every method swallows its errors and reports success — evidence retention
 * must never fail a dictation session (callers log the failure).
 */
class AudioEvidenceStore(
    private val rootDir: File,
    /**
     * The user's retention horizon in seconds. Default = "keep forever"
     * (the store's own contract) — the APP wires the live user setting;
     * a wired 0 means "nothing is retained" (the gate stops new writes and
     * evict() clears all stragglers).
     */
    private val retentionSeconds: () -> Long = { Long.MAX_VALUE / 1000 },
    /** The invisible safety valve: free space below this evicts oldest-first. */
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    /** Injectable clock (#127): stamps session recency; tests inject a counter. */
    private val now: () -> Long = System::currentTimeMillis
) {

    companion object {
        /**
         * #142 safety valve floor: when the volume holding the evidence has
         * less than this free, the oldest sessions are evicted. Chosen above
         * Android's own storage-warning thresholds so the evidence retention
         * never triggers the OS low-storage UX first.
         */
        const val DEFAULT_MIN_FREE_BYTES = 512L * 1024 * 1024

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
     * (the `session.json` manifest). `false` on failure; never throws.
     */
    @Synchronized
    fun retainBytes(sessionId: String, relativePath: String, bytes: ByteArray): Boolean =
        copyInto(sessionId, relativePath) { it.writeBytes(bytes) }

    /**
     * Time + space eviction over the whole evidence root: deletes session
     * dirs older than the retention horizon, then — while the free space
     * sits below the safety-valve floor — the oldest ones (the newest
     * always survives). Called after every write; cheap (one listFiles +
     * sort per call). Public so the app can also evict at startup.
     */
    fun evict() {
        val retention = retentionSeconds()
        val sessions = try {
            rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        } catch (_: Throwable) {
            return
        }
        if (sessions.isEmpty()) return
        val nowMs = now()
        val newest = sessions.maxByOrNull { sessionTime(it) ?: 0L }
        if (retention <= 0L) {
            // 0 = off: nothing is supposed to exist — clear ALL stragglers
            // (e.g. the horizon was just slid to 0; no "newest" exception —
            // the gate stops new writes, so a survivor would linger forever)
            for (dir in sessions) deleteQuietly(dir)
            return
        }
        for (dir in sessions) {
            val at = sessionTime(dir) ?: continue // no parseable age → space valve only
            if (nowMs - at > retention * 1000L) deleteQuietly(dir)
        }
        // Safety valve: oldest-first until the floor is restored — the newest
        // session is never sacrificed (it is the one just written; note the
        // AGE pass needs no such exception: a just-written session's name
        // prefix is the current time, never older than the horizon).
        var guard = 0
        while (freeSpace() < minFreeBytes && guard++ < 10_000) {
            val oldest = sessions
                .filter { it !== newest && it.isDirectory }
                .minByOrNull { sessionTime(it) ?: Long.MAX_VALUE }
                ?: break
            deleteQuietly(oldest)
        }
    }

    /** Best-effort single delete; a failure is retried by the next evict(). */
    private fun deleteQuietly(dir: File) {
        try {
            dir.deleteRecursively()
        } catch (_: Throwable) {
        }
    }

    private fun freeSpace(): Long = try {
        rootDir.usableSpace
    } catch (_: Throwable) {
        Long.MAX_VALUE // unknown free space → the valve never fires
    }

    /**
     * The session's epoch time: from the #132 name prefix
     * (`yyyyMMdd-HHmm-<8hex>`, LOCAL time) when parseable, else the dir
     * mtime. `null` for unparseable dirs (bare UUIDs) — those can only be
     * removed by the space valve.
     */
    private fun sessionTime(dir: File): Long? {
        val name = dir.name
        val m = Regex("^(\\d{8})-(\\d{4})-").find(name) ?: return try {
            dir.lastModified().takeIf { it > 0L }
        } catch (_: Throwable) {
            null
        }
        val (date, time) = m.destructured
        return try {
            val cal = java.util.Calendar.getInstance()
            cal.set(
                date.substring(0, 4).toInt(),
                date.substring(4, 6).toInt() - 1,
                date.substring(6, 8).toInt(),
                time.substring(0, 2).toInt(),
                time.substring(2, 4).toInt(),
                0
            )
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Writes one evidence file (tmp + rename, so a crashed copy can never
     * leave a HALF-written file that a post-mortem mistakes for ground
     * truth), then runs the eviction. Never throws.
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
            evict()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
