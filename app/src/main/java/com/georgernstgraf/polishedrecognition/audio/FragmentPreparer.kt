package com.georgernstgraf.polishedrecognition.audio

import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Encodes the live recording into small OGG fragments WHILE the user is
 * still dictating (#115, owner design): every ~[FRAGMENT_SECONDS] of
 * accumulated PCM is transcoded in its own MediaCodec/Muxer run and
 * **committed** to the session directory with a manifest entry.
 *
 * Why fragments, not chunks: a mid-stream-cut OGG is undecodable (the
 * OpusHead/OpusTags header pages live at the stream start), but OGG
 * **chaining** (RFC 3533) is a native format feature — simply concatenating
 * self-contained fragment files produces a valid chained stream every
 * decoder (ffmpeg/Whisper) plays through, with a per-link encoder pre-skip
 * "tick" (~6.5 ms) at each seam. A 600-s upload chunk is therefore assembled
 * at stop time by byte-concatenating its fragments — no custom muxer
 * involved.
 *
 * **Silence-aligned boundaries (#117):** the fixed 7-s byte cuts split words
 * at the seams (the owner observed consistent word drops), and each
 * fragment's transcript started cold. The cut point is therefore MOVED into
 * silence: the nominal boundary stays at [FRAGMENT_SECONDS], but the encoder
 * scans forward up to [DEFAULT_SEARCH_SECONDS] for a silence run
 * ([SilenceCutter]) and cuts at its center — fragment *i* ends in a pause,
 * fragment *i+1* starts just before the next onset. The stream stays
 * GAPLESS: fragment *i+1* starts exactly where *i* ended, so the assembled
 * chunks still reproduce the original PCM byte-for-byte. No qualifying
 * silence within the search window (continuous speech) → hard cut at the
 * nominal boundary, as before. Finding the cut may wait for up to the
 * search window of extra audio — a ≤2-s commit-delay cadence cost, still
 * far below the 7-s fragment length.
 *
 * The commit-every-step architecture (owner request): the PCM source is
 * append-only, so encoded fragments stay valid for the lifetime of the
 * session — a pipeline failure + retry reuses them all (a retry after a
 * slow STT endpoint pays ZERO re-encode), a resumed recording only adds
 * fragments. This survives process death via the session id persisted in
 * the controller's snapshot meta. Fragment boundaries are recorded in the
 * manifest ([Entry.end]) so a restored preparer continues at the exact
 * last cut — legacy manifests (without offsets) are discarded and the
 * prefix re-encoded (the PCM is fully available; costs seconds).
 *
 * Encoder-failure fallback: if the Opus transcode fails (e.g. no encoder on
 * the ROM), the whole session switches to WAV fragments — the already
 * encoded OGG fragments are discarded and re-encoded as WAV from the still
 * fully available PCM prefix, and chunks are then assembled as WAV files.
 * Never mixed-format chunks. In [wavMode] the session writes WAV fragments
 * from the start (#116): the live transcription path runs regardless of
 * `compress_audio`, which only selects the fragment format.
 */
class FragmentPreparer(
    private val pcm: PcmSource,
    private val transcoder: AudioTranscoder,
    private val sessionDir: File,
    private val sampleRate: Int = 16_000,
    private val fragmentBytes: Int = DEFAULT_FRAGMENT_BYTES,
    chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
    chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
    /**
     * WAV-fragment mode (#116): skip the Opus transcoder entirely and write
     * plain WAV fragments — the live transcription path runs regardless of
     * `compress_audio`; the setting only selects the fragment format. No
     * MediaCodec needed, robust on ROMs without an Opus encoder.
     */
    private val wavMode: Boolean = false,
    /**
     * Forward silence-search window in PCM bytes (#117). Tests inject small
     * values: 0 reproduces the legacy fixed-offset cuts exactly.
     */
    private val searchBytes: Int = DEFAULT_SEARCH_BYTES,
    private val pollIntervalMs: Long = 500,
    private val logger: RotatingJsonLogger? = null
) {

    /** `end` = PCM byte offset AFTER this fragment (#117) — 0 marks legacy. */
    data class Entry(val name: String, val bytes: Long, val end: Long)
    data class ManifestState(val fallbackToWav: Boolean, val fragments: List<Entry>)

    private val gson = Gson()

    // CopyOnWriteArrayList: mutated by the encoder thread (add on commit,
    // clear on fallback/prune), read concurrently by [committedFragments]
    // from the controller coroutine (#116) — snapshot iteration without locks.
    private val entries = CopyOnWriteArrayList<Entry>()
    private var fallbackToWav = false
    private var nextFragment = 0

    /**
     * Per-fragment commit hook (#116): fired (best-effort, on the encoder
     * thread) after a fragment is committed to disk + manifest — the live
     * transcription worker consumes fragments through this as they close.
     * `index` is the fragment's position in the stream, `file` the committed
     * OGG/WAV fragment file.
     */
    var onFragmentCommitted: ((index: Int, file: File) -> Unit)? = null

    /**
     * Upload chunk cap in PCM bytes (#117): the smaller of the byte limit
     * (25 MB WAV) and the duration limit (600 s = 19.2 MB of 16-kHz mono
     * 16-bit PCM). Chunks accumulate fragments until adding the next would
     * exceed this — with variable fragment lengths a count-based grouping
     * could overshoot the cap, and the Phase 2 auto-sizer may settle on
     * fragment sizes different from the default, so the cap must be
     * enforced per accumulated byte, not per fragment count.
     */
    private val chunkMaxPcmBytes = minOf(
        chunkMaxBytes.toLong(),
        (chunkMaxSeconds * sampleRate * 2).toLong()
    )

    @Volatile private var running = false
    private var workerThread: Thread? = null

    init {
        sessionDir.mkdirs()
        recover()
        // WAV mode is a session property: force the flag AFTER recover so a
        // stale OGG manifest can never mix formats into a WAV session.
        if (wavMode) fallbackToWav = true
    }

    /** Fragment size in PCM bytes (7 s · 16 kHz · 16 bit · mono). */
    companion object {
        const val FRAGMENT_SECONDS = 7.0
        const val DEFAULT_FRAGMENT_BYTES = (FRAGMENT_SECONDS * 32_000).toInt()

        /** Default forward silence-search window (#117): 2 s. */
        const val DEFAULT_SEARCH_SECONDS = 2.0
        val DEFAULT_SEARCH_BYTES = (DEFAULT_SEARCH_SECONDS * 32_000).toInt()
        private const val MANIFEST = "manifest.json"
    }

    /**
     * Rebuilds state from a previous preparation of the same session dir:
     * the manifest's longest valid contiguous prefix (file present with the
     * recorded size) becomes the resume point; orphan files beyond it are
     * removed so a stale crash can never poison the stream.
     *
     * Fragment boundaries must be known to continue the stream gaplessly
     * (#117): a manifest without `end` offsets (pre-#117) is discarded
     * wholesale — the PCM prefix is fully available and re-encoding it
     * costs seconds, while a wrong boundary would corrupt every chunk.
     */
    private fun recover() {
        val manifest = File(sessionDir, MANIFEST)
        if (!manifest.isFile) return
        try {
            val state = gson.fromJson(manifest.readText(), ManifestState::class.java) ?: return
            fallbackToWav = state.fallbackToWav
            val legacy = state.fragments.any { it.end <= 0L }
            if (legacy) {
                sessionDir.listFiles()?.forEach { file ->
                    if (file.name != MANIFEST) file.delete()
                }
                fallbackToWav = wavMode
                return
            }
            var count = 0
            for (entry in state.fragments) {
                val file = File(sessionDir, entry.name)
                if (file.isFile && file.length() == entry.bytes && entry.end <= pcm.pcmSize()) {
                    count++
                } else break
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

    /** PCM offset where the next fragment begins: the last committed end (#117). */
    private fun nextFragmentStart(): Long =
        entries.lastOrNull()?.end?.takeIf { it > 0 } ?: (nextFragment.toLong() * fragmentBytes)

    /** PCM bytes fragment [index] spans, derived from the committed boundaries. */
    private fun fragmentPcmBytes(index: Int): Long {
        val end = entries[index].end
        val start = if (index == 0) 0L else entries[index - 1].end
        return (end - start).coerceAtLeast(0L)
    }

    /**
     * Starts the background worker on a DEDICATED daemon thread: the worker
     * loop runs (potentially forever, until [stopWorker]) — a shared
     * single-thread executor would deadlock the NEXT session's
     * [stopWorker] behind an abandoned predecessor's never-ending future
     * (found in the #116 test suite). Idempotent.
     */
    fun startWorker() {
        if (running) return
        running = true
        workerThread = Thread {
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
        }.apply {
            name = "FragmentPreparer"
            isDaemon = true
            start()
        }
    }

    /** Signals the worker to stop and waits for the in-flight fragment. */
    fun stopWorker() {
        running = false
        workerThread?.let {
            it.interrupt()
            runCatching { it.join() }
        }
        workerThread = null
    }

    /**
     * Encodes every fragment whose cut point is DECIDABLE (#117): a
     * fragment commits once either a silence run was found within the
     * searched window, or the full search window is available (hard cut at
     * the nominal boundary). With [searchBytes] = 0 this degenerates to the
     * legacy fixed-offset behavior.
     */
    fun encodeAvailableFragments() {
        while (true) {
            val start = nextFragmentStart()
            val nominalEnd = start + fragmentBytes
            val total = pcm.pcmSize()
            if (total < nominalEnd) return
            val windowEnd = minOf(total, nominalEnd + searchBytes.toLong())
            val cut = if (windowEnd > nominalEnd) {
                SilenceCutter.findCut(pcm.copyPcmRange(nominalEnd, windowEnd))
                    ?.let { nominalEnd + it }
            } else null
            if (cut == null && windowEnd < nominalEnd + searchBytes) {
                // window still growing and no silence yet — wait for more
                // audio rather than splitting a word unnecessarily
                return
            }
            encodeFragment(nextFragment, start, cut ?: nominalEnd)
            nextFragment++
        }
    }

    /**
     * Synchronously encodes everything not yet committed: first any full
     * fragments that became available, then the trailing partial fragment
     * (which is the LAST fragment — no seam follows it, so the remainder is
     * taken whole). Called by the controller after the capture stopped —
     * with 7-s fragments this is sub-second work in the normal case.
     */
    fun prepareTailSync() {
        encodeAvailableFragments()
        val start = nextFragmentStart()
        val total = pcm.pcmSize()
        if (total > start) {
            encodeFragment(nextFragment, start, total)
            nextFragment++
        }
    }

    /**
     * Concatenates the committed fragments into upload chunks: fragments
     * accumulate until the next one would exceed the PCM-byte cap
     * ([chunkMaxPcmBytes], #117) — duration/byte limits hold for ANY
     * fragment size, which the Phase 2 auto-sized fragments require.
     * Returns [] when nothing was encoded at all — callers fall back to the
     * single-file path.
     */
    fun assembleChunks(): List<File> {
        if (entries.isEmpty()) return emptyList()
        val ext = if (fallbackToWav) "wav" else "ogg"
        val chunks = mutableListOf<File>()
        var index = 0
        var chunkNo = 1
        while (index < entries.size) {
            var acc = 0L
            var groupEnd = index
            while (groupEnd < entries.size) {
                val fragBytes = fragmentPcmBytes(groupEnd)
                if (groupEnd > index && acc + fragBytes > chunkMaxPcmBytes) break
                acc += fragBytes
                groupEnd++
            }
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
        fallbackToWav = wavMode
        nextFragment = 0
        sessionDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * (index, file) of every committed fragment, in commit order (#116).
     * Safe to call while the encoder thread runs — COW snapshot semantics.
     */
    fun committedFragments(): List<Pair<Int, File>> =
        entries.mapIndexed { index, entry -> index to File(sessionDir, entry.name) }

    private fun encodeFragment(index: Int, start: Long, end: Long) {
        if (end <= start) return
        val data = pcm.copyPcmRange(start, end)
        val started = System.currentTimeMillis()
        val silenceAligned = (end - start) != fragmentBytes.toLong() ||
            (start % fragmentBytes != 0L)

        if (!fallbackToWav && !wavMode) {
            val target = File(sessionDir, "frag_%06d.ogg".format(index))
            try {
                transcoder.transcode(WavWriter.write(data, sampleRate), target)
                entries.add(Entry(target.name, target.length(), end))
            } catch (e: Exception) {
                // Session-level fallback: OGG is impossible on this ROM —
                // discard the OGG fragments and rebuild the WHOLE prefix as
                // WAV (the PCM prefix is immutable and fully available),
                // then continue in WAV mode below for this very fragment.
                // The committed boundaries (#117) are captured BEFORE the
                // clear so the rebuild reproduces the exact same ranges.
                val boundaries = entries.map { it.end }
                fallbackToWav = true
                entries.clear()
                sessionDir.listFiles()?.forEach { file ->
                    if (file.name != MANIFEST) file.delete()
                }
                for (i in 0 until index) {
                    val s = if (i == 0) 0L else boundaries[i - 1].takeIf { it > 0 }
                        ?: (i.toLong() * fragmentBytes)
                    val e = boundaries.getOrNull(i)?.takeIf { it > 0 }
                        ?: (s + fragmentBytes)
                    val rebuilt = writeWavFragment(i, pcm.copyPcmRange(s, e))
                    entries.add(Entry(rebuilt.name, rebuilt.length(), e))
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
            logFragment(index, data.size, end, silenceAligned, started)
            notifyCommitted(index, "frag_%06d.ogg".format(index))
            return
        }
        entries.add(Entry(file.name, file.length(), end))
        writeManifest()
        logFragment(index, data.size, end, silenceAligned, started)
        notifyCommitted(index, file.name)
    }

    private fun notifyCommitted(index: Int, name: String) {
        try {
            onFragmentCommitted?.invoke(index, File(sessionDir, name))
        } catch (_: Throwable) {
        }
    }

    /**
     * Fragment evidence with the #117 boundary data: `pcmBytes` is the
     * fragment's true PCM length (variable with silence-aligned cuts),
     * `silenceAligned` records whether the cut came from the silence
     * search rather than a hard/nominal boundary — the on-device seam
     * quality check reads this from `prepare.json`.
     */
    private fun logFragment(index: Int, bytes: Int, end: Long, silenceAligned: Boolean, started: Long) {
        logger?.log(
            "prepare",
            gson.toJson(
                mapOf(
                    "fragment" to index,
                    "bytes" to bytes,
                    "pcmEnd" to end,
                    "silenceAligned" to silenceAligned,
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
