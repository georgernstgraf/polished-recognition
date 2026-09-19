package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
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
        coEvery { pipeline.transcribe(any(), any()) } returns Result.success("hi")
    }

    private fun newController(): VoiceSessionController = VoiceSessionController(
        RuntimeEnvironment.getApplication(),
        pipeline,
        settings,
        transcoder,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
        val fileSlot = slot<File>()
        coVerify { pipeline.transcribe(capture(fileSlot), any()) }
        return fileSlot.captured
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

        assertThat(uploadedFile().name).isEqualTo("recording.ogg")
        verify(exactly = 1) { transcoder.transcode(any(), any()) }
    }

    @Test
    fun `transcoder failure falls back to recording wav`() {
        settings.compressAudio = true
        every { transcoder.transcode(any(), any()) } throws IOException("no encoder")

        recordAndStop()

        assertThat(uploadedFile().name).isEqualTo("recording.wav")
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
        coEvery { pipeline.transcribe(any(), any()) } coAnswers {
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
        coEvery { pipeline.transcribe(any(), any()) } coAnswers {
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
        coEvery { pipeline.transcribe(any(), any()) } coAnswers {
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
        coEvery { pipeline.transcribe(any(), any()) } returns Result.failure(IOException("offline"))
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
        coEvery { pipeline.transcribe(any(), any()) } returns Result.failure(IOException("offline"))
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
        coEvery { pipeline.transcribe(any(), any()) } returns
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
        coEvery { pipeline.transcribe(any(), any()) } returns Result.failure(IOException("offline"))
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

    private fun clearSessionFiles() {
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        File(cacheDir, "session.pcm").delete()
        File(cacheDir, "session.meta").delete()
    }
}
