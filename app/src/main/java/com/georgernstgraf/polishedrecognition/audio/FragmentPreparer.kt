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
 * far below the fragment length.
 *
 * **Acoustic pre-roll (#117 round 2):** the text prompt conditions only the
 * decoder — it cannot add acoustic context, and a server-side VAD can trim
 * quiet onsets near the clip edge. Every fragment ≥ 1 therefore ALSO gets a
 * `preroll_%06d.<ext>` side file: the last [PRE_ROLL_SECONDS] of PCM before
 * its start, in the SAME format as the fragments. The fragment FILES stay
 * gapless (chunk assembly still reproduces the original PCM byte-for-byte) —
 * the pre-roll exists only to be prepended to the fragment's UPLOAD
 * ([com.georgernstgraf.polishedrecognition.pipeline.FragmentTranscriber]
 * byte-concatenates `preRoll + fragment` into the upload; OGG chaining makes
 * that a valid stream). The echoed pre-roll text is trimmed from the
 * transcript by token-matching against the previous fragment's known
 * transcript tail. A failed pre-roll encode degrades to `null` and must
 * NEVER trigger the session-level WAV fallback — the upload simply omits it.
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
    /**
     * Acoustic pre-roll length in PCM bytes (#117 round 2): the tail of the
     * previous fragment re-sent ahead of this one IN THE UPLOAD ONLY (the
     * fragment files stay gapless). Tests inject 0 (off), mirroring
     * [searchBytes].
     */
    private val preRollBytes: Int = DEFAULT_PRE_ROLL_BYTES,
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
     * OGG/WAV fragment file, `preRoll` the acoustic pre-roll side file
     * (#117 round 2; `null` for fragment 0 or when its encode failed).
     */
    var onFragmentCommitted: ((index: Int, file: File, preRoll: File?) -> Unit)? = null

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

    /** Fragment size in PCM bytes (21 s · 16 kHz · 16 bit · mono, #117 round 2). */
    companion object {
        const val FRAGMENT_SECONDS = 21.0
        const val DEFAULT_FRAGMENT_BYTES = (FRAGMENT_SECONDS * 32_000).toInt()

        /** Default forward silence-search window (#117): 2 s. */
        const val DEFAULT_SEARCH_SECONDS = 2.0
        val DEFAULT_SEARCH_BYTES = (DEFAULT_SEARCH_SECONDS * 32_000).toInt()

        /** Acoustic pre-roll (#117 round 2): 1 s of the previous fragment. */
        const val PRE_ROLL_SECONDS = 1.0
        val DEFAULT_PRE_ROLL_BYTES = (PRE_ROLL_SECONDS * 32_000).toInt()
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
            // The pre-roll side files are NOT manifest entries (#117 round 2)
            // — they must survive the orphan cleanup exactly like their
            // fragments, or a restored session loses its seam context.
            val kept = state.fragments.take(count)
            val keep = kept.map { it.name }.toSet() +
                kept.map { it.name.replaceFirst("frag_", "preroll_") }.toSet()
            sessionDir.listFiles()?.forEach { file ->
                if (file.name != MANIFEST && file.name !in keep) file.delete()
            }
            entries.clear()
            entries.addAll(kept)
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
     * even at the 21-s default fragment size this is fast work in the
     * normal case (the live worker already consumed every full fragment).
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
     * Writes into [targetDir] (the session dir by default; the #117 shadow
     * comparison assembles into its own dir so `clearPrepared()` can never
     * delete the chunks under the running shadow upload). Returns [] when
     * nothing was encoded at all — callers fall back to the single-file
     * path.
     */
    fun assembleChunks(targetDir: File = sessionDir): List<File> {
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
            val target = File(targetDir, "recording_%d.%s".format(chunkNo, ext))
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
     * (index, file, preRoll) of every committed fragment, in commit order
     * (#116; the pre-roll side file #117 round 2). The pre-roll is derived
     * from the fragment's name and is `null` when its file is absent
     * (fragment 0, a failed pre-roll encode, a legacy session). Safe to call
     * while the encoder thread runs — COW snapshot semantics.
     */
    fun committedFragments(): List<Triple<Int, File, File?>> =
        entries.mapIndexed { index, entry ->
            val file = File(sessionDir, entry.name)
            val preRoll = File(sessionDir, entry.name.replaceFirst("frag_", "preroll_"))
            Triple(index, file, preRoll.takeIf { it.isFile })
        }

    private fun encodeFragment(index: Int, start: Long, end: Long) {
        if (end <= start) return
        val data = pcm.copyPcmRange(start, end)
        val started = System.currentTimeMillis()
        val silenceAligned = (end - start) != fragmentBytes.toLong() ||
            (start % fragmentBytes != 0L)
        var preRoll: File? = null

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
                    // The rebuild's delete sweep wiped the pre-rolls — they
                    // are re-created for every rebuilt fragment (#117 round 2).
                    writePreRoll(i, s, ogg = false)
                    entries.add(Entry(rebuilt.name, rebuilt.length(), e))
                }
                logger?.log(
                    "prepare",
                    gson.toJson(mapOf("fallback" to true, "fragment" to index, "error" to (e.message ?: "")))
                )
            }
        }

        val file = if (fallbackToWav) writeWavFragment(index, data) else {
            // OGG path already appended its entry above; the pre-roll encode
            // happens BEFORE the manifest write so a crash can never leave a
            // committed fragment without the pre-roll it advertised. A failed
            // pre-roll encode yields `null` — it must NOT trigger the
            // session-level WAV fallback.
            preRoll = writePreRoll(index, start, ogg = true)
            writeManifest()
            logFragment(index, data.size, end, silenceAligned, started)
            notifyCommitted(index, "frag_%06d.ogg".format(index), preRoll)
            return
        }
        preRoll = writePreRoll(index, start, ogg = false)
        entries.add(Entry(file.name, file.length(), end))
        writeManifest()
        logFragment(index, data.size, end, silenceAligned, started)
        notifyCommitted(index, file.name, preRoll)
    }

    private fun notifyCommitted(index: Int, name: String, preRoll: File?) {
        try {
            onFragmentCommitted?.invoke(index, File(sessionDir, name), preRoll)
        } catch (_: Throwable) {
        }
    }

    /**
     * Writes fragment [index]'s acoustic pre-roll (#117 round 2): the
     * [preRollBytes] of PCM immediately before the fragment's start (clamped
     * at the stream start), encoded in the SAME format as the fragments.
     * Fragment 0 has no predecessor → `null`. ANY failure → `null`: a
     * missing pre-roll degrades gracefully (bare-fragment upload) and a
     * pre-roll transcode failure must NEVER trigger the session-level WAV
     * fallback — this is why the OGG transcode here is isolated from the
     * fragment encode's own catch block.
     */
    private fun writePreRoll(index: Int, start: Long, ogg: Boolean): File? {
        if (index == 0 || preRollBytes <= 0) return null
        return try {
            val from = (start - preRollBytes).coerceAtLeast(0L)
            if (from >= start) return null
            val data = pcm.copyPcmRange(from, start)
            val target = if (ogg) File(sessionDir, "preroll_%06d.ogg".format(index))
            else File(sessionDir, "preroll_%06d.wav".format(index))
            if (ogg) transcoder.transcode(WavWriter.write(data, sampleRate), target)
            else target.writeBytes(WavWriter.write(data, sampleRate))
            target
        } catch (_: Throwable) {
            null
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
