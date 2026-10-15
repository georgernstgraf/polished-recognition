package com.georgernstgraf.polishedrecognition.config

/**
 * #146: moved verbatim out of `SettingsStore.kt` (where it lived since the
 * beginning) so :core's transcriber/runner can reference it without dragging
 * :app's Android-bound stores along. Package unchanged — all existing imports
 * stay valid.
 */
data class SttProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val apiToken: String,
    val model: String
)
