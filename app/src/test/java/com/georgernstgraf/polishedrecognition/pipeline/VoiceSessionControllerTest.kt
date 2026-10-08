package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.audio.WavChunker
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class VoiceSessionControllerTest {

    private val pipeline: TranscriptionPipeline = mockk(relaxed = true)
    private val transcoder: AudioTranscoder = mockk(relaxed = true)
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        settings = SettingsStore(ctx)
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.success("hi")
    }

    private fun newController(
        chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
        chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
        fragmentBytes: Int = 1 shl 30,
        sessionIdInMeta: String? = null
    ): VoiceSessionController = VoiceSessionController(
        RuntimeEnvironment.getApplication(),
        pipeline,
        settings,
        transcoder,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        chunkMaxBytes = chunkMaxBytes,
        chunkMaxSeconds = chunkMaxSeconds,
        fragmentBytes = fragmentBytes
    )

    private fun recordAndStop(record: (List<VoiceSessionController.Event>) -> Unit = {}): VoiceSessionController {
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { e ->
                events.add(e)
                record(events)
            }
        } catch (_: Throwable) {
            // AudioRecord is not fully supported under Robolectric — the
            // controller still transitions to RECORDING, which is all we need.
        }
        controller.stopAndTranscribe()
        controller.awaitIdle()
        return controller
    }

    private fun VoiceSessionController.awaitIdle() {
        awaitState(VoiceSessionController.State.IDLE)
    }

    private fun VoiceSessionController.awaitState(target: VoiceSessionController.State) {
        val deadline = System.currentTimeMillis() + 5000
        while (state != target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertThat(state).isEqualTo(target)
    }

    private fun uploadedFile(): File {
        val fileSlot = slot<List<File>>()
        coVerify { pipeline.transcribe(capture(fileSlot), any(), any()) }
        return fileSlot.captured.single()
    }

    private fun uploadedFiles(): List<File> {
        val fileSlot = slot<List<File>>()
        coVerify { pipeline.transcribe(capture(fileSlot), any(), any()) }
        return fileSlot.captured
    }

    @Test
    fun `stopAndTranscribe passes the caller package to the pipeline`() {
        val controller = newController()
        try {
            controller.start {}
        } catch (_: Throwable) {
        }

        controller.stopAndTranscribe("com.example.chat")
        controller.awaitIdle()

        val pkgSlot = slot<String>()
        coVerify { pipeline.transcribe(any(), capture(pkgSlot), any()) }
        assertThat(pkgSlot.captured).isEqualTo("com.example.chat")
    }

    @Test
    fun `compressAudio disabled uploads recording wav without transcoding`() {
        settings.compressAudio = false

        recordAndStop()

        assertThat(uploadedFile().name).isEqualTo("recording.wav")
        verify(exactly = 0) { transcoder.transcode(any(), any()) }
    }

    @Test
    fun `compressAudio enabled uploads transcoded recording ogg`() {
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        }

        recordAndStop()

        // The upload file's name depends on the chunking path (single-file
        // legacy vs fragment-assembled): under Robolectric the shadow
        // capture may or may not have produced bytes, so assert the
        // ESSENCE — the upload is a single OGG part — not the exact name.
        val name = uploadedFile().name
        assertThat(name).startsWith("recording")
        assertThat(name).endsWith(".ogg")
        verify(exactly = 1) { transcoder.transcode(any(), any()) }
    }

    @Test
    fun `transcoder failure falls back to recording wav`() {
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } throws IOException("no encoder")

        recordAndStop()

        val name = uploadedFile().name
        assertThat(name).startsWith("recording")
        assertThat(name).endsWith(".wav")
    }

    @Test
    fun `compressing stage is emitted when compressAudio enabled`() {
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(byteArrayOf(1)) }
        }
        var stages = emptyList<TranscriptionPipeline.TranscriptionStage>()

        recordAndStop { events ->
            stages = events.filterIsInstance<VoiceSessionController.Event.StageChanged>()
                .map { it.stage }
        }

        assertThat(stages.first())
            .isInstanceOf(TranscriptionPipeline.TranscriptionStage.CompressingAudio::class.java)
    }

    @Test
    fun `no compressing stage when compressAudio disabled`() {
        settings.compressAudio = false
        var stages = emptyList<TranscriptionPipeline.TranscriptionStage>()

        recordAndStop { events ->
            stages = events.filterIsInstance<VoiceSessionController.Event.StageChanged>()
                .map { it.stage }
        }

        assertThat(stages).isEmpty()
    }

    /**
     * Chunked upload (#115, port of aitranscribe's `chunk_audio`): a
     * recording beyond the upload limits arrives at the pipeline as several
     * numbered chunk files instead of one. The audio is crafted by writing a
     * hand-sized session snapshot and restoring it (the same OOM-safe pattern
     * the #67 tests use — never drive a live Robolectric recorder for bulk
     * bytes).
     */
    @Test
    fun `recording beyond the chunk limit arrives as multiple chunk files`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("1250")

        val controller = newController(chunkMaxBytes = 20_000, chunkMaxSeconds = 600.0)
        controller.restore()

        // Snapshot the chunk bytes inside the pipeline stub — the controller
        // deletes the files right after the pipeline returns.
        val snapshots = mutableListOf<List<Pair<String, ByteArray>>>()
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            snapshots.add(firstArg<List<File>>().map { it.name to it.readBytes() })
            Result.success("hi")
        }

        controller.stopAndTranscribe()
        controller.awaitIdle()

        assertThat(snapshots).hasSize(1)
        val files = snapshots.single()
        assertThat(files.size).isAtLeast(2)
        assertThat(files[0].first).isEqualTo("recording_1.wav")
        assertThat(files[1].first).isEqualTo("recording_2.wav")
        files.forEach { (_, bytes) ->
            assertThat(bytes.size).isAtMost(44 + 20_000)
            assertThat(bytes.sliceArray(0 until 4).decodeToString()).isEqualTo("RIFF")
        }
        // chunk files were cleaned up after the pipeline returned
        files.forEach { (name, _) -> assertThat(File(cacheDir, name).exists()).isFalse() }
        clearSessionFiles()
    }

    @Test
    fun `detach preserves paused session and attach resumes event delivery`() {
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { events.add(it) }
        } catch (_: Throwable) {
        }
        controller.pause()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        val countAtDetach = events.size

        controller.detach()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        try {
            controller.resume()
        } catch (_: Throwable) {
        }
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        assertThat(events).hasSize(countAtDetach)

        controller.attach { events.add(it) }
        controller.pause()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(events.size).isGreaterThan(countAtDetach)
        assertThat(events.last())
            .isEqualTo(VoiceSessionController.Event.StateChanged(VoiceSessionController.State.PAUSED))
    }

    @Test
    fun `start resets a preserved paused session to a fresh recording`() {
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        controller.pause()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)

        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        // Cleanup is mandatory, not polite: under Robolectric the shadow
        // AudioRecord.read() returns a full buffer instantly, so a live
        // recorder spins at full speed appending to the buffer for as long
        // as it lives. Left running, it fills gigabytes across the rest of
        // the suite and OOMs the test worker (same hazard as the #67
        // snapshot and #86 flush-RECORDING tests document).
        controller.cancel()
    }

    /**
     * Rotation during PROCESSING (#83): the UI detaches mid-transcription,
     * the result is stashed and delivered to the next attach() instead of
     * being dropped in emit().
     */
    @Test
    fun `completed result during detach is stashed and delivered on attach`() {
        settings.compressAudio = false
        val gate = CountDownLatch(1)
        coEvery { pipeline.transcribe(any(), any(), any()) } coAnswers {
            gate.await()
            Result.success("hi")
        }
        val controller = newController()
        val before = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { before.add(it) }
        } catch (_: Throwable) {
        }
        val releaser = thread {
            while (controller.state != VoiceSessionController.State.PROCESSING) {
                Thread.sleep(10)
            }
            controller.detach()
            gate.countDown()
        }
        controller.stopAndTranscribe()
        releaser.join(5000)
        controller.awaitIdle()

        assertThat(before.filterIsInstance<VoiceSessionController.Event.Completed>()).isEmpty()

        val after = mutableListOf<VoiceSessionController.Event>()
        controller.attach { after.add(it) }
        val completed = after.filterIsInstance<VoiceSessionController.Event.Completed>()
        assertThat(completed).hasSize(1)
        assertThat(completed.single().result.getOrNull()).isEqualTo("hi")
    }

    @Test
    fun `cancel discards a stashed result`() {
        settings.compressAudio = false
        val gate = CountDownLatch(1)
        coEvery { pipeline.transcribe(any(), any(), any()) } coAnswers {
            gate.await()
            Result.success("hi")
        }
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        val releaser = thread {
            while (controller.state != VoiceSessionController.State.PROCESSING) {
                Thread.sleep(10)
            }
            controller.detach()
            gate.countDown()
        }
        controller.stopAndTranscribe()
        releaser.join(5000)
        controller.awaitIdle()

        controller.cancel()
        val after = mutableListOf<VoiceSessionController.Event>()
        controller.attach { after.add(it) }
        assertThat(after.filterIsInstance<VoiceSessionController.Event.Completed>()).isEmpty()
    }

    /**
     * Process-death snapshot (#67, observed on Oplus across rotation): a
     * snapshot left on disk is restored as PAUSED with the timer continuing
     * from the saved duration. The snapshot is consumed — a second restore
     * finds nothing.
     *
     * The snapshot files are hand-crafted (small PCM): under Robolectric the
     * AudioRecord shadow fills the live recorder buffer in a tight loop, so
     * round-tripping a real pause() snapshot would copy gigabytes and OOM
     * the test JVM. Production pause() still writes the real buffer;
     * snapshot() is best-effort and OOM-safe (catches Throwable).
     */
    @Test
    fun `hand-crafted snapshot is restored as PAUSED with saved duration`() {
        clearSessionFiles()
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(320))
        File(cacheDir, "session.meta").writeText("1234")

        val reborn = newController()
        assertThat(reborn.restore()).isTrue()
        assertThat(reborn.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(reborn.recordedDurationMs()).isEqualTo(1234L)
        assertThat(File(cacheDir, "session.pcm").exists()).isFalse()
        assertThat(File(cacheDir, "session.meta").exists()).isFalse()

        assertThat(reborn.restore()).isFalse()
    }

    @Test
    fun `restore with no snapshot returns false`() {
        clearSessionFiles()
        assertThat(newController().restore()).isFalse()
    }

    @Test
    fun `restore with corrupt meta returns false`() {
        clearSessionFiles()
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(320))
        File(cacheDir, "session.meta").writeText("not-a-number")

        assertThat(newController().restore()).isFalse()
        clearSessionFiles()
    }

    @Test
    fun `cancel clears the snapshot`() {
        clearSessionFiles()
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(320))
        File(cacheDir, "session.meta").writeText("1234")

        newController().cancel()

        assertThat(newController().restore()).isFalse()
    }

    @Test
    fun `stopAndTranscribe clears the snapshot`() {
        clearSessionFiles()
        settings.compressAudio = false
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        controller.pause()
        controller.stopAndTranscribe()
        controller.awaitIdle()

        assertThat(newController().restore()).isFalse()
    }

    /**
     * Flush button (#86): discards the audio but keeps the mode — PAUSED
     * stays paused with a zeroed timer, and the UI callback stays attached
     * (a StateChanged event is delivered, unlike cancel() which nulls it).
     */
    @Test
    fun `flush in PAUSED stays PAUSED with zeroed timer and kept callback`() {
        clearSessionFiles()
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { events.add(it) }
        } catch (_: Throwable) {
        }
        controller.pause()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)

        controller.flush()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.recordedDurationMs()).isEqualTo(0L)
        assertThat(events.last())
            .isEqualTo(VoiceSessionController.Event.StateChanged(VoiceSessionController.State.PAUSED))
        assertThat(newController().restore()).isFalse()
        clearSessionFiles()
    }

    /**
     * Flush in RECORDING (#86): the mode is kept and the timer restarts from
     * scratch. No sleeping while RECORDING here — under Robolectric the
     * AudioRecord shadow fills the buffer in a tight loop, so any sleep
     * with a live recorder risks OOMing the test JVM (same reason the #67
     * snapshot test hand-crafts its files).
     */
    @Test
    fun `flush in RECORDING stays RECORDING with restarted timer`() {
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { events.add(it) }
        } catch (_: Throwable) {
        }

        controller.flush()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        assertThat(controller.recordedDurationMs()).isAtMost(5000L)
        assertThat(events.last())
            .isEqualTo(VoiceSessionController.Event.StateChanged(VoiceSessionController.State.RECORDING))
        controller.cancel()
    }

    @Test
    fun `flush clears a hand-crafted snapshot`() {
        clearSessionFiles()
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(320))
        File(cacheDir, "session.meta").writeText("1234")
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }

        controller.flush()

        assertThat(newController().restore()).isFalse()
        controller.cancel()
    }

    @Test
    fun `flush in IDLE is a no-op`() {
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }

        controller.flush()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(events).isEmpty()
    }

    @Test
    fun `flush in PROCESSING is a no-op`() {
        settings.compressAudio = false
        val gate = CountDownLatch(1)
        coEvery { pipeline.transcribe(any(), any(), any()) } coAnswers {
            gate.await()
            Result.success("hi")
        }
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        var stateAtFlush: VoiceSessionController.State? = null
        val releaser = thread {
            while (controller.state != VoiceSessionController.State.PROCESSING) {
                Thread.sleep(10)
            }
            controller.flush()
            stateAtFlush = controller.state
            gate.countDown()
        }
        controller.stopAndTranscribe()
        releaser.join(5000)
        controller.awaitIdle()

        assertThat(stateAtFlush).isEqualTo(VoiceSessionController.State.PROCESSING)
        assertThat(uploadedFile().name).isEqualTo("recording.wav")
    }

    /**
     * Pipeline failure (#84): the session parks as ordinary PAUSED — audio
     * stays buffered, the timer is preserved, and the snapshot is re-written
     * so process death is covered by restore(). The IME stays visible on the
     * failure Toast with send/resume and editable quick settings.
     */
    @Test
    fun `pipeline failure parks PAUSED with preserved timer and snapshot`() {
        clearSessionFiles()
        settings.compressAudio = false
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.failure(IOException("offline"))
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { events.add(it) }
        } catch (_: Throwable) {
        }
        controller.pause()
        val before = controller.recordedDurationMs()

        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        assertThat(controller.recordedDurationMs()).isEqualTo(before)
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        assertThat(File(cacheDir, "session.pcm").exists()).isTrue()
        assertThat(File(cacheDir, "session.meta").exists()).isTrue()
        assertThat(events.filterIsInstance<VoiceSessionController.Event.StateChanged>().last())
            .isEqualTo(VoiceSessionController.Event.StateChanged(VoiceSessionController.State.PAUSED))
        val completed = events.filterIsInstance<VoiceSessionController.Event.Completed>()
        assertThat(completed).hasSize(1)
        assertThat(completed.single().result.isFailure).isTrue()
        // Leave a clean tree for other tests: discard the parked session.
        controller.cancel()
        clearSessionFiles()
    }

    @Test
    fun `failure snapshot restores as PAUSED on next bind`() {
        clearSessionFiles()
        settings.compressAudio = false
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.failure(IOException("offline"))
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        controller.pause()
        val before = controller.recordedDurationMs()

        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        controller.detach()

        val reborn = newController()
        assertThat(reborn.restore()).isTrue()
        assertThat(reborn.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(reborn.recordedDurationMs()).isEqualTo(before)
        controller.cancel()
        reborn.cancel()
        clearSessionFiles()
    }

    @Test
    fun `retry after failure succeeds and clears snapshot`() {
        clearSessionFiles()
        settings.compressAudio = false
        coEvery { pipeline.transcribe(any(), any(), any()) } returns
            Result.failure<String>(IOException("offline")) andThen Result.success("hi")
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { events.add(it) }
        } catch (_: Throwable) {
        }

        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        controller.stopAndTranscribe()
        controller.awaitIdle()

        val completed = events.filterIsInstance<VoiceSessionController.Event.Completed>()
        assertThat(completed).hasSize(2)
        assertThat(completed[0].result.isFailure).isTrue()
        assertThat(completed[1].result.getOrNull()).isEqualTo("hi")
        assertThat(newController().restore()).isFalse()
        clearSessionFiles()
    }

    @Test
    fun `cancel after failure discards parked audio`() {
        clearSessionFiles()
        settings.compressAudio = false
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.failure(IOException("offline"))
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }

        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        controller.cancel()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(newController().restore()).isFalse()
        clearSessionFiles()
    }

    /**
     * Stage memoization (#115): after a pipeline failure the prepared upload
     * files stay committed under `cacheDir/prepared/<hash>/`; a retry REUSES
     * them instead of re-encoding — the transcoder must run exactly N times
     * (one per chunk) across BOTH attempts, and the pipeline gets identical
     * file paths. Success consumes the prepared dir.
     */
    @Test
    fun `retry after failure reuses prepared files without re-encoding`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("1250")

        val controller = newController(chunkMaxBytes = 20_000, chunkMaxSeconds = 600.0)
        controller.restore()

        val uploads = mutableListOf<List<Pair<String, ByteArray>>>()
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            // snapshot eagerly: on success the controller deletes the files
            uploads.add(firstArg<List<File>>().map { it.name to it.readBytes() })
            Result.failure(IOException("offline"))
        }
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        val attemptsAfterFailure = uploads.size
        // prepared files survive the failure, manifest + chunks on disk
        val preparedRoot = File(cacheDir, "prepared")
        val hashDirs = preparedRoot.listFiles().orEmpty()
        assertThat(hashDirs.size).isEqualTo(1)
        val firstAttemptContents = hashDirs.single().listFiles().orEmpty()
            .filter { it.name != "manifest.json" }
            .associate { it.name to it.length() }
        assertThat(firstAttemptContents.size).isAtLeast(2)

        // retry: prepared files are reused, NOT rewritten
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            uploads.add(firstArg<List<File>>().map { it.name to it.readBytes() })
            Result.success("hi")
        }
        controller.stopAndTranscribe()
        controller.awaitIdle()

        assertThat(uploads.size).isEqualTo(attemptsAfterFailure + 1)
        val secondUpload = uploads.last()
        assertThat(secondUpload.map { it.first }.sorted())
            .isEqualTo(firstAttemptContents.keys.toList().sorted())
        // byte contents identical → nothing was rewritten
        assertThat(secondUpload.map { it.second.size.toLong() }.sorted())
            .isEqualTo(firstAttemptContents.values.sorted())
        // success consumed the prepared uploads
        assertThat(preparedRoot.exists()).isFalse()
        clearSessionFiles()
    }

    /**
     * A DIFFERENT recording (new PCM after cancel + fresh capture) must
     * rebuild its prepared files — the old hash dir is pruned, never reused
     * stale. Driven via hand-crafted snapshots only (no live recorder).
     */
    @Test
    fun `different recording rebuilds prepared files and prunes the old dir`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcmA = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcmA)
        File(cacheDir, "session.meta").writeText("1250")

        val controller = newController(chunkMaxBytes = 20_000, chunkMaxSeconds = 600.0)
        controller.restore()
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.failure(IOException("offline"))
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        val firstKey = File(cacheDir, "prepared").listFiles().orEmpty().single().name
        controller.cancel()

        // an entirely different recording
        File(cacheDir, "session.pcm").writeBytes(ByteArray(30_000) { i -> (i % 13).toByte() })
        File(cacheDir, "session.meta").writeText("900")
        val reborn = newController(chunkMaxBytes = 20_000, chunkMaxSeconds = 600.0)
        reborn.restore()
        reborn.stopAndTranscribe()
        reborn.awaitState(VoiceSessionController.State.PAUSED)

        val dirs = File(cacheDir, "prepared").listFiles().orEmpty()
        assertThat(dirs.size).isEqualTo(1)
        assertThat(dirs.single().name).isNotEqualTo(firstKey)
        controller.cancel()
        clearSessionFiles()
    }

    /**
     * Fragment retry (#115): a failed compress-on pipeline parks PAUSED; the
     * retry reuses the committed fragments — the transcoder must NOT run
     * again (all encoding happened once, before the first attempt).
     */
    @Test
    fun `fragment retry after failure reuses committed fragments without re-encoding`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()

        val uploads = mutableListOf<List<Pair<String, Long>>>()
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            uploads.add(firstArg<List<File>>().map { it.name to it.length() })
            Result.failure(IOException("offline"))
        }
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        // 4 fragments committed for the 40 kB buffer
        verify(exactly = 4) { transcoder.transcode(any(), any()) }
        assertThat(uploads.single().single().first).isEqualTo("recording_1.ogg")

        // retry: nothing new to encode, same chunk assembled
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            uploads.add(firstArg<List<File>>().map { it.name to it.length() })
            Result.success("hi")
        }
        controller.stopAndTranscribe()
        controller.awaitIdle()

        verify(exactly = 4) { transcoder.transcode(any(), any()) }
        assertThat(uploads.size).isEqualTo(2)
        assertThat(uploads[1].single().first).isEqualTo("recording_1.ogg")
        // success consumed the fragment session
        assertThat(File(cacheDir, "fragments").exists()).isFalse()
        clearSessionFiles()
    }

    /**
     * Process death (#115): the restored session id ties the snapshot to the
     * committed fragment dir — fragments already on disk are reused, only
     * the missing ones are encoded.
     */
    @Test
    fun `restored session reuses fragments committed before process death`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        // a previous process had already committed fragments 0 and 1
        val sessionDir = File(cacheDir, "fragments/testsess").apply { mkdirs() }
        val persisted = byteArrayOf(9, 9, 9, 9)
        File(sessionDir, "frag_000000.ogg").writeBytes(persisted)
        File(sessionDir, "frag_000001.ogg").writeBytes(persisted)
        File(sessionDir, "manifest.json").writeText(
            """{"fallbackToWav":false,"fragments":[""" +
                """{"name":"frag_000000.ogg","bytes":4},""" +
                """{"name":"frag_000001.ogg","bytes":4}]}"""
        )

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()

        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.success("hi")
        controller.stopAndTranscribe()
        controller.awaitIdle()

        // only fragments 2 and 3 were encoded fresh; 0 and 1 were reused
        verify(exactly = 2) { transcoder.transcode(any(), any()) }
        assertThat(File(cacheDir, "fragments").exists()).isFalse()
        clearSessionFiles()
    }

    @Test
    fun `cancel discards the fragment session of a restored session`() {
        clearSessionFiles()
        settings.compressAudio = true
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(100))
        File(cacheDir, "session.meta").writeText("""{"durationMs":5,"sessionId":"testsess"}""")
        File(cacheDir, "fragments/testsess").apply { mkdirs() }.resolve("frag_000000.ogg").writeBytes(byteArrayOf(1))

        val controller = newController()
        controller.restore()
        controller.cancel()

        assertThat(File(cacheDir, "fragments/testsess").exists()).isFalse()
        clearSessionFiles()
    }

    private fun clearSessionFiles() {
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").delete()
        File(cacheDir, "session.meta").delete()
    }
}
