package com.georgernstgraf.polishedrecognition.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioRecorderListener
import com.georgernstgraf.polishedrecognition.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class PolishedRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioRecorder = com.georgernstgraf.polishedrecognition.audio.AudioRecorder()
    private var callback: Callback? = null
    private var partialResultsJob: Job? = null
    private var recordingId = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        recordingId++
        val rid = recordingId
        Log.d(TAG, "[$rid] onStartListening")

        callback = listener
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.listening_notification)))

        val recorderListener = object : AudioRecorderListener {
            override fun onRmsChanged(rms: Float) {
                Log.v(TAG, "[$rid] rmsChanged rms=%.1f".format(rms))
                callback?.rmsChanged(rms)
            }

            override fun onSpeechBegin() {
                Log.d(TAG, "[$rid] onSpeechBegin callback")
                callback?.beginningOfSpeech()
            }
        }
        audioRecorder.start(recorderListener)
        Log.d(TAG, "[$rid] audioRecorder.start() called, starting partial results")

        startPartialResults(rid)
    }

    override fun onCancel(listener: Callback) {
        Log.d(TAG, "[$recordingId] onCancel — caller cancelled, dropping audio")
        stopPartialResults()
        audioRecorder.cancel()
        stopProcessing(listener, Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList())
        })
    }

    override fun onStopListening(listener: Callback) {
        val rid = recordingId
        Log.d(TAG, "[$rid] onStopListening — user released mic")
        stopPartialResults()
        val wavData = audioRecorder.stop()
        Log.d(TAG, "[$rid] audioRecorder.stop() = %d bytes WAV".format(wavData.size))

        updateNotification(getString(R.string.processing_notification))

        scope.launch {
            try {
                val app = application as PolishedRecognitionApp
                val file = File(cacheDir, "recording.wav")
                file.writeBytes(wavData)

                Log.d(TAG, "[$rid] calling transcriptionPipeline.transcribe()")
                val result = app.transcriptionPipeline.transcribe(file)

                file.delete()

                if (result.isSuccess) {
                    val text = result.getOrThrow()
                    Log.d(TAG, "[$rid] transcription success: `${text}`")
                    val bundle = Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    }
                    stopProcessing(listener, bundle)
                } else {
                    val error = result.exceptionOrNull()
                    val errorMsg = error?.message ?: "Unknown error"
                    Log.e(TAG, "[$rid] transcription failed: $errorMsg")
                    listener.error(if (errorMsg.contains("network", ignoreCase = true)) {
                        SpeechRecognizer.ERROR_NETWORK
                    } else {
                        SpeechRecognizer.ERROR_SERVER
                    })
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$rid] exception in onStopListening", e)
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun startPartialResults(rid: Int) {
        Log.d(TAG, "[$rid] startPartialResults — sending dots every 400ms")
        partialResultsJob = scope.launch {
            var dotCount = 0
            repeat(Int.MAX_VALUE) {
                if (!isActive) return@launch
                delay(400)
                dotCount = (dotCount + 1) % 5
                val dots = ".".repeat(dotCount.coerceAtLeast(1))
                val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(dots))
                }
                Log.v(TAG, "[$rid] partialResults: `$dots`")
                callback?.partialResults(bundle)
            }
        }
    }

    private fun stopPartialResults() {
        Log.d(TAG, "[$recordingId] stopPartialResults")
        partialResultsJob?.cancel()
        partialResultsJob = null
    }

    private fun stopProcessing(listener: Callback, bundle: Bundle) {
        val rid = recordingId
        Log.d(TAG, "[$rid] stopProcessing — delivering results")
        listener.results(bundle)
        listener.endOfSpeech()
        listener.readyForSpeech(Bundle.EMPTY)
        callback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopPartialResults()
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
        private const val TAG = "PolishedRecognition"
        private const val CHANNEL_ID = "voice_recognition"
        private const val NOTIFICATION_ID = 1001
    }
}
