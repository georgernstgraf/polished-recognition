package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.FragmentTranscriber

/**
 * #205: the LIVE PREVIEW in the target field — the dictation grows in the
 * client's `Editable` through the composing region while it is transcribed,
 * and the terminal step then delivers exactly what it delivers today.
 *
 * The state lives HERE, next to the app-scoped controller, **never on the IME
 * instance**: after a rotation the new instance receives `Completed` via
 * `attach` and would otherwise blind-insert into a field that already holds
 * the provisional text — the forbidden duplicate. Events are not replayed, so
 * a fresh instance must be able to read the streaming state synchronously.
 *
 * Main thread only (the controller's scope is `Dispatchers.Main` and the IME
 * lives there); the class is plain mutable state with no synchronisation.
 *
 * Vocabulary (owner, #205): **P** the accumulated echo-trimmed transcript
 * (the provisional text, markers included), **B** the ≤64 chars left of the
 * composition, **K** the verified committed prefix of P, **Q** the polished
 * delivery text.
 */
class LivePreview {

    /** The kill switch, read at session start (#205 D1). */
    var enabled = false
        private set

    /** A session is streaming (or about to): the display may write. */
    var active = false
        private set

    /** The field the preview writes into, captured at the first append. */
    var field: RotationGate.FieldId? = null

    /** B — the before-window, captured at the first append (I3: null ⇒ no stream). */
    var before: String? = null

    /** P — the latest marker-joined transcript. */
    var provisional = ""
        private set

    /** The composing text last written to the client (never includes K). */
    var sent = ""

    /** K — the part of P the client holds as committed text (verified). */
    var committedPrefix = ""

    /** At least one composing write (or a verified bind find) happened. */
    var streamed = false

    /** Reconciliation at a bind succeeded (#205 D3). */
    var reconciled = false

    /** The display was abandoned — the terminal must classify (#205 D3/D4). */
    var aborted = false

    /** No selection could be read at session start (#205 D6). */
    var selectionUnreadable = false

    /** How many `<pending>` gaps the provisional text carries (diagnostics). */
    var pendingMarkers = 0
        private set

    /** The last terminal classification (diagnostics, `insertion.json`). */
    var outcome: StreamOutcome? = null

    /** Starts a session: remembers the kill switch and forgets the old take. */
    fun begin(livePreviewEnabled: Boolean) {
        reset()
        enabled = livePreviewEnabled
        active = livePreviewEnabled
    }

    /** A finished fragment arrived — the display tick's input (#205 D2). */
    fun onFragments(text: String, markers: Int) {
        if (!active) return
        provisional = text
        pendingMarkers = markers
    }

    fun recordOutcome(result: StreamOutcome) {
        outcome = result
    }

    /**
     * The take is over (or the composition was cleared): no session streams
     * any more. The last take's diagnostics stay readable for the terminal
     * `insertion.json` record until the next [begin] resets everything.
     */
    fun end() {
        active = false
    }

    private fun reset() {
        enabled = false
        active = false
        field = null
        before = null
        provisional = ""
        sent = ""
        committedPrefix = ""
        streamed = false
        reconciled = false
        aborted = false
        selectionUnreadable = false
        pendingMarkers = 0
        outcome = null
    }

    /** True when there is anything in the field that a terminal step must classify. */
    val hasDisplayedText: Boolean get() = active && streamed && provisional.isNotEmpty()
}

/**
 * #205: the terminal classification of the field (#205 D4). Pure, so the whole
 * matrix is unit-testable without an `InputConnection`.
 */
enum class StreamOutcome {
    /** The field shows B only — insert Q through today's path. */
    CLEARED,

    /** The field already holds the whole provisional text — insert nothing. */
    COMMITTED_BY_APP,

    /** A verified prefix K sits in the field — append the raw suffix. */
    SUFFIX_APPENDED,

    /** The field cannot be trusted — insert nothing, Q to the clipboard. */
    MISMATCH,

    /** The kill switch was off — today's path, byte-identical. */
    DISABLED,

    /** Streaming was enabled but nothing could be displayed — today's path. */
    UNREADABLE,

    /**
     * #207: the field holds our verified streamed text, but the polished
     * delivery Q differs from it (LLM mode) — that is NOT "already
     * delivered". The streamed span behind the cursor was deleted, so the
     * caller inserts Q through today's path and the field ends with exactly
     * Q. Never fires for an identical delivery (raw mode keeps the clipboard
     * behaviour of [COMMITTED_BY_APP]/[SUFFIX_APPENDED]).
     */
    COMMITTED_STREAM_REPLACED
}

