package com.georgernstgraf.polishedrecognition.dictation

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.util.Date

/**
 * The Workbench's dictation FIFO (#167, epic #166 D2/D4/D14).
 *
 * **One file per dictation** under [DIR_NAME] in `filesDir`, never
 * SharedPreferences: the whole file is rewritten on every `apply()`, which
 * would lose exactly the edits D14 wants to keep. Writes are temp file +
 * rename, so a crash never leaves a half-written dictation.
 *
 * The directory is excluded from cloud backup and device transfer in both
 * rule files (`res/xml/backup_rules.xml` for API 30,
 * `res/xml/data_extraction_rules.xml` for API 31+); `BackupRulesTest` pins
 * [DIR_NAME] to them.
 *
 * Corpus tolerance: an unparseable file is skipped with a warning — one
 * corrupt file must not blind the whole FIFO (same rule as
 * `BundleIo.loadAll`).
 */
class DictationStore(
    context: Context,
    /**
     * The configured FIFO depth (D2). Read on every append/prune so a Settings
     * change applies without rebuilding the store.
     */
    private val fifoSize: () -> Int = { DEFAULT_FIFO_SIZE },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: (Date) -> String = { newDictationId(it) }
) {

    private val gson = Gson()
    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    /**
     * All kept dictations, **oldest created first** (#208: creation order —
     * a touch must not move the page the user is reading). The ring's "newest"
     * is the last element, and [pruneToSize] evicts from the front — display
     * order and eviction order cannot disagree.
     *
     * An entry is only ever created by [append] — a mere glance at the
     * Workbench never evicts a real dictation (D4).
     */
    @Synchronized
    fun loadAll(): List<Dictation> =
        dictationFiles()
            .mapNotNull { read(it) }
            .sortedWith(compareBy({ it.createdAtMs }, { it.id }))

    /**
     * Creates a dictation for [raw] and prunes the FIFO to its configured
     * depth in the same call (D4: the entry exists as soon as the first
     * transcript arrives — or the compose slot is materialised, #173).
     */
    @Synchronized
    fun append(raw: String): Dictation {
        // Strictly increasing stamps: two dictations created in the same
        // millisecond must still have a stable FIFO order (the file id is
        // random), so the newest is always `max(now, newest + 1)`.
        val existing = loadAll()
        val newestCreated = existing.maxOfOrNull { it.createdAtMs } ?: 0L
        val createdAt = maxOf(now(), newestCreated + 1)
        val dictation = Dictation(
            id = newId(Date(createdAt)),
            raw = raw,
            createdAtMs = createdAt
        )
        ensureDir()
        write(dictation)
        pruneToSize()
        return dictation
    }

    /**
     * Replaces the raw text and **discards** the polished transcript — a raw
     * edit never leaves stale polished text behind (inherited rule, no
     * confirmation and no silent re-polish).
     *
     * An unchanged raw text is a strict no-op: no write, no MRU bump (#173).
     */
    @Synchronized
    fun updateRaw(id: String, raw: String): Dictation? = update(id) { current ->
        if (raw == current.raw) current else current.copy(raw = raw, polished = null)
    }

    /**
     * Sets (or overwrites) the single polished slot; blank or `null` clears
     * it. Writing the same value again is a no-op — no MRU bump (#173).
     */
    @Synchronized
    fun updatePolished(id: String, polished: String?): Dictation? = update(id) { current ->
        val normalized = polished?.takeIf { it.isNotBlank() }
        if (normalized == current.polished) current else current.copy(polished = normalized)
    }

    /**
     * **Promote**: the polished text becomes the raw text and the polished
     * slot is **cleared** (#208: a move, not a copy — a promotion that left
     * its own text in place showed the same content twice, in the fields and
     * across the ring's pages). A real promotion is a write like any other
     * content change.
     *
     * No polished text means nothing to promote: `null`, no write. Promoting
     * when `raw == polished` already holds is a strict no-op — no write
     * (same rule as [updatePolished]).
     */
    @Synchronized
    fun replaceRawWithPolished(id: String): Dictation? {
        val current = read(fileFor(id)) ?: return null
        val polished = current.polished ?: return null
        if (polished == current.raw) return current
        return update(id) { it.copy(raw = polished, polished = null) }
    }

    /**
     * **Clear**: empties the single polished slot — `null`, never `""` — and
     * bumps the MRU stamp. Clearing an already empty slot is the usual no-op
     * (no write, no MRU bump).
     */
    @Synchronized
    fun clearPolished(id: String): Dictation? = update(id) { it.copy(polished = null) }

    /** Deletes every kept dictation. */
    @Synchronized
    fun clear() {
        dictationFiles().forEach { it.delete() }
    }

    /**
     * Enforces the current FIFO depth (a shrunk Settings value prunes on save,
     * #172). The **oldest created** dictations go first (#208: creation order
     * — an edit no longer protects an entry from eviction); the newest
     * survives even at a cap below one.
     */
    @Synchronized
    fun pruneToSize() {
        val cap = fifoSize().coerceAtLeast(1)
        val kept = loadAll()
        if (kept.size <= cap) return
        kept.dropLast(cap).forEach { fileFor(it.id).delete() }
    }

    /**
     * Applies [transform] and writes only when it actually changes the
     * dictation. A change is **never** a reorder (#208): the ring sorts and
     * evicts by [Dictation.createdAtMs], so editing, polishing or promoting
     * leaves the page exactly where it is.
     */
    private fun update(id: String, transform: (Dictation) -> Dictation): Dictation? {
        val file = fileFor(id)
        val current = read(file) ?: return null
        val updated = transform(current)
        if (updated == current) return current
        write(updated)
        return updated
    }

    private fun write(dictation: Dictation) {
        ensureDir()
        val target = fileFor(dictation.id)
        val temp = File(dir, "${dictation.id}$TEMP_SUFFIX")
        try {
            temp.writeText(gson.toJson(dictation), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                // renameTo can fail on some filesystems; fall back to a direct
                // write so the dictation is never silently lost.
                target.writeText(gson.toJson(dictation), Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed for ${dictation.id}", e)
            temp.delete()
        }
    }

    private fun read(file: File): Dictation? {
        if (!file.isFile) return null
        return try {
            // #208 legacy tolerance: files written while `updatedAtMs` existed
            // (the #173 MRU key) carry the extra property; Gson ignores it.
            gson.fromJson(file.readText(Charsets.UTF_8), Dictation::class.java)
                ?.takeIf { it.id.isNotBlank() && it.raw.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "skipping unparseable dictation ${file.name}", e)
            null
        }
    }

    private fun dictationFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(JSON_SUFFIX) }?.toList() ?: emptyList()

    private fun fileFor(id: String) = File(dir, "$id$JSON_SUFFIX")

    private fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "cannot create $dir")
        }
    }

    companion object {
        private const val TAG = "DictationStore"

        /**
         * **Load-bearing outside this class**: both backup rule files exclude
         * exactly this directory, and `BackupRulesTest` pins the three
         * together (the #101 pattern).
         */
        const val DIR_NAME = "dictations"

        /** D2: FIFO depth when nothing was configured yet. */
        const val DEFAULT_FIFO_SIZE = 3

        /** The Settings range for the FIFO depth (#172). */
        const val MIN_FIFO_SIZE = 1
        const val MAX_FIFO_SIZE = 10

        private const val JSON_SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".json.tmp"
    }
}
