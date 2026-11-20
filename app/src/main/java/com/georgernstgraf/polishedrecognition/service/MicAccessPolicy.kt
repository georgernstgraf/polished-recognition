package com.georgernstgraf.polishedrecognition.service

import android.app.AppOpsManager

/**
 * #165 layer 2: even with the window-draw gate ([AutoStartTiming]) a start
 * can land in a denied window on a ROM with different timing, and the mic
 * can be blocked outright (privacy toggle, a revoked app-op). Both cases
 * deliver silence, which would be uploaded as a noise-only session.
 *
 * The check runs shortly after the capture began — an `AudioRecord` reports
 * its recording configuration (and whether the platform is silencing it)
 * only once the framework has registered the record.
 */
object MicAccessPolicy {

    /** Grace period after a start before the capture is verified. */
    const val CHECK_DELAY_MS = 400L

    /**
     * @param appOpMode the RECORD_AUDIO app-op mode for this uid
     *   ([AppOpsManager.checkOpNoThrow])
     * @param clientSilenced the platform's own silencing flag for the live
     *   `AudioRecord` (`AudioRecordingConfiguration.isClientSilenced`)
     * @param capturing whether the `AudioRecord` is actually running — an
     *   uninitialized record means the platform refused the capture
     */
    fun isBlocked(appOpMode: Int, clientSilenced: Boolean, capturing: Boolean): Boolean =
        !capturing ||
            clientSilenced ||
            appOpMode == AppOpsManager.MODE_IGNORED ||
            appOpMode == AppOpsManager.MODE_ERRORED
}
