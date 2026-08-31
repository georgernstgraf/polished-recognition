package com.georgernstgraf.polishedrecognition.pipeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioRecorderListener
import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.audio.OpusOggTranscoder
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VoiceSessionController(
    context: Context,
    private val pipeline: TranscriptionPipeline,
    private val settings: SettingsStore,
    private val transcoder: AudioTranscoder = OpusOggTranscoder(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {

    enum class State { IDLE, RECORDING, PAUSED, PROCESSING }

    sealed class Event {
        data class StateChanged(val state: State) : Event()
        data class RmsChanged(val rms: Float) : Event()
        object SpeechBegin : Event()
        data class StageChanged(val stage: TranscriptionPipeline.TranscriptionStage) : Event()
        data class Completed(val result: Result<String>) : Event()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder = AudioRecorder()

    var state: State = State.IDLE
        private set

    private var callback: ((Event) -> Unit)? = null
    private var accumulatedMs = 0L
    private var segStartMs = 0L
    private var transcribeJob: Job? = null

    fun start(onEvent: (Event) -> Unit) {
        if (state == State.RECORDING || state == State.PROCESSING) return
        callback = onEvent
        accumulatedMs = 0L
        segStartMs = System.currentTimeMillis()
        state = State.RECORDING
        recorder.start(makeRecorderListener())
        emit(Event.StateChanged(state))
    }

    fun pause() {
        if (state != State.RECORDING) return
        accumulatedMs += System.currentTimeMillis() - segStartMs
        state = State.PAUSED
        recorder.pause()
        emit(Event.StateChanged(state))
    }

    fun resume() {
        if (state != State.PAUSED) return
        segStartMs = System.currentTimeMillis()
        state = State.RECORDING
        recorder.resume(makeRecorderListener())
        emit(Event.StateChanged(state))
    }

    fun stopAndTranscribe() {
        if (state != State.RECORDING && state != State.PAUSED) return
        if (state == State.RECORDING) {
            accumulatedMs += System.currentTimeMillis() - segStartMs
        }
        val wav = recorder.stop()
        state = State.PROCESSING
        emit(Event.StateChanged(state))

        transcribeJob = scope.launch {
            val result = try {
                val file = prepareAudioFile(wav)
                val r = pipeline.transcribe(file) { stage -> emit(Event.StageChanged(stage)) }
                file.delete()
                r
            } catch (e: Exception) {
                Result.failure(e)
            }
            emit(Event.Completed(result))
            accumulatedMs = 0L
            segStartMs = 0L
            state = State.IDLE
            emit(Event.StateChanged(state))
        }
    }

    fun cancel() {
        transcribeJob?.cancel()
        transcribeJob = null
        runCatching { recorder.cancel() }
        accumulatedMs = 0L
        segStartMs = 0L
        state = State.IDLE
        emit(Event.StateChanged(state))
        callback = null
    }

    fun recordedDurationMs(): Long {
        val live = if (state == State.RECORDING) System.currentTimeMillis() - segStartMs else 0L
        return accumulatedMs + live
    }

    private fun makeRecorderListener() = object : AudioRecorderListener {
        override fun onRmsChanged(rms: Float) = emit(Event.RmsChanged(rms))
        override fun onSpeechBegin() = emit(Event.SpeechBegin)
    }

    /**
     * Writes the recording for upload. With `compress_audio` enabled the WAV is
     * transcoded to Ogg/Opus; any transcoder failure falls back to the original
     * WAV so a recording is never lost over transcoding.
     */
    private suspend fun prepareAudioFile(wav: ByteArray): File {
        if (!settings.compressAudio) return writeWavFile(wav)
        return try {
            withContext(Dispatchers.IO) {
                transcoder.transcode(wav, File(appContext.cacheDir, "recording.ogg"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Opus transcoding failed, falling back to WAV", e)
            writeWavFile(wav)
        }
    }

    private fun writeWavFile(wav: ByteArray): File =
        File(appContext.cacheDir, "recording.wav").apply { writeBytes(wav) }

    private fun emit(event: Event) {
        val cb = callback ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.invoke(event)
        } else {
            mainHandler.post { cb.invoke(event) }
        }
    }

    companion object {
        private const val TAG = "VoiceSessionController"
    }
}
