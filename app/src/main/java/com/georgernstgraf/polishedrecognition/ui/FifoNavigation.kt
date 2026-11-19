package com.georgernstgraf.polishedrecognition.ui

/**
 * #169 (epic #166 D6): pure FIFO navigation for the Workbench — the newest
 * dictation sits at the end of [com.georgernstgraf.polishedrecognition.dictation.DictationStore.loadAll],
 * so "older" walks the index down and "newer" walks it up.
 *
 * An empty list has no valid index (`-1`); the buttons are disabled in both
 * directions and no dictation is shown.
 */
object FifoNavigation {

    fun canGoOlder(index: Int, size: Int): Boolean = size > 0 && index in 1 until size

    fun canGoNewer(index: Int, size: Int): Boolean = size > 0 && index in 0 until size - 1

    /** The index a fresh look starts on: the newest entry, or `-1` when empty. */
    fun initialIndex(size: Int): Int = if (size > 0) size - 1 else -1

    fun older(index: Int, size: Int): Int = if (canGoOlder(index, size)) index - 1 else index

    fun newer(index: Int, size: Int): Int = if (canGoNewer(index, size)) index + 1 else index

    /** 1-based "3 / 5" label; empty FIFO shows "0 / 0". */
    fun positionLabel(index: Int, size: Int): String =
        if (size <= 0) "0 / 0" else "${index + 1} / $size"
}
