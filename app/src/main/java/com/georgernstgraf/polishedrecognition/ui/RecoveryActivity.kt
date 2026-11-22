package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.georgernstgraf.polishedrecognition.recovery.RecoveryAudio
import com.georgernstgraf.polishedrecognition.recovery.RecoveryCoordinator
import com.georgernstgraf.polishedrecognition.recovery.RecoveryReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * #185: the "pending transcriptions" list — every journal session that never
 * became text, with the three ways out: re-run the STT, share the audio, or
 * dismiss it explicitly.
 *
 * The Workbench's automatic cleanup is the primary path (it retries what can
 * heal by itself); this screen is where the rest is decided — a dead token, a
 * provider protocol problem, or a request the provider will keep rejecting.
 *
 * Platform Views only (no AppCompat/Material/RecyclerView): rows are inflated
 * from `item_recovery.xml` into a LinearLayout, exactly like the rest of the
 * app. All enablement comes from [RecoveryActions]; the screen writes no rule
 * of its own.
 */
class RecoveryActivity : Activity() {

    private lateinit var app: PolishedRecognitionApp
    private lateinit var journal: FragmentJournal
    private lateinit var coordinator: RecoveryCoordinator
    private lateinit var dictations: DictationStore

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Seam for the Robolectric tests; production reads the journal off-main. */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /** The session a MANUAL re-run is working on (the only one that shows a spinner). */
    private var manualSession: String? = null

    /** One journal session plus what the row needs to decide its actions. */
    private class Row(
        val summary: FragmentJournal.SessionSummary,
        val records: List<FragmentJournal.FragmentRecord>,
        val hasAudio: Boolean
    )

    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as PolishedRecognitionApp
        journal = app.fragmentJournal
        coordinator = app.recoveryCoordinator
        dictations = DictationStore(this, fifoSize = { app.settingsStore.workbenchFifoSize })
        setContentView(R.layout.activity_recovery)
        list = findViewById(R.id.recovery_list)
        empty = findViewById(R.id.recovery_empty)
    }

    override fun onResume() {
        super.onResume()
        coordinator.onFinished = { outcome -> onRunFinished(outcome) }
        reload()
    }

    override fun onPause() {
        coordinator.onFinished = null
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- loading + rendering -------------------------------------------------

    private fun reload() {
        scope.launch {
            val loaded = withContext(ioDispatcher) {
                val active = app.voiceSessionController.activeSessionId()
                journal.recoverableSessions()
                    .filter { it.sessionId != active }
                    .map { summary ->
                        val records = journal.readRecords(summary.sessionId)
                        val dir = journal.sessionDir(summary.sessionId)
                        Row(
                            summary = summary,
                            records = records,
                            hasAudio = records.any { record ->
                                record.uploadFile?.let { File(dir, it).isFile } == true
                            }
                        )
                    }
            }
            rows = loaded
            render()
        }
    }

    private fun render() {
        list.removeAllViews()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val busy = coordinator.isRunning()
        val providerConfigured = app.settingsStore.sttProvider != null
        rows.forEach { row ->
            val summary = row.summary
            val actions = RecoveryActions.enabled(
                completed = summary.completed,
                hasAudio = row.hasAudio,
                busy = busy,
                providerConfigured = providerConfigured
            )
            val view = layoutInflater.inflate(R.layout.item_recovery, list, false)
            view.findViewById<TextView>(R.id.recovery_time).text = timeLabel(summary)
            view.findViewById<TextView>(R.id.recovery_detail).text = resources.getQuantityString(
                R.plurals.recovery_detail,
                summary.fragmentCount,
                (summary.durationMs / 1000L).toInt(),
                summary.fragmentCount,
                reasonLabel(summary)
            )
            view.findViewById<TextView>(R.id.recovery_progress).visibility =
                if (busy && summary.sessionId == manualSession) View.VISIBLE else View.GONE

            view.findViewById<Button>(R.id.recovery_rerun).apply {
                visibility = if (actions.restore) View.GONE else View.VISIBLE
                isEnabled = actions.reRun
                setOnClickListener { reRun(row) }
            }
            view.findViewById<Button>(R.id.recovery_restore).apply {
                visibility = if (actions.restore) View.VISIBLE else View.GONE
                isEnabled = actions.restore
                setOnClickListener { restore(row) }
            }
            view.findViewById<Button>(R.id.recovery_share).apply {
                isEnabled = actions.share
                setOnClickListener { share(row) }
            }
            view.findViewById<Button>(R.id.recovery_dismiss).apply {
                isEnabled = actions.dismiss
                setOnClickListener { confirmDismiss(row) }
            }
            list.addView(view)
        }
    }

    private fun timeLabel(summary: FragmentJournal.SessionSummary): String {
        val at = summary.startedAtMs ?: return summary.sessionId
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
    }

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
            }
        )
    }

    // --- actions -------------------------------------------------------------

    private fun reRun(row: Row) {
        if (!coordinator.startManual(row.summary.sessionId)) {
            toast(getString(R.string.recovery_busy))
            return
        }
        manualSession = row.summary.sessionId
        render()
    }

    private fun restore(row: Row) {
        val text = row.records
            .mapNotNull { it.sttTrimmed?.takeIf { value -> value.isNotBlank() } }
            .joinToString(" ")
            .trim()
        if (text.isBlank()) {
            toast(getString(R.string.recovery_restore_empty))
            return
        }
        scope.launch {
            val restored = withContext(ioDispatcher) {
                val created = dictations.append(text)
                val stored = dictations.loadAll().any { it.id == created.id }
                if (stored) journal.deleteSession(row.summary.sessionId)
                stored
            }
            toast(
                if (restored) getString(R.string.recovery_restored)
                else getString(R.string.recovery_store_failed)
            )
            reload()
        }
    }

    private fun share(row: Row) {
        scope.launch {
            val uris = withContext(ioDispatcher) { shareUris(row) }
            if (uris.isEmpty()) {
                toast(getString(R.string.recovery_share_empty))
                return@launch
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "audio/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.recovery_chooser)))
        }
    }

    /**
     * A WAV session becomes ONE reconstructed file (pre-rolls removed); an OGG
     * session shares its pieces, because the app cannot decode them.
     */
    private fun shareUris(row: Row): List<Uri> {
        val dir = journal.sessionDir(row.summary.sessionId)
        val wav = RecoveryAudio.reconstructWav(dir, row.records)
        val files = if (wav != null) {
            val shareDir = File(cacheDir, RecoveryAudio.SHARE_DIR).apply { mkdirs() }
            val out = File(shareDir, "${row.summary.sessionId}.wav")
            out.writeBytes(wav)
            listOf(out)
        } else {
            RecoveryAudio.pieceFiles(dir, row.records)
        }
        return files.mapNotNull { file ->
            try {
                FileProvider.getUriForFile(this, "$packageName.recovery", file)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun confirmDismiss(row: Row) {
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_dismiss_title)
            .setMessage(R.string.recovery_dismiss_message)
            .setPositiveButton(R.string.recovery_dismiss_ok) { _, _ -> dismiss(row) }
            .setNegativeButton(R.string.recovery_cancel, null)
            .show()
    }

    private fun dismiss(row: Row) {
        scope.launch {
            withContext(ioDispatcher) {
                journal.markDismissed(row.summary.sessionId)
            }
            reload()
        }
    }

    // --- outcome -------------------------------------------------------------

    private fun onRunFinished(outcome: RecoveryCoordinator.Outcome) {
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
        toast(message)
        reload()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
