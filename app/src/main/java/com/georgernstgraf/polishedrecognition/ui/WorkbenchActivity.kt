package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.LanguageOptions
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.delivery.ClipboardDelivery
import com.georgernstgraf.polishedrecognition.dictation.Dictation
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * #169 (epic #166): the Workbench — the app's own window. A FIFO of
 * dictations, each one raw transcript plus at most one polished transcript,
 * navigated with ◀ / ▶.
 *
 * The IME stays the dictation surface: the Workbench only supplies a focused,
 * editable field for it (D10 — the insertion lands at the caret) and observes
 * the shared [VoiceSessionController] through a **secondary** listener (D3:
 * only Workbench dictations enter the FIFO). It must never call `attach()` —
 * that would overwrite the IME's primary callback and consume the held
 * `pendingResult`, i.e. deafen the keyboard.
 *
 * #173 amends the entry model:
 *  - **the visible page decides the target** (decision 1): compose slot ⇒ NEW,
 *    a stored dictation with focus in Raw ⇒ RAW_EDIT, focus in Polished ⇒
 *    POLISHED_EDIT. A stored page never creates an entry.
 *  - the **compose slot** is transient: shown on entry, never persisted while
 *    empty, outside the ring, materialised on pause / ＋ / a navigation with
 *    content (decision 2).
 *  - the ring is **last-touched (MRU)** order with wrap-around (decisions 5/7).
 *  - the fields are content-sized accordions (decision 8).
 *
 * Delivery (D11/#170): the completed transcript goes to the clipboard on a
 * send; a **changed** field that loses focus delivers itself; the per-field
 * copy button always delivers what is visible. The clipboard is marked
 * sensitive on API 33+ (D12).
 */
class WorkbenchActivity : Activity() {

    private lateinit var app: PolishedRecognitionApp
    private lateinit var settings: SettingsStore
    private lateinit var store: DictationStore
    private lateinit var controller: VoiceSessionController

    private lateinit var root: View
    private lateinit var rawField: EditText
    private lateinit var polishedField: EditText
    private lateinit var positionLabel: TextView
    private lateinit var emptyHint: TextView
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var newButton: Button
    private lateinit var copyRawButton: Button
    private lateinit var copyPolishedButton: Button
    private lateinit var rawToggle: View
    private lateinit var polishedToggle: View
    private lateinit var rawChevron: TextView
    private lateinit var polishedChevron: TextView
    private lateinit var rawPreview: TextView
    private lateinit var polishedPreview: TextView
    private lateinit var autoLlmCheckbox: CheckBox
    private lateinit var languageDropdown: AutoCompleteTextView
    private lateinit var repolishButton: Button

    /** MRU order, least recently edited first — the last element is "newest". */
    private var dictations: List<Dictation> = emptyList()

    /**
     * [FifoNavigation.COMPOSE_INDEX] on the entry page, otherwise the position
     * of the shown dictation in [dictations].
     */
    private var index = FifoNavigation.COMPOSE_INDEX
    private var currentId: String? = null

    /** The transient compose slot's content, while it is not yet materialised. */
    private var composeRaw = ""
    private var composePolished = ""

    /** True while [render] writes the fields — edits are not user edits. */
    private var rendering = false

    /** The Workbench is the visible surface, so a transcript belongs to it (D3). */
    private var visible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * #171: the polish call, injectable so the screen's state machine is
     * testable without a network. Production uses the shared pipeline (same
     * prompts, same provider).
     */
    internal var polishRunner: suspend (String, String?) -> Result<String> = { text, language ->
        app.transcriptionPipeline.polish(text, language)
    }

    /** The per-dictation target language: seeded from settings, never written back. */
    private var polishLanguage: String? = null
    private var polishing = false
    private var polishJob: Job? = null

    /** Guards the Auto-LLM checkbox against its own programmatic state writes. */
    private var silenceAutoLlm = false

    /**
     * The field text as it was when the dictation was shown, for the
     * "changed field lost focus" trigger (D11b).
     */
    private var shownRaw = ""
    private var shownPolished = ""

    private val sessionListener: (VoiceSessionController.Event) -> Unit = { event ->
        when (event) {
            is VoiceSessionController.Event.Completed -> {
                event.result.getOrNull()?.let { onTranscriptArrived(it) }
            }
            is VoiceSessionController.Event.StateChanged -> {
                if (event.state == VoiceSessionController.State.RECORDING) onRecordingStarted()
            }
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as PolishedRecognitionApp
        settings = app.settingsStore
        controller = app.voiceSessionController
        store = DictationStore(this, fifoSize = { settings.workbenchFifoSize })

        setContentView(R.layout.activity_workbench)
        root = findViewById(R.id.workbench_root)
        rawField = findViewById(R.id.workbench_raw)
        polishedField = findViewById(R.id.workbench_polished)
        positionLabel = findViewById(R.id.workbench_position)
        emptyHint = findViewById(R.id.workbench_empty_hint)
        prevButton = findViewById(R.id.workbench_prev)
        nextButton = findViewById(R.id.workbench_next)
        newButton = findViewById(R.id.workbench_new)
        copyRawButton = findViewById(R.id.workbench_copy_raw)
        copyPolishedButton = findViewById(R.id.workbench_copy_polished)
        rawToggle = findViewById(R.id.workbench_raw_toggle)
        polishedToggle = findViewById(R.id.workbench_polished_toggle)
        rawChevron = findViewById(R.id.workbench_raw_chevron)
        polishedChevron = findViewById(R.id.workbench_polished_chevron)
        rawPreview = findViewById(R.id.workbench_raw_preview)
        polishedPreview = findViewById(R.id.workbench_polished_preview)
        autoLlmCheckbox = findViewById(R.id.workbench_auto_llm)
        languageDropdown = findViewById(R.id.workbench_language)
        repolishButton = findViewById(R.id.workbench_repolish)

        applyEdgeToEdgeInsets()
        wireActions()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        controller.addSecondaryListener(sessionListener)
        // D9: the spinner is seeded from the global settings at display time;
        // the choice lives for this visit only and is never written back.
        seedLanguageSpinner()
        reload()
        // D15: entering the Workbench starts the IME immediately. The IME's
        // own auto-start path does the rest — a foreign keyboard simply opens.
        rawField.requestFocus()
        revealSoftKeyboard()
    }

    /**
     * #173 decision 4: the keyboard must be up on entry. OxygenOS ignored a
     * `showSoftInput` that ran before the window held focus (`mShowRequested`
     * stayed false on the OPO), so the focus callback retries once the window
     * is really focused.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && rawField.hasFocus()) revealSoftKeyboard()
    }

    override fun onPause() {
        visible = false
        // #173 decision 2/3: a slot with any content is materialised before the
        // window goes away, so nothing typed into it is ever lost.
        materialiseCompose()
        commitEdits()
        controller.removeSecondaryListener(sessionListener)
        super.onPause()
    }

    override fun onDestroy() {
        polishJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** #169: targetSdk 36 lays the window out edge-to-edge; we own the insets. */
    private fun applyEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val base = WorkbenchInsets.baseOf(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val target = WorkbenchInsets.paddings(
                base = base,
                systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()),
                displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()),
                ime = insets.getInsets(WindowInsetsCompat.Type.ime()),
            )
            view.setPadding(target.left, target.top, target.right, target.bottom)
            insets
        }
    }

    /** #173 decision 4: entering the Workbench must show the soft keyboard. */
    private fun revealSoftKeyboard() {
        rawField.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(rawField, InputMethodManager.SHOW_IMPLICIT)
            // The compat path is the one OxygenOS listens to once the window
            // is focused; both are idempotent while the IME is already up.
            WindowCompat.getInsetsController(window, rawField)?.show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun wireActions() {
        newButton.setOnClickListener { newEntry() }
        findViewById<Button>(R.id.workbench_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        prevButton.setOnClickListener { navigate(older = true) }
        nextButton.setOnClickListener { navigate(older = false) }
        copyRawButton.setOnClickListener { deliver(rawField.text.toString(), notify = true) }
        copyPolishedButton.setOnClickListener { deliver(polishedField.text.toString(), notify = true) }
        // #173 decision 8: the label row is a summary — a tap collapses or
        // expands its field; focus always expands.
        rawToggle.setOnClickListener { toggleSection(isRaw = true) }
        polishedToggle.setOnClickListener { toggleSection(isRaw = false) }
        // D11b: a CHANGED field losing focus delivers itself; an unchanged
        // field left delivers nothing (the content is already the visible
        // dictation, not news for the clipboard).
        rawField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setSectionExpanded(isRaw = true, expanded = true) else onFieldLeft(isRaw = true)
        }
        polishedField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setSectionExpanded(isRaw = false, expanded = true) else onFieldLeft(isRaw = false)
        }
        // #171 (D8): the checkbox is both a state and an action — switching it
        // ON immediately polishes the current dictation and delivers the result.
        autoLlmCheckbox.setOnCheckedChangeListener { _, checked ->
            if (silenceAutoLlm) return@setOnCheckedChangeListener
            onAutoLlmChanged(checked)
        }
        repolishButton.setOnClickListener { rePolish(notify = true) }
        setSectionExpanded(isRaw = true, expanded = true)
        setSectionExpanded(isRaw = false, expanded = true)
    }

    private fun deliver(text: String, notify: Boolean) {
        if (!ClipboardDelivery.deliver(this, text, getString(R.string.workbench_title))) return
        if (notify) {
            Toast.makeText(this, getString(R.string.workbench_delivered), Toast.LENGTH_SHORT).show()
        }
    }

    /** D11b: commit first (so the store matches the screen), then deliver the edit. */
    private fun onFieldLeft(isRaw: Boolean) {
        if (rendering) return
        val field = if (isRaw) rawField else polishedField
        val shown = if (isRaw) shownRaw else shownPolished
        val text = field.text.toString()
        val changed = text != shown
        val idBefore = currentId
        commitEdits()
        if (changed && text.isNotEmpty()) deliver(text, notify = false)
        syncPositions()
        // #173 decision 9: with Auto LLM on, a changed Raw field is polished
        // once per field-leave — typed edits included — as long as the
        // polished slot is still empty.
        val id = currentId
        if (isRaw && changed && id != null && id == idBefore &&
            settings.workbenchAutoLlm && !polishing
        ) {
            val current = dictations.firstOrNull { it.id == id }
            if (current != null && current.raw.isNotBlank() && current.polished.isNullOrBlank()) {
                polish(id, current.raw, notify = false)
            }
        }
    }

    // ------------------------------------------------------------- accordion

    private fun toggleSection(isRaw: Boolean) {
        val field = if (isRaw) rawField else polishedField
        setSectionExpanded(isRaw, expanded = field.visibility != View.VISIBLE)
    }

    /** #173 decision 8: one open/closed state per section, never persisted. */
    private fun setSectionExpanded(isRaw: Boolean, expanded: Boolean) {
        val field = if (isRaw) rawField else polishedField
        val chevron = if (isRaw) rawChevron else polishedChevron
        val preview = if (isRaw) rawPreview else polishedPreview
        field.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.text = if (expanded) CHEVRON_OPEN else CHEVRON_CLOSED
        updatePreview(preview, if (expanded) "" else field.text.toString())
    }

    private fun updatePreview(preview: TextView, text: String) {
        preview.text = text
        preview.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ state

    private fun reload(selectId: String? = currentId) {
        dictations = store.loadAll()
        val found = selectId?.let { id -> dictations.indexOfFirst { it.id == id } } ?: -1
        index = if (found >= 0) found else FifoNavigation.COMPOSE_INDEX
        if (index == FifoNavigation.COMPOSE_INDEX) {
            composeRaw = ""
            composePolished = ""
        }
        render()
    }

    /**
     * Re-reads the store and follows [selectId] to its new ring position
     * **without** rewriting the fields — the field the user is typing in keeps
     * its caret (#173 decision 5: a touched dictation moves to the top).
     */
    private fun syncPositions(selectId: String? = currentId) {
        dictations = store.loadAll()
        val found = selectId?.let { id -> dictations.indexOfFirst { it.id == id } } ?: -1
        if (found >= 0) {
            index = found
            currentId = selectId
        } else if (index != FifoNavigation.COMPOSE_INDEX) {
            // The shown dictation was evicted — fall back to the entry page.
            index = FifoNavigation.COMPOSE_INDEX
            currentId = null
            composeRaw = ""
            composePolished = ""
        }
        updateChrome()
    }

    private fun render() {
        val dictation = dictations.getOrNull(index)
        currentId = dictation?.id
        rendering = true
        rawField.setText(
            if (index == FifoNavigation.COMPOSE_INDEX) composeRaw else dictation?.raw.orEmpty()
        )
        polishedField.setText(
            if (index == FifoNavigation.COMPOSE_INDEX) composePolished else dictation?.polished.orEmpty()
        )
        rendering = false
        shownRaw = rawField.text.toString()
        shownPolished = polishedField.text.toString()
        updateChrome()
    }

    /** Label, buttons, hints and the LLM row — never the field text. */
    private fun updateChrome() {
        val compose = index == FifoNavigation.COMPOSE_INDEX
        emptyHint.visibility = if (compose && dictations.isEmpty()) View.VISIBLE else View.GONE
        positionLabel.text = FifoNavigation.positionLabel(index, dictations.size)
        // #173 decision 7: both arrows are always live in the ring.
        prevButton.isEnabled = FifoNavigation.canNavigate(dictations.size)
        nextButton.isEnabled = FifoNavigation.canNavigate(dictations.size)
        copyRawButton.isEnabled = shownRaw.isNotEmpty()
        copyPolishedButton.isEnabled = shownPolished.isNotEmpty()
        // Keep the chevron and the collapsed one-line preview in step with the
        // field content, without ever changing the open/closed state.
        setSectionExpanded(isRaw = true, expanded = rawField.visibility == View.VISIBLE)
        setSectionExpanded(isRaw = false, expanded = polishedField.visibility == View.VISIBLE)
        silenceAutoLlm = true
        autoLlmCheckbox.isChecked = settings.workbenchAutoLlm
        silenceAutoLlm = false
        updatePolishControls()
    }

    private fun updatePolishControls() {
        val hasRaw = if (index == FifoNavigation.COMPOSE_INDEX) {
            rawField.text.toString().isNotBlank()
        } else {
            dictations.getOrNull(index)?.raw?.isNotBlank() == true
        }
        repolishButton.isEnabled = hasRaw && !polishing
        languageDropdown.isEnabled = hasRaw && !polishing
        repolishButton.text = getString(
            if (polishing) R.string.workbench_polishing else R.string.workbench_repolish
        )
    }

    // ------------------------------------------------------------- navigation

    /** #173 decision 7: the ring wraps; the compose slot is not part of it. */
    private fun navigate(older: Boolean) {
        commitEdits()
        materialiseCompose()
        syncPositions()
        index = if (older) {
            FifoNavigation.older(index, dictations.size)
        } else {
            FifoNavigation.newer(index, dictations.size)
        }
        render()
    }

    /**
     * #173 decision 6: ＋ is always enabled. On a pristine compose slot it only
     * focuses Raw; anywhere else any content is materialised first and a fresh
     * slot is shown.
     */
    private fun newEntry() {
        commitEdits()
        materialiseCompose()
        if (index != FifoNavigation.COMPOSE_INDEX) {
            index = FifoNavigation.COMPOSE_INDEX
            currentId = null
            composeRaw = ""
            composePolished = ""
            render()
        }
        rawField.requestFocus()
        revealSoftKeyboard()
    }

    // ------------------------------------------------------- compose slot

    /**
     * #173 decision 2: a compose slot with **any** content becomes a real
     * dictation; a pristine one is discarded. Returns the id when an entry was
     * created. The caller decides whether to re-render.
     */
    private fun materialiseCompose(): String? {
        if (index != FifoNavigation.COMPOSE_INDEX) return currentId
        commitEdits()
        if (composeRaw.isBlank() && composePolished.isBlank()) return null
        // A dictation without raw text is rejected on read, so polished-only
        // content (a dictation into Polished on the entry page) becomes Raw.
        val created = store.append(composeRaw.ifBlank { composePolished })
        if (composeRaw.isNotBlank() && composePolished.isNotBlank()) {
            store.updatePolished(created.id, composePolished)
        }
        composeRaw = ""
        composePolished = ""
        dictations = store.loadAll()
        index = dictations.indexOfFirst { it.id == created.id }
            .takeIf { it >= 0 } ?: FifoNavigation.COMPOSE_INDEX
        currentId = created.id
        return created.id
    }

    // -------------------------------------------------------------------- LLM

    /** D9: seed from the global setting; the selection is per-visit only. */
    private fun seedLanguageSpinner() {
        val languages = LanguageOptions.buildLanguageList(settings.customLanguages)
        val current = settings.targetLanguage ?: LanguageOptions.NONE_TARGET_LANGUAGE
        polishLanguage = if (current == LanguageOptions.NONE_TARGET_LANGUAGE) null else current
        languageDropdown.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                languages
            )
        )
        languageDropdown.setText(current, false)
        languageDropdown.threshold = Int.MAX_VALUE
        languageDropdown.setOnClickListener { languageDropdown.showDropDown() }
        languageDropdown.setOnItemClickListener { _, _, position, _ ->
            onLanguageSelected(position)
        }
    }

    /** D9: the per-dictation language, never written back to the settings. */
    internal fun onLanguageSelected(position: Int) {
        val selected = LanguageOptions
            .buildLanguageList(settings.customLanguages)
            .getOrNull(position) ?: return
        polishLanguage =
            if (selected == LanguageOptions.NONE_TARGET_LANGUAGE) null else selected
    }

    /** D8: persist the choice; switching ON polishes the current dictation now. */
    private fun onAutoLlmChanged(checked: Boolean) {
        settings.workbenchAutoLlm = checked
        if (checked) rePolish(notify = false)
    }

    /** D9: the visible raw text through the LLM with the chosen language. */
    private fun rePolish(notify: Boolean) {
        if (polishing) return
        // A compose slot with content becomes a real dictation before it is
        // polished — polishing must never work on an entry that cannot be saved.
        materialiseCompose()
        val id = currentId ?: return
        val raw = rawField.text.toString().ifBlank {
            dictations.firstOrNull { it.id == id }?.raw.orEmpty()
        }
        if (raw.isBlank()) return
        polish(id, raw, notify)
    }

    private fun polish(id: String, raw: String, notify: Boolean) {
        polishing = true
        updatePolishControls()
        polishJob = scope.launch {
            val result = runCatching { polishRunner(raw, polishLanguage) }
                .getOrElse { Result.failure(it) }
            polishing = false
            result.onSuccess { polished ->
                // D9: the result overwrites the single polished slot and is
                // delivered to the clipboard; Raw is untouched.
                store.updatePolished(id, polished)
                reload(id)
                deliver(polished, notify = notify)
            }.onFailure {
                // Keep the previous polished text — a failed call never
                // destroys the dictation.
                Toast.makeText(
                    this@WorkbenchActivity,
                    getString(R.string.workbench_polish_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
            updatePolishControls()
        }
    }

    /**
     * Writes the field edits into the store (or into the transient compose
     * slot). A changed **raw** field discards the polished transcript
     * (inherited desktop rule: no confirmation, no silent re-polish).
     */
    private fun commitEdits() {
        val raw = rawField.text.toString()
        val polished = polishedField.text.toString()
        if (index == FifoNavigation.COMPOSE_INDEX) {
            composeRaw = raw
            composePolished = polished
            shownRaw = raw
            shownPolished = polished
            return
        }
        val id = currentId ?: return
        val current = dictations.firstOrNull { it.id == id } ?: return
        if (raw != current.raw) {
            store.updateRaw(id, raw)
            if (current.polished != null) polishedField.setText("")
        } else if (polished != (current.polished ?: "")) {
            store.updatePolished(id, polished.ifBlank { null })
        }
        shownRaw = rawField.text.toString()
        shownPolished = polishedField.text.toString()
    }

    /**
     * Where the transcript of the running dictation belongs (#173 decision 1).
     * Decided when the recording **starts**, i.e. before the IME inserts a
     * single character — after the insertion the field is changed either way,
     * so "user edited it" and "the IME typed into it" are indistinguishable.
     */
    internal enum class DictationTarget { NEW, RAW_EDIT, POLISHED_EDIT }

    private var pendingTarget = DictationTarget.NEW

    /**
     * #173 decision 1 — the visible page decides, never a text comparison:
     *  - compose slot ⇒ NEW (the entry page; the IME's text becomes Raw),
     *  - stored dictation + focus in Raw ⇒ RAW_EDIT (at the caret, whether or
     *    not the user typed first),
     *  - focus in Polished ⇒ POLISHED_EDIT.
     */
    internal fun onRecordingStarted() {
        pendingTarget = when {
            index == FifoNavigation.COMPOSE_INDEX -> DictationTarget.NEW
            polishedField.hasFocus() -> DictationTarget.POLISHED_EDIT
            else -> DictationTarget.RAW_EDIT
        }
    }

    /**
     * D4/#173: the entry exists as soon as the first transcript arrives; the
     * compose slot becomes that entry. The IME has already inserted the text
     * into the focused field (D10) when this runs, so the field is the truth.
     *
     * D11a: the send delivers the transcript to the clipboard.
     */
    internal fun onTranscriptArrived(text: String) {
        if (!visible) return
        val target = pendingTarget
        pendingTarget = DictationTarget.NEW
        when (target) {
            DictationTarget.POLISHED_EDIT -> {
                val id = currentId
                if (id == null) {
                    val created = store.append(text)
                    store.updatePolished(created.id, polishedField.text.toString().ifBlank { null })
                    reload(created.id)
                } else {
                    store.updatePolished(id, polishedField.text.toString().ifBlank { null })
                    // #173 decision 5: the edit bumps it to the top of the ring.
                    syncPositions(id)
                }
            }
            DictationTarget.RAW_EDIT -> {
                val id = currentId
                if (id == null) {
                    reload(store.append(rawField.text.toString().ifBlank { text }).id)
                } else {
                    store.updateRaw(id, rawField.text.toString())
                    polishedField.setText("")
                    shownPolished = ""
                    syncPositions(id)
                }
            }
            DictationTarget.NEW -> {
                val raw = rawField.text.toString().ifBlank { text }
                val created = store.append(raw)
                if (composePolished.isNotBlank()) {
                    store.updatePolished(created.id, composePolished)
                }
                composeRaw = ""
                composePolished = ""
                reload(created.id)
            }
        }
        deliver(text, notify = false)
        // D8: Auto LLM polishes each NEW/edited dictation — never the polished
        // field itself (that is the user's own edit, D10).
        if (settings.workbenchAutoLlm && target != DictationTarget.POLISHED_EDIT && !polishing) {
            currentId?.let { id -> polish(id, rawField.text.toString(), notify = false) }
        }
    }

    private companion object {
        const val CHEVRON_OPEN = "▾"
        const val CHEVRON_CLOSED = "▸"
    }
}
