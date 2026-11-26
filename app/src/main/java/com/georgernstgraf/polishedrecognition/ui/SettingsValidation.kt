package com.georgernstgraf.polishedrecognition.ui

import android.content.Context
import com.georgernstgraf.polishedrecognition.config.EvidenceRetention
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttLatencyProfile
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore
import java.util.Locale

/**
 * #212: the validation + write logic extracted verbatim from the old
 * `SettingsActivity.saveAndClose()` — same checks, same check names, same
 * writes (token/secret split, FIFO prune-on-apply) — parameterized by
 * DRAFT-OR-STORE effective values (`SettingsDraft` pending value wins over
 * the persisted one).
 *
 * The overview's back-exit runs [failedChecks] over the effective values;
 * a failed check names the reason in a toast (the old `saveAborted` style)
 * and its entry is SKIPPED in [apply] — the failing field keeps its previous
 * persisted value while all other pending changes apply. The caller still
 * finishes: leaving Settings never traps the user in the screen.
 */
object SettingsValidation {

    // The abort-check names, in the order the old saveAndClose() ran them.
    const val CHECK_APP_WRAP_WIDTH = "app-wrap-width"
    const val CHECK_STT_MODEL_EMPTY = "stt-model-empty"
    const val CHECK_STT_MODEL_NOT_IN_LIST = "stt-model-not-in-list"
    const val CHECK_LLM_MODEL_EMPTY = "llm-model-empty"
    const val CHECK_LLM_MODEL_NOT_IN_LIST = "llm-model-not-in-list"
    const val CHECK_WRAP_WIDTH = "wrap-width"
    const val CHECK_SLICE_SECONDS = "slice-seconds"
    const val CHECK_SEARCH_PERCENT = "search-percent"
    const val CHECK_WORKBENCH_FIFO_SIZE = "workbench-fifo-size"

    // ---------------------------------------------------------------- effective values

    fun sttName(draft: SettingsDraft, store: SettingsStore): String =
        draft.sttProviderName ?: store.sttProvider?.displayName ?: ""

    fun sttUrl(draft: SettingsDraft, store: SettingsStore): String =
        draft.sttUrl ?: store.sttProvider?.baseUrl ?: ""

    fun sttToken(draft: SettingsDraft, store: SettingsStore): String =
        draft.sttToken ?: store.sttProvider?.apiToken ?: ""

    fun sttModel(draft: SettingsDraft, store: SettingsStore): String =
        draft.sttModel ?: store.sttProvider?.model ?: ""

    fun llmName(draft: SettingsDraft, store: SettingsStore): String =
        draft.llmProviderName ?: store.llmProvider?.displayName ?: ""

    fun llmUrl(draft: SettingsDraft, store: SettingsStore): String =
        draft.llmUrl ?: store.llmProvider?.baseUrl ?: ""

    fun llmToken(draft: SettingsDraft, store: SettingsStore): String =
        draft.llmToken ?: store.llmProvider?.apiToken ?: ""

    fun llmModel(draft: SettingsDraft, store: SettingsStore): String =
        draft.llmModel ?: store.llmProvider?.model ?: ""

    fun wrapWidthText(draft: SettingsDraft, store: SettingsStore): String =
        draft.wrapWidth ?: String.format(Locale.ROOT, "%d", store.wrapWidth)

    fun globalWrapWidth(draft: SettingsDraft, store: SettingsStore): Int =
        wrapWidthText(draft, store).trim().toIntOrNull() ?: store.wrapWidth

    fun sliceAuto(draft: SettingsDraft, store: SettingsStore): Boolean =
        draft.sliceAuto ?: (store.sliceSecondsOverride == null)

    fun sliceSecondsText(draft: SettingsDraft, store: SettingsStore): String =
        draft.sliceSeconds ?: store.sliceSecondsOverride?.let { formatNumber(it) } ?: ""

    fun searchAuto(draft: SettingsDraft, store: SettingsStore): Boolean =
        draft.searchAuto ?: (store.searchPercentOverride == null)

