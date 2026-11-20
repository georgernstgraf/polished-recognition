package com.georgernstgraf.polishedrecognition.pipeline

import java.io.File
import java.util.Calendar

/**
 * The epoch time of a per-session directory (#132 name format
 * `yyyyMMdd-HHmm-<8hex>`, LOCAL time — see `newSessionId`).
 *
 * Shared by the two stores that order session dirs by age: the #142
 * audio-evidence store ([AudioEvidenceStore], age + space eviction) and the
 * #182 fragment journal ([FragmentJournal], size cap). Legacy bare-UUID dirs
 * carry no date, so they fall back to the directory's last-modified stamp;
 * only when neither is usable is the age unknown (`null`) — such a dir can be
 * removed by a size/space valve alone, never by an age rule.
 *
 * Extraction is behaviour-preserving: the parser moved here from
 * `AudioEvidenceStore.sessionTime`, whose eviction tests remain the
 * regression proof.
 */
object SessionDirTime {

    /** Epoch millis of [dir]'s session, or `null` when neither name nor mtime says. */
    fun of(dir: File): Long? {
        val m = NAME_PREFIX.find(dir.name) ?: return lastModified(dir)
        val (date, time) = m.destructured
        return try {
            val cal = Calendar.getInstance()
            cal.set(
                date.substring(0, 4).toInt(),
                date.substring(4, 6).toInt() - 1,
                date.substring(6, 8).toInt(),
                time.substring(0, 2).toInt(),
                time.substring(2, 4).toInt(),
                0
            )
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (_: Throwable) {
            lastModified(dir)
        }
    }

    private fun lastModified(dir: File): Long? = try {
        dir.lastModified().takeIf { it > 0L }
    } catch (_: Throwable) {
        null
    }

    private val NAME_PREFIX = Regex("^(\\d{8})-(\\d{4})-")
}