/**
 * #205 D3: the reconciliation decision against the before-window probe.
 *
 * Events give the timing, the probe gives the truth. [atBind] is the
 * reconciliation after a rebind: AOSP's `onFinishInput()` commits the
 * composition, so the field normally holds `B + K` with K = the whole
 * provisional — the bind must therefore NEVER re-send text the field already
 * shows, or the result is the forbidden duplicate.
 *
 * On a live connection (display tick) our composition is still ours: the
 * window shows `B + K + sent`, and re-sending `P − K` *replaces* the
 * composing region — that is the one re-composition the owner allows.
 *
 * **The probe must cover what it verifies** (device finding, 2026-11-25): the
 * window was read with a constant 64 chars, and a composition outgrows that
 * on the second fragment — the window then showed only the *tail* of our own
 * text and every tick aborted. The window the IME reads is now sized by
 * [windowLength]/[windowLengthForBind], and [decide] tolerates extra context
 * left of B (a longer request can only pull pre-B text into the window).
 */
object LivePreviewReconcile {

    /**
     * The B fingerprint length (`getTextBeforeCursor` at the first append) —
     * NOT the length of a reconciliation probe; size that one with
     * [windowLength]/[windowLengthForBind].
     */
    const val WINDOW = 64

    enum class Action {
        /** Write [Decision.composing] as the composing region. */
        COMPOSE,

        /** The field does not match anything we know — stop displaying. */
        ABORT
    }

    data class Decision(
        val action: Action,
        val composing: String = "",
        val committedPrefix: String = ""
    )

    private val ABORT = Decision(Action.ABORT)

    /**
     * The probe length the live tick needs: B + K + the composition the tick
     * replaces — the window must show ALL of our text or the verification is
     * impossible.
     */
    fun windowLength(before: String, committedPrefix: String, sent: String): Int =
        before.length + committedPrefix.length + sent.length

    /**
     * The probe length a bind reconciliation needs: B plus the whole
     * provisional (the committed prefix K is unknown before the probe).
     */
    fun windowLengthForBind(before: String, provisional: String): Int =
        before.length + provisional.length

    /**
     * @param window      `getTextBeforeCursor(<sized>)` — null when unreadable.
     * @param before      B, the fingerprint captured at the first append.
     * @param committedPrefix K, the verified committed prefix so far.
     * @param sent        the composing text last written (without K).
     * @param provisional P, the provisional text the display wants to show.
     * @param atBind      true after a rebind (the platform committed our text).
     */
    fun decide(
        window: CharSequence?,
        before: String,
        committedPrefix: String,
        sent: String,
        provisional: String,
        atBind: Boolean
    ): Decision {
        // I4: never re-compose unverified; a null probe can never verify.
        if (window == null) return ABORT
        if (provisional.isEmpty()) return ABORT
        val tail = oursInWindow(window.toString(), before, provisional) ?: return ABORT

        if (atBind) {
            // The platform finished our input: `tail` IS the committed
            // prefix now (empty when the client dropped our text, the whole
            // provisional when it committed it, something between when the
            // app committed a prefix). Continue by composing only P − tail.
            return Decision(Action.COMPOSE, provisional.removePrefix(tail), tail)
        }

        return when (tail) {
            // our composition survived (hide/show, no rebind) — replace it
            committedPrefix + sent -> Decision(
                Action.COMPOSE,
                provisional.removePrefix(committedPrefix),
                committedPrefix
            )
            // the client dropped our composition — nothing was committed
            committedPrefix -> Decision(
                Action.COMPOSE,
                provisional.removePrefix(committedPrefix),
                committedPrefix
            )
            // a partially lost composition: we cannot tell what is committed
            else -> ABORT
        }
    }

    /**
     * The part of P the field holds immediately left of the cursor: the
     * window must contain B with ONLY a prefix of P after it. Context left of
     * B is tolerated (the probe asks for more than the composition is long,
     * and the first candidate boundary may sit inside look-alike text); the
     * first B occurrence that verifies wins. Null when no boundary verifies.
     */
    private fun oursInWindow(window: String, before: String, provisional: String): String? {
        if (before.isEmpty()) {
            return window.takeIf { provisional.startsWith(it) }
        }
        var idx = window.indexOf(before)
        while (idx >= 0) {
            val tail = window.substring(idx + before.length)
            if (provisional.startsWith(tail)) return tail
            idx = window.indexOf(before, idx + 1)
        }
        return null
    }
}

