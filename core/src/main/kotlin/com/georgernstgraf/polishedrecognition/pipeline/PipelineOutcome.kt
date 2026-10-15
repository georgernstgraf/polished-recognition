package com.georgernstgraf.polishedrecognition.pipeline

/**
 * #142: what the pipeline DID with the session's raw input — the raw
 * transcript, whether polish ran, and the full LLM pass (prompts +
 * response, or the error). The forensic record consumes it.
 *
 * #146: extracted verbatim from [TranscriptionPipeline] (where it was a
 * nested class) so [SessionBundle] — which lives in :core now — can hold
 * the pipeline's last reported outcome without :core depending on :app.
 * Same package, same shape; the qualified form
 * `TranscriptionPipeline.PipelineOutcome` no longer exists.
 */
data class PipelineOutcome(
    val rawText: String,
    val rawMode: Boolean,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val llmResponse: String? = null,
    val llmError: String? = null
)
