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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioRecorderListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class VoiceRecognitionActivity : Activity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioRecorder = AudioRecorder()
    private var isRecording = false
    private var isPaused = false
    private var blinkAnimator: ValueAnimator? = null
    private var timerJob: Job? = null
    private var recordingStartMs: Long = 0
    private var recordedDurationMs: Long = 0

    private val statusText: TextView by lazy { findViewById(R.id.status_text) }
    private val cancelButton: Button by lazy { findViewById(R.id.cancel_button) }
    private val pauseResumeButton: Button by lazy { findViewById(R.id.pause_resume_button) }
    private val stopButton: Button by lazy { findViewById(R.id.stop_button) }
    private val elapsedText: TextView by lazy { findViewById(R.id.elapsed_text) }
    private val settingsButton: View by lazy { findViewById(R.id.settings_button) }
    private val infoButton: View by lazy { findViewById(R.id.info_button) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_input)

        cancelButton.setOnClickListener { cancelAndFinish() }

        pauseResumeButton.setOnClickListener {
            if (isRecording) pause() else resume()
        }

        stopButton.setOnClickListener { stopRecording() }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        infoButton.setOnClickListener {
            val wasRecording = isRecording
            if (wasRecording) {
                audioRecorder.pause()
                isRecording = false
                stopBlink()
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_input_info_title)
                .setMessage(R.string.voice_input_info_message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener {
                    if (wasRecording) {
                        audioRecorder.resume(recorderListener)
                        isRecording = true
                        startBlink()
                    }
                }
                .show()
        }

        if (hasRecordPermission()) {
            startRecording()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private val recorderListener = object : AudioRecorderListener {
        override fun onRmsChanged(rms: Float) {}
        override fun onSpeechBegin() {}
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
        isPaused = false
        recordingStartMs = System.currentTimeMillis()
        recordedDurationMs = 0
        statusText.text = "Recording\u2026"
        showPauseIcon()
        setButtonsForRecording()

        audioRecorder.start(recorderListener)
        startBlink()
        startTimer()
    }

    private fun pause() {
        recordedDurationMs += System.currentTimeMillis() - recordingStartMs
        isRecording = false
        isPaused = true
        audioRecorder.pause()
        stopBlink()
        statusText.text = "Paused"
        showResumeIcon()
        setButtonsForPause()
    }

    private fun resume() {
        recordingStartMs = System.currentTimeMillis()
        isRecording = true
        isPaused = false
        audioRecorder.resume(recorderListener)
        showPauseIcon()
        statusText.text = "Recording\u2026"
        setButtonsForRecording()
        startBlink()
    }

    private fun showPauseIcon() {
        pauseResumeButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_pause, 0, 0, 0)
        pauseResumeButton.contentDescription = "Pause"
    }

    private fun showResumeIcon() {
        pauseResumeButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_resume, 0, 0, 0)
        pauseResumeButton.contentDescription = "Resume"
    }

    private fun stopRecording() {
        isRecording = false
        isPaused = false
        stopBlink()
        stopTimer()
        statusText.text = "Processing\u2026"
        setButtonsForProcessing()

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
                    val msg = result.exceptionOrNull()?.message ?: "Transcription failed"
                    Toast.makeText(this@VoiceRecognitionActivity, msg, Toast.LENGTH_LONG).show()
                    returnResults(ArrayList())
                }
            } catch (e: Exception) {
                Toast.makeText(this@VoiceRecognitionActivity, e.message ?: "Unexpected error", Toast.LENGTH_LONG).show()
                returnResults(ArrayList())
            }
        }
    }

    private fun setButtonsForRecording() {
        cancelButton.isEnabled = true
        pauseResumeButton.isEnabled = true
        stopButton.isEnabled = true
        settingsButton.isEnabled = false
        settingsButton.alpha = 0.3f
        infoButton.isEnabled = false
        infoButton.alpha = 0.3f
    }

    private fun setButtonsForPause() {
        cancelButton.isEnabled = true
        pauseResumeButton.isEnabled = true
        stopButton.isEnabled = true
        settingsButton.isEnabled = true
        settingsButton.alpha = 1.0f
        infoButton.isEnabled = true
        infoButton.alpha = 1.0f
    }

    private fun setButtonsForProcessing() {
        cancelButton.isEnabled = false
        pauseResumeButton.isEnabled = false
        stopButton.isEnabled = false
        settingsButton.isEnabled = false
        settingsButton.alpha = 0.3f
        infoButton.isEnabled = false
        infoButton.alpha = 0.3f
    }

    private fun startBlink() {
        stopBlink()
        blinkAnimator = ValueAnimator.ofFloat(0.3f, 1.0f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { statusText.alpha = animatedValue as Float }
            start()
        }
    }

    private fun stopBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        statusText.alpha = 1.0f
    }

    private fun cancelAndFinish() {
        stopBlink()
        stopTimer()
        if (isRecording || isPaused) audioRecorder.cancel()
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

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            while (isActive) {
                delay(250)
                val currentSegment = if (isRecording) System.currentTimeMillis() - recordingStartMs else 0
                val total = recordedDurationMs + currentSegment
                val totalSec = total / 1000
                val min = totalSec / 60
                val sec = totalSec % 60
                elapsedText.text = "%02d:%02d".format(min, sec)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun onBackPressed() {
        cancelAndFinish()
    }

    override fun onDestroy() {
        stopBlink()
        stopTimer()
        scope.launch { try { audioRecorder.cancel() } catch (_: Exception) {} }
        super.onDestroy()
    }
}
