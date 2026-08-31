package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
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
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.ui.MicrophonePermissionActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsHintActivity
import com.georgernstgraf.polishedrecognition.ui.VoiceRecognitionActivity

class PolishedVoiceInputIME : InputMethodService() {

    private lateinit var controller: VoiceSessionController
    private lateinit var settings: SettingsStore

    private var rootView: View? = null
    private var micSendButton: ImageButton? = null
    private var pauseResumeButton: ImageButton? = null
    private var cancelButton: ImageButton? = null
    private var languageSpinner: Spinner? = null
    private var rawCheckbox: CheckBox? = null
    private var settingsGear: ImageButton? = null
    private var quickSettingsDivider: View? = null
    private var stageText: TextView? = null
    private var smoothedRms = 0f
    private var voiceAlpha = RmsAlphaMapper.ALPHA_FLOOR
    private var breathAlpha = BREATH_CEIL
    private var breathAnimator: ValueAnimator? = null
    private var rmsLogCount = 0
    private var silenceLangListener = false

    override fun onCreate() {
        super.onCreate()
        val app = application as PolishedRecognitionApp
        controller = VoiceSessionController(this, app.transcriptionPipeline)
        settings = app.settingsStore
        createNotificationChannel()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_input, null)
        rootView = view.findViewById(R.id.ime_root)
        micSendButton = view.findViewById(R.id.ime_mic_send_button)
        pauseResumeButton = view.findViewById(R.id.ime_pause_resume_button)
        cancelButton = view.findViewById(R.id.ime_cancel_button)
        languageSpinner = view.findViewById(R.id.ime_language_spinner)
        rawCheckbox = view.findViewById(R.id.ime_raw)
        settingsGear = view.findViewById(R.id.ime_settings_button)
        quickSettingsDivider = view.findViewById(R.id.ime_quick_settings_divider)
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
            startActivity(
                Intent(this, SettingsHintActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
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

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (controller.state == VoiceSessionController.State.RECORDING ||
            controller.state == VoiceSessionController.State.PROCESSING
        ) {
            controller.cancel()
        }
        refreshQuickSettings()
        applyUiState()
        if (AutoStartPolicy.shouldAutoStart(controller.state, hasMicPermission())) {
            startIfPermitted()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
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
                        if (selected == VoiceRecognitionActivity.NONE_TARGET_LANGUAGE) null else selected
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
            val current = settings.targetLanguage ?: VoiceRecognitionActivity.NONE_TARGET_LANGUAGE
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
        listOf(VoiceRecognitionActivity.NONE_TARGET_LANGUAGE, "English") +
            settings.customLanguages.sorted()

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
                    currentInputConnection?.commitText(text, 1)
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

    private fun applyUiState() {
        val ms = micSendButton ?: return
        val pr = pauseResumeButton ?: return
        val cb = cancelButton ?: return
        val gear = settingsGear ?: return
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
                setQuickSettingsEnabled(false)
                setQuickSettingsVisible(false)
            }
        }
        stageText?.visibility =
            if (s == VoiceSessionController.State.PROCESSING) View.VISIBLE else View.GONE
        updateLanguageEnabled()
    }

    private fun setFlashing(active: Boolean) {
        val root = rootView ?: return
        if (active) {
            smoothedRms = 0f
            voiceAlpha = RmsAlphaMapper.ALPHA_FLOOR
            breathAlpha = BREATH_CEIL
            rmsLogCount = 0
            startBreathing()
        } else {
            breathAnimator?.cancel()
            breathAnimator = null
            root.alpha = 1f
        }
    }

    private fun startBreathing() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(BREATH_CEIL, BREATH_FLOOR).apply {
            duration = BREATH_HALF_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                breathAlpha = it.animatedValue as Float
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
        rootView?.alpha = maxOf(breathAlpha, voiceAlpha)
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
        controller.cancel()
        stopMicForeground()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_recognition_ime"
        private const val NOTIFICATION_ID = 1002
        private const val BREATH_FLOOR = 0.45f
        private const val BREATH_CEIL = 0.9f
        private const val BREATH_HALF_PERIOD_MS = 1000L
        private const val RMS_LOG_TAG = "PolishedRMS"
        private const val RMS_LOG_EVERY = 10
    }
}
