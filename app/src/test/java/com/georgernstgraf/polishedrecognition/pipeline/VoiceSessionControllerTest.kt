package com.georgernstgraf.polishedrecognition.pipeline

import android.os.Looper
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import com.georgernstgraf.polishedrecognition.audio.SeamPolicy
import com.georgernstgraf.polishedrecognition.audio.SilenceQuality
import com.georgernstgraf.polishedrecognition.audio.WavChunker
import com.georgernstgraf.polishedrecognition.audio.WavReader
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttLatencyProfile
import com.georgernstgraf.polishedrecognition.testing.CaptureThreadGuard
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
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.RequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
        // Hermetic per-test state: the controller's snapshot + prepared/
        // fragment dirs live in the SHARED app cacheDir, so a prior test class
        // in the same Robolectric sandbox can otherwise leak a snapshot into a
        // test that never asked for one (a restored fragment then shows up as
        // a spurious finishTranscription instead of the legacy transcribe).
        File(ctx.cacheDir, "session.pcm").delete()
        File(ctx.cacheDir, "session.meta").delete()
        File(ctx.cacheDir, "fragments").deleteRecursively()
        File(ctx.cacheDir, "prepared").deleteRecursively()
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
        coEvery { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns
            mockSttCall(Response.success(SttResponse(text = "hi", language = null)))
        // #198: the app's JVM-global crash handler turns one uncaught
        // background throwable into `exit value 2` and no trace; capture it so
        // a recurrence is a failing test instead of a dead fork.
        CaptureThreadGuard.install()
    }

    @After
    fun tearDown() {
        // #198: the app's handler back, and a captured throwable fails THIS test.
        CaptureThreadGuard.restoreAndFail()
    }

    private fun mockSttCall(response: Response<SttResponse>): Call<SttResponse> {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returns response
        return call
    }

    private fun newController(
        chunkMaxBytes: Int = WavChunker.MAX_CHUNK_BYTES,
        chunkMaxSeconds: Double = WavChunker.MAX_CHUNK_SECONDS,
        /**
         * Adapter for the pre-#124 vocabulary (see FragmentPreparerTest):
         * `fragmentBytes + fragmentSearchBytes` is the slice, the late half of
         * the old forward window is the new search window.
         */
        fragmentBytes: Int = 1 shl 30,
        fragmentSearchBytes: Int = 0,
        fragmentPreRollBytes: Int = 0, // pre-roll off unless a test opts in (#117 round 2)
        seamPolicy: SeamPolicy = SeamPolicy.Q_GATE, // #128 production default
        fragmentQualityMin: Double = FragmentPreparer.DEFAULT_QUALITY_MIN, // #128 gate
        sessionIdInMeta: String? = null,
        mainDispatcher: Boolean = false, // true = production-like queued main-looper
        logger: RotatingJsonLogger? = null,
        sliceSizeProvider: (() -> Double?)? = null, // #124 auto-sizer
        searchPercentProvider: (() -> Double?)? = null, // #124 adaptive window
        audioEvidenceStore: AudioEvidenceStore? = null, // #127 evidence retention
        audioEvidenceEnabled: () -> Boolean = { false },
        journal: FragmentJournal? = null, // #182 durable fragment journal
        /** #186: the network gate the runner asks before the first request. */
        online: () -> Boolean = { true }
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
        sliceBytes = fragmentBytes + fragmentSearchBytes,
        searchWindowBytes = fragmentSearchBytes / 2,
        fragmentPreRollBytes = fragmentPreRollBytes,
        seamPolicy = seamPolicy,
        fragmentQualityMin = fragmentQualityMin,
        // real live-fragment engine (#116) over the mocked STT endpoint,
        // with zero backoff so failure-path tests stay fast
        sttRunnerOverride = SttRequestRunner({ sttApi }, backoffMs = listOf(0L, 0L), isOnline = online),
        logger = logger,
        sliceSizeProvider = sliceSizeProvider,
        searchPercentProvider = searchPercentProvider,
        audioEvidenceStore = audioEvidenceStore,
        audioEvidenceEnabled = audioEvidenceEnabled,
        journal = journal
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
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Waits until [predicate] holds for the pumped event list (#117 flake
     * fix). The FAILURE branch publishes PAUSED and posts its events from an
     * IO thread afterwards — a test that waits on the state field alone can
     * pump too early and read an empty list (the missing-`Completed` flake
     * shape). Waiting on the events themselves makes the handoff
     * deterministic.
     */
    private fun awaitEvents(
        events: MutableList<VoiceSessionController.Event>,
        predicate: (List<VoiceSessionController.Event>) -> Boolean
    ) {
        val shadow = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (predicate(events)) return
            Thread.sleep(10)
        }
        shadow.idle()
        assertThat(predicate(events)).isTrue()
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

        // Which surface is reached is nondeterministic under Robolectric (the
        // shadow AudioRecord may or may not yield a byte, selecting the live
        // fragment path vs the legacy batch path) — the point of this test is
        // that the caller PACKAGE propagates, so accept either surface.
        val legacyPkg = slot<String>()
        val fragmentPkg = slot<String>()
        val usedLegacy = try {
            coVerify { pipeline.transcribe(any(), capture(legacyPkg), any()) }
            true
        } catch (_: AssertionError) {
            false
        }
        val usedFragment = try {
            coVerify { pipeline.finishTranscription(any(), capture(fragmentPkg), any()) }
            true
        } catch (_: AssertionError) {
            false
        }
        assertThat(usedLegacy || usedFragment).isTrue()
        assertThat(if (usedLegacy) legacyPkg.captured else fragmentPkg.captured)
            .isEqualTo("com.example.chat")
    }

    /**
     * #168 (epic #166 D7): a dictation into our own Workbench runs with the
     * pipeline forced to raw, and the flag is cleared again afterwards.
     */
    @Test
    fun `dictating into our own app forces raw mode for the run only (#168)`() {
        val own = RuntimeEnvironment.getApplication().packageName
        val modes = mutableListOf<Boolean>()
        // Relaxed mocks do not store property values, so the session override
        // is observed at the setter: true for the run, false in the cleanup.
        every { pipeline.forceRawMode = any() } answers { modes += arg<Boolean>(0) }
        val controller = newController()
        try {
            controller.start {}
        } catch (_: Throwable) {
        }

        controller.stopAndTranscribe(own)
        controller.awaitIdle()

        assertThat(modes).containsExactly(true, false).inOrder()
    }

    /** A foreign app leaves the global behaviour alone (#168). */
    @Test
    fun `dictating into a foreign app does not force raw mode (#168)`() {
        val modes = mutableListOf<Boolean>()
        every { pipeline.forceRawMode = any() } answers { modes += arg<Boolean>(0) }
        val controller = newController()
        try {
            controller.start {}
        } catch (_: Throwable) {
        }

        controller.stopAndTranscribe("com.example.chat")
        controller.awaitIdle()

        assertThat(modes).containsExactly(false, false).inOrder()
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
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
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
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { transcoder.transcode(any(), any()) }
        clearSessionFiles()
    }

    // ---- #182: durable fragment journal -------------------------------------

    /**
     * #182: the journal is the RECOVERY contract, so it is consumed exactly
     * when the audio is finished with — on a delivered session the whole
     * session dir goes (delivered, not lost).
     */
    @Test
    fun `a delivered session consumes its fragment journal`() {
        clearSessionFiles()
        settings.compressAudio = false
        val ctx = RuntimeEnvironment.getApplication()
        val sid = "20260101-1200-aaaaaaaa"
        File(ctx.cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(ctx.cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"$sid"}""")
        val journal = FragmentJournal(File(ctx.filesDir, FragmentJournal.DIR_NAME))
        journal.sessionDir(sid).deleteRecursively()

        val controller = newController(journal = journal)
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitIdle()

        assertThat(journal.sessionDir(sid).exists()).isFalse()
        clearSessionFiles()
    }

    /**
     * #182: a FAILED session never reaches `discardFragmentSession()` (it
     * parks PAUSED), so its journal — audio included — survives the failure
     * and waits for the retry or the recovery UI (#185).
     */
    @Test
    fun `a failed session keeps its fragment journal with the audio`() {
        clearSessionFiles()
        settings.compressAudio = false
        val ctx = RuntimeEnvironment.getApplication()
        val sid = "20260101-1200-bbbbbbbb"
        File(ctx.cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(ctx.cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"$sid"}""")
        val journal = FragmentJournal(File(ctx.filesDir, FragmentJournal.DIR_NAME))
        journal.sessionDir(sid).deleteRecursively()

        val http401 =
            @Suppress("DEPRECATION")
            Response.error<SttResponse>(401, ResponseBody.create(null, "unauthorized"))
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns
            mockSttCall(http401)

        val controller = newController(journal = journal)
        controller.restore()
        controller.attach { }
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        val record = journal.readRecord(sid, 0)!!
        assertThat(record.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(File(journal.sessionDir(sid), record.uploadFile!!).length()).isGreaterThan(0L)
        // #183: the record says WHY — an AUTH failure stays PENDING (retryable
        // with a new token), never NEEDS_MANUAL, and #185 can render the class.
        assertThat(record.failureClass).isEqualTo(SttFailure.AUTH.name)
        assertThat(record.failureHttpCode).isEqualTo(401)
        assertThat(record.sttError).contains("HTTP 401")
        journal.sessionDir(sid).deleteRecursively()
        clearSessionFiles()
    }

    /**
     * #217: the cross-app RELEASE hands the take's audio to the journal — the
     * recovery contract — instead of deleting it. A restored PAUSED take with
     * a 40 kB spool and no committed fragment: the whole spool is the tail, so
     * the flush journals it as fragment 0 PENDING; the take ends in IDLE, the
     * snapshot is cleared (no resurrection), and the controller forgets the
     * session (the journal belongs to the recovery now).
     */
    @Test
    fun `a released paused take hands its audio to the journal`() {
        clearSessionFiles()
        settings.compressAudio = false
        val ctx = RuntimeEnvironment.getApplication()
        val sid = "20260101-1200-cccccccc"
        File(ctx.cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(ctx.cacheDir, "session.meta").writeText(
            """{"durationMs":11976,"sessionId":"$sid","pausePackage":"net.gsantner.markor"}"""
        )
        val journal = FragmentJournal(File(ctx.filesDir, FragmentJournal.DIR_NAME))
        journal.sessionDir(sid).deleteRecursively()

        val controller = newController(journal = journal)
        controller.restore()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.untranscribedAudioPending()).isTrue()

        val released = controller.releaseToRecovery()

        assertThat(released).isEqualTo(sid)
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(controller.activeSessionId()).isNull()
        assertThat(controller.pausedPackage()).isNull()
        assertThat(controller.recordedDurationMs()).isEqualTo(0L)
        assertThat(controller.untranscribedAudioPending()).isFalse()
        // the recovery contract: one PENDING record, audio on disk.
        val record = journal.readRecord(sid, 0)!!
        assertThat(record.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(File(journal.sessionDir(sid), record.uploadFile!!).length())
            .isGreaterThan(0L)
        journal.sessionDir(sid).deleteRecursively()
        clearSessionFiles()
    }

    /**
     * #217: the release NEVER downgrades a journaled fragment — a fragment the
     * worker already journaled (here: an AUTH-failed PENDING one) keeps its
     * record and its failure evidence; the flush only fills the gaps (the
     * `readRecord` guard).
     */
    @Test
    fun `a release never downgrades an existing journal record`() {
        clearSessionFiles()
        settings.compressAudio = false
        val ctx = RuntimeEnvironment.getApplication()
        val sid = "20260101-1200-dddddddd"
        File(ctx.cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(ctx.cacheDir, "session.meta").writeText(
            """{"durationMs":1250,"sessionId":"$sid","pausePackage":"net.gsantner.markor"}"""
        )
        val journal = FragmentJournal(File(ctx.filesDir, FragmentJournal.DIR_NAME))
        journal.sessionDir(sid).deleteRecursively()

        val http401 =
            @Suppress("DEPRECATION")
            Response.error<SttResponse>(401, ResponseBody.create(null, "unauthorized"))
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns
            mockSttCall(http401)

        // the worker journals fragment 0 as AUTH-PENDING on the failed run…
        val controller = newController(journal = journal)
        controller.restore()
        controller.attach { }
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        assertThat(journal.readRecord(sid, 0)!!.failureClass).isEqualTo(SttFailure.AUTH.name)

        // …and the release must leave that record untouched.
        assertThat(controller.releaseToRecovery()).isEqualTo(sid)
        val record = journal.readRecord(sid, 0)!!
        assertThat(record.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(record.failureClass).isEqualTo(SttFailure.AUTH.name)
        assertThat(File(journal.sessionDir(sid), record.uploadFile!!).length())
            .isGreaterThan(0L)
        journal.sessionDir(sid).deleteRecursively()
        clearSessionFiles()
    }

    // ---- #127: per-session audio-evidence retention --------------------------

    /**
     * A finished session (SUCCESS) writes the forensic bundle into the
     * evidence store BEFORE clearPrepared() wipes the cacheDir originals:
     * #142 shape — `uploads/` (the actually-uploaded fragment audio) +
     * `session.json` (the manifest). NO session.wav, NO fragments/.
     * Driven via a restored snapshot (the hand-crafted pattern, PITFALLS).
     */
    @Test
    fun `finished session retains uploads and sessionJson as audio evidence`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")
        val evidenceRoot = File(cacheDir, "audio-evidence").apply { deleteRecursively(); mkdirs() }
        val store = AudioEvidenceStore(evidenceRoot)

        val controller = newController(
            audioEvidenceStore = store,
            audioEvidenceEnabled = { true }
        )
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitIdle()

        val session = File(evidenceRoot, "testsess")
        // the manifest: complete forensic record
        val manifest = File(session, "session.json")
        assertThat(manifest.isFile).isTrue()
        val parsed = com.google.gson.Gson().fromJson(manifest.readText(), Map::class.java) as Map<String, Any>
        assertThat(parsed["sessionId"]).isEqualTo("testsess")
        assertThat(parsed["startedAt"]).isNotNull()
        assertThat(parsed["endedAt"]).isNotNull()
        val result = parsed["result"] as Map<*, *>
        assertThat(result["deliveryKind"]).isEqualTo("inserted")
        assertThat(result["finalText"]).isEqualTo("hi")
        assertThat(parsed["provider"]).isNotNull()
        // uploads/: the actually-uploaded fragment audio (40 kB PCM → several)
        val uploads = File(session, "uploads").listFiles().orEmpty()
        assertThat(uploads).isNotEmpty()
        // the OLD artifacts are GONE (#142: no session.wav, no fragments/)
        assertThat(File(session, "session.wav").exists()).isFalse()
        assertThat(File(session, "fragments").exists()).isFalse()
        // the cacheDir originals are gone (success path) — the evidence is
        // the only surviving copy, exactly the point of #127
        assertThat(File(cacheDir, "fragments").exists()).isFalse()
        clearSessionFiles()
    }

    /** The toggle is the gate: store wired but OFF (release default) → no evidence at all. */
    @Test
    fun `no audio evidence is written when the toggle is off`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")
        val evidenceRoot = File(cacheDir, "audio-evidence-gated").apply { deleteRecursively(); mkdirs() }
        val store = AudioEvidenceStore(evidenceRoot)

        val controller = newController(
            audioEvidenceStore = store,
            audioEvidenceEnabled = { false }
        )
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitIdle()

        assertThat(evidenceRoot.listFiles().orEmpty()).isEmpty()
        clearSessionFiles()
    }

    /**
     * A FAILED session retains its evidence too — the manifest records the
     * error (`deliveryKind` = "error") so a post-mortem can reach the whole
     * picture days later (run-as is dead on the release OnePlus).
     */
    @Test
    fun `failed session retains uploads and sessionJson too`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")
        val evidenceRoot = File(cacheDir, "audio-evidence-failed").apply { deleteRecursively(); mkdirs() }
        val store = AudioEvidenceStore(evidenceRoot)
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(IOException("LLM down"))

        val controller = newController(
            audioEvidenceStore = store,
            audioEvidenceEnabled = { true }
        )
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        val session = File(evidenceRoot, "testsess")
        assertThat(File(session, "session.json").isFile).isTrue()
        val parsed = com.google.gson.Gson().fromJson(
            File(session, "session.json").readText(), Map::class.java
        ) as Map<String, Any>
        val result = parsed["result"] as Map<*, *>
        assertThat(result["deliveryKind"]).isEqualTo("error")
        assertThat(result["error"]).isEqualTo("LLM down")
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
        verify(exactly = 1) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
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
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } answers {
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

    // --- #192: a pause remembers where the take belongs --------------------

    /** A RECORDING take; the Robolectric capture may throw, the state is what matters. */
    private fun recording(controller: VoiceSessionController) {
        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
    }

    @Test
    fun `a pause remembers the app the take belongs to`() {
        val controller = newController()
        recording(controller)

        controller.pause("com.example.chat")

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.pausedPackage()).isEqualTo("com.example.chat")
        controller.cancel()
    }

    @Test
    fun `a pause without a known caller records no target`() {
        val controller = newController()
        recording(controller)

        controller.pause()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.pausedPackage()).isNull()
        controller.cancel()
    }

    @Test
    fun `resume clears the target - the take is live again`() {
        val controller = newController()
        recording(controller)
        controller.pause("com.example.chat")

        controller.resume()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)
        assertThat(controller.pausedPackage()).isNull()
        controller.cancel()
    }

    @Test
    fun `cancel clears the target - the take is gone`() {
        val controller = newController()
        recording(controller)
        controller.pause("com.example.chat")

        controller.cancel()

        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        assertThat(controller.pausedPackage()).isNull()
        clearSessionFiles()
    }

    /**
     * #192, task 1: the target is session state, so the process-death snapshot
     * must carry it — an IME that dies mid-pause must still know whether the
     * next bind continues the take or ends it (#192's whole decision rests on
     * this one value).
     */
    @Test
    fun `the paused target survives process death`() {
        clearSessionFiles()
        val controller = newController()
        recording(controller)
        controller.pause("com.example.chat")

        // The writer really persisted it (the file is the contract, not the
        // field in RAM).
        val meta = File(RuntimeEnvironment.getApplication().cacheDir, "session.meta").readText()
        assertThat(meta).contains("com.example.chat")
        controller.cancel() // stop the shadow recorder before a second instance

        // Re-craft the meta: cancel() deleted it.
        File(RuntimeEnvironment.getApplication().cacheDir, "session.pcm")
            .writeBytes(ByteArray(320))
        File(RuntimeEnvironment.getApplication().cacheDir, "session.meta").writeText(meta)

        val reborn = newController()
        assertThat(reborn.restore()).isTrue()
        assertThat(reborn.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(reborn.pausedPackage()).isEqualTo("com.example.chat")

        reborn.cancel()
        clearSessionFiles()
    }

    /**
     * A pre-#192 snapshot (bare duration, no target) still restores — and
     * reports no target, which the entry decision resolves through the last
     * served field.
     */
    @Test
    fun `a restored pre-192 snapshot has no target`() {
        clearSessionFiles()
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(320))
        File(cacheDir, "session.meta").writeText("1234")

        val reborn = newController()
        assertThat(reborn.restore()).isTrue()

        assertThat(reborn.pausedPackage()).isNull()
        reborn.cancel()
        clearSessionFiles()
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
        // #118 Phase A: a pendingResult re-delivery is tagged so the observer
        // can tell a rotation re-delivery from a live emit.
        assertThat(completed.single().redelivered).isTrue()
        assertThat(completed.single().partial).isNull()
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
     * #113 salvage: a cancel that destroys a session whose fragments were
     * already transcribed must NOT silently void the paid-for transcripts —
     * the joined text is published as [VoiceSessionController.Event
     * .CancelledWithTranscripts] before the teardown. Driven via a restored
     * session with committed + live-transcribed fragments (the same
     * hand-crafted snapshot pattern as the #116 tests): stop runs the worker
     * (4 fragments transcribed), the LLM failure parks PAUSED with cached
     * transcripts, and the subsequent cancel salvages them.
     */
    @Test
    fun `cancel with transcribed fragments salvages the joined transcript`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } answers {
            callIndex++
            mockSttCall(Response.success(SttResponse(text = "part $callIndex", language = null)))
        }
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(Exception("LLM post-processing failed: HTTP 500"))

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        assertThat(callIndex).isEqualTo(4)

        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        controller.cancel()

        val salvaged = events.filterIsInstance<VoiceSessionController.Event.CancelledWithTranscripts>()
        assertThat(salvaged).hasSize(1)
        assertThat(salvaged.single().text).isEqualTo("part 1 part 2 part 3 part 4")
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
        // The salvage fires only for transcript-bearing sessions; a second
        // cancel (now IDLE, nothing left) stays silent.
        val second = mutableListOf<VoiceSessionController.Event>()
        controller.attach { second.add(it) }
        controller.cancel()
        assertThat(second.filterIsInstance<VoiceSessionController.Event.CancelledWithTranscripts>())
            .isEmpty()
        clearSessionFiles()
    }

    /**
     * #192/#152: the IME asks before an automatic cancel. `salvageableText()`
     * must report exactly what `cancel()` would hand over, and reading it must
     * consume nothing — a destructive read would make the guard discard the
     * audio it has just proved salvageable.
     */
    @Test
    fun `salvageableText reports the cancel salvage without consuming it`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000) { i -> (i % 97).toByte() })
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } answers {
            callIndex++
            mockSttCall(Response.success(SttResponse(text = "part $callIndex", language = null)))
        }
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.failure(Exception("LLM post-processing failed: HTTP 500"))

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)
        assertThat(callIndex).isEqualTo(4)

        assertThat(controller.salvageableText()).isEqualTo("part 1 part 2 part 3 part 4")
        // Read-only: the second look says the same.
        assertThat(controller.salvageableText()).isEqualTo("part 1 part 2 part 3 part 4")

        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        controller.cancel()

        assertThat(events.filterIsInstance<VoiceSessionController.Event.CancelledWithTranscripts>())
            .containsExactly(
                VoiceSessionController.Event.CancelledWithTranscripts("part 1 part 2 part 3 part 4")
            )
        assertThat(controller.salvageableText()).isNull()
        clearSessionFiles()
    }

    /**
     * The other half of the #192 guard: a take with AUDIO but no transcript has
     * nothing to hand over. `salvageableText()` is null while the buffered time
     * is real, so the IME must keep it paused instead of cancelling it into
     * silence.
     */
    @Test
    fun `salvageableText is null for buffered audio without transcripts`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000) { i -> (i % 97).toByte() })
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")
        @Suppress("DEPRECATION")
        val http500 = Response.error<SttResponse>(500, ResponseBody.create(null, "boom"))
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns
            mockSttCall(http500)

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        assertThat(controller.recordedDurationMs()).isGreaterThan(0L)
        assertThat(controller.salvageableText()).isNull()

        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        controller.cancel()
        assertThat(events.filterIsInstance<VoiceSessionController.Event.CancelledWithTranscripts>())
            .isEmpty()
        clearSessionFiles()
    }

    /**
     * #113 salvage counter-case: a cancel with NOTHING transcribed must not
     * emit the salvage event — the IDLE tail is silence, not noise.
     */    @Test
    fun `cancel without transcripts emits no salvage event`() {
        val controller = newController()
        val events = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { events.add(it) }
        } catch (_: Throwable) {
        }
        // cancel directly from RECORDING — no fragment was transcribed yet
        controller.cancel()
        assertThat(events.filterIsInstance<VoiceSessionController.Event.CancelledWithTranscripts>())
            .isEmpty()
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)
    }

    /**
     * #138 regression: a SUCCESSFULLY delivered session must stay silent on a
     * post-delivery cancel. Before the fix, the #133 switch-back destroyed
     * the IME after `Completed`, its onDestroy IDLE branch called `cancel()`,
     * and the never-cleared transcriber success cache was salvaged — toast +
     * clipboard copy of text that was already inserted into the field.
     * Driven via the same restored-fragment-session pattern as the #113
     * salvage test above, but the pipeline SUCCEEDS: the `Completed` event
     * arrives, the state reaches IDLE, and the teardown cancel emits nothing.
     */
    @Test
    fun `cancel after a successful delivery emits no salvage event`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val pcm = ByteArray(40_000) { i -> (i % 97).toByte() }
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } answers {
            callIndex++
            mockSttCall(Response.success(SttResponse(text = "part $callIndex", language = null)))
        }
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns
            Result.success("polished")

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        controller.stopAndTranscribe()
        controller.awaitIdle()

        // The session delivered successfully and reached IDLE.
        assertThat(events.filterIsInstance<VoiceSessionController.Event.Completed>()).hasSize(1)
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.IDLE)

        // The #133 switch-back destroys the IME → its onDestroy IDLE branch
        // calls cancel(): pure teardown, no salvage of the delivered text.
        val after = mutableListOf<VoiceSessionController.Event>()
        controller.attach { after.add(it) }
        controller.cancel()
        assertThat(after.filterIsInstance<VoiceSessionController.Event.CancelledWithTranscripts>())
            .isEmpty()
        clearSessionFiles()
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
        clearSessionFiles()
        settings.compressAudio = false
        // #199: restored snapshot, NOT a live take. The live-vs-batch surface
        // is decided by whether the recording captured ANY PCM at stop (an
        // empty spool falls through to the legacy batch path by design), and
        // the Robolectric shadow AudioRecord yields its first byte on its own
        // schedule — under a loaded fork it can still be zero when
        // stopAndTranscribe() runs, which silently turned this test's
        // finishTranscription expectation into a transcribe call. A restored
        // spool is encoded synchronously in prepareTailSync(), so the live
        // fragment path is the ONLY reachable one; the assertion below stays
        // exactly as it was.
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000))
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"flushproc"}""")
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
        assertThat(controller.restore()).isTrue()
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
        clearSessionFiles()
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
        awaitEvents(events) { e ->
            e.filterIsInstance<VoiceSessionController.Event.StateChanged>()
                .lastOrNull()?.state == VoiceSessionController.State.PAUSED &&
                e.filterIsInstance<VoiceSessionController.Event.Completed>().size == 1
        }
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.recordedDurationMs()).isEqualTo(before)
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        // The snapshot must exist once PAUSED is observable (the failure
        // branch snapshots BEFORE publishing the state — #117 flake fix).
        assertThat(controller.lastSnapshotError).isNull()
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
        // The snapshot must exist once PAUSED is observable (#117 flake fix —
        // the failure branch snapshots BEFORE publishing the state).
        assertThat(controller.lastSnapshotError).isNull()

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
     * #187: the prepared-upload manifest is read through a typed shape now.
     * ONE incomplete entry invalidates the WHOLE list, so a corrupt manifest
     * can never be partially trusted. The fixture makes the two behaviours
     * distinguishable: `stale.wav` alone would validate on disk (a reuse
     * would hand it to the pipeline), while the nameless entry forces the
     * rebuild that produces `recording.wav`.
     */
    @Test
    fun `incomplete prepared manifest is rebuilt instead of partially trusted`() {
        clearSessionFiles()
        settings.compressAudio = false
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(0)) // empty recording → legacy path
        File(cacheDir, "session.meta").writeText("1250")

        val controller = newController()
        controller.restore()

        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.failure(IOException("offline"))
        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.PAUSED)

        val preparedDir = File(cacheDir, "prepared").listFiles().orEmpty().single()
        assertThat(File(preparedDir, "recording.wav").isFile).isTrue()
        // a manifest that WOULD validate if the nameless entry were skipped
        File(preparedDir, "stale.wav").writeBytes(ByteArray(8))
        File(preparedDir, "manifest.json").writeText(
            """{"files":[{"name":"stale.wav","bytes":8},{"bytes":8}]}"""
        )

        val uploadPaths = mutableListOf<String>()
        coEvery { pipeline.transcribe(any(), any(), any()) } answers {
            uploadPaths.add(firstArg<List<File>>().single().absolutePath)
            Result.success("hi")
        }
        controller.stopAndTranscribe()
        controller.awaitIdle()

        // rebuilt from the recording — the stale entry was NOT trusted
        assertThat(uploadPaths.single()).isEqualTo(File(preparedDir, "recording.wav").absolutePath)
        assertThat(File(preparedDir, "stale.wav").exists()).isFalse()
        clearSessionFiles()
    }

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
        verify(exactly = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }

        // retry: the LLM pass succeeds now; the fragments re-drain from cache
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns Result.success("hi")
        controller.stopAndTranscribe()
        controller.awaitIdle()

        verify(exactly = 4) { transcoder.transcode(any(), any()) }
        verify(exactly = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
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
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } answers {
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
        verify(exactly = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
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

        // fragment 1 (0-based) fails permanently. 410 = TERMINAL (#183): a
        // plain non-retryable failure that is NEITHER the prompt fallback
        // (400) NOR the auth latch (401/403, which stops the session's
        // remaining fragments and would change what this test measures).
        val http410 =
            @Suppress("DEPRECATION")
            Response.error<SttResponse>(410, ResponseBody.create(null, "gone"))
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(
                mockSttCall(Response.success(SttResponse(text = "one", language = null))),
                mockSttCall(http410),
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
        awaitEvents(events) { e ->
            e.filterIsInstance<VoiceSessionController.Event.Completed>().isNotEmpty()
        }
        assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
        assertThat(controller.lastSnapshotError).isNull()
        val failed = events.filterIsInstance<VoiceSessionController.Event.Completed>().last().result
        assertThat(failed.exceptionOrNull()!!.message).contains("fragment 2/4")
        assertThat(failed.exceptionOrNull()!!.message).contains("HTTP 410")
        // #183: the CLASS survives the controller, so the IME can act on it
        val failure = failed.exceptionOrNull() as SttRequestRunner.SttRequestException
        assertThat(failure.failure).isEqualTo(SttFailure.TERMINAL)
        assertThat(failure.httpCode).isEqualTo(410)
        coVerify(exactly = 0) { pipeline.finishTranscription(any(), any(), any()) }

        // raw mode retry: the failed fragment is RE-ATTEMPTED (fresh attempt
        // succeeds now), the cached fragments are not re-transcribed, and
        // the raw text comes from finishTranscription
        settings.rawMode = true
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns
            mockSttCall(Response.success(SttResponse(text = "two", language = null)))
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } returns Result.success("hi")
        controller.stopAndTranscribe()
        controller.awaitIdle()

        val stt = sttSlot.captured.getOrThrow()
        assertThat(stt.text).isEqualTo("one two three four")
        assertThat(stt.chunkCount).isEqualTo(4)
        verify(exactly = 5) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        assertThat(File(cacheDir, "fragments").exists()).isFalse()
        clearSessionFiles()
    }

    /**
     * #118 Phase A: a raw-mode rescue delivery (a fragment finally fails, but
     * Raw mode returns the joined partial instead of failing the session) is
     * TAGGED on the [VoiceSessionController.Event.Completed] (`partial`
     * non-null, naming the missing fragment) so an observer can apply a
     * delivery policy instead of committing an incomplete transcript blindly.
     */
    @Test
    fun `raw-mode rescue delivery carries the partial marker`() {
        clearSessionFiles()
        settings.compressAudio = true
        settings.rawMode = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(40_000) { i -> (i % 97).toByte() })
        File(cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"testsess"}""")

        // fragment 1 (0-based) fails permanently: 410 = TERMINAL (#183), no
        // auth latch and no prompt fallback (see the polish/raw test above)
        val http410 =
            @Suppress("DEPRECATION")
            Response.error<SttResponse>(410, ResponseBody.create(null, "gone"))
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(
                mockSttCall(Response.success(SttResponse(text = "one", language = null))),
                mockSttCall(http410),
                mockSttCall(Response.success(SttResponse(text = "three", language = null))),
                mockSttCall(Response.success(SttResponse(text = "four", language = null)))
            )

        val controller = newController(fragmentBytes = 10_000)
        controller.restore()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        controller.stopAndTranscribe()
        controller.awaitIdle()

        val completed = events.filterIsInstance<VoiceSessionController.Event.Completed>().last()
        assertThat(completed.result.isSuccess).isTrue()
        assertThat(completed.redelivered).isFalse()
        val partial = completed.partial
        assertThat(partial).isNotNull()
        assertThat(partial!!.failedIndex).isEqualTo(2)
        assertThat(partial.chunkCount).isEqualTo(4)
        // #186: the reason rides along, so an observer can say WHY.
        assertThat(partial.failure).isEqualTo(SttFailure.TERMINAL)
        assertThat(partial.message).contains("HTTP 410")
        clearSessionFiles()
    }

    /**
     * #186 (device defect): the Samsung flight with airplane mode on, raw mode
     * ON, delivered a fully failed session as an empty "rescue success" — the
     * success branch then marked the journal session COMPLETE and deleted it,
     * so the Workbench showed nothing and no Toast explained why.
     *
     * A rescue is NOT a completion: the journal survives with every fragment
     * PENDING and its failure class, which is exactly what the Workbench lists
     * as an incomplete dictation and what Retry STT finishes.
     */
    @Test
    fun `raw-mode rescue keeps the journal session recoverable instead of completing it`() {
        clearSessionFiles()
        settings.compressAudio = false
        settings.rawMode = true
        val ctx = RuntimeEnvironment.getApplication()
        val sid = "20260101-1200-cccccccc"
        File(ctx.cacheDir, "session.pcm").writeBytes(ByteArray(40_000) { i -> (i % 97).toByte() })
        File(ctx.cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"$sid"}""")
        val journal = FragmentJournal(File(ctx.filesDir, FragmentJournal.DIR_NAME))
        journal.sessionDir(sid).deleteRecursively()

        // The device case: no usable network. The #186 gate answers OFFLINE
        // BEFORE the wire, so the session makes ZERO requests and journals
        // every fragment PENDING with class OFFLINE.
        val controller = newController(fragmentBytes = 10_000, journal = journal, online = { false })
        controller.restore()
        // The delivered text is the joined partial — which is EMPTY here.
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } answers {
            Result.success(sttSlot.captured.getOrThrow().text)
        }
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }

        controller.stopAndTranscribe()
        controller.awaitIdle()

        // Nothing went out (the gate held) — the #186 behaviour is untouched.
        verify(exactly = 0) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        val completed = events.filterIsInstance<VoiceSessionController.Event.Completed>().last()
        assertThat(completed.result.getOrThrow()).isEmpty()
        val partial = completed.partial!!
        assertThat(partial.failedIndex).isEqualTo(1)
        assertThat(partial.chunkCount).isEqualTo(4)
        assertThat(partial.failure).isEqualTo(SttFailure.OFFLINE)
        assertThat(partial.message).contains("No usable network")

        // NOT marked complete, NOT deleted: the journal is the recovery contract.
        assertThat(journal.sessionDir(sid).exists()).isTrue()
        val record = journal.readRecord(sid, 0)!!
        assertThat(record.state).isEqualTo(FragmentJournal.State.PENDING.name)
        assertThat(record.failureClass).isEqualTo(SttFailure.OFFLINE.name)
        val recoverable = journal.recoverableSessions().single { it.sessionId == sid }
        assertThat(recoverable.completed).isFalse()
        assertThat(recoverable.failureClass).isEqualTo(SttFailure.OFFLINE.name)
        assertThat(recoverable.unfinishedCount).isEqualTo(4)

        // #186: the controller must forget the session — the IME's `onDestroy`
        // calls `cancel()` whenever the controller is IDLE, and that would
        // delete exactly the journal this rescue saved.
        assertThat(controller.activeSessionId()).isNull()
        controller.cancel()
        assertThat(journal.sessionDir(sid).exists()).isTrue()

        journal.sessionDir(sid).deleteRecursively()
        clearSessionFiles()
    }

    /**
     * #186 regression: the CLEAN success path is unchanged — every fragment
     * DONE, no rescue marker, so the session is marked complete and its
     * journal dir goes with the delivered transcripts (#185/#138).
     */
    @Test
    fun `a clean raw-mode success still completes and deletes the journal session`() {
        clearSessionFiles()
        settings.compressAudio = false
        settings.rawMode = true
        val ctx = RuntimeEnvironment.getApplication()
        val sid = "20260101-1200-dddddddd"
        File(ctx.cacheDir, "session.pcm").writeBytes(ByteArray(40_000) { i -> (i % 97).toByte() })
        File(ctx.cacheDir, "session.meta").writeText("""{"durationMs":1250,"sessionId":"$sid"}""")
        val journal = FragmentJournal(File(ctx.filesDir, FragmentJournal.DIR_NAME))
        journal.sessionDir(sid).deleteRecursively()

        val controller = newController(fragmentBytes = 10_000, journal = journal)
        controller.restore()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }

        controller.stopAndTranscribe()
        controller.awaitIdle()

        val completed = events.filterIsInstance<VoiceSessionController.Event.Completed>().last()
        assertThat(completed.result.isSuccess).isTrue()
        assertThat(completed.partial).isNull()
        assertThat(journal.sessionDir(sid).exists()).isFalse()
        assertThat(journal.recoverableSessions().none { it.sessionId == sid }).isTrue()
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

    // ---- #132: timestamped session IDs ----------------------------------------

    /**
     * The evidence dir name IS the owner's memory hook: `<yyyyMMdd-HHmm>-<8 hex>`
     * in LOCAL time, one path-safe token. Two ids in the same minute share the
     * prefix but never the suffix.
     */
    @Test
    fun `session ids carry a local-time prefix and stay path-safe`() {
        val a = newSessionId()
        val b = newSessionId()
        assertThat(a).matches("""\d{8}-\d{4}-[0-9a-f]{8}""")
        assertThat(b).matches("""\d{8}-\d{4}-[0-9a-f]{8}""")
        // same minute → same prefix; the uuid suffix keeps them distinct
        assertThat(a.substringBeforeLast('-')).isEqualTo(b.substringBeforeLast('-'))
        assertThat(a).isNotEqualTo(b)
    }

    private fun clearSessionFiles() {
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").delete()
        File(cacheDir, "session.meta").delete()
    }

    /** Feeds [count] profile samples with slope b ≈ 1/`msPerSecond` (fast provider when small). */
    private fun feedProfile(count: Int, msPerSecond: Long, fixedOverheadMs: Long = 150L) {
        val config = settings.sttProvider!!
        for (i in 0 until count) {
            val durationMs = 16_000L + i * 1_000
            settings.recordSttSample(
                baseUrl = config.baseUrl,
                model = config.model,
                mediaType = "audio/ogg",
                durationMs = durationMs,
                elapsedMs = fixedOverheadMs + durationMs / 1000 * msPerSecond
            )
        }
    }

    /**
     * #124 slice resolution: a wired [sliceSizeProvider] converts seconds →
     * PCM bytes (32 000 B/s) and overrides the static default — a 2-s slice
     * over a 200 kB buffer yields 3 full fragments + tail instead of the
     * single giant fragment the default size would encode.
     */
    @Test
    fun `sliceSizeProvider overrides the slice in PCM bytes`() {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(ByteArray(200_000) { i -> (i % 97).toByte() })
        File(cacheDir, "session.meta").writeText("""{"durationMs":6250,"sessionId":"testsess"}""")

        val controller = newController(sliceSizeProvider = { 2.0 })
        controller.restore()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns
            mockSttCall(Response.success(SttResponse(text = "hi", language = null)))
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } returns Result.success("hi")

        controller.stopAndTranscribe()
        controller.awaitIdle()

        val stt = sttSlot.captured.getOrThrow()
        assertThat(stt.chunkCount).isEqualTo(4)
        verify(exactly = 4) { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) }
        clearSessionFiles()
    }

    /**
     * #124: the search window is `p` % of the slice, and it GROWS INTO the
     * slice — so a larger percentage cuts earlier and therefore yields more,
     * shorter fragments. That is exactly the trade-off the adaptive rule
     * exploits: as small a window as the silence hunting allows, because a
     * small window means long fragments and few seams.
     */
    @Test
    fun `searchPercentProvider derives the search window from the slice`() {
        val wide = chunkCountFor(percent = 60.0) // 6 s window of a 10 s slice
        val narrow = chunkCountFor(percent = 10.0) // 1 s window
        assertThat(wide).isGreaterThan(narrow)
    }

    /** Runs one 40-s dictation (two 1-s pauses) with the given window % and returns the chunk count. */
    private fun chunkCountFor(percent: Double): Int {
        clearSessionFiles()
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } answers {
            secondArg<File>().apply { writeBytes(firstArg()) }
        }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        // loud speech with zero runs at [15, 16] s and [28, 29] s
        val data = ByteArray(1_280_000) { i ->
            val silence = (i in 480_000 until 512_000) || (i in 896_000 until 928_000)
            if (silence) 0 else if (i % 2 == 0) 0x55 else 0xAA.toByte()
        }
        File(cacheDir, "session.pcm").writeBytes(data)
        File(cacheDir, "session.meta").writeText("""{"durationMs":40000,"sessionId":"testsess"}""")

        val controller = newController(
            sliceSizeProvider = { 10.0 }, // 10 s slice
            searchPercentProvider = { percent }
        )
        controller.restore()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } returns
            mockSttCall(Response.success(SttResponse(text = "hi", language = null)))
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } returns Result.success("hi")

        controller.stopAndTranscribe()
        controller.awaitIdle()
        return sttSlot.captured.getOrThrow().chunkCount
    }

    /**
     * #124 invariant: a fragment is never longer than its slice, so the
     * sizer's MAX_SLICE_SECONDS must stay within the 300-s upload chunk cap
     * (#120) — plus the 1-s pre-roll. A guard against future cap drift.
     */
    @Test
    fun `the maximum slice stays within the chunk cap`() {
        assertThat(SttLatencyProfile.MAX_SLICE_SECONDS + 1.0 <= WavChunker.MAX_CHUNK_SECONDS).isTrue()
    }

    // ---- #128: Q-gated stitching, end-to-end over the restore seam ----------

    /** #126/#128 fixture: [frames] 10-ms frames of a ±[amp] Nyquist square. */
    private fun nyquistFrames(frames: Int, amp: Int): ByteArray {
        val out = ByteArray(frames * SilenceQuality.FRAME_BYTES)
        var sample = 0
        for (i in 0 until out.size step 2) {
            val v = if (sample % 2 == 0) amp else -amp
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            sample++
        }
        return out
    }

    /**
     * [total] bytes of ±3000 "speech" except the whole 10-ms frames inside
     * [zeroRange] (byte range, exclusive end), which are floor — the solid
     * #128 pause fixture (a span fully inside the pause reads Q = its frame
     * count, mirror-verified).
     */
    private fun speechWithPause(total: Int, zeroRange: IntRange): ByteArray {
        val frame = SilenceQuality.FRAME_BYTES
        val out = ByteArray(total)
        var sample = 0
        for (f in 0 until total / frame) {
            val a = f * frame
            val amp = if (zeroRange.first <= a && a + frame <= zeroRange.last + 1) 0 else 3000
            for (i in 0 until frame step 2) {
                val v = if (sample % 2 == 0) amp else -amp
                out[a + i] = (v and 0xFF).toByte()
                out[a + i + 1] = ((v shr 8) and 0xFF).toByte()
                sample++
            }
        }
        return out
    }

    /**
     * One restored fragment-session run (#128 pattern, see PITFALLS: the
     * fragment path is only reachable via a hand-crafted snapshot):
     * WAV fragments (parseable uploads), frag = search = 1 s → the
     * late-half span is [nominal + 0.5 s, nominal + 1 s], a 1-s pre-roll.
     * Returns every STT call's (uploaded PCM bytes, prompt text) in order
     * plus the delivered [TranscriptionPipeline.SttResult].
     */
    private fun runRestoredSession(
        pcm: ByteArray,
        seamPolicy: SeamPolicy = SeamPolicy.Q_GATE,
        fragmentQualityMin: Double = FragmentPreparer.DEFAULT_QUALITY_MIN,
        logger: RotatingJsonLogger? = null,
        responses: List<String>
    ): Pair<List<Pair<Int, String?>>, TranscriptionPipeline.SttResult> {
        clearSessionFiles()
        settings.compressAudio = false // WAV fragments → parseable composed uploads
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").writeBytes(pcm)
        File(cacheDir, "session.meta").writeText(
            """{"durationMs":${pcm.size / 32},"sessionId":"testsess"}"""
        )
        val calls = mutableListOf<Pair<Int, String?>>()
        var callIndex = 0
        every { sttApi.transcribeAudioSync(any(), any(), any(), any(), any(), any()) } answers {
            val part = invocation.args[1] as MultipartBody.Part
            val wav = WavReader.read(okio.Buffer().also { part.body.writeTo(it) }.readByteArray())
            val promptBody = invocation.args[4] as? RequestBody
            val prompt = promptBody?.let { p -> okio.Buffer().also { p.writeTo(it) }.readUtf8() }
            calls.add(wav.data.size to prompt)
            val text = responses.getOrNull(callIndex) ?: "resp$callIndex"
            callIndex++
            mockSttCall(Response.success(SttResponse(text = text, language = null)))
        }
        val controller = newController(
            fragmentBytes = 32_000,
            fragmentSearchBytes = 32_000,
            fragmentPreRollBytes = 32_000,
            seamPolicy = seamPolicy,
            fragmentQualityMin = fragmentQualityMin,
            logger = logger
        )
        controller.restore()
        val events = mutableListOf<VoiceSessionController.Event>()
        controller.attach { events.add(it) }
        val sttSlot = slot<Result<TranscriptionPipeline.SttResult>>()
        coEvery { pipeline.finishTranscription(capture(sttSlot), any(), any()) } returns Result.success("hi")

        controller.stopAndTranscribe()
        controller.awaitIdle()
        clearSessionFiles()
        return calls.toList() to sttSlot.captured.getOrThrow()
    }

    /**
     * The #126 whisper-THREADING seam (late-half span Q ≈ 2.3) meets the
     * #128 production gate: Q < qualityMin (10) → the tail fragment's
     * upload is composed with the 1-s pre-roll, its request carries the
     * previous transcript as the seam prompt, and the echoed pre-roll text
     * is trimmed against the known tail BEFORE the join (stt-trim
     * evidence). Mirror-verified: cut at 48 800 → fragment 0 = 48 800 B,
     * tail = 23 200 B, composed upload = 32 000 + 23 200 B.
     */
    @Test
    fun `q-gate stitches a low-quality whisper seam end-to-end`() {
        var pcm = ByteArray(0)
        repeat(45) { blk -> pcm += nyquistFrames(5, if (blk % 2 == 0) 120 else 240) }
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val logDir = File(cacheDir, "trimlogs").apply { deleteRecursively(); mkdirs() }

        val (calls, stt) = runRestoredSession(
            pcm,
            logger = RotatingJsonLogger(logDir),
            responses = listOf("eins zwei", "eins zwei drei") // the tail echoes the pre-roll
        )

        // fragment 0: bare upload (the searched cut), contextless
        assertThat(calls[0]).isEqualTo(48_800 to null as String?)
        // the tail: STITCHED — the composed upload carries the 1-s pre-roll
        // AND the request carries the seam prompt
        assertThat(calls[1]).isEqualTo(55_200 to "eins zwei")
        // the echoed pre-roll text was trimmed before the join
        assertThat(stt.text).isEqualTo("eins zwei drei")
        assertThat(stt.chunkCount).isEqualTo(2)
        val trim = File(logDir, "stt-trim.json").readText()
        assertThat(trim).contains("\"fragment\":1")
        assertThat(trim).contains("\"echoTokens\":2")
        assertThat(trim).contains("\"uploadWithPreRoll\":true")
    }

    /**
     * A solid pause (late-half span all floor → Q = 50 ≥ qualityMin = 10,
     * mirror-verified; cut at 56 160) is GOOD ENOUGH: the seam stays BARE —
     * the tail uploads unchanged, no prompt, and no trim evidence is
     * written (nothing was fed, nothing echoes).
     */
    @Test
    fun `q-gate leaves a solid-pause seam bare end-to-end`() {
        val pcm = speechWithPause(96_000, 48_000 until 64_000)
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val logDir = File(cacheDir, "trimlogs").apply { deleteRecursively(); mkdirs() }

        val (calls, stt) = runRestoredSession(
            pcm,
            logger = RotatingJsonLogger(logDir),
            responses = listOf("eins zwei", "und fertig")
        )

        assertThat(calls[0]).isEqualTo(56_160 to null as String?)
        assertThat(calls[1]).isEqualTo(39_840 to null as String?) // bare tail, no prompt
        assertThat(stt.text).isEqualTo("eins zwei und fertig")
        assertThat(File(logDir, "stt-trim.json").exists()).isFalse()
    }

    /**
     * [VoiceSessionController.fragmentQualityMin] plumbing: the SAME
     * solid-pause seam (Q = 50) flips from bare (default threshold 10) to
     * stitched when the threshold rises above the measured quality (60) —
     * the knob reaches the preparer's gate.
     */
    @Test
    fun `fragmentQualityMin plumbing - a threshold above the seam Q stitches it`() {
        val pcm = speechWithPause(96_000, 48_000 until 64_000) // Q = 50

        val bare = runRestoredSession(pcm, responses = listOf("a", "b"))
        val stitched = runRestoredSession(
            pcm,
            fragmentQualityMin = 60.0,
            responses = listOf("eins zwei", "eins zwei drei")
        )

        // default (10): Q = 50 ≥ 10 → bare tail
        assertThat(bare.first[1]).isEqualTo(39_840 to null as String?)
        // threshold 60: Q = 50 < 60 → the tail is stitched (32 000 pre-roll
        // + 39 840 fragment) and prompted
        assertThat(stitched.first[1]).isEqualTo(71_840 to "eins zwei")
        // and the echo trim heals the duplicated pre-roll text
        assertThat(stitched.second.text).isEqualTo("eins zwei drei")
    }

    /**
     * The frozen FORCED_ONLY A/B arm keeps its exact pre-#128 behaviour
     * end-to-end: the SAME whisper seam (silence-ALIGNED, Q ≈ 2.3 > 0)
     * stays bare — no pre-roll, no prompt. The discriminating case against
     * the Q-gate (which stitches this exact audio, see the whisper test).
     */
    @Test
    fun `forced-only arm keeps the aligned whisper seam bare end-to-end`() {
        var pcm = ByteArray(0)
        repeat(45) { blk -> pcm += nyquistFrames(5, if (blk % 2 == 0) 120 else 240) }

        val (calls, stt) = runRestoredSession(
            pcm,
            seamPolicy = SeamPolicy.FORCED_ONLY,
            responses = listOf("eins zwei", "drei")
        )

        assertThat(calls[0]).isEqualTo(48_800 to null as String?)
        assertThat(calls[1]).isEqualTo(23_200 to null as String?)
        assertThat(stt.text).isEqualTo("eins zwei drei")
    }

    /**
     * #198: a capture thread that fails (here: the spool cannot be written —
     * a full disk on a device) must NOT let its throwable reach the app's
     * JVM-global crash handler: that path is `System.exit(2)`, a crash dialog
     * mid-dictation on a device and a dead test fork here. The take is parked
     * PAUSED with its audio, exactly like a failed pipeline run (#84), and
     * the failure is delivered so the keyboard can say why.
     */
    @Test
    fun `a capture failure parks the take PAUSED instead of killing the app (#198)`() {
        val ctx = RuntimeEnvironment.getApplication()
        // The recorder spools to `cacheDir/recording.pcm`. A NON-EMPTY
        // directory there makes every spool write fail (resetLocked cannot
        // delete it) — the device case is a full disk.
        val spool = File(ctx.cacheDir, "recording.pcm")
        spool.deleteRecursively()
        spool.mkdirs()
        File(spool, "keep").writeText("x")
        try {
            val controller = newController(mainDispatcher = true)
            val events = mutableListOf<VoiceSessionController.Event>()

            controller.start { events += it }
            assertThat(controller.state).isEqualTo(VoiceSessionController.State.RECORDING)

            val deadline = System.currentTimeMillis() + 5_000
            while (controller.state == VoiceSessionController.State.RECORDING &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            shadowOf(Looper.getMainLooper()).idle()

            // parked, not dead — and the audio the capture did get is kept
            assertThat(controller.state).isEqualTo(VoiceSessionController.State.PAUSED)
            assertThat(controller.isCaptureRunning()).isFalse()
            val completed = events.filterIsInstance<VoiceSessionController.Event.Completed>()
            assertThat(completed).hasSize(1)
            assertThat(completed.single().result.exceptionOrNull())
                .isInstanceOf(IOException::class.java)
            controller.cancel()
        } finally {
            spool.deleteRecursively()
        }
    }
}
