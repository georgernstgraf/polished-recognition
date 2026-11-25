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

    // ------------------------------------------- probe sizing (device finding)

    /**
     * Device finding 2026-11-25: the window was read with a constant 64
     * chars, so a composition outgrowing it showed only its own TAIL and
     * every second tick aborted — the first fragment displayed, then the
     * preview froze until Send. The sized probe covers the whole
     * composition; the decision must replace it in full.
     */
    @Test
    fun `a composition longer than the old constant window still reconciles`() {
        val first = "wort ".repeat(24) // 120 chars — outgrew the old 64
        val second = "$first und weiter"
        val d = reconcile(
            window = first,
            before = "",
            committedPrefix = "",
            sent = first,
            provisional = second
        )

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo(second)
    }

    @Test
    fun `a longer request may pull context left of B into the window - the tick tolerates it`() {
        val d = reconcile(
            window = "alter text abchallo",
            before = "abc",
            committedPrefix = "",
            sent = "hallo",
            provisional = "hallo welt"
        )

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo("hallo welt")
    }

    @Test
    fun `a longer request may pull context left of B into the window - the bind tolerates it`() {
        val d = reconcile(
            window = "alter text abchal",
            before = "abc",
            provisional = "hallo welt",
            atBind = true
        )

        assertThat(d.action).isEqualTo(LivePreviewReconcile.Action.COMPOSE)
        assertThat(d.composing).isEqualTo("lo welt")
        assertThat(d.committedPrefix).isEqualTo("hal")
    }

    @Test
    fun `the probe lengths cover B plus everything of ours`() {
        assertThat(LivePreviewReconcile.windowLength("abc", "hal", "lo")).isEqualTo(8)
        assertThat(LivePreviewReconcile.windowLengthForBind("abc", "hallo")).isEqualTo(8)
        assertThat(LivePreviewTerminal.windowLength("abc", "hallo")).isEqualTo(8)
        assertThat(LivePreviewTerminal.windowLength(null, "hallo")).isEqualTo(5)
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

    @Test
    fun `a long committed provisional is classified even with context left of B`() {
        val long = "wort ".repeat(24)
        val d = LivePreviewTerminal.classify(
            window = "alterkram abc$long",
            before = "abc",
            provisional = long
        )

        assertThat(d.outcome).isEqualTo(StreamOutcome.COMMITTED_BY_APP)
        assertThat(d.append).isEmpty()
    }

    @Test
    fun `a cleared field with context left of B is still cleared`() {
        val d = LivePreviewTerminal.classify(
            window = "alterkram abc",
            before = "abc",
            provisional = "wort ".repeat(24)
        )

        assertThat(d.outcome).isEqualTo(StreamOutcome.CLEARED)
        assertThat(d.append).isEmpty()
    }

    // ------------------------------------------------------------- markers I7

    @Test
    fun `marker stripping collapses the join and keeps the words`() {
        assertThat(LivePreviewTerminal.stripMarkers("a <pending> b <pending>")).isEqualTo("a b")
        assertThat(LivePreviewTerminal.stripMarkers("<pending>")).isEmpty()
        assertThat(LivePreviewTerminal.stripMarkers("hallo welt")).isEqualTo("hallo welt")
    }
}
