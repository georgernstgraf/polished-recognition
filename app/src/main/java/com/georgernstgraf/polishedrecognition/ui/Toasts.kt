package com.georgernstgraf.polishedrecognition.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Toast

/**
 * #207: the ONE toast surface of the app. Every production `Toast.makeText`
 * routes through [show]/[short]/[hint] — durations are decided here, not at
 * the call sites.
 *
 * The default is deliberately LONGER than the platform durations: a
 * LENGTH_LONG toast (≈3.5 s) was unreadable for full-sentence messages (the
 * 2026-11-25 incident), so [show] keeps the toast visible for
 * [DEFAULT_TARGET_MS] by chaining re-shows of the SAME instance
 * ([reshows] is the pure chain policy). A fresh toast replaces the running
 * chain — cancel-safe, no old text ever resurrects over a newer one.
 *
 * Call-site decisions (#207): [show] is the default for every informative
 * message; [short] exists only for genuinely trivial confirmations
 * ("Settings saved", "Token valid", …); [hint] keeps the #88 mechanics — a
 * reused instance, gravity-top, NO chaining, cancelled by [dismissHint]
 * when the long-pressing finger lifts. At most one toast is ever alive:
 * any new [show]/[short] cancels BOTH the previous message chain and a
 * live hint, so two surfaces never overlap.
 */
object Toasts {

    /** The readable default duration every informative toast aims for. */
    const val DEFAULT_TARGET_MS = 5_500L

    /** The nominal visibility of one `Toast.LENGTH_LONG` segment. */
    const val SEGMENT_MS = 3_500L

    /** A re-show fires this long BEFORE the segment would end — no blink. */
    const val RESHOW_LEAD_MS = 250L

    private val stepMs: Long
        get() = SEGMENT_MS - RESHOW_LEAD_MS

    // One Handler per show(): the object outlives a Robolectric test's
    // per-test main looper, a fresh Handler never binds a dead scheduler.
    private var handler: Handler? = null

    private var message: Toast? = null
    private var messageChain: Runnable? = null
    private var generation = 0

    private var hint: Toast? = null

    /**
     * The informative default: a LENGTH_LONG toast kept readable by
     * re-showing it until [DEFAULT_TARGET_MS] elapsed.
     */
    fun show(context: Context, text: CharSequence) {
        cancelMessage()
        cancelHint()
        val toast = Toast.makeText(context, text, Toast.LENGTH_LONG)
        message = toast
        toast.show()
        scheduleChain(toast, DEFAULT_TARGET_MS)
    }

    /**
     * The trivial-confirmation variant: a plain single LENGTH_SHORT show,
     * deliberately NOT chained — one-word acknowledgements need no 5.5 s.
     */
    fun short(context: Context, text: CharSequence) {
        cancelMessage()
        cancelHint()
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * The #88 long-press hint: one reused instance (repeated long-presses
     * must not churn show/cancel through the notification manager), shown
     * top-centre, never chained — [dismissHint] ends it when the finger
     * lifts. Replaces a running message chain: one toast at a time.
     */
    fun hint(context: Context, text: CharSequence, yOffsetPx: Int) {
        cancelMessage()
        val toast = hint ?: Toast.makeText(context, text, Toast.LENGTH_LONG).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, yOffsetPx)
            hint = this
        }
        toast.setText(text)
        toast.show()
    }

    /**
     * Ends the hint when the long press is over. The hint is a separate
     * instance from every message toast, and every [show]/[short] dismisses
     * the hint FIRST — so a finger lift can only ever cancel the hint
     * itself, never a message shown since the press started.
     */
    fun dismissHint() {
        cancelHint()
    }

    /** Cancels everything alive — the owner of the last toast calls this. */
    fun cancelAll() {
        cancelMessage()
        dismissHint()
    }

    /**
     * Pure chain policy (#207): the delays before each re-show that extends
     * one toast to [targetMs]. Re-shows fire uniformly [stepMs] apart, each
     * [RESHOW_LEAD_MS] before the current segment would end; one is
     * scheduled only when the display would otherwise end before the
     * target elapses. An unchained target (≤ one segment) yields an empty
     * list — the toast keeps its single platform show.
     */
    fun reshows(
        targetMs: Long,
        segmentMs: Long = SEGMENT_MS,
        leadMs: Long = RESHOW_LEAD_MS,
    ): List<Long> {
        val step = segmentMs - leadMs
        val delays = mutableListOf<Long>()
        var start = 0L
        while (start + segmentMs < targetMs) {
            start += step
            delays += start
        }
        return delays
    }

    private fun scheduleChain(toast: Toast, targetMs: Long) {
        val delays = reshows(targetMs)
        if (delays.isEmpty()) return
        val gen = generation
        val looperHandler = Handler(Looper.getMainLooper())
        handler = looperHandler
        var index = 0
        val task = object : Runnable {
            override fun run() {
                if (gen != generation) return // a newer toast owns the slot
                toast.show()
                index++
                if (index < delays.size) {
                    looperHandler.postDelayed(this, delays[index] - delays[index - 1])
                }
            }
        }
        messageChain = task
        looperHandler.postDelayed(task, delays[0])
    }

    private fun cancelMessage() {
        generation++
        messageChain?.let { chain -> handler?.removeCallbacks(chain) }
        handler = null
        messageChain = null
        message?.cancel()
        message = null
    }

    /** A live hint never outlives a newer message surface. */
    private fun cancelHint() {
        hint?.cancel()
        hint = null
    }
}
