package com.georgernstgraf.polishedrecognition.audio

import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Encodes the live recording into small OGG fragments WHILE the user is
 * still dictating (#115, owner design): every [FRAGMENT_SECONDS] of
 * accumulated PCM is transcoded in its own MediaCodec/MediaMuxer run and
 * **committed** to the session directory with a manifest entry.
 *
 * Why fragments, not chunks: a mid-stream-cut OGG is undecodable (the
 * OpusHead/OpusTags header pages live at the stream start), but OGG
 * **chaining** (RFC 3533) is a native format feature — simply concatenating
 * self-contained fragment files produces a valid chained stream every
 * decoder (ffmpeg/Whisper) plays through, with a per-link encoder pre-skip
 * "tick" (~6.5 ms) at each seam. A 600-s upload chunk is therefore assembled
 * at stop time by byte-concatenating its [chunkFragments] fragments — no
 * custom muxer involved.
 *
 * The commit-every-step architecture (owner request): the PCM source is
 * append-only, so encoded fragments stay valid for the lifetime of the
 * session — a pipeline failure + retry reuses them all (a retry after a
 * slow STT endpoint pays ZERO re-encode), a resumed recording only adds
 * fragments. This survives process death via the session id persisted in
 * the controller's snapshot meta.
 *
 * Encoder-failure fallback: if the Opus transcode fails (e.g. no encoder on
 * the ROM), the whole session switches to WAV fragments — the already
 * encoded OGG fragments are discarded and re-encoded as WAV from the still
 * fully available PCM prefix, and chunks are then assembled as WAV files.
 * Never mixed-format chunks.
 */
class FragmentPreparer(
    private val pcm: PcmSource,
    private val transcoder: AudioTranscoder,
    private val sessionDir: File,
    private val sampleRate: Int = 16_000,
    private val fragmentBytes: Int = DEFAULT_FRAGMENT_BYTES,
    chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
    chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
    private val pollIntervalMs: Long = 500,
    private val logger: RotatingJsonLogger? = null,
    private val workerExecutor: ExecutorService = SHARED_EXECUTOR
) {

    data class Entry(val name: String, val bytes: Long)
    data class ManifestState(val fallbackToWav: Boolean, val fragments: List<Entry>)

    private val gson = Gson()
    private val entries = mutableListOf<Entry>()
    private var fallbackToWav = false
    private var nextFragment = 0

    /**
     * Upload chunk = this many fragments. Derived so a full chunk stays
     * within the STT limits: floor(600 s / 7 s) = 85 fragments = 595 s
     * (≤ 600 s cap, ~19 MB WAV / ~1.8 MB OGG). At least 1, so a
     * test-injected huge fragment size still assembles one chunk per
     * fragment.
     */
    private val chunkFragments =
        maxOf(1, ((chunkMaxSeconds * sampleRate * 2).toInt()) / fragmentBytes)

    @Volatile private var running = false
    private var workerFuture: Future<*>? = null

    init {
        sessionDir.mkdirs()
        recover()
    }

    /** Fragment size in PCM bytes (7 s · 16 kHz · 16 bit · mono). */
    companion object {
        const val FRAGMENT_SECONDS = 7.0
        const val DEFAULT_FRAGMENT_BYTES = (FRAGMENT_SECONDS * 32_000).toInt()
        private const val MANIFEST = "manifest.json"
        private val SHARED_EXECUTOR: ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "FragmentPreparer").apply { isDaemon = true }
            }
    }

    /**
     * Rebuilds state from a previous preparation of the same session dir:
     * the manifest's longest valid contiguous prefix (file present with the
     * recorded size) becomes the resume point; orphan files beyond it are
     * removed so a stale crash can never poison the stream.
     */
    private fun recover() {
        val manifest = File(sessionDir, MANIFEST)
        if (!manifest.isFile) return
        try {
            val state = gson.fromJson(manifest.readText(), ManifestState::class.java) ?: return
            fallbackToWav = state.fallbackToWav
            var count = 0
            for (entry in state.fragments) {
                val file = File(sessionDir, entry.name)
                if (file.isFile && file.length() == entry.bytes) count++ else break
            }
            nextFragment = count
            val keep = state.fragments.take(count).map { it.name }.toSet()
            sessionDir.listFiles()?.forEach { file ->
                if (file.name != MANIFEST && file.name !in keep) file.delete()
            }
            entries.clear()
            entries.addAll(state.fragments.take(count))
        } catch (_: Throwable) {
        }
    }

    /**
     * Starts the background worker: polls the PCM source and encodes every
     * full fragment as soon as it becomes complete. Idempotent.
     */
    fun startWorker() {
        if (running) return
        running = true
        workerFuture = workerExecutor.submit {
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
            } catch (_: Throwable) {
            }
            while (running) {
                val before = nextFragment
                encodeAvailableFragments()
                if (nextFragment == before) {
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    /** Signals the worker to stop and waits for the in-flight fragment. */
    fun stopWorker() {
        running = false
        workerFuture?.let { runCatching { it.get() } }
        workerFuture = null
    }

    /** Encodes every COMPLETE fragment currently available in the buffer. */
    fun encodeAvailableFragments() {
        val total = pcm.pcmSize()
        while ((nextFragment + 1).toLong() * fragmentBytes <= total) {
            encodeFragment(nextFragment)
            nextFragment++
        }
    }

    /**
     * Synchronously encodes everything not yet committed: first any full
     * fragments that became available, then the trailing partial fragment.
     * Called by the controller after the capture stopped — with 7-s
     * fragments this is sub-second work in the normal case.
     */
    fun prepareTailSync() {
        encodeAvailableFragments()
        if (pcm.pcmSize() > nextFragment.toLong() * fragmentBytes) {
            encodeFragment(nextFragment)
            nextFragment++
        }
    }

    /**
     * Concatenates the committed fragments into upload chunks of
     * [chunkFragments] fragments each (chained OGG, or plain WAV assembly
     * after the fallback). Returns [] when nothing was encoded at all —
     * callers fall back to the single-file path.
     */
    fun assembleChunks(): List<File> {
        if (entries.isEmpty()) return emptyList()
        val ext = if (fallbackToWav) "wav" else "ogg"
        val chunks = mutableListOf<File>()
        var index = 0
        var chunkNo = 1
        while (index < entries.size) {
            val groupEnd = minOf(index + chunkFragments, entries.size)
            val target = File(sessionDir, "recording_%d.%s".format(chunkNo, ext))
            FileOutputStream(target).use { out ->
                for (i in index until groupEnd) {
                    File(sessionDir, entries[i].name).inputStream().use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
            }
            chunks.add(target)
            index = groupEnd
            chunkNo++
        }
        return chunks
    }

    /** Discards all committed fragments (flush/cancel — the PCM they encoded is gone). */
    fun prune() {
        entries.clear()
        fallbackToWav = false
        nextFragment = 0
        sessionDir.listFiles()?.forEach { it.delete() }
    }

    private fun encodeFragment(index: Int) {
        val start = index.toLong() * fragmentBytes
        val end = minOf(start + fragmentBytes, pcm.pcmSize())
        if (end <= start) return
        val data = pcm.copyPcmRange(start, end)
        val started = System.currentTimeMillis()

        if (!fallbackToWav) {
            val target = File(sessionDir, "frag_%06d.ogg".format(index))
            try {
                transcoder.transcode(WavWriter.write(data, sampleRate), target)
                entries.add(Entry(target.name, target.length()))
            } catch (e: Exception) {
                // Session-level fallback: OGG is impossible on this ROM —
                // discard the OGG fragments and rebuild the WHOLE prefix as
                // WAV (the PCM prefix is immutable and fully available),
                // then continue in WAV mode below for this very fragment.
                fallbackToWav = true
                entries.clear()
                sessionDir.listFiles()?.forEach { file ->
                    if (file.name != MANIFEST) file.delete()
                }
                for (i in 0 until index) {
                    val rebuilt = writeWavFragment(
                        i,
                        pcm.copyPcmRange(
                            i.toLong() * fragmentBytes,
                            i.toLong() * fragmentBytes + fragmentBytes
                        )
                    )
                    entries.add(Entry(rebuilt.name, rebuilt.length()))
                }
                logger?.log(
                    "prepare",
                    gson.toJson(mapOf("fallback" to true, "fragment" to index, "error" to (e.message ?: "")))
                )
            }
        }

        val file = if (fallbackToWav) writeWavFragment(index, data) else {
            // OGG path already appended its entry above
                writeManifest()
            logFragment(index, data.size, started)
            return
        }
        entries.add(Entry(file.name, file.length()))
        writeManifest()
        logFragment(index, data.size, started)
    }

    private fun logFragment(index: Int, bytes: Int, started: Long) {
        logger?.log(
            "prepare",
            gson.toJson(
                mapOf(
                    "fragment" to index,
                    "bytes" to bytes,
                    "encodeMs" to (System.currentTimeMillis() - started),
                    "mode" to if (fallbackToWav) "wav" else "ogg"
                )
            )
        )
    }

    private fun writeWavFragment(index: Int, data: ByteArray): File {
        val target = File(sessionDir, "frag_%06d.wav".format(index))
        target.writeBytes(WavWriter.write(data, sampleRate))
        return target
    }

    private fun writeManifest() {
        try {
            File(sessionDir, MANIFEST).writeText(gson.toJson(ManifestState(fallbackToWav, entries.toList())))
        } catch (_: Throwable) {
        }
    }
}
