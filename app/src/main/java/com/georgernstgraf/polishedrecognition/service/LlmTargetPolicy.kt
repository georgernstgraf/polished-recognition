package com.georgernstgraf.polishedrecognition.service

/**
 * #168 (epic #166 D7): target-app rule for the LLM.
 *
 * Inside the app's own Workbench the LLM is always off: the dictation is the
 * raw transcript that the editor shows and redacts, and a polish there would
 * either double-polish or land in the wrong field. The rule is evaluated per
 * bind from the **caller package**, never persisted and never written back to
 * `raw_mode` — that preference is global and would leak into foreign apps.
 *
 * A `null` caller (unresolvable) never forces anything: the safe default is to
 * keep the user's configured behaviour.
 */
object LlmTargetPolicy {

    fun forcesRaw(callerPackage: String?, ownPackage: String): Boolean =
        !callerPackage.isNullOrBlank() && callerPackage == ownPackage
}
