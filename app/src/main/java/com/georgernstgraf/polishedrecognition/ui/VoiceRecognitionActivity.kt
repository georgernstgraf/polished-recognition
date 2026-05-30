package com.georgernstgraf.polishedrecognition.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
        private const val REQUEST_RECORD_AUDIO = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioRecorder = AudioRecorder()
    private var isRecording = false
    private var blinkAnimator: ValueAnimator? = null

    private val micButton: ImageButton by lazy { findViewById(R.id.mic_button) }
    private val statusText: TextView by lazy { findViewById(R.id.status_text) }
    private val cancelButton: View by lazy { findViewById(R.id.cancel_button) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_input)

        val partialText = findViewById<TextView>(R.id.partial_text)
        partialText.visibility = View.GONE

        micButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        cancelButton.setOnClickListener { cancelAndFinish() }
        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (hasRecordPermission()) {
            startRecording()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startRecording() {
        isRecording = true
        statusText.text = "Recording\u2026"
        micButton.setImageResource(R.drawable.ic_stop)

        val recorderListener = object : AudioRecorderListener {
            override fun onRmsChanged(rms: Float) {}
            override fun onSpeechBegin() {}
        }
        audioRecorder.start(recorderListener)

        startBlink()
    }

    private fun stopRecording() {
        isRecording = false
        stopBlink()
        statusText.text = "Processing\u2026"
        micButton.isEnabled = false
        micButton.setImageResource(android.R.drawable.ic_btn_speak_now)

        val wavData = audioRecorder.stop()

        scope.launch {
            try {
                val app = application as PolishedRecognitionApp
                val file = File(cacheDir, "recording.wav")
                file.writeBytes(wavData)

                val result = app.transcriptionPipeline.transcribe(file)
                file.delete()

                if (result.isSuccess) {
                    val text = result.getOrThrow()
                    returnResults(arrayListOf(text))
                } else {
                    returnResults(ArrayList())
                }
            } catch (e: Exception) {
                returnResults(ArrayList())
            }
        }
    }

    private fun startBlink() {
        stopBlink()
        blinkAnimator = ValueAnimator.ofFloat(0.3f, 1.0f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { micButton.alpha = animatedValue as Float }
            start()
        }
    }

    private fun stopBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        micButton.alpha = 1.0f
    }

    private fun cancelAndFinish() {
        stopBlink()
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
        stopBlink()
        scope.launch { try { audioRecorder.cancel() } catch (_: Exception) {} }
        super.onDestroy()
    }
}
