package com.georgernstgraf.polishedrecognition.ui

import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #212: the OutputControlActivity tests — presets, the per-app wrap draft
 * mechanics (commit on selection change, block on invalid, forget) and the
 * `app-wrap-width` validation hook. Per-app WIDTH writes go through the
 * learner executor and are asserted at the DRAFT level (the store's async
 * write is exercised by `SettingsStoreTest` with an injected direct executor).
 */
@RunWith(RobolectricTestRunner::class)
class OutputControlActivityTest {

    private lateinit var store: SettingsStore

    private fun knownApps() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("known_apps", 0)

    @Before
    fun setUp() {
        store = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).settingsStore
        knownApps().edit().clear().commit()
        SettingsDraft.clear()
    }

    @After
    fun tearDown() {
        knownApps().edit().clear().commit()
        SettingsDraft.clear()
    }

    private fun launch(): OutputControlActivity =
        Robolectric.buildActivity(OutputControlActivity::class.java).setup().get()

    private fun OutputControlActivity.wrapWidthField(): EditText = findViewById(R.id.wrap_width)
    private fun OutputControlActivity.appWrapDropdown(): AutoCompleteTextView = findViewById(R.id.app_wrap_app)
    private fun OutputControlActivity.appWrapWidthField(): EditText = findViewById(R.id.app_wrap_width)

    private fun seedTwoApps() {
        knownApps().edit().putString(
            "known_apps",
            """{"com.example.reader":{"label":"Reader","lastSeenMs":5,"wrapWidth":null},""" +
                """"com.example.writer":{"label":"Writer","lastSeenMs":4,"wrapWidth":40}}"""
        ).commit()
    }

    @Test
    fun `global wrap width is loaded from the store and presets write the draft`() {
        val activity = launch()
        assertThat(activity.wrapWidthField().text.toString()).isEqualTo("80")

        activity.findViewById<Button>(R.id.wrap_120).performClick()
        assertThat(SettingsDraft.wrapWidth).isEqualTo("120")
        assertThat(activity.wrapWidthField().text.toString()).isEqualTo("120")

        activity.findViewById<Button>(R.id.wrap_off).performClick()
        assertThat(SettingsDraft.wrapWidth).isEqualTo("0")
    }

    @Test
    fun `per-app row loads from the store and edits stay pending`() {
        seedTwoApps()
        val activity = launch()

        // MRU first (#102): the Reader row is preselected, empty = use global.
        assertThat(activity.appWrapDropdown().text.toString()).isEqualTo("Reader")
        assertThat(activity.appWrapWidthField().text.toString()).isEmpty()

        activity.appWrapWidthField().setText("90")
        assertThat(SettingsDraft.pendingAppWrap).isEqualTo("com.example.reader" to "90")
    }

    @Test
    fun `a selection change commits the displayed value into the draft map`() {
        seedTwoApps()
        val activity = launch()
        activity.appWrapWidthField().setText("90")

        // Select the second app (Writer): the Reader row's 90 is committed first.
        val writerPosition = (0 until activity.appWrapDropdown().adapter.count)
            .first { activity.appWrapDropdown().adapter.getItem(it) == "Writer" }
        activity.appWrapDropdown().onItemClickListener!!
            .onItemClick(null, null, writerPosition, writerPosition.toLong())

        assertThat(SettingsDraft.appWrapWidths["com.example.reader"]).isEqualTo(90)
        assertThat(activity.appWrapDropdown().text.toString()).isEqualTo("Writer")
        assertThat(activity.appWrapWidthField().text.toString()).isEqualTo("40")
        assertThat(SettingsDraft.pendingAppWrap?.first).isEqualTo("com.example.writer")
    }

    @Test
    fun `an invalid per-app value BLOCKS the selection change and fails validation`() {
        seedTwoApps()
        val activity = launch()
        activity.appWrapWidthField().setText("5")

        val writerPosition = (0 until activity.appWrapDropdown().adapter.count)
            .first { activity.appWrapDropdown().adapter.getItem(it) == "Writer" }
        activity.appWrapDropdown().onItemClickListener!!
            .onItemClick(null, null, writerPosition, writerPosition.toLong())

        // The change was blocked: still on the Reader row, error shown…
        assertThat(activity.appWrapDropdown().text.toString()).isEqualTo("Reader")
        assertThat(activity.appWrapWidthField().error).isNotNull()
        // …the pending raw stays in the draft, so the overview's back-exit
        // names the app-wrap-width check.
        assertThat(SettingsDraft.pendingAppWrap).isEqualTo("com.example.reader" to "5")
        store.wrapWidth = 80 // deterministic effective state for the check
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .contains(SettingsValidation.CHECK_APP_WRAP_WIDTH)
    }

    @Test
    fun `forget removes the row immediately and marks the draft`() {
        seedTwoApps()
        val activity = launch()
        assertThat(activity.appWrapDropdown().adapter.count).isEqualTo(2)

        activity.findViewById<Button>(R.id.app_wrap_forget).performClick()

        assertThat(activity.appWrapDropdown().adapter.count).isEqualTo(1)
        assertThat(activity.appWrapDropdown().text.toString()).isEqualTo("Writer")
        assertThat(SettingsDraft.appWrapForgotten).contains("com.example.reader")
        assertThat(SettingsDraft.appWrapWidths).doesNotContainKey("com.example.reader")
    }

    @Test
    fun `the empty hint shows when no app was ever learned`() {
        val activity = launch()
        assertThat(activity.findViewById<android.view.View>(R.id.app_wrap_empty_hint).visibility)
            .isEqualTo(android.view.View.VISIBLE)
        assertThat(activity.appWrapDropdown().isEnabled).isFalse()
    }
}