    fun searchPercentText(draft: SettingsDraft, store: SettingsStore): String =
        draft.searchPercent ?: store.searchPercentOverride?.let { formatNumber(it) } ?: ""

    fun fifoText(draft: SettingsDraft, store: SettingsStore): String =
        draft.workbenchFifoSize ?: String.format(Locale.ROOT, "%d", store.workbenchFifoSize)

    fun compressAudio(draft: SettingsDraft, store: SettingsStore): Boolean =
        draft.compressAudio ?: store.compressAudio

    fun imeLivePreview(draft: SettingsDraft, store: SettingsStore): Boolean =
        draft.imeLivePreview ?: store.imeLivePreview

    fun evidenceRetentionPosition(draft: SettingsDraft, store: SettingsStore): Int =
        draft.evidenceRetentionPosition
            ?: EvidenceRetention.positionFor(store.evidenceRetentionSeconds)

    /** The displayed number: integral values without a decimal point. */
    fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    // ---------------------------------------------------------------- validation

    /**
     * Runs the old saveAndClose() checks IN THE OLD ORDER over the effective
     * values and returns the names of the FAILED checks (empty = all good).
     */
    fun failedChecks(draft: SettingsDraft, store: SettingsStore): List<String> {
        val failed = mutableListOf<String>()

        draft.pendingAppWrap?.let { (_, raw) ->
            if (!isValidPerAppRaw(raw)) failed += CHECK_APP_WRAP_WIDTH
        }

        val sttModel = sttModel(draft, store).trim()
        if (sttModel.isEmpty()) {
            failed += CHECK_STT_MODEL_EMPTY
        } else {
            val sttModels = store.getSttModels(sttUrl(draft, store))
            if (sttModels.isNotEmpty() && sttModel !in sttModels) {
                failed += CHECK_STT_MODEL_NOT_IN_LIST
            }
        }

        val llmModel = llmModel(draft, store).trim()
        if (llmModel.isEmpty()) {
            failed += CHECK_LLM_MODEL_EMPTY
        } else {
            val llmModels = store.getLlmModels(llmUrl(draft, store))
            if (llmModels.isNotEmpty() && llmModel !in llmModels) {
                failed += CHECK_LLM_MODEL_NOT_IN_LIST
            }
        }

        val wrapWidth = wrapWidthText(draft, store).trim().toIntOrNull()
        if (wrapWidth == null || (wrapWidth != 0 && wrapWidth < SettingsStore.MIN_WRAP_WIDTH)) {
            failed += CHECK_WRAP_WIDTH
        }

        if (!sliceAuto(draft, store)) {
            val value = sliceSecondsText(draft, store).trim().toFloatOrNull()
            if (value == null ||
                value < SttLatencyProfile.MIN_SLICE_SECONDS ||
                value > SttLatencyProfile.MAX_SLICE_SECONDS
            ) {
                failed += CHECK_SLICE_SECONDS
            }
        }

        if (!searchAuto(draft, store)) {
            val value = searchPercentText(draft, store).trim().toFloatOrNull()
            if (value == null ||
                value < SttLatencyProfile.MIN_SEARCH_PERCENT ||
                value > SttLatencyProfile.MAX_SEARCH_PERCENT
            ) {
                failed += CHECK_SEARCH_PERCENT
            }
        }

        val fifoSize = fifoText(draft, store).trim().toIntOrNull()
        if (fifoSize == null ||
            fifoSize < DictationStore.MIN_FIFO_SIZE ||
            fifoSize > DictationStore.MAX_FIFO_SIZE
        ) {
            failed += CHECK_WORKBENCH_FIFO_SIZE
        }

        return failed
    }

    /** The per-app wrap rule (#100): empty = global, 0 = off, otherwise >= MIN_WRAP_WIDTH. */
    fun isValidPerAppRaw(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true
        val value = trimmed.toIntOrNull() ?: return false
        return value == 0 || value >= SettingsStore.MIN_WRAP_WIDTH
    }

