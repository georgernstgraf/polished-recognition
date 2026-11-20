package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.SttFailure
import com.georgernstgraf.polishedrecognition.pipeline.SttRequestRunner
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #183: the failure class reaches the user as ACTION. "HTTP 401" alone was the
 * complaint that produced the issue — the token is dead and only the user can
 * replace it, while a transient failure needs no advice at all.
 *
 * The class → resource mapping is pure ([SttFailureText]); the composition with
 * the technical detail is the IME's, exercised on the real service.
 */
@RunWith(RobolectricTestRunner::class)
class SttFailureTextTest {

    private class Ime : PolishedVoiceInputIME()

    private lateinit var service: Ime

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
        service = Robolectric.buildService(Ime::class.java).create().get()
    }

    @Test
    fun `auth and protocol carry a hint, transient and terminal do not`() {
        assertThat(SttFailureText.resFor(SttFailure.AUTH)).isEqualTo(R.string.ime_stt_token_rejected)
        assertThat(SttFailureText.resFor(SttFailure.PROTOCOL)).isEqualTo(R.string.ime_stt_unusable_response)
        assertThat(SttFailureText.resFor(SttFailure.TRANSIENT)).isNull()
        assertThat(SttFailureText.resFor(SttFailure.TERMINAL)).isNull()
        assertThat(SttFailureText.resFor(null)).isNull()
    }

    @Test
    fun `an auth failure shows the hint plus the technical detail`() {
        val text = service.failureText(
            SttRequestRunner.SttRequestException("fragment 3/7: HTTP 401", 401, SttFailure.AUTH)
        )

        assertThat(text).isEqualTo(
            service.getString(R.string.ime_stt_token_rejected) + "\n" + "fragment 3/7: HTTP 401"
        )
    }

    @Test
    fun `a transient failure shows the detail alone`() {
        val text = service.failureText(
            SttRequestRunner.SttRequestException("HTTP 503", 503, SttFailure.TRANSIENT)
        )

        assertThat(text).isEqualTo("HTTP 503")
    }

    @Test
    fun `anything without a message falls back to the generic string`() {
        assertThat(service.failureText(null)).isEqualTo(service.getString(R.string.ime_error))
        assertThat(service.failureText(IllegalStateException()))
            .isEqualTo(service.getString(R.string.ime_error))
    }
}
