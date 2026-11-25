package com.georgernstgraf.polishedrecognition.ui

import com.georgernstgraf.polishedrecognition.dictation.Dictation
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal

/**
 * #188: one page of the Workbench ring.
 *
 * #185 gave the leftovers a screen of their own next to the ring. That was a second list over the same question — "what is still
 * waiting for me?" — so #188 merges both into **one** ring: a stored
 * dictation and an unfinished journal session are pages of the same list, and
 * the leftover page carries the actions the separate screen used to carry.
 *
 * A page is only ever a **view** over its source: the [DictationStore] stays
 * the one truth for text, the [FragmentJournal] the one truth for audio. No
 * page is persisted, no schema changed.
 */
sealed interface WorkbenchPage {

    /** A dictation the store holds: Raw plus at most one Polished. */
    data class Stored(val dictation: Dictation) : WorkbenchPage

    /**
     * A journal session that never became text (#185's leftover).
     *
     * [hasAudio] is the one fact the journal summary does not carry and the
     * action rule needs (share/retry without audio would promise a file the
     * app does not have); the caller decides it off the main thread.
     */
    data class Leftover(
        val summary: FragmentJournal.SessionSummary,
        val hasAudio: Boolean
    ) : WorkbenchPage
}

/**
 * #188: the merge rule of the ring, pure and testable — the activity only
 * renders what this returns.
 *
 * ## Order
 *
 * **Ascending by the page's last change**, the same direction
 * [DictationStore.loadAll] uses (least recently changed first, so the LAST
 * element is the ring's "newest"):
 *
 * - a stored page changes when its text changes ([Dictation.updatedAtMs]);
 * - a leftover changes when it started (`startedAtMs`) — it has no content
 *   to touch, and its recording is its whole being.
 *
 * A leftover without any instant sorts **last** (the newest position): the
 * journal could not date it, and a page that might be today's must not be
 * buried under everything else. Ties break by id / session id, so the order is
 * total and a rebuild never shuffles two pages.
 */
object WorkbenchPages {

    /** The instant a page is ordered by; `null` when the journal has none. */
    fun changedAt(page: WorkbenchPage): Long? = when (page) {
        is WorkbenchPage.Stored -> page.dictation.updatedAtMs
        is WorkbenchPage.Leftover -> page.summary.startedAtMs
    }

    /** The page's stable identity — the dictation id or the journal session id. */
    fun idOf(page: WorkbenchPage): String = when (page) {
        is WorkbenchPage.Stored -> page.dictation.id
        is WorkbenchPage.Leftover -> page.summary.sessionId
    }

    fun merge(
        dictations: List<Dictation>,
        leftovers: List<WorkbenchPage.Leftover>
    ): List<WorkbenchPage> {
        val pages = ArrayList<WorkbenchPage>(dictations.size + leftovers.size)
        dictations.forEach { pages += WorkbenchPage.Stored(it) }
        pages += leftovers
        pages.sortWith(ORDER)
        return pages
    }

    /** The last leftover — the page the banner jumps to. `null` at zero. */
    fun newestLeftoverIndex(pages: List<WorkbenchPage>): Int? =
        pages.indexOfLast { it is WorkbenchPage.Leftover }.takeIf { it >= 0 }

    /** How many pages are real dictations (the ones a FIFO cap applies to). */
    fun storedCount(pages: List<WorkbenchPage>): Int =
        pages.count { it is WorkbenchPage.Stored }

    private val ORDER = Comparator<WorkbenchPage> { left, right ->
        val a = changedAt(left)
        val b = changedAt(right)
        when {
            a == null && b == null -> idOf(left).compareTo(idOf(right))
            a == null -> 1
            b == null -> -1
            a != b -> a.compareTo(b)
            else -> idOf(left).compareTo(idOf(right))
        }
    }
}
