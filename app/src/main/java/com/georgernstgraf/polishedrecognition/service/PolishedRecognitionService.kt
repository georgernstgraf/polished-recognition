package com.georgernstgraf.polishedrecognition.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioRecorderListener
import com.georgernstgraf.polishedrecognition.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class PolishedRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioRecorder = com.georgernstgraf.polishedrecognition.audio.AudioRecorder()
    private var callback: Callback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        callback = listener
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.listening_notification)))

        val recorderListener = object : AudioRecorderListener {
            override fun onRmsChanged(rms: Float) {
                callback?.rmsChanged(rms)
            }

            override fun onSpeechBegin() {
                callback?.beginningOfSpeech()
            }
        }
        audioRecorder.start(recorderListener)
    }

    override fun onCancel(listener: Callback) {
        audioRecorder.cancel()
        stopProcessing(listener, Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList())
        })
    }

    override fun onStopListening(listener: Callback) {
        val wavData = audioRecorder.stop()

        updateNotification(getString(R.string.processing_notification))

        scope.launch {
            try {
                val app = application as PolishedRecognitionApp
                val file = File(cacheDir, "recording.wav")
                file.writeBytes(wavData)

                val result = app.transcriptionPipeline.transcribe(file)

                file.delete()

                if (result.isSuccess) {
                    val text = result.getOrThrow()
                    val bundle = Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                        putStringArrayList(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
                    }
                    stopProcessing(listener, bundle)
                } else {
                    val error = result.exceptionOrNull()
                    val errorMsg = error?.message ?: "Unknown error"
                    Toast.makeText(this@PolishedRecognitionService, errorMsg, Toast.LENGTH_LONG).show()
                    listener.error(if (errorMsg.contains("network", ignoreCase = true)) {
                        SpeechRecognizer.ERROR_NETWORK
                    } else {
                        SpeechRecognizer.ERROR_SERVER
                    })
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (e: Exception) {
                Toast.makeText(this@PolishedRecognitionService, e.message ?: "Unexpected error", Toast.LENGTH_LONG).show()
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopProcessing(listener: Callback, bundle: Bundle) {
        listener.results(bundle)
        listener.endOfSpeech()
        listener.readyForSpeech(Bundle.EMPTY)
        callback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.launch {
            try { audioRecorder.cancel() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Recognition",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active voice recognition session"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
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

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "voice_recognition"
        private const val NOTIFICATION_ID = 1001
    }
}
