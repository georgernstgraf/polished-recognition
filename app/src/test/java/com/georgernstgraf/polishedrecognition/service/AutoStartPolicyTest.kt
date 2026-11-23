package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController.State
import com.georgernstgraf.polishedrecognition.service.AutoStartPolicy.EntryAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #192: the entry decision reads like a matrix — state × "is this the app the
 * paused take belongs to" × permission. The rule the issue rests on: a pause
 * may only resume itself where it belongs. The regression: a take paused by
 * the keyboard switch 3.6 minutes earlier was resumed in the SAME app and its
 * audio merged into the next send (measured `INSERTION length=1125`) — and
 * before #192 it did so in EVERY app.
 *
 * There is deliberately no reason and no time dimension: every pause continues
 * in its own app, however long ago ("always continue, no exceptions"), and
 * nothing else continues anywhere.
 */
class AutoStartPolicyTest {

    private fun action(
        state: State,
        sameTarget: Boolean = true,
        permission: Boolean = true
    ) = AutoStartPolicy.entryAction(state, sameTarget, permission)

    @Test
    fun `IDLE with permission fresh-starts`() {
        assertThat(action(State.IDLE, sameTarget = true)).isEqualTo(EntryAction.FRESH_START)
        assertThat(action(State.IDLE, sameTarget = false)).isEqualTo(EntryAction.FRESH_START)
    }

    @Test
    fun `without mic permission nothing happens automatically`() {
        State.entries.forEach { state ->
            listOf(false, true).forEach { sameTarget ->
                assertThat(action(state, sameTarget, permission = false))
                    .isEqualTo(EntryAction.NONE)
            }
        }
    }

    @Test
    fun `live sessions are never touched automatically`() {
        listOf(State.RECORDING, State.PROCESSING).forEach { state ->
            listOf(false, true).forEach { sameTarget ->
                assertThat(action(state, sameTarget)).isEqualTo(EntryAction.NONE)
            }
        }
    }

    @Test
    fun `PAUSED in its own app resumes - however long ago it was paused`() {
        assertThat(action(State.PAUSED, sameTarget = true)).isEqualTo(EntryAction.RESUME)
    }

    @Test
    fun `PAUSED in another app fresh-starts through the salvage path`() {
        assertThat(action(State.PAUSED, sameTarget = false)).isEqualTo(EntryAction.FRESH_START)
    }

    @Test
    fun `the full matrix - one row per outcome, no state left unhandled`() {
        data class Row(val state: State, val sameTarget: Boolean, val expected: EntryAction)

        val rows = listOf(
            Row(State.IDLE, true, EntryAction.FRESH_START),
            Row(State.IDLE, false, EntryAction.FRESH_START),
            Row(State.RECORDING, true, EntryAction.NONE),
            Row(State.RECORDING, false, EntryAction.NONE),
            Row(State.PROCESSING, true, EntryAction.NONE),
            Row(State.PROCESSING, false, EntryAction.NONE),
            Row(State.PAUSED, true, EntryAction.RESUME),
            Row(State.PAUSED, false, EntryAction.FRESH_START)
        )

        rows.forEach { row ->
            assertThat("${row.state}/sameTarget=${row.sameTarget}" to action(row.state, row.sameTarget))
                .isEqualTo("${row.state}/sameTarget=${row.sameTarget}" to row.expected)
        }
    }
}
