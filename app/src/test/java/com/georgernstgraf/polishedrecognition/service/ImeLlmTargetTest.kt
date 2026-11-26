package com.georgernstgraf.polishedrecognition.service

import android.Manifest
import android.app.Application
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #168 (epic #166 D7): inside our own app the IME renders the LLM toggle
 * **disabled**, and the global preference is never written. #211: the
 * disabled toggle shows the **effective** state (unchecked = raw inside
 * our own app); a foreign bind restores the global state.
 */
@RunWith(RobolectricTestRunner::class)
class ImeLlmTargetTest {

    private lateinit var service: PolishedVoiceInputIME
    private lateinit var app: PolishedRecognitionApp

    private val controller: VoiceSessionController
        get() = app.voiceSessionController

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settingsStore.rawMode = false
        service = Robolectric.buildService(PolishedVoiceInputIME::class.java).create().get()
    }

    @After
    fun tearDown() {
        if (controller.state != VoiceSessionController.State.IDLE) controller.cancel()
        service.onDestroy()
        app.settingsStore.rawMode = false
    }

    private fun field(pkg: String) = EditorInfo().apply {
        packageName = pkg
        fieldId = 1
    }

    private fun bind(pkg: String): CheckBox {
        val view = service.onCreateInputView()
        service.onStartInputView(field(pkg), false)
        return view.findViewById(R.id.ime_llm)
    }

    @Test
    fun `our own app renders the llm toggle disabled and unchecked`() {
        val toggle = bind(app.packageName)

        assertThat(toggle.isEnabled).isFalse()
        // #211: the LLM is globally ON here — the disabled toggle must still
        // not claim a polish the Workbench take never delivers.
        assertThat(toggle.isChecked).isFalse()
        assertThat(toggle.alpha).isLessThan(1f)
    }

    @Test
    fun `rebinding a foreign app restores the global state on the toggle`() {
        bind(app.packageName)

        val toggle = bind("org.markor")

        assertThat(toggle.isEnabled).isTrue()
        // Global preference untouched by the own-app bind: checked = LLM on.
        assertThat(toggle.isChecked).isTrue()
        assertThat(toggle.alpha).isEqualTo(1f)
    }

    @Test
    fun `a foreign app keeps the toggle enabled`() {
        val toggle = bind("org.markor")

        assertThat(toggle.isEnabled).isTrue()
        assertThat(toggle.isChecked).isTrue()
        assertThat(toggle.alpha).isEqualTo(1f)
    }

    @Test
    fun `binding our own app never writes the global raw mode`() {
        app.settingsStore.rawMode = false

        val toggle = bind(app.packageName)
        toggle.performClick()

        assertThat(app.settingsStore.rawMode).isFalse()
        assertThat(SettingsStore(app).rawMode).isFalse()
    }

    @Test
    fun `a foreign app still writes the toggle to the global setting`() {
        val toggle = bind("org.markor")

        toggle.performClick()

        assertThat(app.settingsStore.rawMode).isTrue()
    }
}
