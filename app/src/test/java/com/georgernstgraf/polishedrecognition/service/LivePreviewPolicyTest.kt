package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #205: the live-preview policy, pure — the reconciliation matrix (D3), the
 * terminal cascade (D4) and the marker-stripping rule (I7). No Android, no
 * `InputConnection`: the adapter is the only Android part of the seam.
 */
class LivePreviewPolicyTest {

    private fun reconcile(
        window: String?,
        before: String = "abc",
        committedPrefix: String = "",
        sent: String = "",
        provisional: String = "hallo",
        atBind: Boolean = false
    ): LivePreviewReconcile.Decision = LivePreviewReconcile.decide(
        window = window,
        before = before,
        committedPrefix = committedPrefix,
        sent = sent,
        provisional = provisional,
        atBind = atBind
    )

    // ---------------------------------------------------------------- bind D3

    @Test
    fun `bind with B only re-sends the whole provisional`() {
        val d = reconcile(window = "abc", before = "abc", provisional = "hallo", atBind = true)

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo("hallo")
        assertThat(d.committedPrefix).isEmpty()
    }

    @Test
    fun `bind with B plus a committed prefix composes only the delta`() {
        val d = reconcile(window = "abchal", before = "abc", provisional = "hallo", atBind = true)

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo("lo")
        assertThat(d.committedPrefix).isEqualTo("hal")
    }

    /**
     * The normal rebind case (AOSP `onFinishInput` commits the composition):
     * the whole provisional is already committed text. Re-sending it would be
     * the forbidden duplicate, so nothing is composed and K = P.
     */
    @Test
    fun `bind with the whole provisional committed composes nothing`() {
        val d = reconcile(window = "abchallo", before = "abc", provisional = "hallo", atBind = true)

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEmpty()
        assertThat(d.committedPrefix).isEqualTo("hallo")
    }

    @Test
    fun `bind with anything else aborts the display`() {
        assertThat(reconcile(window = "abcXYZ", atBind = true).action)
            .isEqualTo(LivePreviewReconcile.Action.ABORT)
        assertThat(reconcile(window = null, atBind = true).action)
            .isEqualTo(LivePreviewReconcile.Action.ABORT)
    }

    @Test
    fun `bind never writes into a field whose window does not start with B`() {
        assertThat(reconcile(window = "xyz", before = "abc", atBind = true).action)
            .isEqualTo(LivePreviewReconcile.Action.ABORT)
    }

    // ---------------------------------------------------------------- tick D2

    @Test
    fun `a surviving composition is replaced by the whole delta`() {
        val d = reconcile(
            window = "abchallo",
            before = "abc",
            committedPrefix = "",
            sent = "hallo",
            provisional = "hallo welt",
            atBind = false
        )

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo("hallo welt")
    }

    @Test
    fun `after a committed prefix the tick replaces only the composing region`() {
        val d = reconcile(
            window = "abchal" + "lo",
            before = "abc",
            committedPrefix = "hal",
            sent = "lo",
            provisional = "hallo welt",
            atBind = false
        )

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo("lo welt")
        assertThat(d.committedPrefix).isEqualTo("hal")
    }

    @Test
    fun `a dropped composition is re-sent from the committed prefix`() {
        val d = reconcile(
            window = "abchal",
            before = "abc",
            committedPrefix = "hal",
            sent = "lo",
            provisional = "hallo welt",
            atBind = false
        )

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo("lo welt")
    }

    @Test
    fun `a partially lost composition aborts instead of guessing`() {
        val d = reconcile(
            window = "abchall",
            before = "abc",
            committedPrefix = "",
            sent = "hallo",
            provisional = "hallo welt",
            atBind = false
        )

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.ABORT)
    }

    // ----------------------------------------------------------- terminal D4

    @Test
    fun `cleared field - B only - inserts through today's path`() {
        val d = LivePreviewTerminal.classify(window = "abc", before = "abc", provisional = "hallo")

        assertThat(d.outcome).isEqualTo(StreamOutcome.CLEARED)
        assertThat(d.append).isEmpty()
    }

    @Test
    fun `committed provisional inserts nothing`() {
        val d = LivePreviewTerminal.classify(
            window = "abchallo", before = "abc", provisional = "hallo"
        )

        assertThat(d.outcome).isEqualTo(StreamOutcome.COMMITTED_BY_APP)
        assertThat(d.append).isEmpty()
    }

    @Test
    fun `a committed prefix appends exactly the missing suffix`() {
        val d = LivePreviewTerminal.classify(
            window = "abchallo we", before = "abc", provisional = "hallo welt"
        )

        assertThat(d.outcome).isEqualTo(StreamOutcome.SUFFIX_APPENDED)
        assertThat(d.append).isEqualTo("lt")
    }

    @Test
    fun `the appended suffix never contains a pending marker`() {
        val d = LivePreviewTerminal.classify(
            window = "abchallo",
            before = "abc",
            provisional = "hallo <pending> welt"
        )

        assertThat(d.outcome).isEqualTo(StreamOutcome.SUFFIX_APPENDED)
        assertThat(d.append).isEqualTo("welt")
    }

    @Test
    fun `an edited field is a mismatch - nothing is inserted`() {
        val d = LivePreviewTerminal.classify(window = "abcXYZ", before = "abc", provisional = "hallo")

        assertThat(d.outcome).isEqualTo(StreamOutcome.MISMATCH)
        assertThat(d.append).isEmpty()
    }

    @Test
    fun `a field that ends with the whole provisional but not at B is a mismatch`() {
        val d = LivePreviewTerminal.classify(
            window = "edited hallo", before = "abc", provisional = "hallo"
        )

        // "hallo" is a suffix, but K = P means there is no suffix to append.
        assertThat(d.outcome).isEqualTo(StreamOutcome.MISMATCH)
    }

    @Test
    fun `no before-window means the field was never readable`() {
        val d = LivePreviewTerminal.classify(window = "abc", before = null, provisional = "hallo")

        assertThat(d.outcome).isEqualTo(StreamOutcome.UNREADABLE)
    }

    @Test
    fun `an unreadable terminal window is a mismatch - never a write`() {
        val d = LivePreviewTerminal.classify(window = null, before = "abc", provisional = "hallo")

        assertThat(d.outcome).isEqualTo(StreamOutcome.MISMATCH)
    }

    // ------------------------------------------------------------- markers I7

    @Test
    fun `marker stripping collapses the join and keeps the words`() {
        assertThat(LivePreviewTerminal.stripMarkers("a <pending> b <pending>")).isEqualTo("a b")
        assertThat(LivePreviewTerminal.stripMarkers("<pending>")).isEmpty()
        assertThat(LivePreviewTerminal.stripMarkers("hallo welt")).isEqualTo("hallo welt")
    }
}
