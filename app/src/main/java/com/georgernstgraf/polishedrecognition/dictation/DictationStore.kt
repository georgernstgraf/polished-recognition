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
     * All kept dictations, **least recently edited first** (#173, decision 5).
     * The ring's "newest" is the last element, and [pruneToSize] evicts from
     * the front — display order and eviction order cannot disagree.
     *
     * An entry is only ever created by [append] — a mere glance at the
     * Workbench never evicts a real dictation (D4).
     */
    @Synchronized
    fun loadAll(): List<Dictation> =
        dictationFiles()
            .mapNotNull { read(it) }
            .sortedWith(compareBy({ it.updatedAtMs }, { it.id }))

    /**
     * Creates a dictation for [raw] and prunes the FIFO to its configured
     * depth in the same call (D4: the entry exists as soon as the first
     * transcript arrives — or the compose slot is materialised, #173).
     */
    @Synchronized
    fun append(raw: String): Dictation {
        // Strictly increasing stamps: two dictations created in the same
        // millisecond must still have a stable FIFO order (the file id is
        // random), so the newest is always `max(now, newest + 1)`. The
        // touch stamp additionally respects the newest *edit*, so a new
        // dictation is the ring's last entry even when an old one was just
        // edited "into the future" by the same rule.
        val existing = loadAll()
        val newestCreated = existing.maxOfOrNull { it.createdAtMs } ?: 0L
        val newestEdited = existing.maxOfOrNull { it.updatedAtMs } ?: 0L
        val createdAt = maxOf(now(), newestCreated + 1)
        val dictation = Dictation(
            id = newId(Date(createdAt)),
            raw = raw,
            createdAtMs = createdAt,
            updatedAtMs = maxOf(createdAt, newestEdited + 1)
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
     * #172). The **least recently edited** dictations go first (#173); the
     * newest survives even at a cap below one.
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
     * dictation. A real change bumps [Dictation.updatedAtMs] to
     * `max(now, newest edit + 1)` — strictly increasing, so touches never tie
     * and MRU order stays deterministic (#173). An update never CREATES an
     * entry (#208): promote, edit and polish stay on their dictation; only
     * [append] (the ＋ icon and the compose slot) instantiates one.
     */
    private fun update(id: String, transform: (Dictation) -> Dictation): Dictation? {
        val file = fileFor(id)
        val current = read(file) ?: return null
        val updated = transform(current)
        if (updated == current) return current
        val newestEdited = loadAll().maxOfOrNull { it.updatedAtMs } ?: 0L
        val touched = updated.copy(updatedAtMs = maxOf(now(), newestEdited + 1))
        write(touched)
        return touched
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
            val parsed = gson.fromJson(file.readText(Charsets.UTF_8), Dictation::class.java)
            when {
                parsed == null || parsed.id.isBlank() || parsed.raw.isBlank() -> null
                // #173 legacy: files written before the MRU key existed carry
                // no `updatedAtMs`; Gson leaves it 0, so fall back to creation.
                // The same fallback covers files written by the 2026-11-25
                // build that briefly dropped the field (#208).
                parsed.updatedAtMs > 0 -> parsed
                else -> parsed.copy(updatedAtMs = parsed.createdAtMs)
            }
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
