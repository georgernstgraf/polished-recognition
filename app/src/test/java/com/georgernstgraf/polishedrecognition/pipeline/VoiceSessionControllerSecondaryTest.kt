package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Secondary-listener support (#82): the IME keeps its primary callback
 * forever while a service session observes the shared singleton. None of
 * these tests touch the primary-callback contract — that is covered by
 * [VoiceSessionControllerTest].
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSessionControllerSecondaryTest {

    private val pipeline: TranscriptionPipeline = mockk(relaxed = true)
    private val transcoder: AudioTranscoder = mockk(relaxed = true)
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        settings = SettingsStore(ctx)
        settings.compressAudio = false
        coEvery { pipeline.transcribe(any(), any()) } returns Result.success("hi")
    }

    private fun newController(): VoiceSessionController = VoiceSessionController(
        RuntimeEnvironment.getApplication(),
        pipeline,
        settings,
        transcoder,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    private fun VoiceSessionController.awaitState(target: VoiceSessionController.State) {
        val deadline = System.currentTimeMillis() + 5000
        while (state != target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertThat(state).isEqualTo(target)
    }

    @Test
    fun `startShared delivers events to secondary without disturbing primary`() {
        val controller = newController()
        val primary = mutableListOf<VoiceSessionController.Event>()
        val secondary = mutableListOf<VoiceSessionController.Event>()
        controller.attach { primary.add(it) }
        try {
            controller.startShared { secondary.add(it) }
        } catch (_: Throwable) {
            // AudioRecord is not fully supported under Robolectric — the
            // controller still transitions to RECORDING, which is all we need.
        }

        assertThat(secondary.filterIsInstance<VoiceSessionController.Event.StateChanged>())
            .containsExactly(
                VoiceSessionController.Event.StateChanged(VoiceSessionController.State.RECORDING)
            )
        // Primary slot untouched: cancel() still reports to the primary.
        controller.cancel()
        assertThat(primary.last()).isEqualTo(
            VoiceSessionController.Event.StateChanged(VoiceSessionController.State.IDLE)
        )
    }

    @Test
    fun `secondary listener receives completion alongside primary`() {
        val controller = newController()
        val primary = mutableListOf<VoiceSessionController.Event>()
        val secondary = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.start { primary.add(it) }
        } catch (_: Throwable) {
        }
        controller.addSecondaryListener { secondary.add(it) }

        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.IDLE)

        val primaryDone = primary.filterIsInstance<VoiceSessionController.Event.Completed>()
        val secondaryDone = secondary.filterIsInstance<VoiceSessionController.Event.Completed>()
        assertThat(primaryDone).hasSize(1)
        assertThat(secondaryDone).hasSize(1)
        assertThat(secondaryDone.single().result.getOrNull()).isEqualTo("hi")
    }

    @Test
    fun `removed secondary listener receives nothing`() {
        val controller = newController()
        val secondary = mutableListOf<VoiceSessionController.Event>()
        val listener: (VoiceSessionController.Event) -> Unit = { secondary.add(it) }
        controller.addSecondaryListener(listener)
        controller.removeSecondaryListener(listener)
        try {
            controller.start { }
        } catch (_: Throwable) {
        }

        assertThat(secondary).isEmpty()
        controller.cancel()
    }

    @Test
    fun `startShared is a no-op while recording`() {
        val controller = newController()
        try {
            controller.start { }
        } catch (_: Throwable) {
        }
        val secondary = mutableListOf<VoiceSessionController.Event>()

        controller.startShared { secondary.add(it) }

        assertThat(secondary).isEmpty()
        controller.cancel()
    }
}
