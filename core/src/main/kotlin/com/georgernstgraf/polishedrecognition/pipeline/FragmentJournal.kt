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

    /**
     * The session-level marker (`session.json`) — written FIRST, before any
     * fragment record, so it is the session's validity marker.
     *
     * ## #185: the three session states
     *
     * - **unfinished** (neither field set): the transcription never produced a
     *   delivered result — a failed request, a kill, a reboot. This is what
     *   the recovery UI offers for a re-run.
     * - **completed** ([completedAtMs] set): every fragment is `DONE` and the
     *   session's result was delivered. The controller deletes the whole
     *   session dir right after; the marker therefore only survives when that
     *   delete failed or the process died in between. The recovery UI then
     *   offers the stored text instead of a pointless re-run — the marker is
     *   what makes the two cases distinguishable at all.
     * - **dismissed** ([dismissedAtMs]/[dismissReason] set): the user threw
     *   the audio away explicitly. Everything but this marker is already
     *   gone; the tombstone keeps the "no silent drops" record.
     */
    class SessionRecord(
        val sessionId: String,
        var startedAt: Long? = null,
        /** #185: all fragments DONE and the transcript delivered. */
        var completedAtMs: Long? = null,
        /** #185: explicit dismissal (tombstone — audio + records deleted). */
        var dismissedAtMs: Long? = null,
        /** #185: why it was dismissed (`user_discarded`). */
        var dismissReason: String? = null
    )

    /**
     * #185: one recoverable session, as the recovery UI needs it. Derived from
     * the marker + the fragment records; never throws.
     */
    data class SessionSummary(
        val sessionId: String,
        val startedAtMs: Long?,
        /** `true` when the transcript was delivered but the dir survived. */
        val completed: Boolean,
        val fragmentCount: Int,
        val doneCount: Int,
        val pendingCount: Int,
        val needsManualCount: Int,
        /** Audio seconds actually dictated (sum of the fragment durations). */
        val durationMs: Long,
        val totalBytes: Long,
        /** #183 class of the LOWEST incomplete fragment, when one was recorded. */
        val failureClass: String?,
        val failureHttpCode: Int?
    ) {
        /** Fragments without a stored transcript — what a re-run would send. */
        val unfinishedCount: Int get() = fragmentCount - doneCount
    }

    companion object {
        /** #185: how many dismissal tombstones the root keeps (newest win). */
        const val MAX_TOMBSTONES = 50

        /** #185: the only dismissal reason the UI writes. */
        const val REASON_USER_DISCARDED = "user_discarded"
        /**
         * The journal's directory name under `filesDir`. Excluded from cloud
         * backup and device transfer in BOTH rule files — the journal holds
         * private audio and transcript text (`BackupRulesTest` pins this).
         */
        const val DIR_NAME = "journal"

        /** 256 MB over the whole root; the newest session always survives. */
        const val DEFAULT_CAP_BYTES = 256L * 1024 * 1024

        const val SESSION_FILE = "session.json"
        private const val RECORD_PREFIX = "fragment_"
        private const val JSON_SUFFIX = ".json"
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
        // #185: dismissal tombstones are records, not recovery paths — they are
        // capped by COUNT (newest win) and never by the audio cap they do not
        // contribute to.
        var deleted = pruneTombstonesLocked(sessions)
        val candidatesAll = try {
            rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        } catch (_: Throwable) {
            return deleted
        }
        if (candidatesAll.isEmpty()) return deleted
        var total = candidatesAll.sumOf { sizeOf(it) }
        if (total <= capBytes) return deleted
        val newest = candidatesAll.maxByOrNull { SessionDirTime.of(it) ?: 0L }
        val candidates = candidatesAll
            .filter { it.name != keepSessionId && it !== newest }
            .sortedBy { SessionDirTime.of(it) ?: Long.MAX_VALUE }
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

    /** Deletes the oldest dismissal tombstones beyond [MAX_TOMBSTONES]. */
    private fun pruneTombstonesLocked(sessions: List<File>): Int {
        val tombstones = sessions.mapNotNull { dir ->
            val record = readSessionLocked(dir.name) ?: return@mapNotNull null
            if (record.dismissedAtMs == null) null else dir to (record.dismissedAtMs ?: 0L)
        }
        if (tombstones.size <= MAX_TOMBSTONES) return 0
        var deleted = 0
        tombstones
            .sortedBy { it.second }
            .dropLast(MAX_TOMBSTONES)
            .forEach { (dir, _) -> if (deleteQuietly(dir)) deleted++ }
        return deleted
    }

    /** Reads one record, or null when it does not exist / is unreadable. */
    @Synchronized
    fun readRecord(sessionId: String, index: Int): FragmentRecord? = readRecordLocked(sessionId, index)

    /** Every fragment record of [sessionId], in index order (#185). */
    @Synchronized
    fun readRecords(sessionId: String): List<FragmentRecord> = readRecordsLocked(sessionId)

    /** The session marker, or null when absent/unreadable (#185). */
    @Synchronized
    fun readSession(sessionId: String): SessionRecord? = readSessionLocked(sessionId)

    /**
     * #185: marks the session as COMPLETED — every fragment is `DONE` and the
     * result was delivered. Written by the controller's success branch right
     * before it deletes the session dir, so a delete that fails (or a process
     * death in between) can never masquerade as an unfinished dictation.
     *
     * `false` when the session has no marker or the write failed; never throws.
     */
    @Synchronized
    fun markCompleted(sessionId: String): Boolean =
        updateSession(sessionId) { it.completedAtMs = now() }

    /**
     * #185: the explicit dismissal. The marker records WHY first (a dismissal
     * is never silent — the requirement is a RECORD before the delete), then
     * every fragment record and audio file is removed. The marker itself stays
     * as a tombstone until [prune] caps the tombstone count.
     *
     * `false` when the session has no marker or the record could not be
     * written; never throws. The audio is only deleted after a successful
     * record write.
     */
    @Synchronized
    fun markDismissed(sessionId: String, reason: String = REASON_USER_DISCARDED): Boolean {
        if (sessionId.isBlank()) return false
        val recorded = updateSession(sessionId) {
            it.dismissedAtMs = now()
            it.dismissReason = reason
        }
        if (!recorded) return false
        return try {
            sessionDir(sessionId).listFiles()?.forEach { file ->
                if (file.isFile && file.name != SESSION_FILE) file.delete()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * #185: every session the recovery UI can act on, newest first — the
     * unfinished ones (a re-run is possible: the audio of the missing
     * fragments is on disk) and the completed ones whose dir survived (the
     * stored text can be restored without a single request).
     *
     * A session without any fragment record is debris from a crash between
     * `mkdirs` and the first write — there is nothing to recover, so it is
     * skipped. Dismissed tombstones are never listed.
     *
     * Never throws; a single unreadable session must not blind the list.
     */
    @Synchronized
    fun recoverableSessions(): List<SessionSummary> = try {
        rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> summaryOf(dir) }
            ?.sortedByDescending { it.startedAtMs ?: 0L }
            .orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun summaryOf(dir: File): SessionSummary? = try {
        val session = readSessionLocked(dir.name)
        val records = readRecordsLocked(dir.name)
        if (session == null || session.dismissedAtMs != null || records.isEmpty()) {
            null
        } else {
            val firstFailure = records
                .filter { it.state != State.DONE.name && it.failureClass != null }
                .minByOrNull { it.index }
            // Every fragment has its text → there is nothing left to send, even
            // when the explicit marker is missing (a re-run that finished but
            // could not delete, or a kill right after the last fragment). The
            // honest action is "restore the text", never a second transcription.
            val allDone = records.all { it.state == State.DONE.name }
            SessionSummary(
                sessionId = dir.name,
                startedAtMs = session.startedAt ?: SessionDirTime.of(dir),
                completed = session.completedAtMs != null || allDone,
                fragmentCount = records.size,
                doneCount = records.count { it.state == State.DONE.name },
                pendingCount = records.count { it.state == State.PENDING.name },
                needsManualCount = records.count { it.state == State.NEEDS_MANUAL.name },
                durationMs = records.sumOf { it.durationMs ?: 0L },
                totalBytes = sizeOf(dir),
                failureClass = firstFailure?.failureClass,
                failureHttpCode = firstFailure?.failureHttpCode
            )
        }
    } catch (_: Throwable) {
        null
    }

    /** apply [change] to the session marker; `false` when it does not exist. */
    private fun updateSession(sessionId: String, change: (SessionRecord) -> Unit): Boolean {
        if (sessionId.isBlank()) return false
        return try {
            val current = readSessionLocked(sessionId) ?: return false
            change(current)
            writeJsonLocked(File(sessionDir(sessionId), SESSION_FILE), current)
        } catch (_: Throwable) {
            false
        }
    }

    private fun readSessionLocked(sessionId: String): SessionRecord? = try {
        val file = File(sessionDir(sessionId), SESSION_FILE)
        if (!file.isFile) null
        else gson.fromJson(file.readText(Charsets.UTF_8), SessionRecord::class.java)
    } catch (_: Throwable) {
        null
    }

    private fun readRecordsLocked(sessionId: String): List<FragmentRecord> = try {
        sessionDir(sessionId).listFiles()
            ?.filter { it.isFile && it.name.startsWith(RECORD_PREFIX) && it.name.endsWith(JSON_SUFFIX) }
            ?.mapNotNull { file ->
                try {
                    gson.fromJson(file.readText(Charsets.UTF_8), FragmentRecord::class.java)
                } catch (_: Throwable) {
                    null
                }
            }
            ?.sortedBy { it.index }
            .orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

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
