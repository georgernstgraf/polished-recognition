package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PostSendSwitchPolicyTest {

    private val latin = "com.android.inputmethod.latin/.LatinIME"

    @Test
    fun `setting on with a resolved target switches`() {
        assertThat(PostSendSwitchPolicy.decide(autoSwitch = true, target = latin))
            .isEqualTo(PostSendSwitchPolicy.Decision.SWITCH)
    }

    @Test
    fun `setting off hides regardless of target`() {
        assertThat(PostSendSwitchPolicy.decide(autoSwitch = false, target = latin))
            .isEqualTo(PostSendSwitchPolicy.Decision.HIDE_ONLY)
    }

    @Test
    fun `no available typing keyboard hides silently`() {
        // Unlike the manual keyboard-switch button, the automatic switch never
        // opens the system keyboard settings (#133, owner decision).
        assertThat(PostSendSwitchPolicy.decide(autoSwitch = true, target = null))
            .isEqualTo(PostSendSwitchPolicy.Decision.HIDE_ONLY)
    }
}