    // ---------------------------------------------------------------- apply

    /**
     * Writes every effective value whose check did not fail. Entries with a
     * failed check keep their previous persisted value (the caller still
     * leaves the screen). Purely pending prompt values are written only when
     * the draft holds one.
     */
    fun apply(
        context: Context,
        draft: SettingsDraft,
        store: SettingsStore,
        promptStore: PromptStore,
        failed: Collection<String>
    ) {
        // Per-app widths: fold the pending raw first, then write the map.
        val widths = draft.appWrapWidths.toMutableMap()
        draft.pendingAppWrap?.let { (pkg, raw) ->
            val trimmed = raw.trim()
            val value = trimmed.toIntOrNull()
            when {
                trimmed.isEmpty() -> widths[pkg] = null
                value != null && (value == 0 || value >= SettingsStore.MIN_WRAP_WIDTH) ->
                    widths[pkg] = value
                // else: invalid — CHECK_APP_WRAP_WIDTH already failed; the
                // entry keeps its previous persisted value.
            }
        }
        store.saveKnownAppWidths(widths.filterKeys { it !in draft.appWrapForgotten })

        // Provider entries are written only when the draft holds a pending
        // change — an untouched (or entirely empty) draft never rewrites the
        // stored config, and never materialises an empty one over a null.
        val sttPending = draft.sttProviderName != null || draft.sttUrl != null ||
            draft.sttToken != null || draft.sttModel != null
        if (sttPending && CHECK_STT_MODEL_EMPTY !in failed && CHECK_STT_MODEL_NOT_IN_LIST !in failed) {
            store.sttProvider = SttProviderConfig(
                displayName = sttName(draft, store),
                baseUrl = sttUrl(draft, store),
                apiToken = sttToken(draft, store),
                model = sttModel(draft, store).trim()
            )
        }
        val llmPending = draft.llmProviderName != null || draft.llmUrl != null ||
            draft.llmToken != null || draft.llmModel != null
        if (llmPending && CHECK_LLM_MODEL_EMPTY !in failed && CHECK_LLM_MODEL_NOT_IN_LIST !in failed) {
            store.llmProvider = LlmProviderConfig(
                displayName = llmName(draft, store),
                baseUrl = llmUrl(draft, store),
                apiToken = llmToken(draft, store),
                model = llmModel(draft, store).trim()
            )
        }

        store.compressAudio = compressAudio(draft, store)
        store.imeLivePreview = imeLivePreview(draft, store)
        store.evidenceRetentionSeconds =
            EvidenceRetention.secondsFor(evidenceRetentionPosition(draft, store))

        if (CHECK_WRAP_WIDTH !in failed) {
            store.wrapWidth = wrapWidthText(draft, store).trim().toIntOrNull() ?: store.wrapWidth
        }

        // #124 fragment geometry: auto = null, manual = the effective value.
        if (CHECK_SLICE_SECONDS !in failed) {
            store.sliceSecondsOverride =
                if (sliceAuto(draft, store)) null
                else sliceSecondsText(draft, store).trim().toFloatOrNull()
        }
        if (CHECK_SEARCH_PERCENT !in failed) {
            store.searchPercentOverride =
                if (searchAuto(draft, store)) null
                else searchPercentText(draft, store).trim().toFloatOrNull()
        }

        // #172 (epic #166 D2): persist the FIFO depth, then prune at once —
        // a shrunk cap must not leave orphan dictation files.
        if (CHECK_WORKBENCH_FIFO_SIZE !in failed) {
            val fifoSize = fifoText(draft, store).trim().toIntOrNull()
            if (fifoSize != null) {
                store.workbenchFifoSize = fifoSize
                DictationStore(context, fifoSize = { store.workbenchFifoSize }).pruneToSize()
            }
        }

        draft.systemPrompt?.let { promptStore.set(PromptStore.KEY_SYSTEM, it) }
        draft.targetLanguageClause?.let { promptStore.set(PromptStore.KEY_TRANSLATE, it) }
    }
}
