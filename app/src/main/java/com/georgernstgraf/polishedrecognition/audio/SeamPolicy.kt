package com.georgernstgraf.polishedrecognition.audio

/**
 * How the seam mechanism — the acoustic pre-roll (and, in the transcriber,
 * the Whisper text prompt) — is applied at a fragment boundary (#122).
 *
 * A cut is either **silence-aligned** (it landed inside a pause found by
 * [SilenceCutter]) or **forced** (no silence in the search window → hard cut
 * at the nominal boundary). A forced cut splits a word across the seam, so
 * the overlap is exactly what recovers it; a silence-aligned cut already
 * separates the two fragments cleanly, so adding overlap/prompt there can
 * only *manufacture* contact (spurious duplication) — the round-2 symptom.
 */
enum class SeamPolicy {
    /** Apply the pre-roll + prompt to EVERY seam (the pre-#122 behaviour). */
    ALL,

    /** Apply them only at forced (hard) seams; silence-aligned seams get none. */
    FORCED_ONLY,

    /** Never apply them (no pre-roll, no prompt). */
    OFF;
}
