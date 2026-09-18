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
    private var pendingResult: Result<String>? = null
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

    /**
     * (Re-)binds the UI callback. A transcription result that completed while
     * no UI was bound (e.g. display rotation during PROCESSING, #83) is held
     * in [pendingResult] and delivered now, so it is committed exactly as if
     * no rotation had happened.
     */
    fun attach(onEvent: (Event) -> Unit) {
        callback = onEvent
        pendingResult?.let {
            pendingResult = null
            emit(Event.Completed(it))
        }
    }

    fun detach() {
        callback = null
    }

    fun pause() {
        if (state != State.RECORDING) return
        accumulatedMs += System.currentTimeMillis() - segStartMs
        state = State.PAUSED
        recorder.pause()
        // Persist the session while it is safely stopped: if the process dies
        // (observed on Oplus across rotation), the next bind restores it via
        // restore() instead of losing the dictation (#67).
        snapshot()
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
        // The session is over — its bytes are in hand, so any process-death
        // snapshot is stale from here on.
        clearSnapshot()
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
            if (callback != null) {
                emit(Event.Completed(result))
            } else {
                pendingResult = result
            }
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
        clearSnapshot()
        accumulatedMs = 0L
        segStartMs = 0L
        pendingResult = null
        state = State.IDLE
        emit(Event.StateChanged(state))
        callback = null
    }

    fun recordedDurationMs(): Long {
        val live = if (state == State.RECORDING) System.currentTimeMillis() - segStartMs else 0L
        return accumulatedMs + live
    }

    /**
     * Persists the live session (raw PCM + recorded duration) to `cacheDir`
     * so a dead process can pick it up via [restore]. Best-effort and
     * synchronous: called with the recorder stopped (pause/teardown), never
     * mid-capture. A failure must never break the in-memory session.
     */
    fun snapshot() {
        if (state != State.RECORDING && state != State.PAUSED) return
        try {
            val total = accumulatedMs +
                if (state == State.RECORDING) System.currentTimeMillis() - segStartMs else 0L
            File(appContext.cacheDir, SNAP_PCM).writeBytes(recorder.snapshotPcm())
            File(appContext.cacheDir, SNAP_META).writeText(total.toString())
        } catch (_: Throwable) {
            // Best-effort: snapshotting must never break the live session —
            // this explicitly includes OutOfMemoryError on absurdly large
            // buffers, not just ordinary I/O failures.
        }
    }

    /**
     * Restores a snapshot left by a dead process and parks the session as
     * PAUSED (resume appends to the restored audio, the timer continues from
     * the saved duration). Returns true when a snapshot was consumed; the
     * files are deleted so only the first bind after death restores. Emits
     * `StateChanged(PAUSED)` when a UI is already attached.
     */
    fun restore(): Boolean {
        if (state != State.IDLE) return false
        try {
            val meta = File(appContext.cacheDir, SNAP_META)
            val pcmFile = File(appContext.cacheDir, SNAP_PCM)
            if (!meta.exists() || !pcmFile.exists()) return false
            val total = meta.readText().trim().toLongOrNull() ?: return false
            recorder.restorePcm(pcmFile.readBytes())
            meta.delete()
            pcmFile.delete()
            accumulatedMs = total.coerceAtLeast(0L)
            segStartMs = 0L
            state = State.PAUSED
            emit(Event.StateChanged(state))
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun clearSnapshot() {
        try {
            File(appContext.cacheDir, SNAP_PCM).delete()
            File(appContext.cacheDir, SNAP_META).delete()
        } catch (_: Throwable) {
        }
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
        emit(Event.StageChanged(TranscriptionPipeline.TranscriptionStage.CompressingAudio))
        return try {
            withContext(Dispatchers.IO) {
                val ogg = transcoder.transcode(wav, File(appContext.cacheDir, "recording.ogg"))
                Log.i(TAG, "Audio compressed: wav=${wav.size}B ogg=${ogg.length()}B")
                ogg
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
        private const val SNAP_PCM = "session.pcm"
        private const val SNAP_META = "session.meta"
    }
}
