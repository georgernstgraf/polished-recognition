package com.georgernstgraf.polishedrecognition.ui

/**
 * #178 (Workbench operation buttons) — **which** controls are usable on the
 * shown page, decided in one pure place (`FifoNavigation`'s sibling, #173).
 *
 * The activity only reads the flags; it never re-derives an enable rule of its
 * own, so a button can never contradict the data it acts on:
 *
 * - **polish** needs raw text and a free pipeline — a running polish is not
 *   started twice, and an empty page has nothing to send.
 * - **promote**, **clearPolished** and **copyPolished** act on the polished
 *   slot: without one they would be silent no-ops
 *   ([com.georgernstgraf.polishedrecognition.dictation.DictationStore] returns
 *   `null`), so they are disabled instead of lying.
 * - **copyRaw** acts on the raw text, which every stored dictation has.
 * - **prev** and **next** only make sense once the ring holds two entries:
 *   with one entry both arrows would just swap it for itself.
 * - **new** is always available — the compose slot is reachable from every
 *   page, including an empty ring.
 */
object WorkbenchActions {

    /** Enabled state of the Workbench's controls for one page. */
    data class Actions(
        val polish: Boolean,
        val promote: Boolean,
        val clearPolished: Boolean,
        val copyRaw: Boolean,
        val copyPolished: Boolean,
        val prev: Boolean,
        val next: Boolean,
        val new: Boolean
    )

    /**
     * @param hasRaw the shown page carries raw text (a stored dictation does;
     *   the empty compose slot does not)
     * @param hasPolished the page's single polished slot is filled
     * @param polishing a polish request is in flight
     * @param ringSize number of stored dictations in the FIFO
     */
    fun enabled(
        hasRaw: Boolean,
        hasPolished: Boolean,
        polishing: Boolean,
        ringSize: Int
    ): Actions = Actions(
        polish = hasRaw && !polishing,
        promote = hasPolished,
        clearPolished = hasPolished,
        copyRaw = hasRaw,
        copyPolished = hasPolished,
        prev = ringSize >= 2,
        next = ringSize >= 2,
        new = true
    )
}
