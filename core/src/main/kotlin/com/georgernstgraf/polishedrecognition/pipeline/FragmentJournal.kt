package com.georgernstgraf.polishedrecognition.pipeline

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/**
 * #182: the DURABLE fragment journal — a write-ahead record of every fragment
 * (audio + metadata + transcript) that is independent of `cacheDir`, of the
 * #127/#142 evidence retention and of the user's retention slider.
 *
 * ## Why it exists
 *
 * A dictation is unrecoverable work: the audio existed exactly once. The
 * capture path's fragment dir (`cacheDir/fragments/<sessionId>/`) is a CACHE
 * directory — the OS may clear it, and it does not survive an app kill plus a
 * later cleanup. The forensic `session.json` (#142) is gated by the retention
 * slider and is a post-mortem record, not a recovery contract. So without
 * this journal there is no guaranteed path from "the app died / the upload
 * failed" back to "the audio still exists" — PITFALLS: cacheDir fragments are
 * not durability.
 *
 * ## Layout
 *
 * ```
 * <rootDir>/<sessionId>/
 *   session.json          the session marker — written FIRST, validity marker
 *   fragment_0000.json    one record per fragment (state + metadata + text)
 *   upload_000000.wav     the EXACT bytes that went to the provider
 * ```
 *
 * The audio is the **composed upload** (pre-roll + fragment, #117), not the
 * bare fragment: the pre-roll is ~1 s per fragment (≈2 % overhead), and it
 * makes a retry byte-identical to the original attempt — the property #184's
 * idempotency claim rests on. It also frees a later re-run (#185) from
 * depending on cacheDir pre-rolls that may be gone.
 *
 * ## Contract
 *
 * - **Write-ahead:** [writePending] is called BEFORE the first upload attempt;
 *   the record is flipped by [markDone] only after the transcript is stored.
 * - **Ordering inside [writePending]:** the audio is written before the
 *   record. A crash between the two leaves an orphan audio file (harmless,
 *   visible on disk); the reverse order would leave a `PENDING` record whose
 *   audio is missing — a recovery path that promises a file it does not have.
 * - **Atomic:** every file is written to `<name>.tmp` and renamed over the
 *   target, so a crash can never leave a half-written record that a reader
 *   mistakes for truth. `.tmp` files are ignored by [prune].
 * - **Best-effort:** every method swallows its errors and reports success with
 *   a Boolean — exactly like [AudioEvidenceStore]. Journal retention must
 *   never fail a dictation; callers log a failure and carry on.
 * - **Bounded:** [prune] enforces a hard [capBytes] over the whole root,
 *   evicting the OLDEST sessions first and never the newest or the session
 *   named by `keepSessionId`. There is deliberately NO age rule: deleting
 *   unfinished work by age is exactly what the user needs time to act on, and
 *   #185 owns any *recorded* dismissal.
 *
 * Pure JVM with an injectable root + clock, so the durability semantics are
 * fully unit-testable without Android.
 */
