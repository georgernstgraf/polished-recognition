package com.georgernstgraf.polishedrecognition.service

/**
 * #165: an automatic capture may only start once the IME window is actually
 * DRAWN.
 *
 * `onStartInputView` fires while the window is still invisible (cold start:
 * ~20-50 ms before the first frame — the reporter's logcat shows the FGS
 * denial then `performShowLocked: mDrawState=HAS_DRAWN`). Starting the
 * microphone foreground service inside that gap makes the platform deny the
 * while-in-use types ("Foreground service started from background can not
 * have location/camera/microphone access … New WIU:DENIED"), and the
 * `AudioRecord` delivers silence — a noise-only session uploads and
 * transcribes to "".
 *
 * Neither platform callback is a better gate (AOSP `InputMethodService`,
 * API 36): `onWindowShown()` runs *before* `mWindow.show()` (line 3196 vs.
 * 3201) and `isInputViewShown()` is `mDecorViewVisible`, which
 * `prepareWindow()` sets even earlier (line 3297). The first draw of the
 * input view is the only app-side signal that the window is on screen.
 */
object AutoStartTiming {

    /**
     * How long a deferred auto-start waits for the first draw. Long enough
     * for a slow cold start, short enough that a keyboard which was hidden
     * before it ever showed (a client that hides it on IME change) does not
     * keep a pending capture around.
     */
    const val WINDOW_DRAW_TIMEOUT_MS = 1500L

    enum class Step {
        /** The window is drawn — the pending auto-start may run. */
        RUN,

        /** No frame yet — wait for the draw callback or the timeout. */
        WAIT,

        /** The window never appeared — drop the auto-start. */
        DROP
    }

    fun step(windowDrawn: Boolean, timedOut: Boolean): Step = when {
        windowDrawn -> Step.RUN
        timedOut -> Step.DROP
        else -> Step.WAIT
    }

    /**
     * What the draw timeout does. The timeout exists to give up on a keyboard
     * that never appears (a client that hides it on IME change) — NOT to cut
     * off a slow cold start: measured on a loaded API 36 emulator the first
     * frame arrived ~1.1 s after the bind, i.e. uncomfortably close to
     * [WINDOW_DRAW_TIMEOUT_MS]. A window that is already on its way up
     * (`isInputViewShown()`, i.e. the platform's `mDecorViewVisible`) keeps
     * waiting; the draw still has to happen before anything starts.
     */
    fun onTimeout(windowDrawn: Boolean, inputViewShown: Boolean): Step = when {
        windowDrawn -> Step.RUN
        inputViewShown -> Step.WAIT
        else -> Step.DROP
    }
}
