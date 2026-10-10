package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.service.InsertionReason.LIVE
import com.georgernstgraf.polishedrecognition.service.InsertionReason.RAW_RESCUE_PARTIAL
import com.georgernstgraf.polishedrecognition.service.InsertionReason.REDELIVERED
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InsertionTraceTest {

    @Test
    fun `clean live delivery is LIVE`() {
        assertThat(InsertionTrace.reason(redelivered = false, partial = false))
            .isEqualTo(LIVE)
    }

    @Test
    fun `pendingResult re-delivery is REDELIVERED`() {
        assertThat(InsertionTrace.reason(redelivered = true, partial = false))
            .isEqualTo(REDELIVERED)
    }

    @Test
    fun `raw-rescue partial is RAW_RESCUE_PARTIAL`() {
        assertThat(InsertionTrace.reason(redelivered = false, partial = true))
            .isEqualTo(RAW_RESCUE_PARTIAL)
    }

    @Test
    fun `a redelivered rescue is still a partial`() {
        // Incomplete by construction dominates the delivery path: a rotation
        // re-delivery of a raw-rescue result must not be labelled REDELIVERED.
        assertThat(InsertionTrace.reason(redelivered = true, partial = true))
            .isEqualTo(RAW_RESCUE_PARTIAL)
    }
}
