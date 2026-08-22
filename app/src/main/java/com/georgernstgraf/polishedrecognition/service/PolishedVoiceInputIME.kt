package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
import com.georgernstgraf.polishedrecognition.ui.SettingsActivity
import com.georgernstgraf.polishedrecognition.ui.VoiceRecognitionActivity

class PolishedVoiceInputIME : InputMethodService() {

    private lateinit var controller: VoiceSessionController
    private lateinit var settings: SettingsStore

    private var statusText: TextView? = null
    private var micButton: Button? = null
    private var cancelButton: Button? = null
    private var languageSpinner: Spinner? = null
    private var rawCheckbox: CheckBox? = null
    private var settingsGear: ImageButton? = null
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
        statusText = view.findViewById(R.id.ime_status_text)
        micButton = view.findViewById(R.id.ime_mic_button)
        cancelButton = view.findViewById(R.id.ime_cancel_button)
        languageSpinner = view.findViewById(R.id.ime_language_spinner)
        rawCheckbox = view.findViewById(R.id.ime_raw)
        settingsGear = view.findViewById(R.id.ime_settings_button)

        micButton?.setOnClickListener {
            when (controller.state) {
                VoiceSessionController.State.IDLE -> startIfPermitted()
                VoiceSessionController.State.RECORDING -> controller.stopAndTranscribe()
                else -> Unit
            }
        }
        cancelButton?.setOnClickListener {
            controller.cancel()
            requestHideSelf(0)
        }
        settingsGear?.setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
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
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (controller.state != VoiceSessionController.State.IDLE) {
            controller.cancel()
        }
        stopMicForeground()
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
                this, android.R.layout.simple_spinner_item, langs
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
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
                statusText?.text = when (event.stage) {
                    is TranscriptionPipeline.TranscriptionStage.RequestingStt ->
                        getString(R.string.ime_status_stt)
                    is TranscriptionPipeline.TranscriptionStage.RequestingLlm ->
                        getString(R.string.ime_status_llm)
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
            is VoiceSessionController.Event.RmsChanged,
            is VoiceSessionController.Event.SpeechBegin -> Unit
        }
    }

    private fun applyUiState() {
        val st = statusText ?: return
        val mb = micButton ?: return
        val cb = cancelButton ?: return
        when (controller.state) {
            VoiceSessionController.State.IDLE -> {
                st.text = getString(R.string.ime_status_idle)
                mb.text = getString(R.string.ime_record)
                mb.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_mic, 0, 0)
                mb.isEnabled = true
                cb.isEnabled = true
            }
            VoiceSessionController.State.RECORDING -> {
                st.text = getString(R.string.ime_status_recording)
                mb.text = getString(R.string.ime_stop)
                mb.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_send, 0, 0)
                mb.isEnabled = true
                cb.isEnabled = true
            }
            VoiceSessionController.State.PAUSED -> {
                st.text = getString(R.string.ime_status_paused)
                mb.isEnabled = false
                cb.isEnabled = true
            }
            VoiceSessionController.State.PROCESSING -> {
                st.text = getString(R.string.ime_status_processing)
                mb.text = getString(R.string.ime_processing)
                mb.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_send, 0, 0)
                mb.isEnabled = false
                cb.isEnabled = false
            }
        }
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
                Intent(this, SettingsActivity::class.java),
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
        controller.cancel()
        stopMicForeground()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_recognition_ime"
        private const val NOTIFICATION_ID = 1002
    }
}
