package com.georgernstgraf.polishedrecognition.ui

/**
 * #169 (epic #166 D6) FIFO navigation for the Workbench, amended by #173.
 *
 * The stored dictations form a **ring** in `DictationStore.loadAll()` order
 * (least recently edited first, most recently edited last): ◀ / ▶ wrap around
 * in both directions (#173 decision 7).
 *
 * The **compose slot** ([COMPOSE_INDEX], label "new") sits OUTSIDE the ring: it
 * is the page on entry and the target of ＋ New, but neither arrow can reach
 * it — both arrows leaving it land on the newest stored dictation, which is
 * where the user expects the ring to start.
 *
 * An empty store has nothing to navigate to; both arrows are disabled while
 * the compose slot is shown.
 */
object FifoNavigation {

    /** The id-less compose slot: the entry page, not part of the ring. */
    const val COMPOSE_INDEX = -1

    /** The position label of the compose slot ("new", decision 7). */
    const val COMPOSE_LABEL = "new"

    /** The ring has at least one stored dictation to move to. */
    fun canNavigate(size: Int): Boolean = size > 0

    /** A fresh look starts on the compose slot (#173 decision 2). */
    fun initialIndex(): Int = COMPOSE_INDEX

    /** Older: down the ring, wrapping from the oldest to the newest. */
    fun older(index: Int, size: Int): Int = when {
        !canNavigate(size) -> index
        index == COMPOSE_INDEX -> size - 1
        else -> (index - 1 + size) % size
    }

    /** Newer: up the ring, wrapping from the newest to the oldest. */
    fun newer(index: Int, size: Int): Int = when {
        !canNavigate(size) -> index
        index == COMPOSE_INDEX -> size - 1
        else -> (index + 1) % size
    }

    /** 1-based "3 / 5" label in the ring, "new" on the compose slot. */
    fun positionLabel(index: Int, size: Int): String =
        if (index == COMPOSE_INDEX) COMPOSE_LABEL else "${index + 1} / $size"
}