class FragmentJournal(
    private val rootDir: File,
    /** Hard ceiling over the whole journal root. See [prune]. */
    private val capBytes: Long = DEFAULT_CAP_BYTES,
    /** Injectable clock: stamps session + record creation; tests use a counter. */
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Per-fragment durability state. `NEEDS_MANUAL` is set by #183 only. */
    enum class State { PENDING, DONE, NEEDS_MANUAL }

    /**
     * One fragment's journal record. Fields are nullable/omitted when unknown
     * (Gson drops nulls), so the record is also readable across app versions.
     *
     * `index` carries a default only so that Gson has a no-arg constructor to
     * call when reading a record back — the journal always sets it explicitly
     * (it is the record's key, mirrored in the file name).
     */
    class FragmentRecord(
        var index: Int = 0,
        var state: String = State.PENDING.name,
        var createdAtMs: Long? = null,
        /** Bare fragment duration (pre-roll excluded) — the dictation time. */
        var durationMs: Long? = null,
        var preRollMs: Long? = null,
        /** #128 seam decision that governed this fragment's pre-roll + prompt. */
        var stitched: Boolean? = null,
        /** #122: was the boundary a forced (hard) cut? */
        var seamForced: Boolean? = null,
        /** The Whisper conditioning prompt actually sent (null = none). */
        var prompt: String? = null,
        /** The audio file's name inside the session dir — what will be re-sent. */
        var uploadFile: String? = null,
        var uploadBytes: Long? = null,
        /** The untrimmed provider response (null until DONE). */
        var sttRaw: String? = null,
        /** The stored, echo-trimmed transcript — the durable text. */
        var sttTrimmed: String? = null,
        var sttError: String? = null,
        /**
         * #183: the failure class of the last attempt (`TRANSIENT`, `AUTH`,
         * `PROTOCOL`, `TERMINAL`), so the recovery UI (#185) can explain WHY
         * a fragment is pending instead of showing a bare HTTP code.
         */
        var failureClass: String? = null,
        /** #183: the HTTP status of that attempt, when there was one. */
        var failureHttpCode: Int? = null
    )

    /** The session-level marker (`session.json`). */
    class SessionRecord(
        val sessionId: String,
        var startedAt: Long? = null
    )

    companion object {
        /**
         * The journal's directory name under `filesDir`. Excluded from cloud
         * backup and device transfer in BOTH rule files — the journal holds
         * private audio and transcript text (`BackupRulesTest` pins this).
         */
        const val DIR_NAME = "journal"

        /** 256 MB over the whole root; the newest session always survives. */
        const val DEFAULT_CAP_BYTES = 256L * 1024 * 1024

        const val SESSION_FILE = "session.json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val TAG = "FragmentJournal"

        /** `fragment_0000.json` — the record of fragment [index]. */
        fun recordName(index: Int): String = "fragment_%04d.json".format(index)

        /** `upload_000000.wav` — the audio of fragment [index]. */
        fun audioName(index: Int, extension: String): String = "upload_%06d.%s".format(index, extension)
    }

    private val gson = Gson()

    /** The session's dir (NOT created by this call). */
    fun sessionDir(sessionId: String): File = File(rootDir, sessionId)

    /**
     * Write-ahead of one fragment: copies [audio] into the session dir and
     * writes the record in [State.PENDING]. Called BEFORE the first upload
     * attempt; re-entrant (a retried fragment overwrites its own entry).
     *
     * `false` when the session id is blank, the audio is missing, or any IO
     * failed — never throws.
     */
    @Synchronized
    fun writePending(sessionId: String, record: FragmentRecord, audio: File): Boolean {
        if (sessionId.isBlank() || !audio.isFile) return false
        return try {
            val dir = sessionDir(sessionId)
            if (!dir.isDirectory && !dir.mkdirs()) return false
            if (!ensureSessionLocked(sessionId)) return false
            // A re-run may change the format (WAV fallback after a failed
            // transcode): drop the stale audio of the SAME index first.
            readRecordLocked(sessionId, record.index)?.uploadFile
                ?.takeIf { it != audioName(record.index, audio.extension) }
                ?.let { File(dir, it).delete() }
            val name = audioName(record.index, audio.extension)
            record.uploadFile = name
            record.uploadBytes = audio.length()
            record.state = State.PENDING.name
            record.sttError = null
            if (record.createdAtMs == null) record.createdAtMs = now()
            // Audio first, record second — see the class KDoc.
            if (!copyAtomically(File(dir, name), audio)) return false
            writeJsonLocked(File(dir, recordName(record.index)), record)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Flips a fragment to [State.DONE] with its transcript. The transcript is
     * written even when the PENDING record went missing (a lost journal write
     * must never cost the text — the text is the more valuable half).
     *
     * `false` on failure; never throws.
     */
    @Synchronized
    fun markDone(sessionId: String, index: Int, sttRaw: String?, sttTrimmed: String?): Boolean =
        update(sessionId, index) { record ->
            record.state = State.DONE.name
            record.sttRaw = sttRaw
            record.sttTrimmed = sttTrimmed
            record.sttError = null
            // #183: a success clears the failure evidence of an earlier attempt.
            record.failureClass = null
            record.failureHttpCode = null
        }

    /**
     * Records a final failure (the runner's retries are exhausted) and KEEPS
     * the fragment: the state stays [State.PENDING] so a later retry picks it
     * up, unless [needsManual] marks it for the manual path (#183 protocol
     * failures). [failureClass]/[httpCode] travel into the record so #185 can
     * say what went wrong without re-running the request.
     */
    @Synchronized
    fun markFailed(
        sessionId: String,
        index: Int,
        error: String?,
        needsManual: Boolean = false,
        failureClass: String? = null,
        httpCode: Int? = null
    ): Boolean = update(sessionId, index) { record ->
        record.state = if (needsManual) State.NEEDS_MANUAL.name else State.PENDING.name
        record.sttError = error
        record.failureClass = failureClass
        record.failureHttpCode = httpCode
    }

    /**
     * Removes a session's journal dir — the audio is finished with (delivered,
     * cancelled or flushed). Then runs [prune], so a session that just ended
     * never leaves the journal above its cap. `false` on failure; never throws.
     */
    @Synchronized
    fun deleteSession(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        val removed = try {
            val dir = sessionDir(sessionId)
            !dir.exists() || dir.deleteRecursively()
        } catch (_: Throwable) {
            false
        }
        prune()
        return removed
    }

    /**
     * Size cap over the whole root: while the total exceeds [capBytes], delete
     * the OLDEST sessions (by the #132 name prefix, mtime fallback). The newest
     * session and [keepSessionId] are never sacrificed. Every eviction is
     * logged with the remaining size — a dropped recovery path is never
     * silent, and #185 turns this into a user-visible dismissal.
     *
     * Returns the number of deleted sessions; never throws.
     */
    @Synchronized
    fun prune(keepSessionId: String? = null): Int {
        val sessions = try {
            rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        } catch (_: Throwable) {
            return 0
        }
        if (sessions.isEmpty()) return 0
        var total = sessions.sumOf { sizeOf(it) }
        if (total <= capBytes) return 0
        val newest = sessions.maxByOrNull { SessionDirTime.of(it) ?: 0L }
        val candidates = sessions
            .filter { it.name != keepSessionId && it !== newest }
            .sortedBy { SessionDirTime.of(it) ?: Long.MAX_VALUE }
        var deleted = 0
        for (dir in candidates) {
            if (total <= capBytes) break
            val size = sizeOf(dir)
            if (deleteQuietly(dir)) {
                total -= size
                deleted++
            }
        }
        if (deleted > 0) {
            CoreLog.w(TAG, "prune: deleted $deleted session(s), $total bytes left (cap $capBytes)")
        }
        return deleted
    }

    /** Reads one record, or null when it does not exist / is unreadable. */
    @Synchronized
    fun readRecord(sessionId: String, index: Int): FragmentRecord? = readRecordLocked(sessionId, index)

    /**
     * The session marker. Written BEFORE any record, so a reader can tell a
     * real session from debris; the only thing a crash between `mkdirs` and
     * this write can leave behind is an empty, zero-byte dir.
     */
    private fun ensureSessionLocked(sessionId: String): Boolean {
        val marker = File(sessionDir(sessionId), SESSION_FILE)
        if (marker.isFile) return true
        return writeJsonLocked(marker, SessionRecord(sessionId, startedAt = now()))
    }

    private fun update(sessionId: String, index: Int, change: (FragmentRecord) -> Unit): Boolean {
        if (sessionId.isBlank()) return false
        return try {
            val dir = sessionDir(sessionId)
            if (!dir.isDirectory) return false
            val record = readRecordLocked(sessionId, index) ?: FragmentRecord(index)
            change(record)
            writeJsonLocked(File(dir, recordName(index)), record)
        } catch (_: Throwable) {
            false
        }
    }

    private fun readRecordLocked(sessionId: String, index: Int): FragmentRecord? = try {
        val file = File(sessionDir(sessionId), recordName(index))
        if (!file.isFile) null
        else gson.fromJson(file.readText(Charsets.UTF_8), FragmentRecord::class.java)
    } catch (_: Throwable) {
        null
    }

    /** tmp + rename, so a crash can never leave a half-written file. */
    private fun writeJsonLocked(target: File, value: Any): Boolean = try {
        val tmp = File(target.parentFile, target.name + TEMP_SUFFIX)
        tmp.writeText(gson.toJson(value), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            val moved = tmp.renameTo(target)
            if (!moved) tmp.delete()
            moved
        } else {
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** Copies [source] to [target] via a temp file; never throws. */
    private fun copyAtomically(target: File, source: File): Boolean = try {
        val tmp = File(target.parentFile, target.name + TEMP_SUFFIX)
        source.inputStream().use { input ->
            FileOutputStream(tmp).use { input.copyTo(it, COPY_BUFFER_BYTES) }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            val moved = tmp.renameTo(target)
            if (!moved) tmp.delete()
            moved
        } else {
            true
        }
    } catch (_: Throwable) {
        try {
            File(target.parentFile, target.name + TEMP_SUFFIX).delete()
        } catch (_: Throwable) {
        }
        false
    }

    private fun deleteQuietly(dir: File): Boolean = try {
        dir.deleteRecursively()
    } catch (_: Throwable) {
        false
    }

    private fun sizeOf(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) {
        0L
    }
}
