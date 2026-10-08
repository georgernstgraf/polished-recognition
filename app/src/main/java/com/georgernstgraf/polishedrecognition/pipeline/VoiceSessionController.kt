package com.georgernstgraf.polishedrecognition.pipeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.georgernstgraf.polishedrecognition.audio.AudioRecorder
import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.audio.OpusOggTranscoder
import com.georgernstgraf.polishedrecognition.audio.WavChunker
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class VoiceSessionController(
    context: Context,
    private val pipeline: TranscriptionPipeline,
    private val settings: SettingsStore,
    private val transcoder: AudioTranscoder = OpusOggTranscoder(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Upload-size limit per chunk in bytes (#115). */
    private val chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
    /** Upload-duration limit per chunk in seconds (#115). */
    private val chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS
) {

    enum class State { IDLE, RECORDING, PAUSED, PROCESSING }

    sealed class Event {
        data class StateChanged(val state: State) : Event()
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
    /**
     * Secondary observers (#82). The primary [callback] slot belongs to the
     * IME forever; a RecognitionService session observes the shared
     * singleton through these without deafening the keyboard. Secondary
     * listeners receive live events only — never held [pendingResult]
     * deliveries, which belong to the primary attach flow.
     */
    private val secondaryListeners = mutableSetOf<(Event) -> Unit>()

    /**
     * Registers a secondary observer without touching the primary callback
     * slot (#82). The observer owns its registration and must call
     * [removeSecondaryListener] when done.
     */
    fun addSecondaryListener(listener: (Event) -> Unit) {
        secondaryListeners += listener
    }

    fun removeSecondaryListener(listener: (Event) -> Unit) {
        secondaryListeners -= listener
    }

    fun start(onEvent: (Event) -> Unit) {
        if (state == State.RECORDING || state == State.PROCESSING) return
        callback = onEvent
        beginRecording()
    }

    /**
     * Starts a session without touching the primary callback slot (#82,
     * service entry point): same guard and recording setup as [start], but
     * the caller observes via its secondary registration instead of
     * becoming primary.
     */
    fun startShared(secondary: (Event) -> Unit) {
        if (state == State.RECORDING || state == State.PROCESSING) return
        addSecondaryListener(secondary)
        beginRecording()
    }

    private fun beginRecording() {
        accumulatedMs = 0L
        segStartMs = System.currentTimeMillis()
        state = State.RECORDING
        recorder.start()
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
        recorder.resume()
        emit(Event.StateChanged(state))
    }

    /**
     * @param callerPackage package of the app being dictated into (#100), used
     *   to resolve the per-app line-wrap width. `null` for callers that cannot
     *   provide one (bound service) → global width.
     */
    fun stopAndTranscribe(callerPackage: String? = null) {
        if (state != State.RECORDING && state != State.PAUSED) return
        if (state == State.RECORDING) {
            accumulatedMs += System.currentTimeMillis() - segStartMs
        }
        segStartMs = 0L
        // Non-destructive stop (#84): the PCM stays buffered so a failed
        // pipeline can park the session as PAUSED with audio + timer intact.
        // The buffer is discarded (flushBuffer) only after a successful
        // upload below.
        val wav = recorder.stopPreservingBuffer()
        // The session is over — its bytes are in hand, so any process-death
        // snapshot is stale from here on.
        clearSnapshot()
        state = State.PROCESSING
        emit(Event.StateChanged(state))

        transcribeJob = scope.launch {
            val result = try {
                val files = prepareAudioFiles(wav)
                val r = pipeline.transcribe(files, callerPackage) { stage ->
                    emit(Event.StageChanged(stage))
                }
                // On success the prepared uploads are consumed; on failure
                // they stay committed so the retry reuses them instead of
                // re-encoding (#115 stage memoization).
                if (r.isSuccess) clearPrepared()
                r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (result.isSuccess) {
                recorder.flushBuffer()
                deliver(result)
                accumulatedMs = 0L
                segStartMs = 0L
                state = State.IDLE
                emit(Event.StateChanged(state))
            } else {
                // Pipeline failure (#84): park as ordinary PAUSED — the audio
                // stays buffered (retry re-sends, resume appends), the timer
                // is preserved, and the snapshot is re-written so process
                // death is covered by restore(). The IME stays visible, shows
                // the failure Toast, and offers send/resume plus editable
                // quick settings — as if the send had never happened.
                state = State.PAUSED
                snapshot()
                emit(Event.StateChanged(state))
                deliver(result)
            }
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

    /**
     * Discards the recorded audio but keeps the session mode (#86, flush
     * button): RECORDING keeps capturing into a fresh buffer, PAUSED stays
     * paused with an empty buffer and a zeroed timer. Unlike [cancel], the
     * UI callback stays attached and the IME stays open — closing the IME
     * is cancel's job. No-op in IDLE (nothing recorded) and PROCESSING
     * (the upload bytes are already extracted, the pipeline owns them).
     */
    fun flush() {
        if (state != State.RECORDING && state != State.PAUSED) return
        recorder.flushBuffer()
        clearSnapshot()
        accumulatedMs = 0L
        if (state == State.RECORDING) segStartMs = System.currentTimeMillis()
        emit(Event.StateChanged(state))
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

    /**
     * Prepares the recording(s) for upload. With `compress_audio` enabled the
     * WAV is transcoded to Ogg/Opus; any transcoder failure falls back to the
     * original WAV so a recording is never lost over transcoding.
     *
     * Chunking (#115, port of aitranscribe's `chunk_audio`): uploads beyond
     * the STT limits (25 MB / 600 s) are split at sample boundaries BEFORE
     * compression — an Ogg is never split (impossible without re-encode),
     * each chunk is compressed (or falls back to WAV) individually.
     * Recordings within both limits keep the legacy single-file names
     * `recording.{wav,ogg}`.
     *
     * **Stage memoization (#115, owner request after the first on-device
     * long dictation):** every prepared upload file is committed under
     * `cacheDir/prepared/<sha256-of-wav>/` with a `manifest.json`. A failed
     * pipeline parks the session as PAUSED (the ↺ bar) and a retry recomputes
     * only the hash — matching prepared files are REUSED instead of being
     * re-encoded (a 3×400 s Opus transcode costs minutes of CPU; re-doing it
     * on every retry was pure waste). Files are reused only when the PCM is
     * byte-identical (resume appends change the hash → rebuild, which is
     * correct since chunk boundaries shift). The prepared dir is deleted
     * after a successful pipeline run and pruned when a different recording
     * is prepared, so at most one session's uploads ever sit in cacheDir.
     */
    private suspend fun prepareAudioFiles(wav: ByteArray): List<File> {
        val prepared = preparedDirFor(wav)
        if (prepared.reused) {
            Log.i(TAG, "Prepared upload reused from cache: ${prepared.dir.name} (${prepared.manifest.size} files)")
            return prepared.manifest.map { File(prepared.dir, it.name) }
        }

        val chunks = try {
            WavChunker.chunk(wav, chunkMaxBytes, chunkMaxSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "Chunk planning failed; using the full recording for upload", e)
            listOf(wav)
        }

        if (chunks.size <= 1) {
            val file = prepareSingleAudioFile(wav, prepared.dir)
            prepared.writeManifest(listOf(file))
            return listOf(file)
        }

        Log.w(
            TAG,
            "Recording exceeds upload limits, splitting into ${chunks.size} chunks " +
                "(largest ${chunks.maxOf { it.size } / 1024} kB)"
        )
        if (settings.compressAudio) {
            emit(Event.StageChanged(TranscriptionPipeline.TranscriptionStage.CompressingAudio))
        }
        val files = mutableListOf<File>()
        chunks.forEachIndexed { index, chunkWav ->
            files.add(prepareChunkFile(chunkWav, index, chunks.size, prepared.dir))
        }
        prepared.writeManifest(files)
        return files
    }

    private suspend fun prepareChunkFile(chunkWav: ByteArray, index: Int, total: Int, dir: File): File {
        val target = File(dir, "recording_%d.%s".format(index + 1, if (settings.compressAudio) "ogg" else "wav"))
        if (!settings.compressAudio) {
            return target.apply { writeBytes(chunkWav) }
        }
        return withContext(Dispatchers.IO) {
            try {
                val ogg = transcoder.transcode(chunkWav, target)
                Log.i(TAG, "Chunk %d/%d compressed: wav=%dB ogg=%dB".format(index + 1, total, chunkWav.size, ogg.length()))
                ogg
            } catch (e: Exception) {
                Log.w(TAG, "Chunk %d/%d Opus transcoding failed, falling back to WAV".format(index + 1, total), e)
                File(dir, "recording_%d.wav".format(index + 1)).apply { writeBytes(chunkWav) }
            }
        }
    }

    private suspend fun prepareSingleAudioFile(wav: ByteArray, dir: File): File {
        if (!settings.compressAudio) return writeWavFile(wav, dir)
        emit(Event.StageChanged(TranscriptionPipeline.TranscriptionStage.CompressingAudio))
        return try {
            withContext(Dispatchers.IO) {
                val ogg = transcoder.transcode(wav, File(dir, "recording.ogg"))
                Log.i(TAG, "Audio compressed: wav=${wav.size}B ogg=${ogg.length()}B")
                ogg
            }
        } catch (e: Exception) {
            Log.w(TAG, "Opus transcoding failed, falling back to WAV", e)
            writeWavFile(wav, dir)
        }
    }

    private fun writeWavFile(wav: ByteArray, dir: File): File =
        File(dir, "recording.wav").apply { writeBytes(wav) }

    /** Upload manifest entry: file name + size, validated against disk on reuse. */
    private data class ManifestEntry(val name: String, val bytes: Long)

    /** A committed preparation: its directory, the upload manifest, and whether it was reused. */
    private class Prepared(
        val dir: File,
        val manifest: List<ManifestEntry>,
        val reused: Boolean
    ) {
        fun writeManifest(files: List<File>) {
            val entries = files.map { ManifestEntry(it.name, it.length()) }
            try {
                File(dir, MANIFEST).writeText(
                    com.google.gson.Gson().toJson(mapOf("files" to entries.map { mapOf("name" to it.name, "bytes" to it.bytes) }))
                )
            } catch (_: Throwable) {
            }
        }

        companion object {
            const val MANIFEST = "manifest.json"
        }
    }

    /**
     * Returns the prepared dir for `wav`: reused (manifest valid, all files
     * present with the recorded sizes) when the PCM is byte-identical to a
     * previous preparation, freshly created (previous dirs pruned) otherwise.
     */
    private fun preparedDirFor(wav: ByteArray): Prepared {
        val key = try {
            sha256Hex(wav).substring(0, 16)
        } catch (_: Throwable) {
            "fallback${System.currentTimeMillis()}"
        }
        val root = File(appContext.cacheDir, PREPARED_DIR)
        val dir = File(root, key)
        if (dir.isDirectory) {
            val entries = readManifest(dir)
            if (entries != null && entries.all { File(dir, it.name).let { f -> f.exists() && f.length() == it.bytes } }) {
                return Prepared(dir, entries, reused = true)
            }
            // stale or broken: rebuild in place
            dir.deleteRecursively()
        } else {
            // bound the cache to one session: prune other recordings' dirs
            root.listFiles()?.forEach { if (it.name != key) it.deleteRecursively() }
        }
        dir.mkdirs()
        return Prepared(dir, emptyList(), reused = false)
    }

    private fun readManifest(dir: File): List<ManifestEntry>? = try {
        val raw = File(dir, Prepared.MANIFEST).readText()
        @Suppress("UNCHECKED_CAST")
        val parsed = com.google.gson.Gson().fromJson(raw, Map::class.java) as Map<String, Any>
        (parsed["files"] as List<Map<String, Any>>).map { entry ->
            ManifestEntry(entry["name"] as String, (entry["bytes"] as Double).toLong())
        }
    } catch (_: Throwable) {
        null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Deletes the prepared-upload dir after a successful pipeline run. */
    private fun clearPrepared() {
        try {
            File(appContext.cacheDir, PREPARED_DIR).deleteRecursively()
        } catch (_: Throwable) {
        }
    }

    /**
     * Delivers a finished pipeline result to whichever consumer is attached
     * (#82). Both the primary (IME) callback and any secondary observers get
     * the live `Completed` event; only when nobody is listening is the result
     * held in [pendingResult] for the next primary [attach] (rotation, #83).
     *
     * A secondary-only consumer — the bound `RecognitionService` — must be
     * served here too: otherwise its session falls through to
     * `StateChanged(IDLE)` and is reported to the caller as `ERROR_CLIENT`
     * even though the pipeline produced a good result.
     */
    private fun deliver(result: Result<String>) {
        if (callback != null || secondaryListeners.isNotEmpty()) {
            emit(Event.Completed(result))
        } else {
            pendingResult = result
        }
    }

    private fun emit(event: Event) {
        val cb = callback
        // Snapshot: a listener removed between post and delivery must not
        // fire — re-read inside the posted block too.
        if (cb == null && secondaryListeners.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb?.invoke(event)
            secondaryListeners.toList().forEach { it.invoke(event) }
        } else {
            mainHandler.post {
                cb?.invoke(event)
                secondaryListeners.toList().forEach { it.invoke(event) }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceSessionController"
        private const val SNAP_PCM = "session.pcm"
        private const val SNAP_META = "session.meta"
        private const val PREPARED_DIR = "prepared"
    }
}
