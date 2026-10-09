package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
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
import okhttp3.ResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Call
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class VoiceSessionControllerTest {

    private val pipeline: TranscriptionPipeline = mockk(relaxed = true)
    private val transcoder: AudioTranscoder = mockk(relaxed = true)
    private val sttApi: OpenAiSttApiService = mockk(relaxed = true)
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        settings = SettingsStore(ctx)
        // The live fragment worker (#116) resolves the provider itself.
        settings.sttProvider = com.georgernstgraf.polishedrecognition.config.SttProviderConfig(
            displayName = "LAN",
            baseUrl = "http://10.8.0.16:11437/v1/",
            apiToken = "token",
            model = "large-v3"
        )
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.success("hi")
        // The live fragment path (#116) ends in finishTranscription.
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns Result.success("hi")
        coEvery { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            mockSttCall(Response.success(SttResponse(text = "hi", language = null)))
    }

    private fun mockSttCall(response: Response<SttResponse>): Call<SttResponse> {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returns response
        return call
    }

    private fun newController(
        chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
        chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
        fragmentBytes: Int = 1 shl 30,
        fragmentSearchBytes: Int = 0, // legacy fixed-offset cuts unless a test opts in (#117)
        sessionIdInMeta: String? = null,
        shadowSttEnabled: () -> Boolean = { false },
        mainDispatcher: Boolean = false, // true = production-like queued main-looper (#117 shadow)
        logger: RotatingJsonLogger? = null
    ): VoiceSessionController = VoiceSessionController(
        RuntimeEnvironment.getApplication(),
        pipeline,
        settings,
        transcoder,
        scope = CoroutineScope(
            SupervisorJob() + if (mainDispatcher) Dispatchers.Main else Dispatchers.Unconfined
        ),
        chunkMaxBytes = chunkMaxBytes,
        chunkMaxSeconds = chunkMaxSeconds,
        fragmentBytes = fragmentBytes,
        fragmentSearchBytes = fragmentSearchBytes,
        // real live-fragment engine (#116) over the mocked STT endpoint,
        // with zero backoff so failure-path tests stay fast
        sttRunnerOverride = SttRequestRunner({ sttApi }, backoffMs = listOf(0L, 0L)),
        logger = logger,
        shadowSttEnabled = shadowSttEnabled
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
        // Events may have been posted from an IO thread (the live fragment
        // worker resumed there, #116) — pump the main looper so posted
        // runnables deliver before the caller reads its event list.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
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

    /**
     * #116: the live fragment path runs regardless of `compress_audio` —
     * with compression OFF the fragments are plain WAV (no transcoder), each
     * one uploaded directly. Driven via a restored snapshot (the Robolectric
     * live-capture byte count is nondeterministic).
     */
    @Test
    fun `compressAudio disabled transcribes live as WAV fragments without transcoding`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        val controller = newController()
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitIdle()

        verify(exactly = 0) { transcoder.transcode(any(), any()) }
        // one fragment (whole recording at the default huge test fragment size)
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { pipeline.finishTranscription(any(), any(), any()) }
        coVerify(exactly = 0) { pipeline.transcribe(any(), any(), any()) }
        clearSessionFiles()
    }

    /**
     * #116: with `compress_audio` on, the recording is transcribed through
     * the LIVE fragment worker — one STT request per committed fragment, no
     * assembled chunk file reaches the batch pipeline. Driven via a restored
     * snapshot (Robolectric cannot capture real mic bytes — an empty live
     * recording falls through to the legacy path).
     */
    @Test
    fun `compressAudio enabled transcribes through the live fragment worker`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        val controller = newController()
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitIdle()

        coVerify(exactly = 0) { pipeline.transcribe(any(), any(), any()) }
        coVerify(exactly = 1) { pipeline.finishTranscription(any(), any(), any()) }
        // one fragment (whole recording at the default huge test fragment size)
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { transcoder.transcode(any(), any()) }
        clearSessionFiles()
    }

    @Test
    fun `transcoder failure falls back to wav fragments and still transcribes`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } throws IOException("no encoder")
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        val controller = newController()
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitIdle()

        // The session-level WAV fallback (#115) must not kill the
        // transcription: the fragment uploads as audio/wav instead (#116).
        coVerify(exactly = 1) { pipeline.finishTranscription(any(), any(), any()) }
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
        clearSessionFiles()
    }

    /**
     * #116: the live path emits the preparing stage in BOTH formats — the
     * fragment worker runs regardless of `compress_audio`, which now only
     * selects the fragment format (OGG vs WAV).
     */
    @Test
    fun `preparing stage is emitted regardless of compressAudio`() {
        for (compress in listOf(true, false)) {
            settings.compressAudio = compress
            var stages = emptyList<TranscriptionPipeline.TranscriptionStage>()

            recordAndStop { events ->
                stages = events.filterIsInstance<VoiceSessionController.Event.StageChanged>()
                    .map { it.stage }
            }

            assertThat(stages.first())
                .isInstanceOf(TranscriptionPipeline.TranscriptionStage.CompressingAudio::class.java)
        }
    }

    /**
     * Live fragments replace chunk planning (#116): a recording beyond the
     * upload limits is uploaded as per-fragment live requests — each 7-s
     * fragment is far below the limits, so WavChunker never runs on the live
     * path. The audio is crafted via a hand-sized session snapshot (the same
     * OOM-safe pattern the #67 tests use — never drive a live Robolectric
     * recorder for bulk bytes).
     */
    @Test
    fun `recording beyond the upload limits uploads as live fragments`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("1250")

        val controller = newController(
            chunkMaxBytes = 20_000,
            chunkMaxSeconds = 600.0,
            fragmentBytes = 10_000
        )
        controller.restore()

        var uploads = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } answers {
            uploads++
            mockSttCall(Response.success(SttResponse(text = "part $uploads", language = null)))
        }
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } returns Result.success("hi")

        controller.stopAndTranscribe()
        controller.awaitIdle()

        // 40 kB / 10 kB = 4 live fragment uploads — the upload limits played
        // no role, no chunk planning happened, the batch pipeline never ran
        assertThat(uploads).isEqualTo(4)
        coVerify(exactly = 0) { pipeline.transcribe(any(), any(), any()) }
        val stt = sttSlot.captured.getOrThrow()
        assertThat(stt.chunkCount).isEqualTo(4)
        assertThat(stt.text).isEqualTo("part 1 part 2 part 3 part 4")
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
        // Gate BOTH pipeline entries: the live fragment path (#116) ends in
        // finishTranscription, the legacy path in transcribe — and the
        // Robolectric capture byte count decides which one runs.
        coEvery { pipeline.transcribe(any(), any(), any()) } coAnswers {
            gate.await()
            Result.success("hi")
        }
        coEvery { pipeline.finishTranscription(any(), any(), any()) } coAnswers {
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
        // Gate BOTH pipeline entries (see the detach/stash test above).
        coEvery { pipeline.transcribe(any(), any(), any()) } coAnswers {
            gate.await()
            Result.success("hi")
        }
        coEvery { pipeline.finishTranscription(any(), any(), any()) } coAnswers {
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
        // Gate BOTH pipeline entries (see the detach/stash test above).
        coEvery { pipeline.transcribe(any(), any(), any()) } coAnswers {
            gate.await()
            Result.success("hi")
        }
        coEvery { pipeline.finishTranscription(any(), any(), any()) } coAnswers {
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
        // flush in PROCESSING was a no-op: the in-flight transcription ran to
        // completion — the live path's finishTranscription, not the batch one
        coVerify(exactly = 1) { pipeline.finishTranscription(any(), any(), any()) }
        coVerify(exactly = 0) { pipeline.transcribe(any(), any(), any()) }
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
        // Fail BOTH pipeline entries (live vs legacy path, #116)
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.failure(IOException("offline"))
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(Exception("LLM post-processing failed: HTTP 500"))
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
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(Exception("LLM post-processing failed: HTTP 500"))
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
        // Fail then succeed on BOTH pipeline entries (live vs legacy, #116)
        coEvery { pipeline.transcribe(any(), any(), any()) } returnsMany
            listOf(Result.failure(IOException("offline")), Result.success("hi"))
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returnsMany
            listOf(Result.failure(Exception("LLM post-processing failed: HTTP 500")), Result.success("hi"))
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
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(Exception("LLM post-processing failed: HTTP 500"))
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
     * Stage memoization (#115), now reachable only via the legacy safety net
     * (#116 left it for EMPTY recordings — the live fragment path handles
     * everything else and caches its transcripts in memory instead): a
     * pipeline failure keeps the prepared upload file committed under
     * `cacheDir/prepared/<hash>/`; a retry REUSES it (identical file path)
     * instead of rebuilding. Success consumes the prepared dir.
     */
    @Test
    fun `retry after failure reuses prepared files without re-encoding`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(0)) // empty recording → legacy path
        File(cacheDir, "session.meta").writeText("1250")

        val controller = newController()
        controller.restore()

        val uploadPaths = mutableListOf<String>()
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            // snapshot eagerly: on success the controller deletes the files
            uploadPaths.add(firstArg<List<File>>().single().absolutePath)
            Result.failure(IOException("offline"))
        }
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        val preparedRoot = File(cacheDir, "prepared")
        val hashDirs = preparedRoot.listFiles().orEmpty()
        assertThat(hashDirs.size).isEqualTo(1)
        assertThat(uploadPaths.single()).startsWith(hashDirs.single().absolutePath)

        // retry: the prepared file is reused, NOT rewritten
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            uploadPaths.add(firstArg<List<File>>().single().absolutePath)
            Result.success("hi")
        }
        controller.stopAndTranscribe()
        controller.awaitIdle()

        assertThat(uploadPaths.size).isEqualTo(2)
        assertThat(uploadPaths[1]).isEqualTo(uploadPaths[0])
        // success consumed the prepared uploads
        assertThat(preparedRoot.exists()).isFalse()
        clearSessionFiles()
    }

    // "different recording rebuilds prepared files and prunes the old dir"
    // (#115) was retired with #116: the live fragment path handles every
    // non-empty recording (new sessions prune other sessions' FRAGMENT dirs
    // in startFragmentPreparation), so the prepared-dir pruning is only
    // reachable for empty recordings — where two empty recordings hash
    // identically and the scenario cannot arise.

    /**
     * Fragment retry (#116): a failed compress-on session parks PAUSED; the
     * retry re-drains the CACHED transcripts — the transcoder must NOT run
     * again (all encoding happened once, before the first attempt) and no
     * STT request may be re-sent for already-transcribed fragments.
     */
    @Test
    fun `fragment retry after failure reuses cached transcripts without re-encoding or re-uploading`() {
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

        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(Exception("LLM post-processing failed: HTTP 500"))
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        // 4 fragments committed for the 40 kB buffer, each transcribed once
        verify(exactly = 4) { transcoder.transcode(any(), any()) }
        verify(exactly = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }

        // retry: the LLM pass succeeds now; the fragments re-drain from cache
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns Result.success("hi")
        controller.stopAndTranscribe()
        controller.awaitIdle()

        verify(exactly = 4) { transcoder.transcode(any(), any()) }
        verify(exactly = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
        // success consumed the fragment session
        assertThat(File(cacheDir, "fragments").exists()).isFalse()
        clearSessionFiles()
    }

    /**
     * Process death (#115/#116): the restored session id ties the snapshot
     * to the committed fragment dir — fragments already on disk are reused,
     * only the missing ones are encoded, and ALL of them flow through the
     * live worker into one ordered [TranscriptionPipeline.SttResult].
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
                """{"name":"frag_000000.ogg","bytes":4,"end":10000},""" +
                """{"name":"frag_000001.ogg","bytes":4,"end":20000}]}"""
        )

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()

        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } answers {
            callIndex++
            mockSttCall(Response.success(SttResponse(text = "part $callIndex", language = null)))
        }
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } returns Result.success("hi")
        controller.stopAndTranscribe()
        controller.awaitIdle()

        // only fragments 2 and 3 were encoded fresh; 0 and 1 were reused
        verify(exactly = 2) { transcoder.transcode(any(), any()) }
        // all four fragments transcribed, joined in fragment order
        verify(exactly = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
        val stt = sttSlot.captured.getOrThrow()
        assertThat(stt.text).isEqualTo("part 1 part 2 part 3 part 4")
        assertThat(stt.chunkCount).isEqualTo(4)
        assertThat(File(cacheDir, "fragments").exists()).isFalse()
        clearSessionFiles()
    }

    /**
     * Raw rescue (#116, owner decision): when a fragment finally fails,
     * polish mode fails the session naming the LOWEST-ORDER failed fragment
     * — in raw mode the retry re-attempts the failed fragment and delivers
     * the (complete) raw text without re-transcribing the cached fragments
     * (the Raw toggle is the user's rescue path when the polish fails).
     */
    @Test
    fun `fragment failure fails in polish mode and delivers partial text in raw mode`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        // fragment 1 (0-based) fails permanently (HTTP 401 = non-transient;
        // 401 also keeps the #117 prompt-rejection fallback out of the way,
        // which only keys on 400)
        val http401 =
            @Suppress("DEPRECATION")
            Response.error<SttResponse>(401, ResponseBody.create(null, "unauthorized"))
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returnsMany
            listOf(
                mockSttCall(Response.success(SttResponse(text = "one", language = null))),
                mockSttCall(http401),
                mockSttCall(Response.success(SttResponse(text = "three", language = null))),
                mockSttCall(Response.success(SttResponse(text = "four", language = null)))
            )

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()
        // attach (not start): the restored session is PAUSED — a start() here
        // would re-begin recording and reset the restored buffer.
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }

        // polish mode: the session fails, the lowest-order failure is named
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        // the result is delivered via a main-looper post (the pipeline flow
        // resumed on an IO worker) — pump the looper before reading events
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val failed = events.filterIsInstance<VoiceSessionController.Event.Completed>().last().result
        assertThat(failed.exceptionOrNull()!!.message).contains("fragment 2/4")
        assertThat(failed.exceptionOrNull()!!.message).contains("HTTP 401")
        coVerify(exactly = 0) { pipeline.finishTranscription(any(), any(), any()) }

        // raw mode retry: the failed fragment is RE-ATTEMPTED (fresh attempt
        // succeeds now), the cached fragments are not re-transcribed, and
        // the raw text comes from finishTranscription
        settings.rawMode = true
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returns
            mockSttCall(Response.success(SttResponse(text = "two", language = null)))
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } returns Result.success("hi")
        controller.stopAndTranscribe()
        controller.awaitIdle()

        val stt = sttSlot.captured.getOrThrow()
        assertThat(stt.text).isEqualTo("one two three four")
        assertThat(stt.chunkCount).isEqualTo(4)
        verify(exactly = 5) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
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

    /**
     * #117 shadow comparison: after a successful fragment session the
     * assembled full recording is transcribed once more (evidence off) and
     * both texts land in `stt-shadow.json`. Production dispatches the
     * shadow on the MAIN looper behind the transcribe job — the test
     * replicates that (mainDispatcher = true) and pumps the looper until
     * the log record appears.
     */
    @Test
    fun `shadow comparison logs the full-context STT next to the fragment transcripts`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        val logDir = File(cacheDir, "shadow-test-logs").apply { deleteRecursively(); mkdirs() }
        val controller = newController(
            fragmentBytes = 10_000,
            shadowSttEnabled = { true },
            mainDispatcher = true,
            logger = RotatingJsonLogger(logDir)
        )
        controller.restore()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }

        // 4 fragment responses, then the shadow's single full-context call
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) } returnsMany
            listOf(
                mockSttCall(Response.success(SttResponse(text = "one", language = null))),
                mockSttCall(Response.success(SttResponse(text = "two", language = null))),
                mockSttCall(Response.success(SttResponse(text = "three", language = null))),
                mockSttCall(Response.success(SttResponse(text = "four", language = null))),
                mockSttCall(Response.success(SttResponse(text = "one two three four FULL", language = null)))
            )
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns Result.success("hi")
        // the shadow runs on the pipeline's REAL runner (production), not on
        // a relaxed mock — otherwise its failure is swallowed invisibly
        every { pipeline.sttRequestRunner } returns SttRequestRunner({ sttApi }, backoffMs = listOf(0L, 0L))

        controller.stopAndTranscribe()
        val shadowFile = File(logDir, "stt-shadow.json")
        val deadline = System.currentTimeMillis() + 10_000
        while ((controller.state != VoiceSessionController.State.IDLE || !shadowFile.exists()) &&
            System.currentTimeMillis() < deadline
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        println("DIAG shadow dir exists=${File(cacheDir, "shadow").exists()} contents=${File(cacheDir, "shadow").listFiles()?.map { it.name }}")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        println("DIAG shadow dir after extra pump exists=${File(cacheDir, "shadow").exists()}")
        verify(atLeast = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any()) }
        assertThat(shadowFile.exists()).isTrue()
        val shadow = com.google.gson.Gson().fromJson(
            shadowFile.readText(), Map::class.java
        ) as Map<String, Any>
        assertThat(shadow["fragmentText"]).isEqualTo("one two three four")
        assertThat(shadow["fullText"]).isEqualTo("one two three four FULL")
        assertThat(shadow["chunks"]).isEqualTo(1.0)
        assertThat(shadow["error"]).isNull()
        clearSessionFiles()
    }

    private fun clearSessionFiles() {
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").delete()
        File(cacheDir, "session.meta").delete()
    }
}
