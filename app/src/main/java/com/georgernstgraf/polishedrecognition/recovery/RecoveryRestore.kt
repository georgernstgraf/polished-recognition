package com.georgernstgraf.polishedrecognition.recovery

import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal

/**
 * #188: "Restore text" — a completed session's stored transcript becomes a
 * Workbench dictation, with **no request**.
 *
 * Extracted from #185's recovery screen when the action moved onto the
 * Workbench page, so there is exactly one copy of the rule. The rule is the
 * same one the re-run follows (`RecoveryCoordinator.recoverOne`):
 *
 * 1. join the `sttTrimmed` text of the fragments in index order;
 * 2. write it into the [com.georgernstgraf.polishedrecognition.dictation.DictationStore]
 *    and verify the store really holds it;
 * 3. **only then** delete the journal session.
 *
 * The order is the whole point: the text is the valuable half, so a store that
 * did not keep it must leave the audio in place. Deleting first would be the
 * one unrecoverable mistake in this epic.
 *
 * The two side effects are a [Target], not a `DictationStore` + `FragmentJournal`
 * pair, because that makes the *order* and the *condition* assertable in a
 * unit test without a device, a Robolectric activity or a network.
 */
object RecoveryRestore {

    /** What a restore may touch, in the order the contract requires. */
    interface Target {
        /** Appends [text]; the created dictation's id, or `null` when the store did not keep it. */
        fun store(text: String): String?

        /** Removes the journal session — only ever called after a successful [store]. */
        fun drop(sessionId: String)
    }

    sealed interface Outcome {
        /** The text is stored and the journal session is gone. */
        data class Restored(val dictationId: String) : Outcome

        /** Every fragment has no stored text — there is nothing to restore. */
        object NoText : Outcome

        /** The store refused the text; the journal was NOT touched. */
        object NotStored : Outcome
    }

    /**
     * The stored transcript of [records]: the `sttTrimmed` text of every
     * fragment in index order, single-spaced. Blank fragments are skipped (a
     * silent fragment legitimately has none); an entirely blank session yields
     * `""`.
     */
    fun textOf(records: List<FragmentJournal.FragmentRecord>): String =
        records
            .mapNotNull { it.sttTrimmed?.takeIf { text -> text.isNotBlank() } }
            .joinToString(" ")
            .trim()

    fun restore(
        sessionId: String,
        records: List<FragmentJournal.FragmentRecord>,
        target: Target
    ): Outcome {
        val text = textOf(records)
        if (text.isBlank()) return Outcome.NoText
        val id = target.store(text) ?: return Outcome.NotStored
        target.drop(sessionId)
        return Outcome.Restored(id)
    }
}
