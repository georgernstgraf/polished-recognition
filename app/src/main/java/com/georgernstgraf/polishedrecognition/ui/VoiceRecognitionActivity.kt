package com.georgernstgraf.polishedrecognition.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioRecorderListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class VoiceRecognitionActivity : Activity() {

    companion object {
        private const val TAG = "VoiceRecognitionAct"
        private const val REQUEST_RECORD_AUDIO = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioRecorder = AudioRecorder()
    private var isRecording = false
    private var blinkAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_input)

        val micButton = findViewById<ImageButton>(R.id.mic_button)
        val statusText = findViewById<TextView>(R.id.status_text)
        val partialText = findViewById<TextView>(R.id.partial_text)
        val cancelButton = findViewById<View>(R.id.cancel_button)

        partialText.visibility = View.GONE

        micButton.setOnClickListener {
            if (isRecording) {
                stopRecording(statusText, micButton)
            } else if (hasRecordPermission()) {
                startRecording(statusText, micButton)
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }

        cancelButton.setOnClickListener {
            cancelAndFinish()
        }

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording(findViewById(R.id.status_text), findViewById(R.id.mic_button))
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startRecording(statusText: TextView, micButton: ImageButton) {
        Log.d(TAG, "startRecording")
        isRecording = true
        statusText.text = "\u25AA Tap to stop"
        micButton.setImageResource(android.R.drawable.ic_media_pause)

        val recorderListener = object : AudioRecorderListener {
            override fun onRmsChanged(rms: Float) {}
            override fun onSpeechBegin() {}
        }
        audioRecorder.start(recorderListener)

        startBlink(micButton)
    }

    private fun stopRecording(statusText: TextView, micButton: ImageButton) {
        Log.d(TAG, "stopRecording")
        isRecording = false
        stopBlink(micButton)
        statusText.text = "Processing\u2026"
        micButton.isEnabled = false
        micButton.setImageResource(android.R.drawable.ic_btn_speak_now)

        val wavData = audioRecorder.stop()
        Log.d(TAG, "audioRecorder stopped, wav size=${wavData.size}")

        scope.launch {
            try {
                val app = application as PolishedRecognitionApp
                val file = File(cacheDir, "recording.wav")
                file.writeBytes(wavData)

                val result = app.transcriptionPipeline.transcribe(file)
                file.delete()

                if (result.isSuccess) {
                    val text = result.getOrThrow()
                    Log.d(TAG, "transcription success: `$text`")
                    returnResults(arrayListOf(text))
                } else {
                    Log.e(TAG, "transcription failed: ${result.exceptionOrNull()?.message}")
                    returnResults(ArrayList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "exception during transcription", e)
                returnResults(ArrayList())
            }
        }
    }

    private fun startBlink(button: ImageButton) {
        blinkAnimator = ObjectAnimator.ofFloat(button, "alpha", 1.0f, 0.2f, 1.0f).apply {
            duration = 1800
            repeatMode = ObjectAnimator.RESTART
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopBlink(button: ImageButton) {
        blinkAnimator?.cancel()
        blinkAnimator = null
        button.alpha = 1.0f
    }

    private fun cancelAndFinish() {
        Log.d(TAG, "cancelAndFinish")
        stopBlink(findViewById(R.id.mic_button))
        if (isRecording) audioRecorder.cancel()
        returnResults(ArrayList())
    }

    private fun returnResults(results: ArrayList<String>) {
        val intent = Intent().apply {
            putStringArrayListExtra(SpeechRecognizer.RESULTS_RECOGNITION, results)
            putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results)
        }
        setResult(if (results.isEmpty()) RESULT_CANCELED else RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        stopBlink(findViewById(R.id.mic_button))
        scope.launch { try { audioRecorder.cancel() } catch (_: Exception) {} }
        super.onDestroy()
    }
}
