package com.georgernstgraf.polishedrecognition.ui

import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/**
 * #185: the pending-transcriptions screen. The empty state has no dead UI, an
 * unfinished session offers the re-run, a completed one the restore, and a
 * dismissal refuses to disappear without a recorded reason.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryActivityTest {

    private val context get() = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
    private val journal get() = context.fragmentJournal

    private val session = "20260101-1200-aaaaaaaa"
    private var clock = 1_700_000_000_000L

    @Before
    fun setUp() {
        context.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        File(context.filesDir, FragmentJournal.DIR_NAME).deleteRecursively()
        File(context.filesDir, DictationStore.DIR_NAME).deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, FragmentJournal.DIR_NAME).deleteRecursively()
        File(context.filesDir, DictationStore.DIR_NAME).deleteRecursively()
    }

    private fun launch(): RecoveryActivity {
        val controller = Robolectric.buildActivity(RecoveryActivity::class.java)
        controller.get().ioDispatcher = Dispatchers.Unconfined
        return controller.setup().get().also { idle() }
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun seed(failureClass: String? = null, completed: Boolean = false) {
        val audio = File(context.cacheDir, "seed-${session}.wav").apply { writeBytes(ByteArray(64)) }
        journal.writePending(
            session,
            FragmentJournal.FragmentRecord(index = 0, durationMs = 7_000L, preRollMs = 1_000L),
            audio
        )
        if (failureClass != null) journal.markFailed(session, 0, "HTTP 500", failureClass = failureClass)
        if (completed) {
            journal.markDone(session, 0, "roh", "fertiger Text")
            journal.markCompleted(session)
        }
    }

    private fun RecoveryActivity.row(index: Int = 0): View =
        findViewById<LinearLayout>(R.id.recovery_list).getChildAt(index)

    private fun RecoveryActivity.emptyState(): TextView = findViewById(R.id.recovery_empty)

    private fun View.button(id: Int): Button = findViewById(id)

    @Test
    fun `an empty journal shows the hint and no rows`() {
        val activity = launch()

        assertThat(activity.emptyState().visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.findViewById<LinearLayout>(R.id.recovery_list).childCount).isEqualTo(0)
    }

    @Test
    fun `an unfinished session offers a re-run - disabled without a provider`() {
        seed(failureClass = "AUTH")
        val activity = launch()

        val row = activity.row()
        assertThat(activity.emptyState().visibility).isEqualTo(View.GONE)
        assertThat(row.button(R.id.recovery_rerun).visibility).isEqualTo(View.VISIBLE)
        // no STT provider configured in the test prefs → the re-run is refused
        assertThat(row.button(R.id.recovery_rerun).isEnabled).isFalse()
        assertThat(row.button(R.id.recovery_restore).visibility).isEqualTo(View.GONE)
        assertThat(row.button(R.id.recovery_dismiss).isEnabled).isTrue()
        assertThat(activity.findViewById<TextView>(R.id.recovery_title).text.toString()).isNotEmpty()
    }

    @Test
    fun `a completed session offers the stored text instead of a re-run`() {
        seed(completed = true)
        val activity = launch()

        val row = activity.row()
        assertThat(row.button(R.id.recovery_restore).visibility).isEqualTo(View.VISIBLE)
        assertThat(row.button(R.id.recovery_restore).isEnabled).isTrue()
        assertThat(row.button(R.id.recovery_rerun).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `dismissing asks first, then records the reason and removes the row`() {
        seed(failureClass = "TERMINAL")
        val activity = launch()
        activity.row().button(R.id.recovery_dismiss).performClick()
        idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        assertThat(activity.findViewById<LinearLayout>(R.id.recovery_list).childCount).isEqualTo(0)
        assertThat(activity.emptyState().visibility).isEqualTo(View.VISIBLE)
        val marker = journal.readSession(session)!!
        assertThat(marker.dismissReason).isEqualTo(FragmentJournal.REASON_USER_DISCARDED)
        assertThat(marker.dismissedAtMs).isNotNull()
        // only the tombstone is left — the audio is gone
        assertThat(journal.sessionDir(session).listFiles()!!.map { it.name })
            .containsExactly(FragmentJournal.SESSION_FILE)
    }
}
