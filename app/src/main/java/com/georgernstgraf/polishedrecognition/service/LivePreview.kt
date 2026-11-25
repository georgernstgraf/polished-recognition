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
    UNREADABLE
}

/**
 * #205 D3: the reconciliation decision against the 64-char window probe.
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
 */
object LivePreviewReconcile {

    /** The window the probe reads (`getTextBeforeCursor`) — owner's ≤64 chars. */
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
     * @param window      `getTextBeforeCursor(WINDOW)` — null when unreadable.
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
        if (!window.startsWith(before)) return ABORT
        val after = window.substring(before.length).toString()
        // Whatever the client shows of OUR text must be a beginning of P —
        // anything else is an edit we cannot reason about.
        if (!provisional.startsWith(after)) return ABORT

        if (atBind) {
            // The platform finished our input: `after` IS the committed
            // prefix now (empty when the client dropped our text, the whole
            // provisional when it committed it, something between when the
            // app committed a prefix). Continue by composing only P − K.
            return Decision(Action.COMPOSE, provisional.removePrefix(after), after)
        }

        return when (after) {
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
}

/**
 * #205 D4: the terminal classification — what the field holds AFTER the
 * composition was abandoned. Pure; the caller performs the one write the
 * [StreamOutcome.SUFFIX_APPENDED] decision asks for.
 */
object LivePreviewTerminal {

    data class Decision(val outcome: StreamOutcome, val append: String = "")

    /**
     * @param window      `getTextBeforeCursor(WINDOW)` after the composition was cleared.
     * @param before      B, or null when it was never captured (I3).
     * @param provisional P, markers included.
     */
    fun classify(window: CharSequence?, before: String?, provisional: String): Decision {
        // There is no fingerprint of "before our text" — the field cannot be
        // classified at all; the caller falls back to today's path.
        if (before == null) return Decision(StreamOutcome.UNREADABLE)
        // A field we cannot read is not a field we may write into (I3).
        if (window == null) return Decision(StreamOutcome.MISMATCH)
        val w = window.toString()
        if (w == before) return Decision(StreamOutcome.CLEARED)
        if (provisional.isNotEmpty() && w == before + provisional) {
            return Decision(StreamOutcome.COMMITTED_BY_APP)
        }
        // The prefix case: the cursor sits directly behind a proper prefix K
        // of P (the window ends where the cursor is). Append-only — the only
        // write the terminal does without a clear field, and never a delete.
        val longest = longestPrefixSuffix(provisional, w)
        if (longest in 1 until provisional.length) {
            val suffix = stripMarkers(provisional.substring(longest))
            if (suffix.isNotBlank()) return Decision(StreamOutcome.SUFFIX_APPENDED, suffix)
        }
        return Decision(StreamOutcome.MISMATCH)
    }

    /** The longest prefix of [text] that is a suffix of [window], or 0. */
    private fun longestPrefixSuffix(text: String, window: String): Int {
        val max = minOf(text.length, window.length)
        for (len in max downTo 1) {
            if (window.regionMatches(window.length - len, text, 0, len)) return len
        }
        return 0
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
