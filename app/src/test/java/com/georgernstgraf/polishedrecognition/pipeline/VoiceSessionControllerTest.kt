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

    private fun recordAndStop(): VoiceSessionController {
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
            // AudioRecord is not fully supported under Robolectric — the
            // controller still transitions to RECORDING, which is all we need.
        }
        controller.stopAndTranscribe()
        controller.awaitIdle()
        return controller
    }

    private fun VoiceSessionController.awaitIdle() {
        val deadline = System.currentTimeMillis() + 5000
        while (state != VoiceSessionController.State.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertThat(state).isEqualTo(VoiceSessionController.State.IDLE)
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
}
