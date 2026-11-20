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
     * @param onCompose the shown page is the compose slot
     *   ([com.georgernstgraf.polishedrecognition.ui.FifoNavigation.COMPOSE_INDEX]),
     *   not a stored card
     *
     * **The arrows are asymmetric on purpose** (#173 decision 7, #178): the
     * compose slot sits *outside* the ring, so leaving it is a real move — one
     * stored card is a destination ([FifoNavigation.older]/[FifoNavigation.newer]
     * both land on index 0). On a stored card the ring is the destination, and
     * with a single entry ◀ and ▶ would only swap the card for itself, so they
     * stay disabled until a second one exists. Requiring two entries everywhere
     * would make a store holding exactly one dictation unreachable from the
     * entry page — the regression this parameter fixes.
     */
    fun enabled(
        hasRaw: Boolean,
        hasPolished: Boolean,
        polishing: Boolean,
        ringSize: Int,
        onCompose: Boolean
    ): Actions {
        // From the compose slot one stored card is reachable; inside the ring
        // one card is only a self-loop.
        val canNavigate = if (onCompose) ringSize >= 1 else ringSize >= 2
        return Actions(
            polish = hasRaw && !polishing,
            promote = hasPolished,
            clearPolished = hasPolished,
            copyRaw = hasRaw,
            copyPolished = hasPolished,
            prev = canNavigate,
            next = canNavigate,
            new = true
        )
    }
}
