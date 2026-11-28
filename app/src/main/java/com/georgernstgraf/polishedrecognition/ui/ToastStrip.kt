package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.georgernstgraf.polishedrecognition.R

/**
 * #222: the owned toast strip view — ONE look for both surfaces, so the
 * message stays recognisable as the same channel the platform toast was.
 * Platform-Toast convention (dark pill, light text in BOTH modes; explicit
 * colours because the IME service context has no theme guarantee), rounded,
 * `GONE` when idle so the host layout is byte-identical to pre-#222 while
 * no toast is shown (and the contrast audit skips it).
 *
 * The strip owns its display duration: [display] schedules its own hide —
 * the #207 chain policy is a platform-toast repair and does NOT run here.
 */
class ToastStrip(context: Context) : TextView(context) {

    private val hideHandler = Handler(Looper.getMainLooper())
    private var hide: Runnable? = null

    init {
        visibility = GONE
        val density = resources.displayMetrics.density
        setPadding(
            (16 * density).toInt(),
            (10 * density).toInt(),
            (16 * density).toInt(),
            (10 * density).toInt(),
        )
        background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(context.getColor(R.color.toast_strip_background))
        }
        setTextColor(context.getColor(R.color.toast_strip_text))
        textSize = 14f
    }

    /** The current message — the test window into what the strip says. */
    val currentText: CharSequence?
        get() = if (isVisible) text else null

    /** Shows the message for [durationMs], replacing whatever was visible. */
    fun display(text: CharSequence, durationMs: Long) {
        this.text = text
        visibility = VISIBLE
        hide?.let { hideHandler.removeCallbacks(it) }
        hide = Runnable { conceal() }.also { hideHandler.postDelayed(it, durationMs) }
    }

    /** Hides the strip immediately — cancel-safe, idempotent when idle. */
    fun conceal() {
        hide?.let { hideHandler.removeCallbacks(it) }
        hide = null
        visibility = GONE
    }
}

/**
 * Base of the two #222 host surfaces: registration under the OWNER context
 * (the only identity `Toasts.show(this, …)` carries) plus the shared
 * display/cancel plumbing. Subclasses own only where the strip lives.
 */
abstract class ToastStripHost(
    private val owner: Context,
    val strip: ToastStrip,
) : Toasts.StripHost {

    // #219: the attach window is re-entered per visibility cycle now (the IME
    // attaches in onCreateInputView AND onStartInputView, detaches in
    // onFinishInputView) — the flag makes both directions idempotent, so a
    // re-show never double-adds the strip and a double hide is a no-op.
    private var attached = false

    /** Registers the host; a no-op while already attached. */
    fun attach() {
        if (attached) return
        attachStrip()
        Toasts.attachHost(owner, this)
        attached = true
    }

    /**
     * Lifecycle teardown: unregister FIRST — [Toasts.detachHost] hides a
     * visible strip immediately, and later [Toasts.show] calls for this
     * owner fall back to the platform toast (correct for a hidden or dying
     * window). Idempotent: a second detach changes nothing.
     */
    fun detach() {
        if (!attached) return
        attached = false
        Toasts.detachHost(owner)
        detachStrip()
    }

    val currentText: CharSequence?
        get() = strip.currentText

    override fun showStrip(text: CharSequence, durationMs: Long) {
        strip.display(text, durationMs)
    }

    override fun cancelStrip() {
        strip.conceal()
    }

    protected abstract fun attachStrip()

    protected abstract fun detachStrip()
}

/**
 * #222: the Workbench host — the strip rides the activity's content root
 * (`android.R.id.content`), anchored by [ToastStripPolicy.overlayTop] so its
 * bottom edge sits at the policy fraction of the usable height.
 */
class WorkbenchToastStripHost(activity: Activity) :
    ToastStripHost(activity, ToastStrip(activity)) {

    private val content = activity.findViewById<ViewGroup>(android.R.id.content)

    override fun attachStrip() {
        content.addView(
            strip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL },
        )
    }

    override fun detachStrip() {
        content.removeView(strip)
    }

    override fun showStrip(text: CharSequence, durationMs: Long) {
        // Measure against the content's width BEFORE the strip becomes
        // visible, so the anchor offset is right in its very first layout
        // frame — no flash at the top edge. Unlaid-out hosts show unpositioned
        // and the next show fixes it (never happens for a real activity).
        if (content.width > 0 && content.height > 0) {
            strip.measure(
                View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            (strip.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = ToastStripPolicy.overlayTop(content.height, strip.measuredHeight)
                strip.layoutParams = lp
            }
        }
        super.showStrip(text, durationMs)
    }
}

/**
 * #222: the IME host — the strip is DOCKED as the first child of the voice
 * bar's root: the IME window's own position does the anchoring (the window
 * sits above the keyboard area ≈ 55–60 % screen height in portrait), so no
 * screen math runs here (see [ToastStripPolicy] for why the surfaces differ).
 */
class ImeToastStripHost(owner: Context, private val root: LinearLayout) :
    ToastStripHost(owner, ToastStrip(owner)) {

    override fun attachStrip() {
        root.addView(
            strip,
            0,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * root.resources.displayMetrics.density).toInt() },
        )
    }

    override fun detachStrip() {
        root.removeView(strip)
    }
}
