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
    private lateinit var copyRawButton: Button
    private lateinit var copyPolishedButton: Button
    private lateinit var autoLlmCheckbox: CheckBox
    private lateinit var languageDropdown: AutoCompleteTextView
    private lateinit var repolishButton: Button

    private var dictations: List<Dictation> = emptyList()
    private var index = -1
    private var currentId: String? = null

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
        copyRawButton = findViewById(R.id.workbench_copy_raw)
        copyPolishedButton = findViewById(R.id.workbench_copy_polished)
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
        rawField.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(rawField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onPause() {
        visible = false
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

    private fun wireActions() {
        findViewById<Button>(R.id.workbench_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        prevButton.setOnClickListener {
            commitEdits()
            index = FifoNavigation.older(index, dictations.size)
            render()
        }
        nextButton.setOnClickListener {
            commitEdits()
            index = FifoNavigation.newer(index, dictations.size)
            render()
        }
        copyRawButton.setOnClickListener { deliver(rawField.text.toString(), notify = true) }
        copyPolishedButton.setOnClickListener { deliver(polishedField.text.toString(), notify = true) }
        // D11b: a CHANGED field losing focus delivers itself; an unchanged
        // field left delivers nothing (the content is already the visible
        // dictation, not news for the clipboard).
        rawField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onFieldLeft(isRaw = true)
        }
        polishedField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onFieldLeft(isRaw = false)
        }
        // #171 (D8): the checkbox is both a state and an action — switching it
        // ON immediately polishes the current dictation and delivers the result.
        autoLlmCheckbox.setOnCheckedChangeListener { _, checked ->
            if (silenceAutoLlm) return@setOnCheckedChangeListener
            onAutoLlmChanged(checked)
        }
        repolishButton.setOnClickListener { rePolish(notify = true) }
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
        commitEdits()
        if (changed && text.isNotEmpty()) deliver(text, notify = false)
    }

    // ------------------------------------------------------------------ state

    private fun reload(selectId: String? = currentId) {
        dictations = store.loadAll()
        val found = selectId?.let { id -> dictations.indexOfFirst { it.id == id } } ?: -1
        index = if (found >= 0) found else FifoNavigation.initialIndex(dictations.size)
        render()
    }

    private fun render() {
        val dictation = dictations.getOrNull(index)
        currentId = dictation?.id
        rendering = true
        rawField.setText(dictation?.raw ?: "")
        polishedField.setText(dictation?.polished ?: "")
        rendering = false
        shownRaw = rawField.text.toString()
        shownPolished = polishedField.text.toString()
        emptyHint.visibility = if (dictations.isEmpty()) View.VISIBLE else View.GONE
        positionLabel.text = FifoNavigation.positionLabel(index, dictations.size)
        prevButton.isEnabled = FifoNavigation.canGoOlder(index, dictations.size)
        nextButton.isEnabled = FifoNavigation.canGoNewer(index, dictations.size)
        copyRawButton.isEnabled = shownRaw.isNotEmpty()
        copyPolishedButton.isEnabled = shownPolished.isNotEmpty()
        silenceAutoLlm = true
        autoLlmCheckbox.isChecked = settings.workbenchAutoLlm
        silenceAutoLlm = false
        updatePolishControls()
    }

    private fun updatePolishControls() {
        val hasRaw = dictations.getOrNull(index)?.raw?.isNotBlank() == true
        repolishButton.isEnabled = hasRaw && !polishing
        languageDropdown.isEnabled = hasRaw && !polishing
        repolishButton.text = getString(
            if (polishing) R.string.workbench_polishing else R.string.workbench_repolish
        )
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
     * Writes the field edits into the store. A changed **raw** field discards
     * the polished transcript (inherited desktop rule: no confirmation, no
     * silent re-polish) — the polished field is cleared with it.
     */
    private fun commitEdits() {
        val id = currentId ?: return
        val current = dictations.firstOrNull { it.id == id } ?: return
        val raw = rawField.text.toString()
        val polished = polishedField.text.toString()
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
     * Where the transcript of the running dictation belongs. Decided when the
     * recording **starts**, i.e. before the IME inserts a single character:
     * after the insertion the field is changed either way, so "user edited it"
     * and "the IME typed into it" would be indistinguishable.
     */
    internal enum class DictationTarget { NEW, RAW_EDIT, POLISHED_EDIT }

    private var pendingTarget = DictationTarget.NEW

    /**
     * #169 (epic #166 D4/D10): a dictation is a **new** FIFO entry unless the
     * user is already editing the shown one.
     *  - focus in Polished ⇒ the transcript is a manual edit of the polished
     *    text (D10),
     *  - a manually changed Raw field ⇒ the transcript continues that edit,
     *  - otherwise ⇒ a new dictation (the compose path).
     */
    internal fun onRecordingStarted() {
        pendingTarget = when {
            polishedField.hasFocus() -> DictationTarget.POLISHED_EDIT
            rawField.text.toString() != shownRaw -> DictationTarget.RAW_EDIT
            else -> DictationTarget.NEW
        }
    }

    /**
     * D4: a dictation entry is created when the **first transcript arrives**,
     * never by opening the screen. The IME has already inserted the text into
     * the focused field (D10) when this runs.
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
                    reload(id)
                }
            }
            DictationTarget.RAW_EDIT -> {
                val id = currentId
                if (id == null) {
                    reload(store.append(rawField.text.toString()).id)
                } else {
                    store.updateRaw(id, rawField.text.toString())
                    polishedField.setText("")
                    reload(id)
                }
            }
            DictationTarget.NEW -> reload(store.append(text).id)
        }
        deliver(text, notify = false)
        // D8: Auto LLM polishes each NEW/edited dictation — never the polished
        // field itself (that is the user's own edit, D10).
        if (settings.workbenchAutoLlm && target != DictationTarget.POLISHED_EDIT && !polishing) {
            currentId?.let { id -> polish(id, rawField.text.toString(), notify = false) }
        }
    }
}
