package com.georgernstgraf.polishedrecognition.service

/**
 * #118 Phase A: pure classification of a result delivery for the `insertion`
 * breadcrumb stream. Extracted so the reason mapping is unit tested without an
 * Android surface.
 */
enum class InsertionReason { LIVE, REDELIVERED, RAW_RESCUE_PARTIAL }

object InsertionTrace {

    /**
     * A raw-mode rescue partial dominates: the delivered text is incomplete by
     * construction regardless of how it was delivered (a redelivered rescue is
     * still a partial). Otherwise [redelivered] distinguishes the
     * `pendingResult` re-delivery after a rotation (#83) from a live emit.
     */
    fun reason(redelivered: Boolean, partial: Boolean): InsertionReason = when {
        partial -> InsertionReason.RAW_RESCUE_PARTIAL
        redelivered -> InsertionReason.REDELIVERED
        else -> InsertionReason.LIVE
    }
}
