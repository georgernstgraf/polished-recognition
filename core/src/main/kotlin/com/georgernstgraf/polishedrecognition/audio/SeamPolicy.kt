package com.georgernstgraf.polishedrecognition.audio

/**
 * How the seam mechanism — the acoustic pre-roll, the Whisper text prompt
 * and (keyed on the pre-roll) the echo trim — is applied at a fragment
 * boundary (#122, #128).
 *
 * **#128:** the production default is [Q_GATE] — stitching is a QUALITY
 * decision, the one Boolean downstream of the cut (`stitch = Q < Q_min`):
 * the cut is best-effort, the stitch is the guarantee. The #126 live
 * failure (a word split across a silence-aligned seam, transcribed by BOTH
 * fragments) happened precisely because a silence-aligned seam routed
 * around every safety net; correctness must not depend on the cut being
 * perfect.
 *
 * [ALL] / [FORCED_ONLY] / [OFF] keep their exact pre-#128 semantics as
 * frozen study arms for the #129 research pass — production never
 * selects them (the controller default is [Q_GATE]); the `:analyzer`
 * CLI can select them per run.
 */
enum class SeamPolicy {
    /** Apply the pre-roll + prompt to EVERY seam (the pre-#122 behaviour). */
    ALL,

    /**
     * Apply them only at forced (hard) seams; silence-aligned seams
     * (`Q > 0`) get none — the pre-#128 production default, kept as the
     * study's "after" A/B arm.
     */
    FORCED_ONLY,

    /** Never apply them (no pre-roll, no prompt). */
    OFF,

    /**
     * #128 quality gate — the production default: stitch (pre-roll +
     * prompt + echo trim) at every seam whose quality Q falls below
     * `qualityMin` (FragmentPreparer's threshold). The forced path's
     * Q = 0 always stitches; a null Q (legacy/unmeasured cut) stitches
     * too — the conservative direction. Boundary: Q == qualityMin is
     * GOOD ENOUGH (bare seam, strict less-than).
     */
    Q_GATE;
}