/**
 * #205 D4: the terminal classification — what the field holds AFTER the
 * composition was abandoned. Pure; the caller performs the one write the
 * [StreamOutcome.SUFFIX_APPENDED] decision asks for, or the verified deletion
 * the [StreamOutcome.COMMITTED_STREAM_REPLACED] decision asks for (#207).
 */
object LivePreviewTerminal {

    data class Decision(
        val outcome: StreamOutcome,
        val append: String = "",
        /** #207: chars of OUR verified streamed text to delete left of the cursor. */
        val deleteBefore: Int = 0
    )

    /**
     * @param window      `getTextBeforeCursor(<sized>)` after the composition was cleared.
     * @param before      B, or null when it was never captured (I3).
     * @param provisional P, markers included.
     * @param delivery    Q, the polished delivery text. When Q differs from
     *   what the field holds of the streamed provisional (LLM mode with an
     *   effective polish), the committed streamed state is NOT "already
     *   delivered" — #207: the span is replaced so the field ends with
     *   exactly Q. An identical delivery (raw mode, a polish no-op) or a
     *   blank one keeps the clipboard behaviour.
     */
    fun classify(
        window: CharSequence?,
        before: String?,
        provisional: String,
        delivery: String
    ): Decision {
        // There is no fingerprint of "before our text" — the field cannot be
        // classified at all; the caller falls back to today's path.
        if (before == null) return Decision(StreamOutcome.UNREADABLE)
        // A field we cannot read is not a field we may write into (I3).
        if (window == null) return Decision(StreamOutcome.MISMATCH)
        val w = window.toString()
        // The boundary the cursor sits behind: B with only a prefix of P
        // after it (context left of B is tolerated — the probe asks for more
        // than a long provisional needs, #205 device finding).
        val tail = oursBehindCursor(w, before, provisional)
            ?: return Decision(StreamOutcome.MISMATCH)
        val identical = delivery == stripMarkers(provisional)
        return when {
            // nothing of ours after B — insert Q through today's path
            tail.isEmpty() -> Decision(StreamOutcome.CLEARED)
            // the field already holds the whole provisional text
            tail.length == provisional.length ->
                if (identical || delivery.isBlank()) Decision(StreamOutcome.COMMITTED_BY_APP)
                else replaceStreamedSpan(tail)
            else -> {
                // A verified prefix K of P sits behind B — append only the
                // missing, marker-free suffix (I2: never delete) — unless the
                // delivery differs: then the appended raw text is not what
                // the user asked for, and the verified span is replaced (#207).
                val suffix = stripMarkers(provisional.substring(tail.length))
                if (suffix.isNotBlank()) {
                    if (identical || delivery.isBlank()) Decision(StreamOutcome.SUFFIX_APPENDED, suffix)
                    else replaceStreamedSpan(tail)
                } else Decision(StreamOutcome.MISMATCH)
            }
        }
    }

    /** #207: the span behind the cursor is OUR verified text — delete it whole. */
    private fun replaceStreamedSpan(tail: String) =
        Decision(StreamOutcome.COMMITTED_STREAM_REPLACED, deleteBefore = tail.length)

    /**
     * The probe length the terminal classification needs: B plus the whole
     * provisional — the composition may have been committed in full.
     */
    fun windowLength(before: String?, provisional: String): Int =
        (before?.length ?: 0) + provisional.length

    /** The longest P-prefix the window holds directly behind B, or null. */
    private fun oursBehindCursor(
        window: String,
        before: String,
        provisional: String
    ): String? {
        if (before.isEmpty()) {
            return window.takeIf { provisional.startsWith(it) }
        }
        var idx = window.indexOf(before)
        while (idx >= 0) {
            val tail = window.substring(idx + before.length)
            if (provisional.startsWith(tail)) return tail
            idx = window.indexOf(before, idx + 1)
        }
        return null
    }

    /**
     * #205 I7: `<pending>` markers are display only. The appended raw suffix
     * never contains one, and the joining spaces collapse cleanly.
     */
    fun stripMarkers(text: String): String =
        text.replace(FragmentTranscriber.PENDING_MARKER, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("\\s+")
}
