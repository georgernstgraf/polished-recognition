package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * #210: the voice bar never becomes the fullscreen extract editor.
 *
 * The `InputMethodService` default of `onEvaluateFullscreenMode` returns true
 * in landscape: the platform then covers the target app with its own
 * extracted-text editor (a Done button, and on OxygenOS a LIGHT field even in
 * dark mode) instead of the voice bar. This IME is a bar over the target field
 * in every orientation, so the refusal is unconditional — pinned here for both
 * orientations so a later "fullscreen is nice in landscape" idea has to argue
 * with this test first.
 */
@RunWith(RobolectricTestRunner::class)
class ImeFullscreenModeTest {

    private fun landscapeService(): PolishedVoiceInputIME {
        val app = RuntimeEnvironment.getApplication()
        app.resources.configuration.orientation =
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return Robolectric.buildService(PolishedVoiceInputIME::class.java)
            .create().get()
            .also { ShadowLooper.idleMainLooper() }
    }

    @Test
    fun `landscape never goes fullscreen - the extract editor never replaces the voice bar`() {
        val service = landscapeService()
        try {
            assertThat(service.onEvaluateFullscreenMode()).isFalse()
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun `portrait never goes fullscreen either`() {
        val app = RuntimeEnvironment.getApplication()
        app.resources.configuration.orientation =
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val service = Robolectric.buildService(PolishedVoiceInputIME::class.java)
            .create().get()
        try {
            assertThat(service.onEvaluateFullscreenMode()).isFalse()
        } finally {
            service.onDestroy()
        }
    }
}
