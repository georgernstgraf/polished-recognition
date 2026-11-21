package com.georgernstgraf.polishedrecognition.ui

import android.view.View
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
 * #185: the Workbench entry point — the leftovers banner is GONE when there is
 * nothing to recover (no dead UI) and names the count otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class WorkbenchRecoveryBannerTest {

    private val context get() = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
    private val journal get() = context.fragmentJournal

    private val session = "20260101-1200-cccccccc"

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

    /** A dead token is never auto-retried, so the test stays network-free. */
    private fun seedUnrecoverable() {
        val audio = File(context.cacheDir, "seed-banner.wav").apply { writeBytes(ByteArray(64)) }
        journal.writePending(session, FragmentJournal.FragmentRecord(index = 0), audio)
        journal.markFailed(session, 0, "HTTP 401", failureClass = "AUTH", httpCode = 401)
    }

    @Test
    fun `no leftovers means no banner`() {
        val activity = launch()

        assertThat(activity.banner().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `a leftover names its count in the banner`() {
        seedUnrecoverable()

        val activity = launch()

        assertThat(activity.banner().visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.banner().text.toString()).contains("1")
    }
}
