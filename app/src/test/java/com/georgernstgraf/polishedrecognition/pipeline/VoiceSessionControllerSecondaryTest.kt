package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import com.georgernstgraf.polishedrecognition.audio.AudioTranscoder
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
    private val sttApi: OpenAiSttApiService = mockk(relaxed = true)
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        settings = SettingsStore(ctx)
        settings.compressAudio = false
        // Live fragment worker parity with VoiceSessionControllerTest (#116).
        settings.sttProvider = SttProviderConfig(
            displayName = "LAN",
            baseUrl = "http://10.8.0.16:11437/v1/",
            apiToken = "token",
            model = "large-v3"
        )
        coEvery { pipeline.transcribe(any(), any(), any()) } returns Result.success("hi")
        coEvery { pipeline.finishTranscription(any(), any(), any()) } returns Result.success("hi")
        coEvery { sttApi.transcribeAudioSync(any(), any(), any(), any()) } returns
            mockSttCall(Response.success(SttResponse(text = "hi", language = null)))
    }

    private fun mockSttCall(response: Response<SttResponse>): Call<SttResponse> {
        val call = mockk<Call<SttResponse>>()
        every { call.execute() } returns response
        return call
    }

    private fun newController(): VoiceSessionController = VoiceSessionController(
        RuntimeEnvironment.getApplication(),
        pipeline,
        settings,
        transcoder,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        sttRunnerOverride = SttRequestRunner({ sttApi }, backoffMs = listOf(0L, 0L))
    )

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
    fun `secondary-only session receives completion without a primary callback`() {
        val controller = newController()
        val secondary = mutableListOf<VoiceSessionController.Event>()
        try {
            controller.startShared { secondary.add(it) }
        } catch (_: Throwable) {
            // AudioRecord is not fully supported under Robolectric.
        }

        controller.stopAndTranscribe()
        controller.awaitState(VoiceSessionController.State.IDLE)

        // Regression (#82): with no primary (IME) callback attached — exactly
        // the Duolingo/Corvus case — the result must still reach the secondary
        // listener. Before the fix it fell through to StateChanged(IDLE) and
        // the caller was told ERROR_CLIENT despite a good transcription.
        val done = secondary.filterIsInstance<VoiceSessionController.Event.Completed>()
        assertThat(done).hasSize(1)
        assertThat(done.single().result.getOrNull()).isEqualTo("hi")
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
