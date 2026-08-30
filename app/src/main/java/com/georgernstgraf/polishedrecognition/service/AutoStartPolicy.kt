package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController

object AutoStartPolicy {
    fun shouldAutoStart(
        state: VoiceSessionController.State,
        hasMicPermission: Boolean
    ): Boolean = state == VoiceSessionController.State.IDLE && hasMicPermission
}
