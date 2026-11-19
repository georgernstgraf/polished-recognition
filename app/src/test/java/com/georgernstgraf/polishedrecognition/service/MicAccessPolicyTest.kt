package com.georgernstgraf.polishedrecognition.service

import android.app.AppOpsManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #165 layer 2: what counts as a blocked microphone after a capture started.
 */
class MicAccessPolicyTest {

    @Test
    fun `a granted, running, unsilenced capture is fine`() {
        assertThat(
            MicAccessPolicy.isBlocked(
                AppOpsManager.MODE_ALLOWED, clientSilenced = false, capturing = true
            )
        ).isFalse()
    }

    @Test
    fun `while-in-use mode is fine while the window is visible`() {
        // The normal state for a foreground service holding the microphone
        // type: allowed as long as the app is in the foreground.
        assertThat(
            MicAccessPolicy.isBlocked(
                AppOpsManager.MODE_FOREGROUND, clientSilenced = false, capturing = true
            )
        ).isFalse()
    }

    @Test
    fun `a silenced client is blocked`() {
        assertThat(
            MicAccessPolicy.isBlocked(
                AppOpsManager.MODE_FOREGROUND, clientSilenced = true, capturing = true
            )
        ).isTrue()
    }

    @Test
    fun `an ignored app-op is blocked`() {
        assertThat(
            MicAccessPolicy.isBlocked(
                AppOpsManager.MODE_IGNORED, clientSilenced = false, capturing = true
            )
        ).isTrue()
    }

    @Test
    fun `an errored app-op is blocked`() {
        assertThat(
            MicAccessPolicy.isBlocked(
                AppOpsManager.MODE_ERRORED, clientSilenced = false, capturing = true
            )
        ).isTrue()
    }

    @Test
    fun `a capture that never started is blocked`() {
        // AudioRecorder.start() returns silently when the platform refuses to
        // initialize the record — such a take would upload pure silence.
        assertThat(
            MicAccessPolicy.isBlocked(
                AppOpsManager.MODE_ALLOWED, clientSilenced = false, capturing = false
            )
        ).isTrue()
    }

    @Test
    fun `the check runs before a fragment could be committed`() {
        // The first fragment is committed after the slice length (>= 5 s), so
        // the check must fire well before that to abort an empty take.
        assertThat(MicAccessPolicy.CHECK_DELAY_MS).isLessThan(2000L)
    }
}
