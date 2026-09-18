package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.LanguageOptions
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.ui.MicrophonePermissionActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsHintActivity

class PolishedVoiceInputIME : InputMethodService() {

    private lateinit var controller: VoiceSessionController
    private lateinit var settings: SettingsStore

    private var rootView: View? = null
    private var rowTop: View? = null
    private var rowButtons: View? = null
    private var micSendButton: ImageButton? = null
    private var pauseResumeButton: ImageButton? = null
    private var cancelButton: ImageButton? = null
    private var languageSpinner: Spinner? = null
    private var rawCheckbox: CheckBox? = null
    private var settingsGear: ImageButton? = null
    private var switchKeyboardButton: ImageButton? = null
    private var quickSettingsDivider: View? = null
    private var recTimer: TextView? = null
    private var recTimerDivider: View? = null
    private var stageText: TextView? = null
    private var smoothedRms = 0f
    private var voiceAlpha = RmsAlphaMapper.ALPHA_FLOOR
    private var breathAlpha = BREATH_CEIL
    private var breathAnimator: ValueAnimator? = null
    private var rmsLogCount = 0
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
     * [VoiceSessionController.restore]. Freezes the first [onStartInputView]
     * like a rotation (no cancel, no auto-resume) so the restored PAUSED
     * dictation is presented, not resumed or discarded; consumed there.
     */
    private var restoredSnapshot = false
    /**
     * Guards the delayed resume check posted by a [ImeStartDecision.Outcome]
     * provisional freeze: each bind bumps [startGen], so a superseded
     * runnable (rapid rebinds, teardown) no-ops instead of resuming against
     * a newer decision. Invalidated in [onDestroy].
     */
    private var startGen = 0
    private var pendingProvisional: Runnable? = null
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

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_input, null)
        rootView = view.findViewById(R.id.ime_root)
        rowTop = view.findViewById(R.id.ime_row_top)
        rowButtons = view.findViewById(R.id.ime_row_buttons)
        micSendButton = view.findViewById(R.id.ime_mic_send_button)
        pauseResumeButton = view.findViewById(R.id.ime_pause_resume_button)
        cancelButton = view.findViewById(R.id.ime_cancel_button)
        languageSpinner = view.findViewById(R.id.ime_language_spinner)
        rawCheckbox = view.findViewById(R.id.ime_raw)
        settingsGear = view.findViewById(R.id.ime_settings_button)
        switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard_button)
        quickSettingsDivider = view.findViewById(R.id.ime_quick_settings_divider)
        recTimer = view.findViewById(R.id.ime_rec_timer)
        recTimerDivider = view.findViewById(R.id.ime_rec_timer_divider)
        stageText = view.findViewById(R.id.ime_stage_text)

        micSendButton?.setOnClickListener {
            when (controller.state) {
                VoiceSessionController.State.IDLE -> startIfPermitted()
                VoiceSessionController.State.RECORDING,
                VoiceSessionController.State.PAUSED -> controller.stopAndTranscribe()
                else -> Unit
            }
        }
        pauseResumeButton?.setOnClickListener {
            when (controller.state) {
                VoiceSessionController.State.RECORDING -> controller.pause()
                VoiceSessionController.State.PAUSED -> controller.resume()
                else -> Unit
            }
        }
        cancelButton?.setOnClickListener {
            controller.cancel()
            requestHideSelf(0)
        }
        settingsGear?.setOnClickListener {
            if (controller.state == VoiceSessionController.State.RECORDING) {
                controller.pause()
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
        switchKeyboardButton?.setOnClickListener {
            if (controller.state == VoiceSessionController.State.RECORDING) {
                controller.pause()
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
        rawCheckbox?.setOnCheckedChangeListener { _, isChecked ->
            if (silenceLangListener) return@setOnCheckedChangeListener
            settings.rawMode = isChecked
            updateLanguageEnabled()
        }
        setupLanguageSpinner()
        applyUiState()
        return view
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
        val now = SystemClock.uptimeMillis()
        val currentField = RotationGate.FieldId(info.packageName, info.fieldId)
        val previousField = app.imeLastField
        val sameField = RotationGate.isSameField(previousField, currentField)
        val gateFresh = RotationGate.isFresh(app.imeConfigChangeMs, now)
        val outcome = ImeStartDecision.decide(
            controller.state, sameField, restarting && configChangeInProgress,
            gateFresh, restoredSnapshot
        )
        configChangeInProgress = false
        app.imeLastField = currentField
        ImeLifecycleTrace.log(
            this, "onStartInputView",
            "restarting=$restarting state=${controller.state} outcome=$outcome " +
                "restored=$restoredSnapshot sameField=$sameField gateFresh=$gateFresh"
        )
        restoredSnapshot = false
        if (outcome == ImeStartDecision.Outcome.CANCEL) {
            controller.cancel()
        }
        refreshQuickSettings()
        applyUiState()
        when (outcome) {
            ImeStartDecision.Outcome.FREEZE -> {
                // Re-assert foreground for a live session: the previous
                // instance may have died or released FGS on teardown, and
                // the session has no visible UI of its own yet — without FGS
                // Oplus may kill the process mid-rotation.
                if (controller.state != VoiceSessionController.State.IDLE) {
                    startMicForeground()
                }
                return
            }
            ImeStartDecision.Outcome.PROVISIONAL_FREEZE -> {
                scheduleProvisionalResume()
                return
            }
            ImeStartDecision.Outcome.CANCEL,
            ImeStartDecision.Outcome.PROCEED -> Unit
        }
        if (AutoStartPolicy.shouldAutoResume(controller.state, hasMicPermission())) {
            startMicForeground()
            controller.resume()
        } else if (AutoStartPolicy.shouldAutoStart(controller.state, hasMicPermission())) {
            startIfPermitted()
        }
    }

    /**
     * Finalizes a [ImeStartDecision.Outcome.PROVISIONAL_FREEZE]: the bind saw
     * PAUSED on the same field with no rotation signal yet, but the mark may
     * simply be late (Oplus delivers the rebind ~150 ms before
     * `onConfigurationChanged`). After [PROVISIONAL_RESUME_DELAY_MS] a still
     * stale gate means a genuine return (hide/show, switch back — resume per
     * #65); a fresh gate confirms rotation (stay frozen). No-ops unless still
     * PAUSED, so user taps in the window (send/cancel/resume) always win;
     * sequential main-thread execution makes double-resume impossible.
     */
    private fun scheduleProvisionalResume() {
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
                "state=${controller.state} gateFresh=$fresh"
            )
            if (fresh) return@Runnable
            if (controller.state != VoiceSessionController.State.PAUSED) return@Runnable
            if (!hasMicPermission()) return@Runnable
            startMicForeground()
            controller.resume()
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
        if (controller.state == VoiceSessionController.State.RECORDING) {
            controller.pause()
        }
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

    private fun refreshQuickSettings() {
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
        silenceLangListener = true
        rawCheckbox?.isChecked = settings.rawMode
        silenceLangListener = false
        updateLanguageEnabled()
    }

    private fun buildLanguageList(): List<String> =
        LanguageOptions.buildLanguageList(settings.customLanguages)

    private fun updateLanguageEnabled() {
        val disabled = rawCheckbox?.isChecked == true
        languageSpinner?.isEnabled = !disabled
        languageSpinner?.alpha = if (disabled) 0.4f else 1.0f
    }

    private fun startIfPermitted() {
        if (hasMicPermission()) {
            startMicForeground()
            controller.start { handleEvent(it) }
        } else {
            startActivity(
                Intent(this, MicrophonePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun handleEvent(event: VoiceSessionController.Event) {
        when (event) {
            is VoiceSessionController.Event.StateChanged -> {
                applyUiState()
                when (event.state) {
                    VoiceSessionController.State.RECORDING -> startMicForeground()
                    VoiceSessionController.State.PROCESSING ->
                        updateNotification(getString(R.string.processing_notification))
                    VoiceSessionController.State.IDLE -> stopMicForeground()
                    else -> Unit
                }
            }
            is VoiceSessionController.Event.StageChanged -> {
                stageText?.text = when (event.stage) {
                    is TranscriptionPipeline.TranscriptionStage.CompressingAudio ->
                        getString(R.string.ime_stage_compressing)
                    is TranscriptionPipeline.TranscriptionStage.RequestingStt ->
                        getString(R.string.ime_stage_stt)
                    is TranscriptionPipeline.TranscriptionStage.RequestingLlm ->
                        getString(R.string.ime_stage_llm)
                }
            }
            is VoiceSessionController.Event.Completed -> {
                val result = event.result
                val text = result.getOrNull()
                if (text != null) {
                    commitWithSpacing(text)
                    requestHideSelf(0)
                } else {
                    Toast.makeText(
                        this,
                        result.exceptionOrNull()?.message ?: getString(R.string.ime_error),
                        Toast.LENGTH_LONG
                    ).show()
                }
                applyUiState()
            }
            is VoiceSessionController.Event.RmsChanged ->
                if (controller.state == VoiceSessionController.State.RECORDING) {
                    onRmsChanged(event.rms)
                }
            is VoiceSessionController.Event.SpeechBegin -> Unit
        }
    }

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

    private fun applyUiState() {
        val ms = micSendButton ?: return
        val pr = pauseResumeButton ?: return
        val cb = cancelButton ?: return
        val gear = settingsGear ?: return
        val switchButton = switchKeyboardButton ?: return
        val s = controller.state
        setFlashing(s == VoiceSessionController.State.RECORDING)
        setPausedEnlarged(s == VoiceSessionController.State.PAUSED)
        when (s) {
            VoiceSessionController.State.IDLE -> {
                ms.setImageResource(R.drawable.ic_mic)
                ms.contentDescription = getString(R.string.ime_mic_desc)
                ms.isEnabled = true
                pr.setImageResource(R.drawable.ic_pause)
                pr.contentDescription = getString(R.string.ime_pause_desc)
                pr.isEnabled = false
                cb.isEnabled = true
                gear.isEnabled = true
                switchButton.isEnabled = true
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
                cb.isEnabled = true
                gear.isEnabled = true
                switchButton.isEnabled = true
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
                cb.isEnabled = true
                gear.isEnabled = true
                switchButton.isEnabled = true
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
                cb.isEnabled = false
                gear.isEnabled = false
                switchButton.isEnabled = false
                setQuickSettingsEnabled(false)
                setQuickSettingsVisible(false)
            }
        }
        stageText?.visibility =
            if (s == VoiceSessionController.State.PROCESSING) View.VISIBLE else View.GONE
        updateRecTimer(s)
        updateLanguageEnabled()
    }

    /**
     * REC time counter (#71): ticking "REC m:ss" during RECORDING (active mic
     * time only, pauses excluded via [VoiceSessionController.recordedDurationMs]),
     * frozen "m:ss" without the REC prefix while PAUSED, hidden otherwise.
     */
    private fun updateRecTimer(s: VoiceSessionController.State) {
        when (s) {
            VoiceSessionController.State.RECORDING -> {
                recTimer?.text = RecTimeFormatter.recording(controller.recordedDurationMs())
                recTimer?.visibility = View.VISIBLE
                recTimerDivider?.visibility = View.VISIBLE
                recTickHandler.removeCallbacks(recTick)
                recTickHandler.postDelayed(recTick, REC_TICK_MS)
            }
            VoiceSessionController.State.PAUSED -> {
                recTickHandler.removeCallbacks(recTick)
                recTimer?.text = RecTimeFormatter.paused(controller.recordedDurationMs())
                recTimer?.visibility = View.VISIBLE
                recTimerDivider?.visibility = View.VISIBLE
            }
            else -> {
                recTickHandler.removeCallbacks(recTick)
                recTimer?.visibility = View.GONE
                recTimerDivider?.visibility = View.GONE
            }
        }
    }

    private fun setFlashing(active: Boolean) {
        if (active) {
            smoothedRms = 0f
            voiceAlpha = RmsAlphaMapper.ALPHA_FLOOR
            breathAlpha = BREATH_CEIL
            rmsLogCount = 0
            startBreathing()
        } else {
            breathAnimator?.cancel()
            breathAnimator = null
            applyAlpha()
        }
    }

    private fun startBreathing() {
        breathAnimator?.cancel()
        // Keyframe cycle so the floor phase *dwells* (15% of the cycle) instead of being
        // touched only momentarily — without the hold, the deep phase is barely perceptible (#69).
        breathAnimator = ValueAnimator.ofPropertyValuesHolder(
            PropertyValuesHolder.ofKeyframe(
                "breathAlpha",
                Keyframe.ofFloat(0f, BREATH_CEIL),
                Keyframe.ofFloat(BREATH_FALL_FRACTION, BREATH_FLOOR),
                Keyframe.ofFloat(BREATH_FALL_FRACTION + BREATH_DWELL_FRACTION, BREATH_FLOOR),
                Keyframe.ofFloat(1f, BREATH_CEIL)
            )
        ).apply {
            duration = BREATH_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                breathAlpha = it.getAnimatedValue("breathAlpha") as Float
                applyAlpha()
            }
            start()
        }
    }

    private fun onRmsChanged(rms: Float) {
        if (!rms.isFinite()) return
        smoothedRms = RmsAlphaMapper.smooth(smoothedRms, rms)
        voiceAlpha = RmsAlphaMapper.alpha(smoothedRms)
        // RMS diagnostics (removed for v1.2.0; re-enable via logcat tag "PolishedRMS"):
        // if (rmsLogCount++ % RMS_LOG_EVERY == 0) {
        //     Log.d(
        //         RMS_LOG_TAG,
        //         "rms=%.0f smoothed=%.0f voiceAlpha=%.3f breathAlpha=%.3f"
        //             .format(rms, smoothedRms, voiceAlpha, breathAlpha)
        //     )
        // }
        applyAlpha()
    }

    private fun applyAlpha() {
        // Pulse the foreground rows only — a dimmed root background over the dark IME window
        // reads as a "pulsing background" in light mode but is invisible in dark mode (#59).
        // Outside RECORDING the rows are pinned to full opacity so a pause during the deep
        // dwell phase cannot freeze the IME near-invisible (#77).
        val a = PulseAlphaPolicy.target(controller.state, breathAlpha, voiceAlpha)
        rowTop?.alpha = a
        rowButtons?.alpha = a
    }

    private fun setPausedEnlarged(enlarged: Boolean) {
        val target = if (enlarged) 1.3f else 1f
        pauseResumeButton?.animate()?.cancel()
        pauseResumeButton?.animate()
            ?.scaleX(target)
            ?.scaleY(target)
            ?.setDuration(150)
            ?.start()
    }

    private fun setQuickSettingsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        languageSpinner?.visibility = visibility
        quickSettingsDivider?.visibility = visibility
        rawCheckbox?.visibility = visibility
    }

    private fun setQuickSettingsEnabled(enabled: Boolean) {
        val sp = languageSpinner ?: return
        val raw = rawCheckbox ?: return
        silenceLangListener = true
        sp.isEnabled = enabled
        sp.alpha = if (enabled) 1.0f else 0.4f
        raw.isEnabled = enabled
        raw.alpha = if (enabled) 1.0f else 0.4f
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

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.listening_notification)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (_: Exception) {
        }
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun stopMicForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        breathAnimator?.cancel()
        breathAnimator = null
        recTickHandler.removeCallbacks(recTick)
        pendingProvisional?.let { recTickHandler.removeCallbacks(it) }
        pendingProvisional = null
        startGen++
        ImeLifecycleTrace.log(this, "onDestroy", "state=${controller.state}")
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
        private const val BREATH_FLOOR = 0.15f
        private const val BREATH_CEIL = 0.9f
        private const val BREATH_CYCLE_MS = 2000L
        private const val BREATH_FALL_FRACTION = 0.425f
        private const val BREATH_DWELL_FRACTION = 0.15f
        private const val RMS_LOG_TAG = "PolishedRMS"
        private const val RMS_LOG_EVERY = 10
        private const val REC_TICK_MS = 1000L
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
