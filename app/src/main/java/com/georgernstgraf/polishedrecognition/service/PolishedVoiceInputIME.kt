package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.LanguageOptions
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.SttRequestRunner
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.ui.HelpActivity
import com.georgernstgraf.polishedrecognition.ui.MicrophonePermissionActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsHintActivity
import com.georgernstgraf.polishedrecognition.ui.Toasts

/**
 * Open for the Robolectric tests: they subclass the service to record what it
 * does to the keyboard and the editor (#174 `ImeCompletedHandoverTest`).
 * Android itself instantiates the concrete class from the manifest.
 */
open class PolishedVoiceInputIME : InputMethodService() {

    private lateinit var controller: VoiceSessionController
    private lateinit var settings: SettingsStore

    private var rootView: View? = null
    private var lastInsets: ImeWindowInsets.Paddings? = null
    private var rowTop: View? = null
    private var rowButtons: View? = null
    private var sendButton: ImageButton? = null
    private var pauseResumeButton: ImageButton? = null
    private var switchButton: ImageButton? = null
    private var deleteWordButton: ImageButton? = null
    private var flushButton: ImageButton? = null
    private var languageSpinner: Spinner? = null
    private var llmCheckbox: CheckBox? = null
    private var settingsGear: ImageButton? = null
    private var quickSettingsDivider: View? = null
    private var recTimer: TextView? = null
    private var stageText: TextView? = null
    private var breathAlpha = BREATH_CEIL
    private var breathAnimator: ValueAnimator? = null
    private var silenceLangListener = false
    /**
     * Set by [onConfigurationChanged], consumed by [onStartInputView]. Fast
     * path for rotation detection when the mark arrives before the rebind;
     * the app-scoped [RotationGate] timestamp + field identity
     * ([PolishedRecognitionApp.imeConfigChangeMs]/`imeLastField`) cover
     * service recreation — and [ImeStartDecision] covers the Oplus order
     * where the rebind lands ~150 ms before the mark.
     */
    private var configChangeInProgress = false
    /**
     * True when [onCreate] consumed a process-death snapshot via
     * [VoiceSessionController.restore]. Trace evidence only since #192: the
     * restored take is NOT frozen any more (owner: "always continue, no
     * exceptions") — the first bind decides it like any other pause; the field
     * exists so a device log can tell a restore from a fresh start. Consumed
     * by the first [onStartInputView].
     */
    private var restoredSnapshot = false
    /**
     * Package of the app currently owning the input field (#100), captured in
     * [onStartInputView]. Used to resolve the per-app line-wrap width and to
     * learn the dictation target on successful insertion.
     */
    private var editorPackage: String? = null
    /**
     * #205 D6: the selection snapshot taken at session start (text + range).
     * Written back on every non-delivering outcome — but only while the
     * before-window still matches (an edited field never gets a blind
     * restore). Connection-bound by design: the app-wide
     * [VoiceSessionController.livePreview] carries the field fingerprint, this
     * carries the selection of the instance that saw it.
     */
    private var selectionSnapshot: EditorPort.Selection? = null
    private var selectionCaptured = false
    /**
     * #205: the field the last bind named. The terminal and the display ticks
     * read [currentFieldId], which prefers `currentInputEditorInfo` but falls
     * back here — a Robolectric service never goes through `doStartInput`, and
     * on a real device the EditorInfo can be gone while the connection still
     * points at the field.
     */
    private var boundField: RotationGate.FieldId? = null
    /**
     * #168 (epic #166 D7): the caller is our own app, so the LLM is forced off
     * for the whole binding and the bar toggle renders disabled. Recomputed on
     * every [refreshQuickSettings] (each bind), never persisted.
     */
    private var llmForcedOff = false
    /**
     * Guards the delayed resume check posted by a [ImeStartDecision.Outcome]
     * provisional freeze: each bind bumps [startGen], so a superseded
     * runnable (rapid rebinds, teardown) no-ops instead of resuming against
     * a newer decision. Invalidated in [onDestroy].
     */
    private var startGen = 0
    private var pendingProvisional: Runnable? = null
    /**
     * #165: true once the input view has actually been DRAWN (the window is
     * on screen), false after [onWindowHidden]. The auto-start waits for this
     * — see [AutoStartTiming].
     */
    private var windowDrawn = false
    /** #165: the auto-start parked until [windowDrawn] (or the timeout). */
    private var pendingAutoStart: AutoStartPolicy.EntryAction? = null
    /** #165: a mic verification is already scheduled for this take. */
    private var micGuardPending = false
    /**
     * #177: the bar is parked after a successful send — ↺ enabled, send and
     * flush disabled, timer hidden — and no automatic start may fire until
     * the user taps ↺/send or the field changes. Pure UI state: the controller
     * is genuinely IDLE (see [PostSendParkingPolicy] for why it must not be
     * PAUSED).
     */
    private var postSendParked = false
    /**
     * #207: a polished result that could not be delivered at Send (the field
     * was unreadable — the device case: two sends parked `outcome=UNREADABLE`
     * and the resume tap cleared them with nothing delivered anywhere). The
     * text is HELD here, not dropped: [attemptHeldDelivery] delivers it into
     * the origin field at the next readable moment (the resume tap, the next
     * bind) or [salvageHeldDelivery] salvages it to the clipboard with a
     * toast naming where the text is — [HeldDeliveryPolicy] decides.
     */
    private var heldDelivery: String? = null
    /** #207: the field the held result was dictated into ([HeldDeliveryPolicy]). */
    private var heldOrigin: RotationGate.FieldId? = null
    /**
     * #118 Phase C: the signature of the last painted bar. [applyUiState] is
     * the single writer of that state, so an equal signature means an
     * identical paint and the rebind storm skips it. [invalidateUiRender]
     * clears it for fresh views — a new view always paints once.
     */
    private var uiSignature: RebindChurn.UiSignature? = null
    /**
     * #118 Phase C: the last [setFlashing] value. An unchanged one leaves the
     * breathing animator completely untouched — recreating it resets the
     * visible phase, the storm's most obvious churn.
     */
    private var flashing = false
    /** #118 Phase C: the text of the currently posted microphone notification. */
    private var lastNotificationText: String? = null
    /**
     * #118 Phase C: this instance already asserted the microphone foreground
     * service. Per-instance on purpose: a NEW instance starts `false`, which
     * covers "the previous instance may have died" exactly once.
     */
    private var foregroundAsserted = false
    /**
     * #118 Phase C measurement: work that really happened since the previous
     * `onStartInputView` trace line, reported there as `churn=…` and reset
     * right after — so every line describes the interval that just ended.
     * Module-visible so the tests can read them. A foreground assert carries
     * its own notification and therefore counts as `fgs`, never as `notify`
     * as well.
     */
    internal var renders = 0
    internal var foregroundAssertions = 0
    internal var notificationPosts = 0
    internal var breathStarts = 0
    /**
     * #192: the entry action the last bind decided (logged in the same trace
     * line). Module-visible so the tests can pin the decision itself — the
     * execution of `RESUME` starts a real capture, which Robolectric's
     * CPU-speed `AudioRecord` shadow makes unusable for anything but a
     * momentary check.
     */
    internal var lastEntryAction: AutoStartPolicy.EntryAction? = null
    private val windowDrawTimeout = Runnable {
        val pending = pendingAutoStart ?: return@Runnable
        when (AutoStartTiming.onTimeout(windowDrawn, isInputViewShown())) {
            AutoStartTiming.Step.RUN -> {
                pendingAutoStart = null
                ImeLifecycleTrace.log(this, "autostart", "run=$pending after-timeout")
                runAutoStart(pending)
            }
            AutoStartTiming.Step.WAIT -> ImeLifecycleTrace.log(
                this, "autostart",
                "still-waiting action=$pending window-shown state=${controller.state}"
            )
            AutoStartTiming.Step.DROP -> {
                pendingAutoStart = null
                ImeLifecycleTrace.log(
                    this, "autostart",
                    "dropped action=$pending reason=window-not-drawn state=${controller.state}"
                )
            }
        }
    }
    private val micGuard = Runnable { checkMicAccess() }
    private val recTickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recTick = object : Runnable {
        override fun run() {
            if (controller.state != VoiceSessionController.State.RECORDING) return
            recTimer?.text = RecTimeFormatter.recording(controller.recordedDurationMs())
            recTickHandler.postDelayed(this, REC_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as PolishedRecognitionApp
        controller = app.voiceSessionController
        controller.attach { handleEvent(it) }
        settings = app.settingsStore
        createNotificationChannel()
        restoredSnapshot = controller.restore()
        ImeLifecycleTrace.log(
            this, "onCreate",
            "state=${controller.state} restored=$restoredSnapshot"
        )
    }

    /**
     * #210: never go fullscreen. The InputMethodService default returns true in
     * landscape, which replaces the voice bar with the platform's extracted-
     * text editor — a white, system-styled edit field with a Done button that
     * covers the app (the "white raw field in landscape" on OxygenOS). This IME
     * is a voice BAR over the target field, in every orientation; the target
     * field itself stays the dictation surface (D10).
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    // #194: an IME input view has no parent at inflate time — the system
    // attaches it to the input-method window afterwards.
    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_input, null)
        rootView = view.findViewById(R.id.ime_root)
        applyImeWindowInsets(rootView!!)
        // #165: the first DRAW of the input view is the only reliable "window
        // is on screen" signal — onWindowShown runs before mWindow.show() and
        // isInputViewShown() is mDecorViewVisible, set even earlier. The
        // auto-start waits for this flag; the listener is cheap (one boolean)
        // and posts, so it never mutates the hierarchy during a draw pass.
        rootView!!.viewTreeObserver.addOnDrawListener {
            if (!windowDrawn) recTickHandler.post { onInputViewDrawn() }
        }
        rowTop = view.findViewById(R.id.ime_row_top)
        rowButtons = view.findViewById(R.id.ime_row_buttons)
        sendButton = view.findViewById(R.id.ime_send_button)
        pauseResumeButton = view.findViewById(R.id.ime_pause_resume_button)
        switchButton = view.findViewById(R.id.ime_switch_button)
        deleteWordButton = view.findViewById(R.id.ime_delete_word_button)
        flushButton = view.findViewById(R.id.ime_flush_button)
        languageSpinner = view.findViewById(R.id.ime_language_spinner)
        llmCheckbox = view.findViewById(R.id.ime_llm)
        settingsGear = view.findViewById(R.id.ime_settings_button)
        quickSettingsDivider = view.findViewById(R.id.ime_quick_settings_divider)
        recTimer = view.findViewById(R.id.ime_rec_timer)
        stageText = view.findViewById(R.id.ime_stage_text)

        sendButton?.setOnClickListener {
            when (controller.state) {
                VoiceSessionController.State.IDLE -> {
                    // #177/#192: a send tap on the no-take bar ends the park
                    // and starts a fresh take (both no-take bars render send
                    // disabled, so this is the stale-callback path only).
                    if (postSendParked) {
                        applyParkingEvent(PostSendParkingPolicy.Event.USER_TAP_SEND)
                    }
                    startIfPermitted()
                }
                VoiceSessionController.State.RECORDING,
                VoiceSessionController.State.PAUSED -> {
                    // #118: remember the field the dictation was sent from so
                    // the insertion breadcrumb can detect a delivery into a
                    // different field after a rotation.
                    (application as PolishedRecognitionApp).imeSessionField = currentFieldId()
                    controller.stopAndTranscribe(currentCallerPackage())
                }
                else -> Unit
            }
        }
        pauseResumeButton?.setOnClickListener {
            when (controller.state) {
                // #192: the owner's own pause — the take continues on a
                // return into this app, however long ago (↺ appends).
                VoiceSessionController.State.RECORDING -> pauseTake("pause-button")
                VoiceSessionController.State.PAUSED -> controller.resume()
                // #177/#192: ↺ on the no-take bar starts a fresh take —
                // nothing is buffered, so there is nothing to resume. The
                // park flag only changes the stage line, but it is cleared
                // here like the stale send tap does, so the bar cannot keep
                // saying "Sent" over a take that was just started. This holds
                // for BOTH no-take bars: fresh idle renders ↺ live, and a live
                // control must have an effect (owner's rule).
                VoiceSessionController.State.IDLE -> {
                    // #207: the resume tap is a readable moment — an
                    // undeliverable UNREADABLE result is due NOW, before the
                    // fresh take takes the field over.
                    attemptHeldDelivery("resume-tap")
                    if (postSendParked) {
                        applyParkingEvent(PostSendParkingPolicy.Event.USER_TAP_RESUME)
                    }
                    startIfPermitted()
                }
                else -> Unit
            }
        }
        // #155: the X hands the field back to the last-used typing keyboard.
        // It pauses a running take first (the audio stays, the #134 salvage
        // resumes it on return) and never discards — that is the trash
        // button's job. Deliberately NOT gated by the "switch back after
        // send" setting: it is the only manual way out of the voice keyboard.
        // #192: the pause remembers its target app — a return into the same
        // app continues (no time limit), another app salvages and starts fresh.
        switchButton?.setOnClickListener {
            if (controller.state == VoiceSessionController.State.RECORDING) {
                pauseTake("keyboard-switch")
            }
            val selfId = currentDefaultImeId() ?: return@setOnClickListener
            val target = resolveKeyKeyboardTarget(selfId)
            if (target == null) {
                openKeyboardSettings()
                return@setOnClickListener
            }
            switchInputMethod(target)
            if (currentDefaultImeId() == selfId) {
                openKeyboardSettings()
            }
        }
        // Delete-word button (#90): tap deletes the last word from the
        // editor, long-press clears the whole field. Deliberately no
        // press-hold hint — the long-press gesture is taken by clear-all.
        deleteWordButton?.setOnClickListener { deleteLastWord() }
        deleteWordButton?.setOnLongClickListener {
            clearEditorField()
            true
        }
        flushButton?.setOnClickListener {
            // Flush (#86): discard the audio but stay in the mode — a fresh
            // take in RECORDING, an empty pause in PAUSED. Leaving the
            // keyboard is the X's job (#155), discarding the trash's.
            when (controller.state) {
                VoiceSessionController.State.RECORDING,
                VoiceSessionController.State.PAUSED -> {
                    // #205 I1: Flush discards the audio, so its preview must
                    // leave the field — clear the composition and restore the
                    // selection snapshot before the controller forgets it.
                    abandonLivePreview(restoreSelection = true)?.let { showToast(it) }
                    controller.flush()
                }
                else -> Unit
            }
        }
        // Press-hold hints (#88): show the fixed hint in the stage-text
        // area above the buttons while the finger is down. The listener
        // returns false so the normal click still fires on release.
        listOf(
            switchButton to R.id.ime_switch_button,
            flushButton to R.id.ime_flush_button,
            pauseResumeButton to R.id.ime_pause_resume_button,
            sendButton to R.id.ime_send_button
        ).forEach { (button, id) ->
            ImeHintPolicy.hintFor(id)?.let { hint -> attachPressHint(button, hint) }
        }
        // Long-press the settings gear opens the Help screen (#107); it
        // replaces the old press-hold hint, so the gear is not in the list
        // above. A tap still opens Settings (#72/#51).
        settingsGear?.setOnLongClickListener {
            if (controller.state == VoiceSessionController.State.RECORDING) {
                pauseTake("gear-help")
            }
            startActivity(
                Intent(this, HelpActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }
        settingsGear?.setOnClickListener {
            if (controller.state == VoiceSessionController.State.RECORDING) {
                // #192: an in-app detour to Settings pauses the take; it
                // continues on the return into this app.
                pauseTake("gear-settings")
            }
            val selfId = currentDefaultImeId() ?: return@setOnClickListener
            val target = resolveKeyKeyboardTarget(selfId)
            if (target == null) {
                openKeyboardSettings()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            switchInputMethod(target)
        }
        // #154: the checkbox says "LLM" — checked means the LLM polishes the
        // dictation, unchecked means raw transcription. The stored domain flag
        // is the inverse (rawMode = skip the LLM), so no preference migration
        // is needed.
        llmCheckbox?.setOnCheckedChangeListener { _, isChecked ->
            if (silenceLangListener) return@setOnCheckedChangeListener
            // #168: inside our own app the toggle is rendered disabled; a
            // stale callback must never write the global setting.
            if (llmForcedOff) return@setOnCheckedChangeListener
            settings.rawMode = !isChecked
            updateLanguageEnabled()
        }
        setupLanguageSpinner()
        // #118 Phase C: the views below are new, so whatever the previous
        // instance painted says nothing about them — this view paints once.
        invalidateUiRender()
        applyUiState()
        return view
    }

    /**
     * #157: from Android 15 (API 35) the IME window is laid out edge-to-edge,
     * so the bottom control row would be drawn underneath the navigation bar;
     * up to API 34 the platform inset the content itself. The paddings are
     * rebuilt from the XML base value on every dispatch — appending to the
     * current padding would grow the view with each inset event.
     */
    private fun applyImeWindowInsets(root: View) {
        val base = ImeWindowInsets.baseOf(root)
        lastInsets = null
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val target = ImeWindowInsets.apply(view, base, insets)
            if (target != lastInsets) {
                lastInsets = target
                val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                ImeLifecycleTrace.log(
                    this, "insets",
                    "sdk=${Build.VERSION.SDK_INT} nav=${bars.left}/${bars.right}/${bars.bottom}" +
                        " cutout=${cutout.left}/${cutout.right}/${cutout.bottom}" +
                        " base=${base.left}/${base.right}/${base.bottom}" +
                        " applied=${target.left}/${target.right}/${target.bottom}"
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * #165: fires *before* the window is actually shown (AOSP calls it ahead
     * of `mWindow.show()`), so it only records the attempt — the first draw
     * of the input view releases the deferred auto-start.
     */
    override fun onWindowShown() {
        super.onWindowShown()
        windowDrawn = false
        ImeLifecycleTrace.log(
            this, "onWindowShown",
            "state=${controller.state} pending=${pendingAutoStart != null}"
        )
    }

    /**
     * #165: without a visible window no capture may start — the microphone
     * foreground service would be denied its while-in-use type. A pending
     * auto-start is dropped (and logged) instead of firing into the void.
     */
    override fun onWindowHidden() {
        super.onWindowHidden()
        windowDrawn = false
        val pending = pendingAutoStart
        val app = application as PolishedRecognitionApp
        val gateFresh = RotationGate.isFresh(app.imeConfigChangeMs, SystemClock.uptimeMillis())
        // #192: the window going away ends the #177 post-send park — "close
        // the IME with nav, then tap to edit text" starts a new dictation
        // instead of showing a parked bar. The same dismissal stops a live
        // capture (owner decision: hiding the keyboard while RECORDING pauses
        // the take, exactly like a nav-bar close).
        val dismisses = !configChangeInProgress && !gateFresh
        if (pending != null) {
            pendingAutoStart = null
            recTickHandler.removeCallbacks(windowDrawTimeout)
            ImeLifecycleTrace.log(
                this, "onWindowHidden",
                "state=${controller.state} dropped=$pending dismiss=$dismisses"
            )
        } else {
            ImeLifecycleTrace.log(
                this, "onWindowHidden",
                "state=${controller.state} dismiss=$dismisses"
            )
        }
        if (dismisses) onKeyboardDismissed()
    }

    /**
     * #192: the keyboard was dismissed (nav-bar down arrow, Back, the window
     * hiding, `onFinishInputView` without a rotation signal). One entry point
     * for both effects, because they must agree: the post-send park ends
     * (#177 park belongs to a keyboard that is still up) and a live take is
     * paused — not killed, so a return into the same app continues it and
     * another app salvages it ([AutoStartPolicy]).
     */
    private fun onKeyboardDismissed() {
        if (postSendParked) applyParkingEvent(PostSendParkingPolicy.Event.DISMISSED)
        if (controller.state == VoiceSessionController.State.RECORDING) {
            pauseTake("keyboard-dismissed")
        }
    }

    /**
     * #192: pauses a live take and records its target package plus the CAUSE in
     * the existing lifecycle stream — one line per pause, so a device log can
     * tell a user pause from a keyboard that went away. The cause is a plain
     * label for the log: no code path branches on it (every pause continues in
     * its own app), so it is not a type.
     */
    private fun pauseTake(cause: String) {
        val pkg = currentCallerPackage()
        controller.pause(pkg)
        ImeLifecycleTrace.log(
            this, "pause",
            "cause=$cause pkg=$pkg state=${controller.state}"
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configChangeInProgress = true
        // App-scoped rotation signal (#83 follow-up): the instance flag above
        // dies with this instance when the ROM destroys the IME service on
        // rotation (Oplus) — the timestamp survives and lets the next
        // instance's onStartInputView recognize the rotation.
        (application as PolishedRecognitionApp).imeConfigChangeMs = SystemClock.uptimeMillis()
        ImeLifecycleTrace.log(this, "onConfigurationChanged")
    }

    /**
     * #205 D3: the reconciliation anchor. `onStartInput` guarantees a valid
     * connection (unlike `onFinishInput`, which is not called on a nav-bar
     * close or `restartInput`), so it is where the field is probed after a
     * rebind. The app-wide [VoiceSessionController.livePreview] state says what
     * we last displayed; the probe says what the client really holds — and the
     * platform has normally committed our composition by now (`onFinishInput`
     * → `finishComposingText`), so re-sending it would be the forbidden
     * duplicate.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        attribute?.let { boundField = RotationGate.FieldId(it.packageName, it.fieldId) }
        reconcileLivePreviewAtBind(attribute)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Rotation (#83 v3): same field, views rebuilt — freeze the session
        // in its state instead of tearing it down. `restarting` alone is
        // unreliable (Oplus rebinds with restarting=false on client-app
        // rotation), and even the app-scoped gate can be stale here: the
        // rebind lands ~150 ms BEFORE onConfigurationChanged writes the
        // mark (proven by ime-lifecycle.log). So cancellation keys off field
        // identity alone — a same-view rebind can never be a genuine field
        // change — while [ImeStartDecision] defers only the PAUSED
        // auto-resume until the mark has had time to arrive.
        val app = application as PolishedRecognitionApp
        editorPackage = info.packageName
        boundField = RotationGate.FieldId(info.packageName, info.fieldId)
        val now = SystemClock.uptimeMillis()
        val currentField = RotationGate.FieldId(info.packageName, info.fieldId)
        val previousField = app.imeLastField
        val sameField = RotationGate.isSameField(previousField, currentField)
        // #192: does the paused take belong to the app bound now? The
        // controller's recorded package survives process death, so it is
        // preferred; a pause without one (a pre-#192 snapshot, an unreadable
        // caller) falls back to the last served field.
        val sameTarget = controller.pausedPackage()?.let { it == currentField.packageName }
            ?: (previousField?.packageName == currentField.packageName)
        val gateFresh = RotationGate.isFresh(app.imeConfigChangeMs, now)
        val outcome = ImeStartDecision.decide(
            controller.state, sameField, restarting && configChangeInProgress,
            gateFresh
        )
        // #192: the entry decision is a pure function of the state and the
        // TARGET app. Computed here (before the outcome switch) so the trace,
        // the tests and the provisional (rotation-deferred) resume all read
        // the SAME value.
        val action = AutoStartPolicy.entryAction(
            state = controller.state,
            sameTarget = sameTarget,
            hasMicPermission = hasMicPermission()
        )
        lastEntryAction = action
        configChangeInProgress = false
        app.imeLastField = currentField
        ImeLifecycleTrace.log(
            this, "onStartInputView",
            "restarting=$restarting state=${controller.state} outcome=$outcome " +
                "action=$action pausedPkg=${controller.pausedPackage()} " +
                "sameTarget=$sameTarget restored=$restoredSnapshot " +
                "sameField=$sameField gateFresh=$gateFresh " +
                RebindChurn.churnLine(
                    renders, foregroundAssertions, notificationPosts, breathStarts
                )
        )
        resetChurnCounters()
        restoredSnapshot = false
        // #207: a bind is the next readable moment after an UNREADABLE send —
        // the held result is delivered into its origin field here, or
        // salvaged when the user has since bound a different one (#192). The
        // attempt only fires for an IDLE controller; a live take keeps it.
        attemptHeldDelivery("bind")
        // #177: a genuine field change ends the post-send park — the parked
        // bar belongs to the field the dictation was sent into. Same-field
        // rebinds (hide/show, rotation) keep it: the keyboard comes back
        // parked instead of starting a new recording by itself.
        if (!sameField && postSendParked) {
            applyParkingEvent(PostSendParkingPolicy.Event.FIELD_CHANGED)
        }
        if (outcome == ImeStartDecision.Outcome.CANCEL) {
            // #205 D3: a field change during RECORDING is a non-delivering
            // terminal path. The connection already points at the NEW field,
            // so the old composition is unreachable — a K left in the old
            // field is the accepted COMMITTED_BY_APP family, and nothing may
            // be written into the new one.
            endLivePreviewWithoutWriting()
            controller.cancel()
        }
        refreshQuickSettings(info.packageName)
        applyUiState()
        when (outcome) {
            ImeStartDecision.Outcome.FREEZE -> {
                // Re-assert foreground for a live session: the previous
                // instance may have died or released FGS on teardown, and
                // the session has no visible UI of its own yet — without FGS
                // Oplus may kill the process mid-rotation. #118 Phase C: the
                // assert is now per-instance (`foregroundAsserted`), so the
                // repeated cycles of ONE storm no longer re-enter
                // startForeground; a recreated instance still does.
                if (controller.state != VoiceSessionController.State.IDLE) {
                    startMicForeground()
                }
                return
            }
            ImeStartDecision.Outcome.PROVISIONAL_FREEZE -> {
                scheduleProvisionalResume(action)
                return
            }
            ImeStartDecision.Outcome.CANCEL,
            ImeStartDecision.Outcome.PROCEED -> Unit
        }
        if (action != AutoStartPolicy.EntryAction.NONE) requestAutoStart(action)
    }

    /**
     * #165: routes an automatic start through the window-draw gate. A window
     * that is already on screen (field-to-field switches, a warm return)
     * starts immediately as before; a cold start parks the action until the
     * input view has been drawn, because the microphone foreground service
     * would otherwise be denied its while-in-use type and record silence.
     */
    private fun requestAutoStart(action: AutoStartPolicy.EntryAction) {
        // #177: while the bar is parked after a send, no automatic start
        // fires — the #165 draw gate below must not resurrect it either, so
        // the park is checked before anything is armed.
        if (postSendParked) {
            ImeLifecycleTrace.log(
                this, "autostart",
                "suppressed action=$action reason=post-send-parked state=${controller.state}"
            )
            return
        }
        if (AutoStartTiming.step(windowDrawn, timedOut = false) == AutoStartTiming.Step.RUN) {
            runAutoStart(action)
            return
        }
        pendingAutoStart = action
        recTickHandler.removeCallbacks(windowDrawTimeout)
        recTickHandler.postDelayed(windowDrawTimeout, AutoStartTiming.WINDOW_DRAW_TIMEOUT_MS)
        ImeLifecycleTrace.log(
            this, "autostart",
            "deferred action=$action state=${controller.state} drawn=$windowDrawn"
        )
    }

    /**
     * #165: the input view drew its first frame — the window is on screen, so
     * a parked auto-start may run now (posted from the draw callback, never
     * inside it).
     */
    internal fun onInputViewDrawn() {
        if (windowDrawn) return
        windowDrawn = true
        val pending = pendingAutoStart
        if (pending == null) {
            ImeLifecycleTrace.log(this, "firstDraw", "state=${controller.state}")
            return
        }
        pendingAutoStart = null
        recTickHandler.removeCallbacks(windowDrawTimeout)
        ImeLifecycleTrace.log(
            this, "firstDraw",
            "run=$pending state=${controller.state} timedOut=false"
        )
        runAutoStart(pending)
    }

    /** #165: a user action supersedes a parked auto-start. */
    private fun cancelPendingAutoStart() {
        if (pendingAutoStart == null) return
        pendingAutoStart = null
        recTickHandler.removeCallbacks(windowDrawTimeout)
        ImeLifecycleTrace.log(this, "autostart", "cancelled")
    }

    /**
     * #177: one transition of the post-send park flag ([PostSendParkingPolicy]).
     * Repaints when the decision asks for parked controls and whenever the
     * flag itself changed — unparking must show the normal idle bar again
     * (send re-enabled), not a stale parked one.
     */
    private fun applyParkingEvent(event: PostSendParkingPolicy.Event) {
        val wasParked = postSendParked
        val parking = PostSendParkingPolicy.decide(controller.state, postSendParked, event)
        postSendParked = parking.parked
        ImeLifecycleTrace.log(
            this, "postSend",
            "event=$event parked=$postSendParked render=${parking.render} state=${controller.state}"
        )
        if (parking.render || postSendParked != wasParked) applyUiState()
    }

    private fun runAutoStart(action: AutoStartPolicy.EntryAction) {
        when (action) {
            AutoStartPolicy.EntryAction.RESUME -> {
                startMicForeground()
                controller.resume()
                scheduleMicGuard()
            }
            AutoStartPolicy.EntryAction.FRESH_START -> {
                // #134/#192: a PAUSED leftover entered in a DIFFERENT app is not
                // auto-resumed. #217: it is SECURED, not stalled — what the take
                // holds decides how ([ImeLeftoverPolicy]): the raw text when it
                // is the only value, the audio via the journal + the #185
                // recovery while untranscribed fragments are in the chain. The
                // fresh recording starts in every case, so the keyboard never
                // comes up stuck in the pause look (`kept=…`, >1 min on device).
                if (controller.state == VoiceSessionController.State.PAUSED) {
                    val pausedMs = controller.recordedDurationMs()
                    when (
                        ImeLeftoverPolicy.decide(
                            hasTranscripts = controller.salvageableText() != null,
                            untranscribedAudio = controller.untranscribedAudioPending(),
                            hasBufferedAudio = pausedMs > 0L
                        )
                    ) {
                        ImeLeftoverPolicy.Action.SAVE_TEXT_AND_CANCEL -> {
                            // The raw text is the only value: into the Workbench
                            // it goes, then the #113 cancel (clipboard + toast
                            // for the same text) deletes the fully-transcribed
                            // journal — nothing untranscribed is left to lose.
                            val salvage = controller.salvageableText()!!
                            (application as PolishedRecognitionApp).dictationStore.append(salvage)
                            controller.cancel()
                        }
                        ImeLeftoverPolicy.Action.RELEASE_TO_RECOVERY -> {
                            val released = controller.releaseToRecovery()
                            if (released == null) {
                                // #152: the journal could NOT secure the audio —
                                // releasing would be the silent loss the app
                                // must never commit. The old kept-guard applies:
                                // the take stays paused, trash = explicit discard.
                                ImeLifecycleTrace.log(
                                    this, "pause",
                                    "cause=foreign-app kept=audio-without-transcripts " +
                                        "pausedMs=$pausedMs"
                                )
                                return
                            }
                            ImeLifecycleTrace.log(
                                this, "pause",
                                "cause=foreign-app released-to-recovery " +
                                    "pausedMs=$pausedMs session=$released"
                            )
                            startIfPermitted()
                            // The #207 surface, readable while the new take records.
                            Toasts.show(
                                this,
                                getString(
                                    R.string.ime_leftover_saved,
                                    RecTimeFormatter.recording(pausedMs)
                                )
                            )
                            kickRecovery(released)
                            return
                        }
                        ImeLeftoverPolicy.Action.CANCEL_ONLY -> controller.cancel()
                    }
                }
                startIfPermitted()
            }
            AutoStartPolicy.EntryAction.NONE -> Unit
        }
    }

    /**
     * #217: the out-of-band transcription kick for a released take. A manual
     * run ([RecoveryCoordinator.startManual]) is always allowed and yields to
     * a live dictation at every fragment boundary (#185), so the recovery of
     * the released journal starts at the cross-app moment and completes as
     * soon as the fresh take's own uploads are done. The `onFinished` slot is
     * deliberately NOT touched — it belongs to the visible screen (the
     * Workbench); a failed kick stays a recoverable leftover behind the
     * Workbench banner. `open` for the tests: the app singleton's coordinator
     * would attempt REAL requests from a unit-test JVM.
     */
    protected open fun kickRecovery(sessionId: String) {
        (application as PolishedRecognitionApp).recoveryCoordinator.startManual(sessionId)
    }

    /**
     * Finalizes a [ImeStartDecision.Outcome.PROVISIONAL_FREEZE]: the bind saw
     * PAUSED on the same field with no rotation signal yet, but the mark may
     * simply be late (Oplus delivers the rebind ~150 ms before
     * `onConfigurationChanged`). After [PROVISIONAL_RESUME_DELAY_MS] a still
     * stale gate means a genuine return (hide/show, switch back); a fresh
     * gate confirms rotation (stay frozen). No-ops unless still PAUSED, so
     * user taps in the window (send/cancel/resume) always win; sequential
     * main-thread execution makes double-resume impossible.
     *
     * #192: the held action comes from [AutoStartPolicy] — the reason still
     * decides, so this delayed path can never become the "a pause resumes by
     * itself" leak it used to be.
     */
    private fun scheduleProvisionalResume(action: AutoStartPolicy.EntryAction) {
        pendingProvisional?.let { recTickHandler.removeCallbacks(it) }
        startGen++
        val gen = startGen
        val app = application as PolishedRecognitionApp
        val check = Runnable {
            if (gen != startGen) return@Runnable
            pendingProvisional = null
            val fresh = RotationGate.isFresh(app.imeConfigChangeMs, SystemClock.uptimeMillis())
            ImeLifecycleTrace.log(
                this, "resumeDecision",
                "state=${controller.state} gateFresh=$fresh action=$action"
            )
            if (fresh) return@Runnable
            if (controller.state != VoiceSessionController.State.PAUSED) return@Runnable
            if (!hasMicPermission()) return@Runnable
            if (action == AutoStartPolicy.EntryAction.NONE) return@Runnable
            runAutoStart(action)
        }
        pendingProvisional = check
        recTickHandler.postDelayed(check, PROVISIONAL_RESUME_DELAY_MS)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Rotation (#83): the AudioRecord thread is view-independent and keeps
        // capturing — pausing here would punch a hole into the recording.
        // The app-scoped gate covers service recreation (#83 follow-up).
        val app = application as PolishedRecognitionApp
        val gateFresh = RotationGate.isFresh(app.imeConfigChangeMs, SystemClock.uptimeMillis())
        ImeLifecycleTrace.log(
            this, "onFinishInputView",
            "state=${controller.state} finishing=$finishingInput " +
                "inst=$configChangeInProgress gate=$gateFresh"
        )
        if (configChangeInProgress) return
        if (gateFresh) return
        // #192: a genuine dismissal — it ends the post-send park and pauses a
        // live take with its reason (see onKeyboardDismissed).
        onKeyboardDismissed()
    }

    private fun setupLanguageSpinner() {
        languageSpinner?.let { sp ->
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    if (silenceLangListener) return
                    val selected = parent?.getItemAtPosition(position) as? String ?: return
                    settings.targetLanguage =
                        if (selected == LanguageOptions.NONE_TARGET_LANGUAGE) null else selected
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun refreshQuickSettings(callerPackage: String? = currentCallerPackage()) {
        val langs = buildLanguageList()
        languageSpinner?.let { sp ->
            silenceLangListener = true
            sp.adapter = ArrayAdapter(
                this, R.layout.ime_spinner_item, langs
            ).also { it.setDropDownViewResource(R.layout.ime_spinner_dropdown_item) }
            val current = settings.targetLanguage ?: LanguageOptions.NONE_TARGET_LANGUAGE
            val idx = langs.indexOf(current).let { if (it >= 0) it else 0 }
            sp.setSelection(idx)
            silenceLangListener = false
        }
        // #168 (epic #166 D7): dictating into our own Workbench always runs
        // raw. #211: the disabled toggle shows the EFFECTIVE state of this
        // context — unchecked inside our own app, where no polish is ever
        // delivered — never the global preference, which a checked-but-
        // disabled box would claim for a take that stays raw.
        llmForcedOff = LlmTargetPolicy.forcesRaw(callerPackage, packageName)
        silenceLangListener = true
        llmCheckbox?.isChecked = !llmForcedOff && !settings.rawMode
        llmCheckbox?.isEnabled = !llmForcedOff
        llmCheckbox?.alpha = if (llmForcedOff) 0.4f else 1.0f
        silenceLangListener = false
        updateLanguageEnabled()
    }

    private fun buildLanguageList(): List<String> =
        LanguageOptions.buildLanguageList(settings.customLanguages)

    private fun updateLanguageEnabled() {
        // #154: the spinner is only usable when the LLM runs — without it there
        // is no translation/polish step the language could target.
        // #168: forced raw inside our own app disables it too.
        val disabled = llmForcedOff || llmCheckbox?.isChecked != true
        languageSpinner?.isEnabled = !disabled
        languageSpinner?.alpha = if (disabled) 0.4f else 1.0f
    }

    /**
     * Starts a take if the microphone is granted. `internal open` so the #177
     * regression tests can count the request instead of executing it — a live
     * capture under Robolectric fills the shadow buffer at CPU speed
     * (`ImeCompletedHandoverTest`; `ImeAutoStartGateTest` covers the real
     * start).
     */
    internal open fun startIfPermitted() {
        // #165: an explicit start supersedes a parked auto-start.
        cancelPendingAutoStart()
        if (hasMicPermission()) {
            // Learn the dictation target when the dictation starts, not only on
            // insertion (#102 amendment): the currently active app must be the
            // top/pre-selected entry of the per-app dropdown while dictating,
            // e.g. when the gear is tapped mid-session and Settings opens.
            currentCallerPackage()?.let { settings.recordKnownApp(it) }
            startMicForeground()
            controller.start { handleEvent(it) }
            scheduleMicGuard()
        } else {
            startActivity(
                Intent(this, MicrophonePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * #165 layer 2: verify shortly after a capture began that the platform is
     * really delivering audio (see [MicAccessPolicy]). A blocked microphone —
     * a denied while-in-use grant on a ROM with different timing, the privacy
     * toggle, a revoked app-op — aborts the take instead of uploading a
     * noise-only session that transcribes to "".
     */
    private fun scheduleMicGuard() {
        if (micGuardPending) return
        micGuardPending = true
        recTickHandler.removeCallbacks(micGuard)
        recTickHandler.postDelayed(micGuard, MicAccessPolicy.CHECK_DELAY_MS)
    }

    private fun checkMicAccess() {
        micGuardPending = false
        if (controller.state != VoiceSessionController.State.RECORDING) return
        // minSdk 30: `checkOpNoThrow` is the public replacement for the
        // deprecated `unsafeCheckOpNoThrow` (deprecated in API 29).
        val mode = runCatching {
            getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow(
                    AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), packageName
                )
        }.getOrNull() ?: return
        val silenced = runCatching { controller.isCaptureSilenced() }.getOrDefault(false)
        val capturing = runCatching { controller.isCaptureRunning() }.getOrDefault(true)
        val blocked = MicAccessPolicy.isBlocked(mode, silenced, capturing)
        ImeLifecycleTrace.log(
            this, "micGuard",
            "mode=$mode silenced=$silenced capturing=$capturing blocked=$blocked " +
                "drawn=$windowDrawn"
        )
        if (!blocked) return
        // #205 I1: the take is aborted — clear its preview before the cancel.
        abandonLivePreview(restoreSelection = true)?.let { showToast(it) }
        controller.cancel()
        Toasts.show(this, getString(R.string.ime_mic_blocked))
    }

    /** Module-visible since #118 Phase C: the tests drive the coalescing through it. */
    internal fun handleEvent(event: VoiceSessionController.Event) {
        when (event) {
            is VoiceSessionController.Event.StateChanged -> {
                applyUiState()
                when (event.state) {
                    VoiceSessionController.State.RECORDING -> startMicForeground()
                    VoiceSessionController.State.PROCESSING ->
                        updateNotification(getString(R.string.processing_notification))
                    VoiceSessionController.State.PAUSED ->
                        // #218: a parked take holds the mic FGS up (#83 — Oplus
                        // reaps the process without it), but the mic is OFF.
                        // The notification must say so: paused text + pause
                        // icon; the text change also yields a fresh timestamp
                        // under #118 churn suppression.
                        updateNotification(
                            getString(R.string.paused_notification),
                            R.drawable.ic_notification_pause
                        )
                    VoiceSessionController.State.IDLE -> {
                        // #205 I1: every path that ends the take without a
                        // delivery (a cancel, an external teardown) clears the
                        // composition on the connection that streamed it. The
                        // terminal step already ended the preview state, so a
                        // delivered session is a no-op here.
                        abandonLivePreview(restoreSelection = true)?.let { showToast(it) }
                        stopMicForeground()
                    }
                    else -> Unit
                }
            }
            is VoiceSessionController.Event.DisplayChanged -> handleDisplayChanged(event)
            is VoiceSessionController.Event.StageChanged -> {
                stageText?.text = when (val stage = event.stage) {
                    is TranscriptionPipeline.TranscriptionStage.CompressingAudio ->
                        getString(R.string.ime_stage_compressing)
                    is TranscriptionPipeline.TranscriptionStage.RequestingStt ->
                        getString(R.string.ime_stage_stt)
                    // #116: the audio length of the fragment going out now.
                    is TranscriptionPipeline.TranscriptionStage.RequestingSttFragment ->
                        getString(R.string.ime_stage_stt_seconds, stage.audioSeconds)
                    is TranscriptionPipeline.TranscriptionStage.RequestingLlm ->
                        resources.getQuantityString(
                            R.plurals.ime_stage_llm_words,
                            stage.wordCount,
                            stage.wordCount
                        )
                }
            }
            is VoiceSessionController.Event.CancelledWithTranscripts -> {
                // #205 I1: the take is discarded — its preview must not stay in
                // the field. Clear the composition and restore the selection
                // snapshot (where one exists) BEFORE the salvage Toast.
                abandonLivePreview(restoreSelection = true)?.let { showToast(it) }
                // #113 salvage: the cancel destroyed a session whose fragments
                // were already transcribed — copy the joined text to the
                // clipboard so the dictation is recoverable. Never inject
                // into the (possibly changed) editor field.
                runCatching {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            getString(R.string.cancelled_dictation_clip),
                            event.text
                        )
                    )
                }
                Toasts.show(
                    this,
                    resources.getQuantityString(
                        R.plurals.cancelled_dictation_saved,
                        event.text.length,
                        event.text.length
                    )
                )
            }
            is VoiceSessionController.Event.Completed -> {
                val result = event.result
                val text = result.getOrNull()
                if (text != null) {
                    handleCompletedSuccess(text, event)
                } else {
                    // #205 I1: a failed/still-parked session inserts nothing,
                    // so the provisional text must leave the field — clear the
                    // composition and restore the selection snapshot first.
                    // A failed restore is SAID in the same Toast as the failure.
                    val warning = abandonLivePreview(restoreSelection = true)
                    val message = listOfNotNull(failureText(result.exceptionOrNull()), warning)
                        .joinToString("\n")
                    showToast(message)
                }
                applyUiState()
            }
        }
    }

    /**
     * #183: the failure Toast text. The technical detail stays visible (the
     * user must see WHAT broke — CONVENTIONS), prefixed with the actionable
     * hint for the classes that have one (a dead token, an unusable response).
     */
    internal fun failureText(error: Throwable?): String {
        val detail = error?.message ?: getString(R.string.ime_error)
        val failure = (error as? SttRequestRunner.SttRequestException)?.failure
        val hint = SttFailureText.resFor(failure) ?: return detail
        return getString(hint) + "\n" + detail
    }

    /**
     * #174: a successful send inserts the text and does **nothing else** to
     * the keyboard's presence. The voice keyboard stays visible and stays the
     * default IME — exactly like a typing keyboard, which holds its place
     * until the user dismisses it with the platform gesture (navigation-bar
     * down arrow / Back). The old #133 handover (`switchInputMethod` +
     * `requestHideSelf`) is gone: leaving the voice keyboard is the X's job
     * (#155, contract in #175).
     *
     * #177: it does change what the keyboard *shows* — the bar parks (see
     * [PostSendParkingPolicy]) so the next automatic bind cannot silently
     * start a new recording.
     *
     * The controller has already stopped the recording when this runs and
     * emits `Completed` before `IDLE`, so this only ever fires on a finished,
     * successfully inserted session.
     *
     * #186: a RESCUED delivery ([VoiceSessionController.Event.Completed.partial])
     * is not a clean success — it inserts the recovered text only when there is
     * any and always shows [rescueText], so an empty rescue can never look like
     * a silent success.
     *
     * #207: a field holding our committed streamed text counts as
     * already-delivered ONLY when the delivery is identical (raw mode). A
     * differing polished Q gets the verified streamed span deleted
     * ([LivePreviewTerminal] → [StreamOutcome.COMMITTED_STREAM_REPLACED]) and
     * is then inserted here, so Send leaves exactly Q in the field.
     */
    internal fun handleCompletedSuccess(
        text: String,
        event: VoiceSessionController.Event.Completed
    ) {
        // #205 D4: classify the field (and abandon the composition) BEFORE
        // anything reaches it. The outcome decides which today-path runs.
        val outcome = finishLivePreview(text)
        logInsertion(text, event, outcome)
        val partial = event.partial
        val messages = mutableListOf<String>()
        when (outcome) {
            StreamOutcome.DISABLED,
            StreamOutcome.CLEARED,
            StreamOutcome.COMMITTED_STREAM_REPLACED -> {
                // Today's single terminal insertion — the field is either
                // verifiably cleared (B only), was never written by us, or our
                // verified streamed span was just deleted for exactly this
                // delivery (#207).
                if (partial == null || text.isNotBlank()) insertResultText(text)
            }
            StreamOutcome.UNREADABLE -> {
                // #186: an empty rescue inserts (and holds) nothing — its
                // Toast below explains where the audio waits.
                val insertable = partial == null || text.isNotBlank()
                if (insertable && !fieldReadable()) {
                    // #207: no connection — `commitWithSpacing` would
                    // silently no-op, exactly the device drop (two sends
                    // parked `outcome=UNREADABLE` and the resume tap cleared
                    // them with nothing delivered anywhere). HOLD the result
                    // instead: delivered into the origin field at the next
                    // readable moment, or salvaged to the clipboard. Never
                    // silently discarded.
                    holdUnreadableDelivery(text)
                } else if (insertable) {
                    insertResultText(text)
                }
            }
            StreamOutcome.COMMITTED_BY_APP -> {
                // The field already holds the whole provisional text; a blind
                // insert would be the forbidden duplicate (I5).
                copyToClipboard(text)
                messages += getString(R.string.ime_raw_kept)
            }
            StreamOutcome.SUFFIX_APPENDED -> {
                // The raw suffix was appended through the verified prefix case;
                // the polished text can only go to the clipboard (never a
                // guessed polished suffix — the LLM output is not aligned).
                copyToClipboard(text)
                messages += getString(R.string.ime_polished_copied)
            }
            StreamOutcome.MISMATCH -> {
                // Never delete, never insert into a field we cannot classify.
                // A dedicated wording: the field is INCOMPLETE, so
                // `ime_raw_kept` would be a lie (owner's rule).
                copyToClipboard(text)
                messages += getString(R.string.ime_stream_incomplete)
            }
        }
        // #186: a raw-mode rescue is not a clean success — a fragment is
        // missing. Insert what was recovered (an EMPTY rescue inserts nothing
        // at all), always say why plus where the rest is. ONE Toast: the
        // preview's message and the rescue's share it.
        if (partial != null) messages += rescueText(partial)
        if (messages.isNotEmpty()) {
            Toasts.show(this, messages.joinToString("\n"))
        }
        // Re-learn on successful insertion (#100): the session already
        // recorded the target at start (#102 amendment); this captures
        // the actual insertion target and is absorbed by the throttle.
        currentCallerPackage()?.let { settings.recordKnownApp(it) }
        // #177: park the bar for the idle controller. The `Completed` event
        // arrives before `StateChanged(IDLE)`, so the parked rendering shows
        // up with that state change; a still-armed #165 auto-start is dropped
        // so it cannot start a new take behind the parked bar.
        cancelPendingAutoStart()
        applyParkingEvent(PostSendParkingPolicy.Event.COMPLETED_SUCCESS)
    }

    /**
     * #205: the editor seam. Every composing write, probe and the terminal
     * suffix go through it; the `insertResultText` override seam above still
     * serves the four existing Robolectric classes. Overridable so the preview
     * tests can drive a recording port instead of an `InputConnection` shadow.
     */
    internal open fun editorPort(): EditorPort? = EditorPort.of(currentInputConnection)

    /**
     * #207: can the CURRENT binding receive a delivery? Overridable so the
     * held-delivery tests decide readability without an `InputConnection`
     * shadow (a Robolectric service never goes through `doStartInput`).
     */
    internal open fun fieldReadable(): Boolean = currentInputConnection != null

    /**
     * #207: an UNREADABLE send's result is HELD, not dropped (the device
     * defect: two sends parked `outcome=UNREADABLE`, the resume taps cleared
     * the park and no text ever arrived anywhere). The origin field is the
     * one the Send press recorded (#118); without one, [HeldDeliveryPolicy]
     * later reads "foreign field" → clipboard, never a blind insertion
     * (#192). A second hold supersedes the first — the older result is
     * salvaged NOW so the two can never arrive out of order. One Toast says
     * the text is waiting; the trace line makes the hold diagnosable.
     */
    private fun holdUnreadableDelivery(text: String) {
        if (heldDelivery != null) salvageHeldDelivery("superseded")
        heldDelivery = text
        heldOrigin = (application as PolishedRecognitionApp).imeSessionField ?: boundField
        ImeLifecycleTrace.log(this, "heldDelivery", "held chars=${text.length}")
        showToast(getString(R.string.ime_delivery_pending))
    }

    /**
     * #207: delivers or salvages the held result at a readable moment. Only
     * an IDLE controller may receive it — a live or paused take owns the
     * field now, and a field the user has since repurposed is not auto-
     * written (#192): the hold waits for an IDLE bind of the ORIGIN field,
     * everything else goes to the clipboard with a toast naming where the
     * text is. Never silently discarded.
     */
    private fun attemptHeldDelivery(trigger: String) {
        val text = heldDelivery ?: return
        if (controller.state != VoiceSessionController.State.IDLE) {
            ImeLifecycleTrace.log(
                this, "heldDelivery", "keep trigger=$trigger state=${controller.state}"
            )
            return
        }
        val current = boundField
        val readable = fieldReadable() && current != null
        when (HeldDeliveryPolicy.decide(heldOrigin, current, readable)) {
            HeldDeliveryPolicy.Action.KEEP ->
                ImeLifecycleTrace.log(
                    this, "heldDelivery", "keep trigger=$trigger state=${controller.state}"
                )
            HeldDeliveryPolicy.Action.DELIVER -> {
                heldDelivery = null
                heldOrigin = null
                insertResultText(text)
                ImeLifecycleTrace.log(
                    this, "heldDelivery", "delivered trigger=$trigger chars=${text.length}"
                )
            }
            HeldDeliveryPolicy.Action.SALVAGE -> salvageHeldDelivery(trigger)
        }
    }

    /**
     * #207: the clipboard fallback — the result never reached a field (or
     * reached the wrong one), so the user is TOLD where the text is now.
     */
    private fun salvageHeldDelivery(reason: String) {
        val text = heldDelivery ?: return
        heldDelivery = null
        heldOrigin = null
        copyToClipboard(text)
        showToast(getString(R.string.ime_delivery_salvaged))
        ImeLifecycleTrace.log(
            this, "heldDelivery", "salvaged reason=$reason chars=${text.length}"
        )
    }

    /**
     * #205 D3: reconciliation after a rebind — the events give the timing, this
     * probe gives the truth. Three window cases: `B` only ⇒ resend the whole
     * provisional (nothing was committed), `B + K` ⇒ compose only the delta,
     * anything else ⇒ the display is abandoned and the terminal classifies.
     *
     * Verified re-composition is allowed exactly here (owner, 2026-11-25);
     * an unverified reconstruction stays forbidden (I4).
     *
     * #207 (workstream 3, owner decision "BOTH"): the whole-provisional-
     * committed case — `B + P` after any unbind/rebind mid-take (gear detour,
     * pause rebind, keyboard switch) — is the platform committing the
     * composition (`onFinishInput` → `finishComposingText`, AOSP default;
     * platform behaviour, NOT a preview bug). Before this workstream it
     * re-composed nothing and the provisional rendered NOT underlined until
     * the next fragment arrived — during a paused take, for the whole pause.
     * That bind case now attempts the guarded underline restore in
     * [attemptRecomposeAtBind]; every other case keeps the behaviour above.
     */
    private fun reconcileLivePreviewAtBind(attribute: EditorInfo?) {
        val stream = controller.livePreview
        if (!stream.active || !stream.enabled || stream.aborted || !stream.streamed) return
        val info = attribute ?: return
        val field = RotationGate.FieldId(info.packageName, info.fieldId)
        val bound = stream.field
        if (bound == null) {
            stream.field = field
        } else if (!RotationGate.isSameField(bound, field)) {
            // Another field bound: the composition in the old field is
            // unreachable and must not be touched (D3).
            stream.aborted = true
            ImeLifecycleTrace.log(
                this, "livePreview",
                "abort bind reason=field-changed state=${controller.state}"
            )
            return
        }
        val before = stream.before ?: return
        val port = editorPort() ?: return
        val probe = port.textBefore(
            LivePreviewReconcile.windowLengthForBind(before, stream.provisional)
        )
        val decision = LivePreviewReconcile.decide(
            window = probe,
            before = before,
            committedPrefix = stream.committedPrefix,
            sent = stream.sent,
            provisional = stream.provisional,
            atBind = true
        )
        if (decision.action != LivePreviewReconcile.Action.COMPOSE) {
            stream.aborted = true
            ImeLifecycleTrace.log(
                this, "livePreview",
                "abort bind reason=window state=${controller.state} " +
                    "before=${before.length} provisional=${stream.provisional.length} " +
                    "window=${probe?.length} tail=${probe?.takeLast(24)}"
            )
            return
        }
        // #207 (workstream 3): the exact B + P case — the whole provisional
        // sits as COMMITTED text behind B, the underline defect's signature
        // (`reconciled committed=934 composed=0`). Everything the restore
        // needs is already verified here: the stream is active and behind the
        // `imeLivePreview` kill switch, the bound field IS the preview's own
        // field (the field-changed guard above), and `decide` verified the
        // field holds exactly B + P. The guarded delete → read-back → compose
        // sequence runs in [attemptRecomposeAtBind].
        if (decision.composing.isEmpty() && decision.committedPrefix == stream.provisional) {
            attemptRecomposeAtBind(port, stream, before, info)
            return
        }
        if (decision.composing.isNotEmpty() || stream.sent.isNotEmpty()) {
            port.setComposing(decision.composing)
        }
        stream.committedPrefix = decision.committedPrefix
        stream.sent = decision.composing
        stream.reconciled = true
        ImeLifecycleTrace.log(
            this, "livePreview",
            "reconciled committed=${decision.committedPrefix.length} " +
                "composed=${decision.composing.length} field=${info.packageName}/${info.fieldId}"
        )
    }

    /**
     * #207 (workstream 3): the guarded underline restore after an unbind —
     * the composing region is rebuilt over the platform-committed provisional
     * so the text renders underlined again while the take is paused.
     *
     * The sequence is **read → delete → read-back → compose**, so a mismatch
     * can never mangle the field:
     *
     * 1. **read** — done by the caller: `decide` verified the field holds
     *    EXACTLY B + P, so the delete targets a span of OUR text and nothing
     *    else.
     * 2. **delete** — `deleteSurroundingText(P.length)` against that verified
     *    span. A REQUEST the client may honour differently ([EditorPort]).
     * 3. **read-back** — BEFORE any write, the same bind `decide` machinery
     *    re-verifies the field over a fresh probe. Only a read-back that
     *    shows B intact with NOTHING of ours behind the cursor may proceed;
     *    a field still holding the whole P means the delete was refused
     *    (today's behaviour — committed, no compose — is the fallback), and
     *    any other mismatch (deleted less, deleted more, field unreadable)
     *    aborts BEFORE the compose write and leaves the classification to
     *    the terminal step.
     * 4. **compose** — `setComposingText(P)` restores the underlined region.
     *
     * Guards, all upstream of the write: the kill switch and the own-field
     * check gate the whole reconcile; the exact B + P match gates the call;
     * the read-back gates the compose. Every abort logs its reason in the
     * existing `livePreview ...` lifecycle line style.
     */
    private fun attemptRecomposeAtBind(
        port: EditorPort,
        stream: LivePreview,
        before: String,
        info: EditorInfo
    ) {
        val provisional = stream.provisional
        val probeLength = LivePreviewReconcile.windowLengthForBind(before, provisional)
        port.deleteBefore(provisional.length)
        val readBack = port.textBefore(probeLength)
        val decision = LivePreviewReconcile.decide(
            window = readBack,
            before = before,
            committedPrefix = "",
            sent = "",
            provisional = provisional,
            atBind = true
        )
        if (decision.action != LivePreviewReconcile.Action.COMPOSE ||
            (decision.composing.isNotEmpty() && decision.composing != provisional)
        ) {
            // The client honoured the delete differently (less than the
            // verified span, into B, or the field went unreadable): never
            // write over a field we cannot verify — the terminal classifies
            // whatever the client actually holds.
            stream.aborted = true
            ImeLifecycleTrace.log(
                this, "livePreview",
                "abort re-compose reason=window deleted=${provisional.length} " +
                    "state=${controller.state} window=${readBack?.length} " +
                    "tail=${readBack?.takeLast(24)}"
            )
            return
        }
        if (decision.composing.isEmpty()) {
            // The delete was refused: the field still holds B + P committed,
            // byte-identical to before the attempt. Today's behaviour
            // (committed text, no compose) is the fallback — the terminal
            // keeps classifying it as already-delivered. The empty compose
            // today's path would send is skipped: it starts no region and
            // writes nothing.
            ImeLifecycleTrace.log(
                this, "livePreview",
                "abort re-compose reason=delete-refused requested=${provisional.length} " +
                    "state=${controller.state}"
            )
        } else {
            port.setComposing(decision.composing)
        }
        stream.committedPrefix = decision.committedPrefix
        stream.sent = decision.composing
        stream.reconciled = true
        ImeLifecycleTrace.log(
            this, "livePreview",
            "reconciled committed=${decision.committedPrefix.length} " +
                "composed=${decision.composing.length} field=${info.packageName}/${info.fieldId}" +
                if (decision.composing.isNotEmpty()) " recomposed=true" else ""
        )
    }

    /**
     * #205 D2: one display tick per finished fragment — resend the whole
     * provisional (minus a verified committed prefix) through the composing
     * region. A failed probe stops only the DISPLAY; the terminal still
     * classifies the field.
     */
    private fun handleDisplayChanged(event: VoiceSessionController.Event.DisplayChanged) {
        val stream = controller.livePreview
        if (!stream.active || !stream.enabled || stream.aborted) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            recTickHandler.post { handleDisplayChanged(event) }
            return
        }
        val field = currentFieldId()
        if (field == null) {
            stream.aborted = true
            return
        }
        val bound = stream.field
        if (bound == null) {
            stream.field = field
        } else if (!RotationGate.isSameField(bound, field)) {
            stream.aborted = true
            ImeLifecycleTrace.log(this, "livePreview", "abort tick reason=field-changed")
            return
        }
        val port = editorPort() ?: run {
            stream.aborted = true
            return
        }
        if (stream.before == null) {
            // I3: never write into a field we cannot read. The very first
            // peek is the gate; a null answer ends the preview for good.
            val peek = port.textBefore(LivePreviewReconcile.WINDOW) ?: run {
                stream.aborted = true
                ImeLifecycleTrace.log(this, "livePreview", "abort tick reason=unreadable")
                return
            }
            // #205 D6: the selection snapshot and B are captured together —
            // B is what sits LEFT OF THE COMPOSITION, i.e. without the
            // selected text the first composing write is about to replace.
            val window = peek.toString()
            captureSelectionSnapshot(port)
            val selected = selectionSnapshot?.text
            stream.before =
                if (selected != null && selected.isNotEmpty() && window.endsWith(selected.toString())) {
                    window.dropLast(minOf(selected.length, window.length))
                } else {
                    window
                }
            // Nothing of ours is in the field yet — the first append needs no
            // reconciliation (and the window still contains the selection the
            // write is about to replace, so it could not be reconciled anyway).
            port.setComposing(event.text)
            stream.streamed = true
            stream.sent = event.text
            stream.committedPrefix = ""
            logStreamAppend(event, sameField = true)
            return
        }
        val probe = port.textBefore(
            LivePreviewReconcile.windowLength(
                stream.before!!, stream.committedPrefix, stream.sent
            )
        )
        val decision = LivePreviewReconcile.decide(
            window = probe,
            before = stream.before!!,
            committedPrefix = stream.committedPrefix,
            sent = stream.sent,
            provisional = event.text,
            atBind = false
        )
        if (decision.action != LivePreviewReconcile.Action.COMPOSE) {
            stream.aborted = true
            ImeLifecycleTrace.log(
                this, "livePreview",
                "abort tick reason=window provisional=${event.text.length} " +
                    "window=${probe?.length} tail=${probe?.takeLast(24)}"
            )
            return
        }
        val write = decision.composing.isNotEmpty() || stream.sent.isNotEmpty()
        if (write) {
            port.setComposing(decision.composing)
            stream.streamed = true
            logStreamAppend(event, sameField = true)
        }
        stream.committedPrefix = decision.committedPrefix
        stream.sent = decision.composing
    }

    /**
     * #205 D6: the selection snapshot at session start. A field that exposes
     * no selection is normal (a plain cursor) — the preview streams anyway,
     * recorded as `selectionUnreadable`.
     */
    private fun captureSelectionSnapshot(port: EditorPort) {
        if (selectionCaptured) return
        selectionCaptured = true
        selectionSnapshot = port.selection()
        if (selectionSnapshot == null) controller.livePreview.selectionUnreadable = true
    }

    /**
     * #205 D4: the terminal step — abandon the composition, classify the field
     * (writing the one verified raw suffix the classification asks for, or
     * deleting the verified streamed span a differing delivery replaces
     * (#207)), end the streaming state, and return the outcome for the
     * delivery branch.
     */
    private fun finishLivePreview(delivery: String): StreamOutcome {
        val stream = controller.livePreview
        if (!stream.active) return StreamOutcome.DISABLED
        val outcome: StreamOutcome
        if (!stream.enabled) {
            outcome = StreamOutcome.DISABLED
        } else if (!stream.streamed) {
            // Enabled, but nothing ever reached the field (no fragment, or the
            // first probe failed) — today's path applies unchanged.
            outcome = StreamOutcome.UNREADABLE
        } else {
            outcome = terminalClassify(stream, delivery)
        }
        ImeLifecycleTrace.log(
            this, "livePreview",
            "terminal outcome=$outcome streamed=${stream.streamed} " +
                "aborted=${stream.aborted} reconciled=${stream.reconciled} " +
                "markers=${stream.pendingMarkers}"
        )
        stream.recordOutcome(outcome)
        stream.end()
        selectionSnapshot = null
        selectionCaptured = false
        return outcome
    }

    private fun terminalClassify(stream: LivePreview, delivery: String): StreamOutcome {
        val field = currentFieldId()
        val sameField = field != null && stream.field != null &&
            RotationGate.isSameField(stream.field, field)
        val port = if (sameField) editorPort() else null
        if (port == null) {
            // The field that streamed is gone (or unreadable). Never insert
            // into a foreign field; the caller sends Q to the clipboard.
            return StreamOutcome.MISMATCH
        }
        // Abandon the composition: it must never be committed as text.
        port.setComposing("")
        val probe = port.textBefore(
            LivePreviewTerminal.windowLength(stream.before, stream.provisional)
        )
        val decision = LivePreviewTerminal.classify(
            window = probe,
            before = stream.before,
            provisional = stream.provisional,
            delivery = delivery
        )
        if (decision.outcome == StreamOutcome.SUFFIX_APPENDED) {
            // Append-only (I2): the raw delta the client does not hold yet.
            port.commit(decision.append)
        } else if (decision.outcome == StreamOutcome.COMMITTED_STREAM_REPLACED) {
            return replaceStreamedSpan(port, stream, delivery, decision.deleteBefore)
        }
        return decision.outcome
    }

    /**
     * #207: the field holds OUR verified streamed text behind the cursor and
     * the polished delivery Q differs — delete the span, so the caller inserts
     * Q through today's path and the field ends with exactly Q (no duplicate,
     * no leftover). The delete is a REQUEST the client may honour differently
     * ([EditorPort]); writing Q over a surviving span would be the forbidden
     * duplicate (I5), so the deletion is verified with the same before-window
     * probe the classification used. A refused delete falls back to
     * [StreamOutcome.MISMATCH]: Q to the clipboard, the field untouched.
     */
    private fun replaceStreamedSpan(
        port: EditorPort,
        stream: LivePreview,
        delivery: String,
        chars: Int
    ): StreamOutcome {
        port.deleteBefore(chars)
        val verify = LivePreviewTerminal.classify(
            window = port.textBefore(
                LivePreviewTerminal.windowLength(stream.before, stream.provisional)
            ),
            before = stream.before,
            provisional = stream.provisional,
            delivery = delivery
        )
        if (verify.outcome != StreamOutcome.CLEARED) {
            ImeLifecycleTrace.log(
                this, "livePreview",
                "replace failed deleted=$chars got=${verify.outcome}"
            )
            return StreamOutcome.MISMATCH
        }
        ImeLifecycleTrace.log(this, "livePreview", "replace deleted=$chars verified=cleared")
        return StreamOutcome.COMMITTED_STREAM_REPLACED
    }

    /**
     * #205 I1: a non-delivering outcome clears the composition on the
     * connection that streamed it — and restores the selection snapshot where
     * one exists (only while the before-window still matches; otherwise the
     * snapshot goes to the clipboard and a Toast explains it).
     */
    private fun abandonLivePreview(restoreSelection: Boolean): String? {
        val stream = controller.livePreview
        if (!stream.active) return null
        val streamed = stream.streamed
        val before = stream.before
        val field = currentFieldId()
        val sameField = field != null && stream.field != null &&
            RotationGate.isSameField(stream.field, field)
        var warning: String? = null
        if (streamed && sameField) {
            val port = editorPort()
            if (port != null) {
                port.setComposing("")
                if (restoreSelection) warning = restoreSelectionSnapshot(port, before)
            }
        }
        stream.end()
        selectionSnapshot = null
        selectionCaptured = false
        return warning
    }

    /** #205 D6: put the snapshot back text-and-range, or return the warning. */
    private fun restoreSelectionSnapshot(port: EditorPort, before: String?): String? {
        val snapshot = selectionSnapshot ?: return null
        // The composition is already cleared. The conservative fingerprint
        // check stays: a wider-than-B window fails more often and falls back
        // to the clipboard — never restores into a field that changed.
        val window = port.textBefore(LivePreviewReconcile.WINDOW)
        if (before != null && window != null && window.toString() == before) {
            port.setComposing(snapshot.text)
            port.finishComposing()
            port.setSelection(snapshot.start, snapshot.end)
            return null
        }
        copyToClipboard(snapshot.text.toString())
        return getString(R.string.ime_selection_restore_failed)
    }

    /** #205 D3: the field changed — forget the preview without writing into the new one. */
    private fun endLivePreviewWithoutWriting() {
        controller.livePreview.end()
        selectionSnapshot = null
        selectionCaptured = false
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.live_preview_clip), text)
            )
        }
    }

    private fun showToast(text: String) {
        if (text.isBlank()) return
        Toasts.show(this, text)
    }

    /**
     * #186: the ONE Toast a rescued delivery shows — the actionable hint for
     * the failure class (when it has one), the technical reason, and where the
     * recording waits. Never a second, generic failure Toast: the text WAS
     * saved, just not completely transcribed.
     */
    internal fun rescueText(partial: VoiceSessionController.Event.PartialInfo): String {
        val parts = mutableListOf<String>()
        SttFailureText.resFor(partial.failure)?.let { parts += getString(it) }
        partial.message?.takeIf { it.isNotBlank() }?.let { parts += it }
        parts += getString(R.string.ime_rescue_saved)
        return parts.joinToString("\n")
    }

    /**
     * The single insertion point of a finished dictation (#116 option C: one
     * commit of the finished — already polished — text, never an incremental
     * one). Overridable so the #174 regression test can assert the insertion
     * without an `InputConnection`.
     */
    internal open fun insertResultText(text: String) = commitWithSpacing(text)

    /**
     * Deletes the last word before the cursor from the editor (#90):
     * trailing whitespace plus the preceding whitespace-delimited token
     * ([DeleteWordPolicy]). Independent of the recording session, so the
     * button stays enabled in every state.
     */
    private fun deleteLastWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(DELETE_WORD_LOOKBACK, 0) ?: return
        val n = DeleteWordPolicy.charsToDelete(before)
        if (n > 0) ic.deleteSurroundingText(n, 0)
    }

    /**
     * Clears the entire editor field (#90, long-press on the delete-word
     * button). Select-all + empty commit inside a batch edit; falls back
     * to a wide surrounding-text delete when the editor exposes no
     * extracted text (e.g. password fields).
     */
    private fun clearEditorField() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            val len = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length
            if (len != null && len > 0) {
                ic.setSelection(0, len)
                ic.commitText("", 1)
            } else if (len == null) {
                ic.deleteSurroundingText(CLEAR_FIELD_FALLBACK, CLEAR_FIELD_FALLBACK)
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Package of the app being dictated into (#100): the freshly focused
     * field's package when available, else the package cached at bind time.
     */
    private fun currentCallerPackage(): String? =
        currentInputEditorInfo?.packageName ?: editorPackage

    /**
     * Commits [text] padded with surrounding spaces as needed (#66): a leading
     * space when the cursor sits at field start or after non-whitespace, a
     * trailing space when nothing whitespace follows. A null side from
     * getTextBefore/AfterCursor (unsupported by some apps) counts as
     * "no whitespace present" so the space is still added.
     */
    private fun commitWithSpacing(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        ic.commitText(InsertionSpacingPolicy.apply(text, before, after), 1)
    }

    /**
     * #118 Phase A insertion breadcrumb: records the single editor-insertion
     * site so a "partial text appeared" repro is conclusive. Captures the
     * delivery reason (live / `pendingResult` re-delivery / raw-mode rescue),
     * the field at send vs at insertion (a changed field after rotation is
     * the prime suspect), the controller state, and whether an
     * `InputConnection` was available. Correlate by timestamp with
     * `ime-lifecycle.log`. Best-effort: logging must never break insertion.
     */
    private fun logInsertion(
        text: String,
        event: VoiceSessionController.Event.Completed,
        outcome: StreamOutcome
    ) {
        try {
            val app = application as PolishedRecognitionApp
            val origin = app.imeSessionField
            val current = currentFieldId()
            val reason = InsertionTrace.reason(
                redelivered = event.redelivered,
                partial = event.partial != null
            ).name
            val stream = controller.livePreview
            app.jsonLogger.log(
                "insertion",
                com.google.gson.Gson().toJson(
                    mapOf(
                        "surface" to "ime",
                        "reason" to reason,
                        "length" to text.length,
                        "failedIndex" to event.partial?.failedIndex,
                        "chunkCount" to event.partial?.chunkCount,
                        "state" to controller.state.name,
                        "originPkg" to origin?.packageName,
                        "originField" to origin?.fieldId,
                        "currentPkg" to current?.packageName,
                        "currentField" to current?.fieldId,
                        "sameField" to (origin != null && current != null &&
                            RotationGate.isSameField(origin, current)),
                        "hasConnection" to (currentInputConnection != null),
                        // #205: what the preview did with this field.
                        "streamed" to event.streamed,
                        "streamOutcome" to outcome.name,
                        "streamAborted" to stream.aborted,
                        "reconciled" to stream.reconciled,
                        "selectionUnreadable" to stream.selectionUnreadable
                    )
                )
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * #205: one `insertion.json` record per display APPEND — the composition
     * grew in the field. `reason=STREAM` separates these from the terminal
     * delivery record, so a device log proves the preview advanced and how
     * many marker gaps the displayed text carries.
     */
    private fun logStreamAppend(event: VoiceSessionController.Event.DisplayChanged, sameField: Boolean) {
        try {
            val app = application as PolishedRecognitionApp
            val current = currentFieldId()
            app.jsonLogger.log(
                "insertion",
                com.google.gson.Gson().toJson(
                    mapOf(
                        "surface" to "ime",
                        "reason" to InsertionReason.STREAM.name,
                        "length" to event.text.length,
                        "sameField" to sameField,
                        "pendingMarkers" to event.pendingMarkers,
                        "state" to controller.state.name,
                        "currentPkg" to current?.packageName,
                        "currentField" to current?.fieldId
                    )
                )
            )
        } catch (_: Throwable) {
        }
    }

    /** The focused field identity, or null when the editor info is absent (#118). */
    private fun currentFieldId(): RotationGate.FieldId? =
        currentInputEditorInfo?.let { RotationGate.FieldId(it.packageName, it.fieldId) }
            ?: boundField

    /**
     * #118 Phase C: a recreated input view must paint once, whatever the last
     * instance rendered — the equality check below is per view.
     */
    private fun invalidateUiRender() {
        uiSignature = null
    }

    /** #118 Phase C: open a fresh measurement interval (see [RebindChurn.churnLine]). */
    private fun resetChurnCounters() {
        renders = 0
        foregroundAssertions = 0
        notificationPosts = 0
        breathStarts = 0
    }

    private fun applyUiState() {
        val ms = sendButton ?: return
        val pr = pauseResumeButton ?: return
        val sw = switchButton ?: return
        val dw = deleteWordButton ?: return
        val fb = flushButton ?: return
        val gear = settingsGear ?: return
        val s = controller.state
        // #118 Phase C: everything below is a pure function of these three
        // inputs, so an identical signature would repaint identical pixels —
        // which is exactly what the rebind storm used to do every cycle.
        val signature = RebindChurn.UiSignature(s, postSendParked, llmForcedOff)
        if (!RebindChurn.render(uiSignature, signature)) return
        uiSignature = signature
        renders++
        setFlashing(s == VoiceSessionController.State.RECORDING)
        // Delete-word edits the editor, not the session — always enabled (#90).
        dw.isEnabled = true
        when (s) {
            VoiceSessionController.State.IDLE -> {
                ms.setImageResource(R.drawable.ic_send)
                ms.contentDescription = getString(R.string.ime_send_desc)
                // #177/#192: with nothing buffered there is exactly ONE bar,
                // whether the take was just sent (the #177 park) or never
                // started. Owner's call: "fresh idle should advance to a
                // parked at 00:00". So the plane is always disabled here
                // (nothing to send), the ↺ always starts a take, and the
                // timer always reads 0:00 — no state in which a control is
                // live without an effect, and no second no-take look.
                ms.isEnabled = false
                pr.setImageResource(R.drawable.ic_resume)
                pr.contentDescription = getString(R.string.ime_new_take_desc)
                pr.isEnabled = true
                sw.isEnabled = true
                fb.isEnabled = false
                gear.isEnabled = true
                setQuickSettingsEnabled(true)
                setQuickSettingsVisible(true)
            }
            VoiceSessionController.State.RECORDING -> {
                ms.setImageResource(R.drawable.ic_send)
                ms.contentDescription = getString(R.string.ime_send_desc)
                ms.isEnabled = true
                pr.setImageResource(R.drawable.ic_pause)
                pr.contentDescription = getString(R.string.ime_pause_desc)
                pr.isEnabled = true
                sw.isEnabled = true
                fb.isEnabled = true
                gear.isEnabled = true
                setQuickSettingsEnabled(true)
                setQuickSettingsVisible(true)
            }
            VoiceSessionController.State.PAUSED -> {
                ms.setImageResource(R.drawable.ic_send)
                ms.contentDescription = getString(R.string.ime_send_desc)
                ms.isEnabled = true
                pr.setImageResource(R.drawable.ic_resume)
                pr.contentDescription = getString(R.string.ime_resume_desc)
                pr.isEnabled = true
                sw.isEnabled = true
                fb.isEnabled = true
                gear.isEnabled = true
                setQuickSettingsEnabled(true)
                setQuickSettingsVisible(true)
            }
            VoiceSessionController.State.PROCESSING -> {
                ms.setImageResource(R.drawable.ic_send)
                ms.contentDescription = getString(R.string.ime_send_desc)
                ms.isEnabled = false
                pr.setImageResource(R.drawable.ic_pause)
                pr.contentDescription = getString(R.string.ime_pause_desc)
                pr.isEnabled = false
                sw.isEnabled = false
                fb.isEnabled = false
                gear.isEnabled = false
                setQuickSettingsEnabled(false)
                setQuickSettingsVisible(false)
            }
        }
        // #201/#204 (owner): a bar with nothing buffered looks like a plain
        // PAUSE at 0:00 — the timer is the only text. The stage line belongs
        // to PROCESSING alone (its events write it); no sentence explains the
        // ↺, whose content description already says "New take".
        stageText?.visibility =
            if (s == VoiceSessionController.State.PROCESSING) View.VISIBLE else View.GONE
        updateRecTimer(s)
        updateLanguageEnabled()
    }

    /**
     * Time counter (#71, #90): ticking plain "m:ss" during RECORDING (active
     * mic time only, pauses excluded via
     * [VoiceSessionController.recordedDurationMs]) — no REC prefix, the
     * pulse signals the state — frozen "m:ss" while PAUSED, hidden otherwise.
     */
    private fun updateRecTimer(s: VoiceSessionController.State) {
        when (s) {
            VoiceSessionController.State.RECORDING -> {
                recTimer?.text = RecTimeFormatter.recording(controller.recordedDurationMs())
                recTimer?.visibility = View.VISIBLE
                recTickHandler.removeCallbacks(recTick)
                recTickHandler.postDelayed(recTick, REC_TICK_MS)
            }
            VoiceSessionController.State.PAUSED -> {
                recTickHandler.removeCallbacks(recTick)
                recTimer?.text = RecTimeFormatter.paused(controller.recordedDurationMs())
                recTimer?.visibility = View.VISIBLE
            }
            // #192: the no-take bar is "parked at 0:00" (owner's wording) —
            // one look for "nothing buffered" instead of a hidden timer.
            VoiceSessionController.State.IDLE -> {
                recTickHandler.removeCallbacks(recTick)
                recTimer?.text = RecTimeFormatter.paused(0L)
                recTimer?.visibility = View.VISIBLE
            }
            else -> {
                recTickHandler.removeCallbacks(recTick)
                recTimer?.visibility = View.GONE
            }
        }
    }

    // #194: this touch listener always returns false and only cancels the
    // hint Toast — the click itself is an OnClickListener on the button, so
    // performClick accessibility is untouched.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressHint(button: ImageButton?, hintRes: Int) {
        button?.setOnLongClickListener {
            showHintToast(getString(hintRes))
            // Consumed: a long-press shows help and must not also tap.
            true
        }
        // Dismiss the hint the moment the finger lifts so it is visible
        // only while pressed (#88). This listener returns false and never
        // alters the IME layout, so single taps work exactly as before —
        // the previous stage-line approach shifted the bar mid-tap and
        // broke them.
        button?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                Toasts.dismissHint()
            }
            false
        }
    }

    /**
     * Single reused hint Toast (#88): creating a new Toast per long-press
     * churns show/cancel through the notification manager and stops
     * appearing after a few consecutive presses — one instance with
     * setText() does not. Since #207 the reuse mechanics live in
     * [Toasts.hint]; this IME keeps only the offset (the bar's geometry).
     */
    private fun showHintToast(text: String) {
        Toasts.hint(this, text, hintYOffsetPx())
    }

    private fun hintYOffsetPx(): Int =
        (120 * resources.displayMetrics.density).toInt()

    private fun setFlashing(active: Boolean) {
        // #118 Phase C: an unchanged breath state must not touch the animator
        // at all — `startBreathing()` cancel+recreates it, resetting the
        // visible phase on every rebind cycle.
        if (!RebindChurn.startBreath(flashing, active)) return
        flashing = active
        if (active) {
            breathAlpha = BREATH_CEIL
            startBreathing()
        } else {
            breathAnimator?.cancel()
            breathAnimator = null
            applyAlpha()
        }
    }

    private fun startBreathing() {
        breathAnimator?.cancel()
        // Volume-independent sine blink (#87): one full 0.3↔1.0 round trip
        // per cycle. A linear phase animator driving sin() eases in/out at
        // the extrema naturally — no keyframes, no dwell, no mic coupling.
        breathAnimator = ValueAnimator.ofFloat(0f, (2f * kotlin.math.PI).toFloat()).apply {
            duration = BREATH_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                breathAlpha = PulseAlphaPolicy.blinkAlpha(
                    it.animatedValue as Float, BREATH_FLOOR, BREATH_CEIL
                )
                applyAlpha()
            }
            start()
        }
        breathStarts++
    }

    private fun applyAlpha() {
        // Pulse the foreground rows only — a dimmed root background over the dark IME window
        // reads as a "pulsing background" in light mode but is invisible in dark mode (#59).
        // Outside RECORDING the rows are pinned to full opacity so a pause during the dim
        // phase cannot freeze the IME near-invisible (#77).
        val a = PulseAlphaPolicy.target(controller.state, breathAlpha)
        rowTop?.alpha = a
        rowButtons?.alpha = a
        // #213 correction: the red belongs to the FONT, never to the background —
        // the background stays neutral in every state. The font is solidly red
        // for the whole recording (only the alpha pulses) and pins back to the
        // neutral base outside RECORDING; applyAlpha runs on every state change
        // via setFlashing, so no pause can freeze the red.
        val base = resources.getColor(R.color.recording_text_primary, theme)
        val pulse = resources.getColor(R.color.recording_pulse_red, theme)
        val color = PulseColorPolicy.target(controller.state, base, pulse)
        val tint = android.content.res.ColorStateList.valueOf(color)
        sendButton?.imageTintList = tint
        pauseResumeButton?.imageTintList = tint
        switchButton?.imageTintList = tint
        deleteWordButton?.imageTintList = tint
        flushButton?.imageTintList = tint
        settingsGear?.imageTintList = tint
        recTimer?.setTextColor(color)
        stageText?.setTextColor(color)
        llmCheckbox?.setTextColor(color)
        llmCheckbox?.buttonTintList = tint
    }

    private fun setQuickSettingsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        languageSpinner?.visibility = visibility
        quickSettingsDivider?.visibility = visibility
        llmCheckbox?.visibility = visibility
    }

    private fun setQuickSettingsEnabled(enabled: Boolean) {
        val sp = languageSpinner ?: return
        val llm = llmCheckbox ?: return
        silenceLangListener = true
        // #168: inside our own app the LLM is forced off, so neither the
        // toggle nor its target-language spinner may be re-enabled here.
        val llmEnabled = enabled && !llmForcedOff
        sp.isEnabled = llmEnabled
        sp.alpha = if (llmEnabled) 1.0f else 0.4f
        llm.isEnabled = llmEnabled
        llm.alpha = if (llmEnabled) 1.0f else 0.4f
        silenceLangListener = false
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openKeyboardSettings() {
        startActivity(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun resolveKeyKeyboardTarget(selfId: String): String? {
        val enabled = getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList
            .map { imi ->
                SwitchTargetPolicy.EnabledIme(
                    id = imi.id,
                    hasKeys = imi.subtypeCount == 0 ||
                        (0 until imi.subtypeCount).any { !imi.getSubtypeAt(it).isAuxiliary }
                )
            }
        val history = SwitchTargetPolicy.parseHistory(
            Settings.Secure.getString(
                contentResolver,
                INPUT_METHODS_SUBTYPE_HISTORY_SETTING
            )
        )
        return SwitchTargetPolicy.targetKeyboard(history, enabled, selfId)
    }

    /**
     * Fresh read of the current default IME id via a direct provider query. A plain
     * Settings.Secure.getString would use the in-process cache, which can stay stale
     * right after switchInputMethod() (change notification is async), so a successful
     * switch could be misread as a no-op.
     */
    private fun currentDefaultImeId(): String? =
        contentResolver.query(
            Uri.withAppendedPath(Settings.Secure.CONTENT_URI, DEFAULT_INPUT_METHOD_SETTING),
            arrayOf(Settings.NameValueTable.VALUE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ime_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.ime_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        text: String,
        icon: Int = R.drawable.ic_notification_mic
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setSmallIcon(icon)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, SettingsHintActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun startMicForeground() {
        val listening = getString(R.string.listening_notification)
        if (!RebindChurn.assertForeground(foregroundAsserted)) {
            // #118 Phase C: this instance already holds the microphone FGS, so
            // re-entering startForeground would be pure churn. Only the
            // visible text is repaired — after a failed session the
            // notification still reads "processing" while recording again.
            postNotification(listening)
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(listening),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            foregroundAsserted = true
            lastNotificationText = listening
            foregroundAssertions++
        } catch (_: Exception) {
            foregroundAsserted = false
        }
    }

    private fun updateNotification(text: String, icon: Int = R.drawable.ic_notification_mic) {
        postNotification(text, icon)
    }

    /** Posts [text] unless the identical text is already on screen (#118 Phase C). */
    private fun postNotification(
        text: String,
        icon: Int = R.drawable.ic_notification_mic
    ) {
        if (!RebindChurn.notify(lastNotificationText, text)) return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text, icon))
            lastNotificationText = text
            notificationPosts++
        } catch (_: Exception) {
        }
    }

    private fun stopMicForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // #118 Phase C: the next assert is a genuinely new one — the FGS and
        // its notification are gone, so neither the flag nor the text survive.
        foregroundAsserted = false
        lastNotificationText = null
    }

    override fun onDestroy() {
        breathAnimator?.cancel()
        breathAnimator = null
        Toasts.cancelAll()
        recTickHandler.removeCallbacks(recTick)
        recTickHandler.removeCallbacks(micGuard)
        micGuardPending = false
        pendingProvisional?.let { recTickHandler.removeCallbacks(it) }
        pendingProvisional = null
        startGen++
        // #165: a parked auto-start dies with the instance.
        pendingAutoStart = null
        recTickHandler.removeCallbacks(windowDrawTimeout)
        ImeLifecycleTrace.log(this, "onDestroy", "state=${controller.state}")
        // #207: a held result whose field never became readable again is
        // salvaged here — the clipboard is its last resort, never a silent
        // drop.
        salvageHeldDelivery("destroy")
        // Rotation-safe teardown (#83, hardened after the Oplus process-death
        // finding): a live session is never cancelled here — RECORDING is
        // parked as PAUSED (pause() also snapshots PCM + duration to disk)
        // and PAUSED is snapshotted, both detached so the next instance
        // re-attaches (a detached PROCESSING result is delivered via
        // pendingResult). Foreground is released ONLY when idle: dropping FGS
        // mid-session lets Oplus kill the process during rotation, wiping the
        // in-memory session. Only an already-idle controller is cancelled.
        // Explicit user cancel is unaffected (it runs before destroy).
        when (controller.state) {
            VoiceSessionController.State.RECORDING -> {
                controller.pause()
                controller.detach()
            }
            VoiceSessionController.State.PAUSED -> {
                controller.snapshot()
                controller.detach()
            }
            VoiceSessionController.State.PROCESSING -> controller.detach()
            VoiceSessionController.State.IDLE -> {
                controller.cancel()
                stopMicForeground()
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_recognition_ime"
        private const val NOTIFICATION_ID = 1002
        private const val BREATH_FLOOR = 0.3f
        private const val BREATH_CEIL = 1f
        private const val BREATH_CYCLE_MS = 1333L
        private const val REC_TICK_MS = 1000L
        /** Chars of pre-cursor text fetched for one delete-word tap (#90). */
        private const val DELETE_WORD_LOOKBACK = 512
        /**
         * Fallback clear radius when the editor exposes no extracted text
         * (#90): the long-press intent is "clear the whole field".
         */
        private const val CLEAR_FIELD_FALLBACK = 9999
        /**
         * Grace window for a provisional freeze (#83 v3): the Oplus rotation
         * mark trails the rebind by ~150 ms, so a PAUSED same-field bind
         * waits this long before resuming — long enough for a late mark,
         * short enough to keep hide/show and switch-return resume snappy.
         */
        private const val PROVISIONAL_RESUME_DELAY_MS = 500L

        /** Settings.Secure key holding the id of the current default IME (hidden constant). */
        private const val DEFAULT_INPUT_METHOD_SETTING = "default_input_method"

        /** Settings.Secure key holding the ordered IME/subtype usage history (hidden constant). */
        private const val INPUT_METHODS_SUBTYPE_HISTORY_SETTING = "input_methods_subtype_history"
    }
}
