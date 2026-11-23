package com.georgernstgraf.polishedrecognition.ui

import android.view.View
import android.widget.EditText
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
import java.io.File

/**
 * #185/#188/#191: the Workbench entry point — the leftovers banner is GONE when
 * there is nothing to recover (no dead UI, no dead tap target), names the count
 * AND the newest leftover's stamp otherwise, says what a tap does ("tap to
 * open"), and a tap jumps to that page instead of opening a screen of its own.
 */
@RunWith(RobolectricTestRunner::class)
class WorkbenchRecoveryBannerTest {

    private val context get() = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
    private val journal get() = context.fragmentJournal

    private val session = "20260101-1200-cccccccc"
    private val newerSession = "20260101-1201-dddddddd"

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

    private fun launch(): WorkbenchActivity {
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java)
        controller.get().ioDispatcher = Dispatchers.Unconfined
        return controller.setup().get().also { idle() }
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun WorkbenchActivity.banner(): TextView = findViewById(R.id.workbench_recovery)

    private fun WorkbenchActivity.stamp(): TextView = findViewById(R.id.workbench_stamp)

    private fun WorkbenchActivity.raw(): EditText = findViewById(R.id.workbench_raw)

    /** A dead token is never auto-retried, so the test stays network-free. */
    private fun seedUnrecoverable(
        sessionId: String = session,
        failureClass: String = "AUTH"
    ) {
        val audio = File(context.cacheDir, "seed-banner-$sessionId.wav")
            .apply { writeBytes(ByteArray(64)) }
        journal.writePending(sessionId, FragmentJournal.FragmentRecord(index = 0), audio)
        journal.markFailed(sessionId, 0, "HTTP 401", failureClass = failureClass, httpCode = 401)
    }

    @Test
    fun `no leftovers means no banner`() {
        val activity = launch()

        assertThat(activity.banner().visibility).isEqualTo(View.GONE)
        // a GONE view is not on screen, so it cannot be a dead tap target
        assertThat(activity.banner().isShown).isFalse()
    }

    @Test
    fun `a leftover names its count, the newest stamp and what a tap does`() {
        seedUnrecoverable()

        val activity = launch()

        assertThat(activity.banner().visibility).isEqualTo(View.VISIBLE)
        // "<n> incomplete · <stamp of the newest> — tap to open" (#191)
        assertThat(activity.banner().text.toString())
            .matches("""1 incomplete dictation · (?:\w{3} )?\d{2}:\d{2} — tap to open""")
    }

    @Test
    fun `two leftovers count twice and tap jumps to the newest page`() {
        // two sessions, a millisecond apart in the journal's own clock; AUTH is
        // older than TERMINAL so the reason alone tells them apart
        seedUnrecoverable(session, failureClass = "AUTH")
        seedUnrecoverable(newerSession, failureClass = "TERMINAL")

        val activity = launch()
        assertThat(activity.banner().text.toString())
            .matches("""2 incomplete dictations · (?:\w{3} )?\d{2}:\d{2} — tap to open""")

        activity.banner().performClick()

        // the jump lands on a leftover page — its field cannot take focus
        assertThat(activity.raw().isEnabled).isFalse()
        assertThat(activity.stamp().text.toString()).contains("provider rejected the request")
        assertThat(activity.findViewById<View>(R.id.workbench_leftover_actions).visibility)
            .isEqualTo(View.VISIBLE)
    }
}
