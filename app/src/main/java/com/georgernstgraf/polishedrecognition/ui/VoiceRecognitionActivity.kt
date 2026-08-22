package com.georgernstgraf.polishedrecognition.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VoiceRecognitionActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 42
        const val NONE_TARGET_LANGUAGE = "None"

        fun buildLanguageList(customLanguages: List<String>): List<String> =
            listOf(NONE_TARGET_LANGUAGE, "English") + customLanguages.sorted()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: VoiceSessionController
    private var blinkAnimator: ValueAnimator? = null
    private var timerJob: Job? = null
    private var processingDurationStr = "00:00"
    private var silenceRawListener = false

    private val statusText: TextView by lazy { findViewById(R.id.status_text) }
    private val cancelButton: Button by lazy { findViewById(R.id.cancel_button) }
    private val pauseResumeButton: MaterialButton by lazy { findViewById(R.id.pause_resume_button) }
    private val stopButton: Button by lazy { findViewById(R.id.stop_button) }
    private val elapsedText: TextView by lazy { findViewById(R.id.elapsed_text) }
    private val settingsButton: View by lazy { findViewById(R.id.settings_button) }
    private val quickLanguage: AutoCompleteTextView by lazy { findViewById(R.id.quick_language) }
    private val quickRaw: CheckBox by lazy { findViewById(R.id.quick_raw) }
    private val settings: SettingsStore by lazy { (application as PolishedRecognitionApp).settingsStore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_input)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancelAndFinish() }
        })

        controller = VoiceSessionController(
            this,
            (application as PolishedRecognitionApp).transcriptionPipeline
        )

        cancelButton.setOnClickListener { cancelAndFinish() }

        pauseResumeButton.setOnClickListener {
            when (controller.state) {
                VoiceSessionController.State.RECORDING -> controller.pause()
                VoiceSessionController.State.PAUSED -> controller.resume()
                else -> Unit
            }
        }

        stopButton.setOnClickListener { controller.stopAndTranscribe() }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupQuickSettings()

        if (hasRecordPermission()) {
            controller.start { onControllerEvent(it) }
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private fun onControllerEvent(event: VoiceSessionController.Event) {
        when (event) {
            is VoiceSessionController.Event.StateChanged -> onStateChanged(event.state)
            is VoiceSessionController.Event.StageChanged -> onStage(event.stage)
            is VoiceSessionController.Event.Completed -> {
                val text = event.result.getOrNull()
                if (text != null) {
                    returnResults(arrayListOf(text))
                } else {
                    val msg = event.result.exceptionOrNull()?.message ?: "Transcription failed"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    returnResults(ArrayList())
                }
            }
            is VoiceSessionController.Event.RmsChanged,
            is VoiceSessionController.Event.SpeechBegin -> Unit
        }
    }

    private fun onStateChanged(state: VoiceSessionController.State) {
        when (state) {
            VoiceSessionController.State.RECORDING -> {
                statusText.text = "Recording\u2026"
                showPauseIcon()
                setButtonsForRecording()
                startBlink()
                startTimer()
            }
            VoiceSessionController.State.PAUSED -> {
                statusText.text = "Paused"
                showResumeIcon()
                setButtonsForPause()
                stopBlink()
                stopTimer()
            }
            VoiceSessionController.State.PROCESSING -> {
                processingDurationStr = formatDuration(controller.recordedDurationMs())
                statusText.text = "Processing\u2026"
                setButtonsForProcessing()
                stopBlink()
                stopTimer()
            }
            VoiceSessionController.State.IDLE -> {
                stopBlink()
                stopTimer()
            }
        }
    }

    private fun onStage(stage: TranscriptionPipeline.TranscriptionStage) {
        when (stage) {
            is TranscriptionPipeline.TranscriptionStage.RequestingStt ->
                elapsedText.text = "requesting $processingDurationStr STT\u2026"
            is TranscriptionPipeline.TranscriptionStage.RequestingLlm -> {
                val tl = settings.targetLanguage
                val base = if (tl != null) "requesting clean up ($tl)"
                    else "requesting clean up"
                elapsedText.text = "$base, ~ ${stage.wordCount} words\u2026"
            }
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            controller.start { onControllerEvent(it) }
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showPauseIcon() {
        pauseResumeButton.setIconResource(R.drawable.ic_pause)
        pauseResumeButton.contentDescription = "Pause"
        pauseResumeButton.backgroundTintList = ColorStateList.valueOf(0xFFF57F17.toInt())
    }

    private fun showResumeIcon() {
        pauseResumeButton.setIconResource(R.drawable.ic_resume)
        pauseResumeButton.contentDescription = "Resume"
        pauseResumeButton.backgroundTintList = ColorStateList.valueOf(0xFF2E7D32.toInt())
    }

    private fun setButtonsForRecording() {
        cancelButton.isEnabled = true
        pauseResumeButton.isEnabled = true
        stopButton.isEnabled = true
        settingsButton.isEnabled = false
        settingsButton.alpha = 0.3f
    }

    private fun setButtonsForPause() {
        cancelButton.isEnabled = true
        pauseResumeButton.isEnabled = true
        stopButton.isEnabled = true
        settingsButton.isEnabled = true
        settingsButton.alpha = 1.0f
    }

    private fun setButtonsForProcessing() {
        cancelButton.isEnabled = false
        pauseResumeButton.isEnabled = false
        stopButton.isEnabled = false
        settingsButton.isEnabled = false
        settingsButton.alpha = 0.3f
        quickLanguage.isEnabled = false
        quickLanguage.alpha = 0.3f
        quickRaw.isEnabled = false
    }

    private fun setupQuickSettings() {
        quickLanguage.threshold = Int.MAX_VALUE
        quickLanguage.setOnClickListener { quickLanguage.showDropDown() }

        quickLanguage.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            settings.targetLanguage = if (selected == NONE_TARGET_LANGUAGE) null else selected
        }

        quickRaw.setOnCheckedChangeListener { _, isChecked ->
            if (silenceRawListener) return@setOnCheckedChangeListener
            settings.rawMode = isChecked
            updateLanguageDropdownEnabled()
        }
    }

    private fun refreshQuickSettings() {
        val languages = buildLanguageList(settings.customLanguages)
        quickLanguage.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languages)
        )

        silenceRawListener = true
        quickRaw.isChecked = settings.rawMode
        silenceRawListener = false

        quickLanguage.setText(settings.targetLanguage ?: NONE_TARGET_LANGUAGE, false)
        updateLanguageDropdownEnabled()
    }

    private fun updateLanguageDropdownEnabled() {
        val disabled = quickRaw.isChecked
        quickLanguage.isEnabled = !disabled
        quickLanguage.alpha = if (disabled) 0.3f else 1.0f
    }

    override fun onResume() {
        super.onResume()
        refreshQuickSettings()
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
        controller.cancel()
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
                elapsedText.text = formatDuration(controller.recordedDurationMs())
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    override fun onDestroy() {
        stopBlink()
        stopTimer()
        controller.cancel()
        super.onDestroy()
    }
}
