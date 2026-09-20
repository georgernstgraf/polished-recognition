package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.ui.MicrophonePermissionActivity
import com.georgernstgraf.polishedrecognition.ui.SettingsActivity

/**
 * Bound `RecognitionService` entry point (#82) for keyboard-less
 * `SpeechRecognizer` callers (Duolingo, Corvus, assistive tools) that honor
 * the system default voice-input service.
 *
 * Additive by design: it drives the shared [VoiceSessionController]
 * singleton as a *secondary* listener, so the IME keeps its primary
 * callback forever and can never go deaf. No pause UI in v1 (client apps
 * offer no pause affordance); settings edits apply automatically because
 * the pipeline resolves them at transcription time.
 */
class PolishedRecognitionService : RecognitionService() {

    private lateinit var controller: VoiceSessionController
    private var clientCallback: Callback? = null
    private var awaitingResult = false

    private val secondaryListener: (VoiceSessionController.Event) -> Unit = { handleEvent(it) }

    override fun onCreate() {
        super.onCreate()
        controller = (application as PolishedRecognitionApp).voiceSessionController
        createNotificationChannel()
    }

    override fun onStartListening(intent: Intent, listener: Callback) {
        if (!hasMicPermission()) {
            startActivity(
                Intent(this, MicrophonePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        // Busy-discard (#82, owner decision): a live IME dictation is
        // discarded — mirroring the IME's own field-change cancel — and the
        // service session starts fresh on the shared singleton.
        if (controller.state != VoiceSessionController.State.IDLE) {
            controller.cancel()
        }
        clientCallback = listener
        awaitingResult = true
        startServiceForeground()
        listener.readyForSpeech(Bundle.EMPTY)
        listener.beginningOfSpeech()
        controller.startShared(secondaryListener)
        if (controller.state != VoiceSessionController.State.RECORDING) {
            finishWithError(listener, SpeechRecognizer.ERROR_CLIENT)
        }
    }

    override fun onStopListening(listener: Callback) {
        listener.let { clientCallback = it }
        if (!awaitingResult) return
        if (controller.state != VoiceSessionController.State.RECORDING &&
            controller.state != VoiceSessionController.State.PAUSED
        ) {
            // Session died without a result (e.g. foreign cancel).
            finishWithError(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        controller.stopAndTranscribe()
    }

    override fun onCancel(listener: Callback) {
        awaitingResult = false
        controller.removeSecondaryListener(secondaryListener)
        if (controller.state != VoiceSessionController.State.IDLE) {
            controller.cancel()
        }
        Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList())
        }.let {
            listener.results(it)
            runCatching { listener.endOfSpeech() }
        }
        clientCallback = null
        stopServiceForeground()
        stopSelf()
    }

    override fun onDestroy() {
        controller.removeSecondaryListener(secondaryListener)
        if (awaitingResult) {
            // System tore us down mid-session: never leak a live mic.
            awaitingResult = false
            runCatching { controller.cancel() }
            runCatching { stopServiceForeground() }
        }
        clientCallback = null
        super.onDestroy()
    }

    private fun handleEvent(event: VoiceSessionController.Event) {
        val cb = clientCallback ?: return
        try {
            when (event) {
                is VoiceSessionController.Event.StateChanged -> when (event.state) {
                    VoiceSessionController.State.PROCESSING ->
                        updateNotification(getString(R.string.processing_notification))
                    VoiceSessionController.State.IDLE ->
                        if (awaitingResult) {
                            // Session died without a result (foreign cancel).
                            finishWithError(cb, SpeechRecognizer.ERROR_CLIENT)
                        }
                    else -> Unit
                }
                is VoiceSessionController.Event.StageChanged ->
                    updateNotification(stageText(event.stage))
                is VoiceSessionController.Event.Completed -> {
                    awaitingResult = false
                    controller.removeSecondaryListener(secondaryListener)
                    event.result.fold(
                        onSuccess = { text ->
                            cb.results(buildResultBundle(text))
                            runCatching { cb.endOfSpeech() }
                            clientCallback = null
                            stopServiceForeground()
                            stopSelf()
                        },
                        onFailure = { e ->
                            val msg = e.message ?: getString(R.string.ime_error)
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            cb.error(mapRecognitionError(msg))
                            // Back to IDLE, no PAUSED residue for the IME
                            // (#82, owner decision). This nulls the IME's
                            // primary slot too — harmless in IDLE (no async
                            // events); its next tap re-attaches via start{}.
                            controller.cancel()
                            clientCallback = null
                            stopServiceForeground()
                            stopSelf()
                        }
                    )
                }
            }
        } catch (_: Exception) {
            finishWithError(cb, SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun finishWithError(cb: Callback, code: Int) {
        awaitingResult = false
        controller.removeSecondaryListener(secondaryListener)
        clientCallback = null
        runCatching { cb.error(code) }
        stopServiceForeground()
        stopSelf()
    }

    private fun stageText(stage: TranscriptionPipeline.TranscriptionStage): String =
        when (stage) {
            is TranscriptionPipeline.TranscriptionStage.CompressingAudio ->
                getString(R.string.ime_stage_compressing)
            is TranscriptionPipeline.TranscriptionStage.RequestingStt ->
                getString(R.string.ime_stage_stt)
            is TranscriptionPipeline.TranscriptionStage.RequestingLlm ->
                getString(R.string.ime_stage_llm)
        }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ime_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.ime_channel_desc) }.let {
            getSystemService(NotificationManager::class.java).createNotificationChannel(it)
        }
    }

    /**
     * Neutral ongoing notification (owner decision — no colorful buttons):
     * same shape as the IME's, own channel + id so the two never clobber
     * each other.
     */
    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun startServiceForeground() {
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

    private fun stopServiceForeground() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "voice_recognition_service"
        private const val NOTIFICATION_ID = 1003

        internal fun mapRecognitionError(message: String): Int = when {
            message.contains("network", ignoreCase = true) ->
                SpeechRecognizer.ERROR_NETWORK
            message.contains("timeout", ignoreCase = true) ->
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT
            else -> SpeechRecognizer.ERROR_SERVER
        }

        internal fun buildResultBundle(text: String): Bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            putStringArrayList(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
        }
    }
}
