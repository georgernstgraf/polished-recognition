package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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
import com.georgernstgraf.polishedrecognition.recovery.RecoveryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *    a stored dictation ⇒ RAW_EDIT. A stored page never creates an entry.
 *  - the **compose slot** is transient: shown on entry, never persisted while
 *    empty, outside the ring, materialised on pause / ＋ / a navigation with
 *    content (decision 2).
 *  - the ring is **last-touched (MRU)** order with wrap-around (decisions 5/7).
 *
 * #178 redesigns the surface:
 *  - the header carries **icons only** (◀ position ⚙ ＋ ▶), no title;
 *  - **Polished is read-only** ([TextView], never a dictation target): the
 *    pipeline produces it, the user only promotes or empties it;
 *  - the actions sit in **three zones** — A above Raw, B below Raw, C below
 *    Polished — with two Polish sites sharing one per-visit language;
 *  - every enable state comes from [WorkbenchActions], never from a rule
 *    written here; promote/clear go through [DictationStore] only.
 *
 * Delivery (D11/#170): the completed transcript goes to the clipboard on a
 * send; a **changed** raw field that loses focus delivers itself; the per-field
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

    /** #178: Polished is read-only — the pipeline owns it, the user promotes it. */
    private lateinit var polishedField: TextView
    private lateinit var positionLabel: TextView
    private lateinit var emptyHint: TextView

    /** #185: "N pending transcriptions — tap to review", GONE when there are none. */
    private lateinit var recoveryBanner: TextView

    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var newButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var copyRawButton: ImageButton
    private lateinit var copyPolishedButton: ImageButton
    private lateinit var promoteBButton: ImageButton
    private lateinit var promoteCButton: ImageButton
    private lateinit var clearPolishedButton: ImageButton
    private lateinit var languageA: AutoCompleteTextView
    private lateinit var languageB: AutoCompleteTextView
    private lateinit var polishA: Button
    private lateinit var polishB: Button

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

    /** Seam for the Robolectric tests; production scans the journal off-main. */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

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

    /**
     * The raw text as it was when the dictation was shown, for the
     * "changed field lost focus" trigger (D11b).
     */
    private var shownRaw = ""

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
        recoveryBanner = findViewById(R.id.workbench_recovery)
        prevButton = findViewById(R.id.workbench_prev)
        nextButton = findViewById(R.id.workbench_next)
        newButton = findViewById(R.id.workbench_new)
        settingsButton = findViewById(R.id.workbench_settings)
        copyRawButton = findViewById(R.id.workbench_copy_raw)
        copyPolishedButton = findViewById(R.id.workbench_copy_polished)
        promoteBButton = findViewById(R.id.workbench_promote_b)
        promoteCButton = findViewById(R.id.workbench_promote_c)
        clearPolishedButton = findViewById(R.id.workbench_clear_polished)
        languageA = findViewById(R.id.workbench_language_a)
        languageB = findViewById(R.id.workbench_language_b)
        polishA = findViewById(R.id.workbench_polish_a)
        polishB = findViewById(R.id.workbench_polish_b)

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
        // #185: entering the Workbench is the moment the leftovers are picked
        // up — the feedback Toast comes before any request goes out.
        app.recoveryCoordinator.onFinished = { outcome -> onRecoveryFinished(outcome) }
        recoverLeftovers()
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
        app.recoveryCoordinator.onFinished = null
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

    // --- #185: leftover recovery ---------------------------------------------

    /**
     * Scans the durable journal on entry and retries what can heal by itself
     * (network failures and interruptions — never a dead token or a provider
     * protocol problem). The Toast goes out before the first request, so the
     * user never wonders whether anything is happening; the run itself yields
     * to a live dictation at every fragment boundary.
     *
     * Deliberately no [reload] afterwards: it would wipe the compose slot the
     * user may be typing into — [syncPositions] re-reads the ring and keeps
     * every field untouched.
     */
    private fun recoverLeftovers() {
        val coordinator = app.recoveryCoordinator
        val journal = app.fragmentJournal
        scope.launch {
            val sessions = withContext(ioDispatcher) { journal.recoverableSessions() }
            val visible = sessions.filter { it.sessionId != controller.activeSessionId() }
            bindRecoveryBanner(visible.size)
            if (coordinator.isRunning()) return@launch
            val auto = visible.filter { coordinator.shouldAutoRecover(it) }
            if (auto.isEmpty()) return@launch
            if (coordinator.startAuto(auto)) {
                Toast.makeText(
                    this@WorkbenchActivity,
                    resources.getQuantityString(R.plurals.recovery_started, auto.size, auto.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onRecoveryFinished(outcome: RecoveryCoordinator.Outcome) {
        if (isFinishing || isDestroyed) return
        val failure = outcome.failures.firstOrNull()
        val message = when {
            failure != null -> getString(R.string.recovery_failed, failure.message)
            outcome.recovered > 0 ->
                resources.getQuantityString(R.plurals.recovery_recovered, outcome.recovered, outcome.recovered)
            else -> resources.getQuantityString(
                R.plurals.recovery_still_pending,
                outcome.stillPending,
                outcome.stillPending
            )
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        syncPositions()
        recoverLeftovers()
    }

    private fun bindRecoveryBanner(pending: Int) {
        recoveryBanner.visibility = if (pending > 0) View.VISIBLE else View.GONE
        if (pending > 0) {
            recoveryBanner.text = resources.getQuantityString(R.plurals.recovery_banner, pending, pending)
        }
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
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        recoveryBanner.setOnClickListener {
            startActivity(Intent(this, RecoveryActivity::class.java))
        }
        prevButton.setOnClickListener { navigate(older = true) }
        nextButton.setOnClickListener { navigate(older = false) }
        copyRawButton.setOnClickListener { deliver(rawField.text.toString(), notify = true) }
        copyPolishedButton.setOnClickListener {
            deliver(polishedField.text.toString(), notify = true)
        }
        // #178: two Polish sites, one language and one pipeline.
        polishA.setOnClickListener { polishCurrent() }
        polishB.setOnClickListener { polishCurrent() }
        promoteBButton.setOnClickListener { promotePolished() }
        promoteCButton.setOnClickListener { promotePolished() }
        clearPolishedButton.setOnClickListener { emptyPolished() }
        // D11b: a CHANGED raw field losing focus delivers itself; an unchanged
        // field left delivers nothing (the content is already the visible
        // dictation, not news for the clipboard).
        rawField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onRawFieldLeft()
        }
        // The enable policy follows the text while it is typed — copy/polish
        // light up on the first character instead of on the next focus change.
        rawField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!rendering) updateChrome()
            }
        })
    }

    private fun deliver(text: String, notify: Boolean) {
        if (!ClipboardDelivery.deliver(this, text, getString(R.string.workbench_title))) return
        if (notify) {
            Toast.makeText(this, getString(R.string.workbench_delivered), Toast.LENGTH_SHORT).show()
        }
    }

    /** D11b: commit first (so the store matches the screen), then deliver the edit. */
    private fun onRawFieldLeft() {
        if (rendering) return
        val text = rawField.text.toString()
        val changed = text != shownRaw
        commitEdits()
        if (changed && text.isNotEmpty()) deliver(text, notify = false)
        syncPositions()
    }

    // ------------------------------------------------------------------ state

    /** Writes a field without counting as a user edit (`rendering`). */
    private fun showRaw(text: String) {
        rendering = true
        rawField.setText(text)
        rendering = false
        shownRaw = text
    }

    private fun showPolished(text: String) {
        rendering = true
        polishedField.text = text
        rendering = false
    }

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
        val compose = index == FifoNavigation.COMPOSE_INDEX
        showRaw(if (compose) composeRaw else dictation?.raw.orEmpty())
        showPolished(if (compose) composePolished else dictation?.polished.orEmpty())
        updateChrome()
    }

    /**
     * Label, buttons and hints — never the field text. **Every** enable state
     * is read from [WorkbenchActions.enabled], so a control can never offer an
     * action the shown page cannot carry out (#178).
     */
    private fun updateChrome() {
        val actions = WorkbenchActions.enabled(
            hasRaw = rawField.text.toString().isNotBlank(),
            hasPolished = polishedField.text.toString().isNotBlank(),
            polishing = polishing,
            ringSize = dictations.size,
            onCompose = index == FifoNavigation.COMPOSE_INDEX
        )
        emptyHint.visibility =
            if (index == FifoNavigation.COMPOSE_INDEX && dictations.isEmpty()) View.VISIBLE
            else View.GONE
        positionLabel.text = FifoNavigation.positionLabel(index, dictations.size)
        prevButton.isEnabled = actions.prev
        nextButton.isEnabled = actions.next
        newButton.isEnabled = actions.new
        copyRawButton.isEnabled = actions.copyRaw
        copyPolishedButton.isEnabled = actions.copyPolished
        promoteBButton.isEnabled = actions.promote
        promoteCButton.isEnabled = actions.promote
        clearPolishedButton.isEnabled = actions.clearPolished
        polishA.isEnabled = actions.polish
        polishB.isEnabled = actions.polish
        languageA.isEnabled = actions.polish
        languageB.isEnabled = actions.polish
        val polishLabel = getString(
            if (polishing) R.string.workbench_polishing else R.string.workbench_polish
        )
        polishA.text = polishLabel
        polishB.text = polishLabel
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
        // content (never produced by a read-only slot, kept for symmetry with
        // the stored shape) becomes Raw.
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
        for (dropdown in listOf(languageA, languageB)) {
            dropdown.setAdapter(
                android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    languages
                )
            )
            // `filter = false`: seeding must not open the dropdown or filter it.
            dropdown.setText(current, false)
            dropdown.threshold = Int.MAX_VALUE
            dropdown.setOnClickListener { dropdown.showDropDown() }
            dropdown.setOnItemClickListener { _, _, position, _ -> onLanguageSelected(position) }
        }
    }

    /**
     * D9: the per-dictation language, never written back to the settings.
     * #178: both Polish sites share it — the pick is mirrored into the other
     * dropdown with `setText(.., filter = false)`, which does not fire an item
     * click, so the two sites cannot recurse into each other.
     */
    internal fun onLanguageSelected(position: Int) {
        val selected = LanguageOptions
            .buildLanguageList(settings.customLanguages)
            .getOrNull(position) ?: return
        polishLanguage =
            if (selected == LanguageOptions.NONE_TARGET_LANGUAGE) null else selected
        languageA.setText(selected, false)
        languageB.setText(selected, false)
    }

    /** D9: the visible raw text through the LLM with the chosen language. */
    private fun polishCurrent() {
        if (polishing) return
        // A compose slot with content becomes a real dictation before it is
        // polished — polishing must never work on an entry that cannot be saved.
        materialiseCompose()
        val id = currentId ?: return
        val raw = rawField.text.toString().ifBlank {
            dictations.firstOrNull { it.id == id }?.raw.orEmpty()
        }
        if (raw.isBlank()) return
        polish(id, raw)
    }

    private fun polish(id: String, raw: String) {
        polishing = true
        updateChrome()
        polishJob = scope.launch {
            val result = runCatching { polishRunner(raw, polishLanguage) }
                .getOrElse { Result.failure(it) }
            polishing = false
            result.onSuccess { polished ->
                // D9: the result overwrites the single polished slot and is
                // delivered to the clipboard; Raw is untouched.
                store.updatePolished(id, polished)
                reload(id)
                deliver(polished, notify = true)
            }.onFailure {
                // Keep the previous polished text — a failed call never
                // destroys the dictation.
                Toast.makeText(
                    this@WorkbenchActivity,
                    getString(R.string.workbench_polish_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
            updateChrome()
        }
    }

    // ----------------------------------------------------- promote / empty

    /**
     * #178: "Make Polished the new RAW" — the store does the move
     * ([DictationStore.replaceRawWithPolished]), so the MRU bump and the
     * no-op-on-equal rule live in one place. Polished keeps its text: it is
     * the polished version of the *new* raw text.
     */
    private fun promotePolished() {
        if (rendering) return
        commitEdits()
        if (index == FifoNavigation.COMPOSE_INDEX) {
            if (composePolished.isBlank() || composePolished == composeRaw) return
            composeRaw = composePolished
            showRaw(composeRaw)
            updateChrome()
            return
        }
        val id = currentId ?: return
        val promoted = store.replaceRawWithPolished(id) ?: return
        showRaw(promoted.raw)
        syncPositions(id)
    }

    /** #178: "Empty the Polished field" — `null`, never `""`, through the store. */
    private fun emptyPolished() {
        if (rendering) return
        commitEdits()
        if (index == FifoNavigation.COMPOSE_INDEX) {
            if (composePolished.isEmpty()) return
            composePolished = ""
            showPolished("")
            updateChrome()
            return
        }
        val id = currentId ?: return
        store.clearPolished(id)
        showPolished("")
        syncPositions(id)
    }

    /**
     * Writes the raw edit into the store (or into the transient compose slot).
     * A changed **raw** field discards the polished transcript (inherited
     * desktop rule: no confirmation, no silent re-polish) — the store does
     * that in [DictationStore.updateRaw], the field is then re-read from it.
     */
    private fun commitEdits() {
        val raw = rawField.text.toString()
        if (index == FifoNavigation.COMPOSE_INDEX) {
            composeRaw = raw
            composePolished = polishedField.text.toString()
            shownRaw = raw
            return
        }
        val id = currentId ?: return
        val current = dictations.firstOrNull { it.id == id } ?: return
        if (raw != current.raw) {
            val updated = store.updateRaw(id, raw)
            showPolished(updated?.polished.orEmpty())
        }
        shownRaw = raw
    }

    /**
     * Where the transcript of the running dictation belongs (#173 decision 1).
     * Decided when the recording **starts**, i.e. before the IME inserts a
     * single character — after the insertion the field is changed either way,
     * so "user edited it" and "the IME typed into it" are indistinguishable.
     *
     * #178: Polished is read-only, so only two targets remain.
     */
    internal enum class DictationTarget { NEW, RAW_EDIT }

    private var pendingTarget = DictationTarget.NEW

    /**
     * #173 decision 1 — the visible page decides, never a text comparison:
     *  - compose slot ⇒ NEW (the entry page; the IME's text becomes Raw),
     *  - stored dictation ⇒ RAW_EDIT (at the caret, whether or not the user
     *    typed first). Polished cannot take focus, so there is no third target.
     */
    internal fun onRecordingStarted() {
        pendingTarget = if (index == FifoNavigation.COMPOSE_INDEX) {
            DictationTarget.NEW
        } else {
            DictationTarget.RAW_EDIT
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
            DictationTarget.RAW_EDIT -> {
                val id = currentId
                if (id == null) {
                    reload(store.append(rawField.text.toString().ifBlank { text }).id)
                } else {
                    // The IME wrote into Raw, so the store discards the
                    // polished slot; the field follows the stored result.
                    val updated = store.updateRaw(id, rawField.text.toString())
                    showRaw(updated?.raw ?: rawField.text.toString())
                    showPolished(updated?.polished.orEmpty())
                    // #173 decision 5: the edit bumps it to the top of the ring.
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
    }
}
