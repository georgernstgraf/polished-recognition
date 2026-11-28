package com.georgernstgraf.polishedrecognition.ui

/**
 * #212: the process-wide singleton holding the PENDING settings edits of the
 * current Settings visit. The single long SettingsActivity became an overview
 * plus four category sub-screens; nothing is written to `SettingsStore` while
 * the user walks the sub-screens — every edit lands here, and the OVERVIEW's
 * back-exit (= leaving Settings entirely) validates the effective values and
 * applies the draft (`SettingsValidation`).
 *
 * A field is "unset" (`null` / absent map entry) when the user never touched
 * it — the current persisted value applies. Values are stored displayable
 * raw (strings for text fields, parsed values for toggles/slider), so a
 * sub-screen can re-display the pending edit exactly as the user left it.
 *
 * NOT part of the draft (write-through today, unchanged):
 *  - the target language + custom-language commits (the IME writes the same
 *    `targetLanguage` field, so a deferred apply would be visible mid-edit),
 *  - the model-list caches (`setSttModels`/`setLlmModels`) — Validate/Fetch
 *    commits its cache immediately, exactly as before,
 *  - forgetting a learned app (`forgetKnownApp` is an immediate delete).
 */
object SettingsDraft {

    // ---- Providers & Tokens (ProvidersActivity) ----
    var sttProviderName: String? = null
    var sttUrl: String? = null
    var sttToken: String? = null
    var sttModel: String? = null
    var llmProviderName: String? = null
    var llmUrl: String? = null
    var llmToken: String? = null
    var llmModel: String? = null

    /**
     * #153: the pending reasoning-effort choice for the LLM provider — the
     * value of the conditional spinner. `null` = no selection (nothing is
     * sent); the row itself only exists when the selected model's fetched
     * capability list advertises effort levels.
     */
    var llmReasoningEffort: String? = null

    // ---- Manage Prompts (PromptsActivity) ----
    var systemPrompt: String? = null
    var targetLanguageClause: String? = null

    // ---- Output Control (OutputControlActivity) ----
    var wrapWidth: String? = null

    /** Per-app widths committed by a selection change; `null` = use global. */
    val appWrapWidths = mutableMapOf<String, Int?>()

    /** The currently displayed but not yet committed per-app raw value. */
    var pendingAppWrap: Pair<String, String>? = null

    /** Apps forgotten while the draft is open (the forget itself is write-through). */
    val appWrapForgotten = mutableSetOf<String>()

    // ---- Advanced / Debugging (AdvancedActivity) ----
    var sliceAuto: Boolean? = null
    var sliceSeconds: String? = null
    var searchAuto: Boolean? = null
    var searchPercent: String? = null
    var workbenchFifoSize: String? = null
    var compressAudio: Boolean? = null
    var imeLivePreview: Boolean? = null
    var crossAppKeep: Boolean? = null
    var evidenceRetentionPosition: Int? = null

    /** True while no field carries a pending edit — the overview's back-exit applies nothing. */
    val isEmpty: Boolean
        get() = sttProviderName == null && sttUrl == null && sttToken == null && sttModel == null &&
            llmProviderName == null && llmUrl == null && llmToken == null && llmModel == null &&
            llmReasoningEffort == null &&
            systemPrompt == null && targetLanguageClause == null &&
            wrapWidth == null && appWrapWidths.isEmpty() && pendingAppWrap == null &&
            appWrapForgotten.isEmpty() &&
            sliceAuto == null && sliceSeconds == null && searchAuto == null && searchPercent == null &&
            workbenchFifoSize == null && compressAudio == null && imeLivePreview == null &&
            crossAppKeep == null &&
            evidenceRetentionPosition == null

    /** Discards every pending edit (called after the apply, and by tests between scenarios). */
    fun clear() {
        sttProviderName = null
        sttUrl = null
        sttToken = null
        sttModel = null
        llmProviderName = null
        llmUrl = null
        llmToken = null
        llmModel = null
        llmReasoningEffort = null
        systemPrompt = null
        targetLanguageClause = null
        wrapWidth = null
        appWrapWidths.clear()
        pendingAppWrap = null
        appWrapForgotten.clear()
        sliceAuto = null
        sliceSeconds = null
        searchAuto = null
        searchPercent = null
        workbenchFifoSize = null
        compressAudio = null
        imeLivePreview = null
        crossAppKeep = null
        evidenceRetentionPosition = null
    }
}
