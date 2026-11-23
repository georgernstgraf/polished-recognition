package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
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
import com.georgernstgraf.polishedrecognition.network.ReconnectMonitor
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.recovery.RecoveryCoordinator
import com.georgernstgraf.polishedrecognition.recovery.RecoveryReason
import com.georgernstgraf.polishedrecognition.recovery.RecoveryRestore
import com.georgernstgraf.polishedrecognition.recovery.RecoveryShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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
 *
 * #188 merges the leftovers into the ring: a page is a [WorkbenchPage.Stored]
 * dictation **or** a [WorkbenchPage.Leftover] (an unfinished journal session,
 * ordered by its start — [WorkbenchPages]). A leftover page has no text at all:
 * Raw is read-only and non-focusable (the IME must never commit into it),
 * [DictationTarget] is NEW there, the stamp line carries its reason, and the
 * four recovery actions sit on the page itself. The #185 recovery screen is retired
 * with that; the Workbench is the only screen and the only owner of
 * [RecoveryCoordinator.onFinished].
 *
 * #191 puts the actions DIRECTLY under the when-line (reason and way out
 * together at the top, found by paging 1/3 · 2/3 · 3/3 on a device otherwise)
 * and makes the banner the one affordance that leads there: accent bar under
 * the header, count + newest stamp + "tap to open", GONE at zero.
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

    /** #188: the page's "when" line — empty on the compose slot, so GONE there. */
    private lateinit var stampLabel: TextView

    /** #185/#188/#191: "<n> incomplete · <stamp> — tap to open", GONE at zero. */
    private lateinit var recoveryBanner: TextView

    /** #188: the four actions of a leftover page — GONE on every other page. */
    private lateinit var leftoverActions: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var restoreButton: Button
    private lateinit var shareButton: Button
    private lateinit var dismissButton: Button

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

    /**
     * #188: the ring — stored dictations AND the journal's leftovers as pages
     * of ONE list, ascending by their last change ([WorkbenchPages.merge]).
     * The last element is "newest"; the compose slot stays outside it.
     */
    private var pages: List<WorkbenchPage> = emptyList()

    /** The journal scan behind the leftover pages and the banner (#185). */
    private var leftovers: List<WorkbenchPage.Leftover> = emptyList()

    /**
     * [FifoNavigation.COMPOSE_INDEX] on the entry page, otherwise the position
     * of the shown page in [pages].
     */
    private var index = FifoNavigation.COMPOSE_INDEX
    private var currentId: String? = null

    /** The session id of the shown page when it is a leftover (#188). */
    private var currentSession: String? = null

    /**
     * #188: the session this screen last asked for a MANUAL re-run, so a
     * finished run can tell "it became text" from "it is still pending".
     */
    private var manualSession: String? = null

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

    /**
     * #188: the manual re-run of one journal session, injectable for the same
     * reason (the retry path must be testable without a request).
     */
    internal var retryRunner: (String) -> Boolean = { sessionId ->
        app.recoveryCoordinator.startManual(sessionId)
    }

    /**
     * #186: the foreground-only reconnect trigger. `null` ⇒ the app's real
     * monitor (started in [onResume], stopped in [onPause]); the tests inject
     * one so no Android network callback is involved.
     */
    internal var reconnectMonitor: ReconnectMonitor? = null

    private val monitor: ReconnectMonitor get() = reconnectMonitor ?: app.reconnectMonitor

    /**
     * #186: how often this screen asked for a recovery — the observable half
     * of the reconnect wiring (a validated edge must land exactly one ask).
     */
    internal var recoverRequests = 0
        private set

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
        stampLabel = findViewById(R.id.workbench_stamp)
        recoveryBanner = findViewById(R.id.workbench_recovery)
        leftoverActions = findViewById(R.id.workbench_leftover_actions)
        retryButton = findViewById(R.id.workbench_retry_stt)
        restoreButton = findViewById(R.id.workbench_restore_text)
        shareButton = findViewById(R.id.workbench_share_audio)
        dismissButton = findViewById(R.id.workbench_dismiss_leftover)
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
        // #186: while this screen is visible, a validated network is the
        // moment a saved dictation becomes transcribable. Foreground only —
        // onPause stops listening, and no background upload is ever started.
        monitor.start { onReconnected() }
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
        // #186: leaving the screen stops the reconnect trigger — the user may
        // keep using the phone while offline; nothing uploads behind them.
        monitor.stop()
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

    // --- #185/#188: leftover recovery -----------------------------------------

    /**
     * The journal scan behind the leftover pages and the banner — off the main
     * thread, one call per [reload]/[recoverLeftovers], never per navigation.
     *
     * `hasAudio` is decided here because it needs the file system (a fragment
     * upload that is already gone cannot be shared or re-sent) and must never
     * block the UI.
     */
    private suspend fun scanLeftovers(): List<WorkbenchPage.Leftover> = withContext(ioDispatcher) {
        val journal = app.fragmentJournal
        val active = controller.activeSessionId()
        journal.recoverableSessions()
            .filter { it.sessionId != active }
            .map { summary ->
                val dir = journal.sessionDir(summary.sessionId)
                val hasAudio = journal.readRecords(summary.sessionId).any { record ->
                    record.uploadFile?.let { name -> File(dir, name).isFile } == true
                }
                WorkbenchPage.Leftover(summary, hasAudio)
            }
    }

    /**
     * Re-reads the journal and folds the leftovers into the ring — the shown
     * page stays where the user put it ([syncPositions] never rewrites a
     * field), so a scan can never eat a half-typed dictation.
     */
    private fun rescanLeftovers() {
        scope.launch {
            leftovers = scanLeftovers()
            syncPositions()
            bindRecoveryBanner()
        }
    }

    /**
     * #185: entering the Workbench (and every finished run) picks the
     * leftovers up and retries what can heal by itself (network failures and
     * interruptions — never a dead token or a provider protocol problem). The
     * Toast goes out before the first request, so the user never wonders
     * whether anything is happening; the run itself yields to a live dictation
     * at every fragment boundary.
     */
    private fun recoverLeftovers() {
        recoverRequests++
        val coordinator = app.recoveryCoordinator
        scope.launch {
            val scanned = scanLeftovers()
            leftovers = scanned
            syncPositions()
            bindRecoveryBanner()
            if (coordinator.isRunning()) return@launch
            val auto = scanned.map { it.summary }.filter { coordinator.shouldAutoRecover(it) }
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

    /**
     * #186: one validated network edge = one recovery ask. [ReconnectMonitor]
     * already collapsed the repeated callbacks into a single edge; this only
     * hops onto the UI thread (the callback arrives on a binder thread) and
     * runs the same entry path — the leftovers that are still auto-retryable
     * are re-run, the rest stay with their reason.
     */
    private fun onReconnected() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            recoverLeftovers()
        }
    }

    /**
     * #188: one [RecoveryCoordinator.Outcome], one Toast, one reload — the
     * Workbench is the only owner of the coordinator's single `onFinished`
     * slot now.
     *
     * A recovered session leaves the ring, so the page that was shown is gone;
     * [refreshPages] then selects the **newest stored page**, which is exactly
     * where the recovered text landed ("it moved to the top"). Any content the
     * user typed into the compose slot meanwhile is materialised first, so the
     * jump never eats a draft.
     */
    private fun onRecoveryFinished(outcome: RecoveryCoordinator.Outcome) {
        if (isFinishing || isDestroyed) return
        val retried = manualSession
        manualSession = null
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
        commitEdits()
        materialiseCompose()
        refreshPages { merged ->
            val stillPending = merged.any {
                it is WorkbenchPage.Leftover && it.summary.sessionId == retried
            }
            if (retried != null && !stillPending) {
                val newest = merged.lastOrNull { it is WorkbenchPage.Stored }
                if (newest != null) Selection.Stored(WorkbenchPages.idOf(newest)) else Selection.Compose
            } else {
                currentSelection()
            }
        }
    }

    /**
     * #188: count plus the newest leftover's stamp. A tap jumps to that page
     * ([WorkbenchPages.newestLeftoverIndex]) instead of opening a list — the
     * leftover IS a page of the ring now.
     *
     * #191: the banner is the ONE affordance that leads to a leftover, so it
     * says what it does ("— tap to open") and is GONE at zero (no count = no
     * element, hence no tap target).
     */
    private fun bindRecoveryBanner() {
        val count = leftovers.size
        recoveryBanner.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count == 0) return
        val newest = WorkbenchPages.newestLeftoverIndex(pages)
            ?.let { pages[it] as? WorkbenchPage.Leftover }
            ?: leftovers.lastOrNull()
        val stamp = WorkbenchStamp.label(
            newest?.summary?.startedAtMs,
            System.currentTimeMillis(),
            locale()
        )
        val countText = resources.getQuantityString(R.plurals.recovery_banner, count, count)
        val detail = if (stamp == null) countText else "$countText · $stamp"
        recoveryBanner.text =
            getString(R.string.recovery_banner_tap_to_open, detail)
    }

    /** The resource locale — the stamp's day/month names follow it. */
    private fun locale(): Locale = resources.configuration.locales[0]

    /**
     * Re-reads the journal and the store, then follows [select] — used after an
     * action changed the journal (restore, dismiss, a finished run), where the
     * leftover list itself is stale.
     */
    private fun refreshPages(select: (List<WorkbenchPage>) -> Selection) {
        scope.launch {
            leftovers = scanLeftovers()
            val merged = WorkbenchPages.merge(store.loadAll(), leftovers)
            applyPages(merged, select(merged), rewriteFields = true)
            bindRecoveryBanner()
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
        recoveryBanner.setOnClickListener { jumpToNewestLeftover() }
        retryButton.setOnClickListener { retryLeftover() }
        restoreButton.setOnClickListener { restoreLeftover() }
        shareButton.setOnClickListener { shareLeftover() }
        dismissButton.setOnClickListener { confirmDismissLeftover() }
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

    /**
     * #188: which page the ring should show — the compose slot, a stored
     * dictation, or a leftover session. Identity survives a re-merge, which is
     * what keeps the shown page under the user while positions shift.
     */
    private sealed interface Selection {
        object Compose : Selection
        data class Stored(val id: String) : Selection
        data class Leftover(val sessionId: String) : Selection
    }

    private fun currentSelection(): Selection {
        val page = pages.getOrNull(index) ?: return Selection.Compose
        return when (page) {
            is WorkbenchPage.Stored -> Selection.Stored(page.dictation.id)
            is WorkbenchPage.Leftover -> Selection.Leftover(page.summary.sessionId)
        }
    }

    private fun indexOf(selection: Selection): Int = when (selection) {
        Selection.Compose -> -1
        is Selection.Stored ->
            pages.indexOfFirst { it is WorkbenchPage.Stored && it.dictation.id == selection.id }
        is Selection.Leftover ->
            pages.indexOfFirst {
                it is WorkbenchPage.Leftover && it.summary.sessionId == selection.sessionId
            }
    }

    private fun reload(
        selection: Selection = currentSelection(),
        rescan: Boolean = true
    ) {
        applyPages(
            WorkbenchPages.merge(store.loadAll(), leftovers),
            selection,
            rewriteFields = true
        )
        if (rescan) rescanLeftovers()
    }

    /**
     * Re-reads the store and the journal and follows [selection] to its new ring
     * position **without** rewriting the fields — the field the user is typing
     * in keeps its caret (#173 decision 5: a touched dictation moves to the top).
     */
    private fun syncPositions(selection: Selection = currentSelection()) {
        applyPages(
            WorkbenchPages.merge(store.loadAll(), leftovers),
            selection,
            rewriteFields = false
        )
    }

    /**
     * Merges [merged] and follows [selection]; a selection that no longer
     * exists (an evicted dictation, a recovered or dismissed session) falls
     * back to a fresh compose slot, because the page the user was reading is
     * gone with its content.
     */
    private fun applyPages(
        merged: List<WorkbenchPage>,
        selection: Selection,
        rewriteFields: Boolean
    ) {
        pages = merged
        val found = indexOf(selection)
        if (found >= 0) {
            index = found
        } else {
            index = FifoNavigation.COMPOSE_INDEX
            if (selection != Selection.Compose) {
                composeRaw = ""
                composePolished = ""
            }
        }
        render(rewriteFields)
    }

    /**
     * The one writer of the page's content (#188).
     *
     * [rewriteFields] is `false` for a position sync: the page identity and the
     * chrome follow the data, the fields keep whatever the user has in them.
     *
     * A leftover page has **no text**: Raw is read-only *and* non-focusable —
     * a field that cannot take focus can never receive an IME commit, which is
     * the property [DictationTarget.NEW] relies on.
     */
    private fun render(rewriteFields: Boolean = true) {
        val page = pages.getOrNull(index)
        val leftover = page is WorkbenchPage.Leftover
        currentId = (page as? WorkbenchPage.Stored)?.dictation?.id
        currentSession = (page as? WorkbenchPage.Leftover)?.summary?.sessionId

        rendering = true
        setRawEditable(!leftover)
        if (rewriteFields) {
            val raw = when (page) {
                is WorkbenchPage.Stored -> page.dictation.raw
                is WorkbenchPage.Leftover -> ""
                null -> composeRaw
            }
            val polished = when (page) {
                is WorkbenchPage.Stored -> page.dictation.polished.orEmpty()
                is WorkbenchPage.Leftover -> ""
                null -> composePolished
            }
            rawField.setText(raw)
            polishedField.text = polished
            shownRaw = raw
        }
        rendering = false
        // A PAGE CHANGE re-arms the IME (D15): the field the user was in may
        // have been a leftover page, and its successor must be able to take a
        // dictation. A mere position sync never grabs focus back — the user may
        // just have left the field on purpose (D11b).
        if (rewriteFields && rawField.isEnabled && visible && !rawField.hasFocus()) {
            rawField.requestFocus()
        }
        renderStamp(page)
        updateChrome()
    }

    /** A disabled EditText can neither take focus nor receive a commit. */
    private fun setRawEditable(editable: Boolean) {
        rawField.isEnabled = editable
        rawField.isFocusable = editable
        rawField.isFocusableInTouchMode = editable
        rawField.isCursorVisible = editable
    }

    /**
     * #188: the when-line under the header. A stored page shows its last
     * change; a leftover continues the same line with its size and the reason
     * it never became text (seconds · fragments · reason, from
     * [RecoveryReason] — this screen invents no rule). The compose slot has no
     * instant, so the line is GONE rather than an empty row.
     */
    private fun renderStamp(page: WorkbenchPage?) {
        val at = page?.let { WorkbenchPages.changedAt(it) }
        val whenText = WorkbenchStamp.label(at, System.currentTimeMillis(), locale())
        val detail = (page as? WorkbenchPage.Leftover)?.let { leftoverDetail(it.summary) }
        val text = when {
            whenText == null -> detail.orEmpty()
            detail == null -> whenText
            else -> "$whenText · $detail"
        }
        stampLabel.text = text
        stampLabel.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun leftoverDetail(summary: FragmentJournal.SessionSummary): String =
        resources.getQuantityString(
            R.plurals.workbench_leftover_detail,
            summary.fragmentCount,
            (summary.durationMs / 1000L).toInt(),
            summary.fragmentCount,
            reasonLabel(summary)
        )

    /** #185: the one reason a leftover shows — never a rule written here. */
    private fun reasonLabel(summary: FragmentJournal.SessionSummary): String {
        val kind = RecoveryReason.of(summary.completed, summary.failureClass)
        return getString(
            when (kind) {
                RecoveryReason.Kind.COMPLETED -> R.string.recovery_reason_completed
                RecoveryReason.Kind.AUTH -> R.string.recovery_reason_auth
                RecoveryReason.Kind.BUDGET -> R.string.recovery_reason_budget
                RecoveryReason.Kind.PROTOCOL -> R.string.recovery_reason_protocol
                RecoveryReason.Kind.NETWORK -> R.string.recovery_reason_network
                RecoveryReason.Kind.TERMINAL -> R.string.recovery_reason_terminal
                RecoveryReason.Kind.INTERRUPTED -> R.string.recovery_reason_interrupted
                RecoveryReason.Kind.OFFLINE -> R.string.recovery_reason_offline
            }
        )
    }

    /**
     * Label, buttons and hints — never the field text. **Every** enable state
     * is read from [WorkbenchActions.enabled], so a control can never offer an
     * action the shown page cannot carry out (#178).
     */
    private fun updateChrome() {
        val page = pages.getOrNull(index)
        val leftover = page as? WorkbenchPage.Leftover
        val actions = WorkbenchActions.enabled(
            hasRaw = rawField.text.toString().isNotBlank(),
            hasPolished = polishedField.text.toString().isNotBlank(),
            polishing = polishing,
            ringSize = pages.size,
            onCompose = index == FifoNavigation.COMPOSE_INDEX
        )
        // The hint speaks for the page: a leftover explains why it is empty and
        // what to do; the compose slot only explains the very first dictation.
        emptyHint.visibility = when {
            leftover != null -> View.VISIBLE
            index == FifoNavigation.COMPOSE_INDEX && pages.isEmpty() -> View.VISIBLE
            else -> View.GONE
        }
        emptyHint.text = getString(
            if (leftover != null) R.string.workbench_leftover_hint else R.string.workbench_empty
        )
        positionLabel.text = FifoNavigation.positionLabel(index, pages.size)
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
        bindLeftoverActions(leftover)
    }

    /**
     * #188: the four actions on a leftover page — the enablement rule is
     * #185's [RecoveryActions] unchanged (retry needs a provider, audio and an
     * idle device; restore exists only for a completed session; dismiss is only
     * blocked while a run is under way).
     */
    private fun bindLeftoverActions(page: WorkbenchPage.Leftover?) {
        leftoverActions.visibility = if (page != null) View.VISIBLE else View.GONE
        if (page == null) return
        val actions = RecoveryActions.enabled(
            completed = page.summary.completed,
            hasAudio = page.hasAudio,
            busy = app.recoveryCoordinator.isRunning(),
            providerConfigured = app.settingsStore.sttProvider != null
        )
        retryButton.visibility = if (actions.restore) View.GONE else View.VISIBLE
        retryButton.isEnabled = actions.reRun
        restoreButton.visibility = if (actions.restore) View.VISIBLE else View.GONE
        restoreButton.isEnabled = actions.restore
        shareButton.isEnabled = actions.share
        dismissButton.isEnabled = actions.dismiss
    }

    // ------------------------------------------------------------- navigation

    /** #173 decision 7: the ring wraps; the compose slot is not part of it. */
    private fun navigate(older: Boolean) {
        commitEdits()
        materialiseCompose()
        syncPositions()
        index = if (older) {
            FifoNavigation.older(index, pages.size)
        } else {
            FifoNavigation.newer(index, pages.size)
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
            currentSession = null
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
        pages = WorkbenchPages.merge(store.loadAll(), leftovers)
        index = pages.indexOfFirst { it is WorkbenchPage.Stored && it.dictation.id == created.id }
            .takeIf { it >= 0 } ?: FifoNavigation.COMPOSE_INDEX
        currentId = created.id
        currentSession = null
        return created.id
    }

    // ------------------------------------------------- leftover page actions

    /**
     * #188: the banner's tap. It jumps to the newest leftover page
     * ([WorkbenchPages.newestLeftoverIndex]) — there is no list behind it any
     * more, the leftover is one of the ring's pages.
     */
    private fun jumpToNewestLeftover() {
        commitEdits()
        materialiseCompose()
        val target = WorkbenchPages.newestLeftoverIndex(pages) ?: return
        index = target
        render()
    }

    /**
     * Retry STT: the explicit re-run of THIS session ([RecoveryCoordinator.startManual]).
     * A refused start (another run won) is said out loud instead of silently
     * doing nothing.
     */
    private fun retryLeftover() {
        val page = pages.getOrNull(index) as? WorkbenchPage.Leftover ?: return
        val sessionId = page.summary.sessionId
        if (!retryRunner(sessionId)) {
            Toast.makeText(this, getString(R.string.recovery_busy), Toast.LENGTH_LONG).show()
            return
        }
        manualSession = sessionId
        updateChrome()
    }

    /** Restore text: the stored transcript of a completed session, no request. */
    private fun restoreLeftover() {
        val page = pages.getOrNull(index) as? WorkbenchPage.Leftover ?: return
        val sessionId = page.summary.sessionId
        scope.launch {
            val outcome = withContext(ioDispatcher) {
                RecoveryRestore.restore(
                    sessionId,
                    app.fragmentJournal.readRecords(sessionId),
                    restoreTarget
                )
            }
            val message = when (outcome) {
                is RecoveryRestore.Outcome.Restored -> R.string.recovery_restored
                RecoveryRestore.Outcome.NoText -> R.string.recovery_restore_empty
                RecoveryRestore.Outcome.NotStored -> R.string.recovery_store_failed
            }
            Toast.makeText(this@WorkbenchActivity, getString(message), Toast.LENGTH_LONG).show()
            refreshPages { merged ->
                val restored = (outcome as? RecoveryRestore.Outcome.Restored)?.dictationId
                if (restored != null && merged.any { WorkbenchPages.idOf(it) == restored }) {
                    Selection.Stored(restored)
                } else {
                    currentSelection()
                }
            }
        }
    }

    /**
     * The store write and the journal delete [RecoveryRestore] orders: the
     * store first, the journal only when it really holds the new dictation.
     */
    private val restoreTarget = object : RecoveryRestore.Target {
        override fun store(text: String): String? {
            val created = store.append(text)
            return created.id.takeIf { id -> store.loadAll().any { it.id == id } }
        }

        override fun drop(sessionId: String) {
            app.fragmentJournal.deleteSession(sessionId)
        }
    }

    /** Share audio: the reconstructed WAV (or the OGG pieces) through a chooser. */
    private fun shareLeftover() {
        val page = pages.getOrNull(index) as? WorkbenchPage.Leftover ?: return
        val sessionId = page.summary.sessionId
        val authority = "$packageName.recovery"
        scope.launch {
            val files = withContext(ioDispatcher) {
                RecoveryShare.files(
                    sessionId,
                    app.fragmentJournal.sessionDir(sessionId),
                    app.fragmentJournal.readRecords(sessionId),
                    cacheDir
                )
            }
            val uris = files.mapNotNull { file ->
                RecoveryShare.uriOf(file) { target ->
                    FileProvider.getUriForFile(this@WorkbenchActivity, authority, target)
                }
            }
            val intent = RecoveryShare.intent(uris)
            if (intent == null) {
                Toast.makeText(
                    this@WorkbenchActivity,
                    getString(R.string.recovery_share_empty),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            startActivity(Intent.createChooser(intent, getString(R.string.recovery_chooser)))
        }
    }

    /** Dismiss: it asks first — a leftover is never dropped without a word. */
    private fun confirmDismissLeftover() {
        val page = pages.getOrNull(index) as? WorkbenchPage.Leftover ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_dismiss_title)
            .setMessage(R.string.recovery_dismiss_message)
            .setPositiveButton(R.string.recovery_dismiss_ok) { _, _ ->
                dismissLeftover(page.summary.sessionId)
            }
            .setNegativeButton(R.string.recovery_cancel, null)
            .show()
    }

    private fun dismissLeftover(sessionId: String) {
        scope.launch {
            withContext(ioDispatcher) { app.fragmentJournal.markDismissed(sessionId) }
            refreshPages { currentSelection() }
        }
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
        val raw = rawField.text.toString().ifBlank { storedById(id)?.raw.orEmpty() }
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
                reload(Selection.Stored(id))
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
        syncPositions(Selection.Stored(id))
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
        syncPositions(Selection.Stored(id))
    }

    /**
     * Writes the raw edit into the store (or into the transient compose slot).
     * A changed **raw** field discards the polished transcript (inherited
     * desktop rule: no confirmation, no silent re-polish) — the store does
     * that in [DictationStore.updateRaw], the field is then re-read from it.
     *
     * A leftover page has no id and no text: there is nothing to commit, which
     * is also why its field never reaches this code with content.
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
        val current = storedById(id) ?: return
        if (raw != current.raw) {
            val updated = store.updateRaw(id, raw)
            showPolished(updated?.polished.orEmpty())
        }
        shownRaw = raw
    }

    /** The stored dictation [id] from the shown ring, or `null` (it may be gone). */
    private fun storedById(id: String): Dictation? =
        pages.filterIsInstance<WorkbenchPage.Stored>()
            .firstOrNull { it.dictation.id == id }
            ?.dictation

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
     *
     * #188: a **leftover page is NEW too**. It has no text a transcript could
     * extend, so a delivery must create a dictation — treating it as an edit
     * would throw the text at a page that cannot hold it.
     */
    internal fun onRecordingStarted() {
        val leftover = pages.getOrNull(index) is WorkbenchPage.Leftover
        pendingTarget = if (index == FifoNavigation.COMPOSE_INDEX || leftover) {
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
     * #188: on a leftover page the field is read-only (the IME could not insert
     * anything), so the transcript itself is the content of the new dictation.
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
                    reload(Selection.Stored(store.append(rawField.text.toString().ifBlank { text }).id))
                } else {
                    // The IME wrote into Raw, so the store discards the
                    // polished slot; the field follows the stored result.
                    val updated = store.updateRaw(id, rawField.text.toString())
                    showRaw(updated?.raw ?: rawField.text.toString())
                    showPolished(updated?.polished.orEmpty())
                    // #173 decision 5: the edit bumps it to the top of the ring.
                    syncPositions(Selection.Stored(id))
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
                reload(Selection.Stored(created.id))
            }
        }
        deliver(text, notify = false)
    }
}
