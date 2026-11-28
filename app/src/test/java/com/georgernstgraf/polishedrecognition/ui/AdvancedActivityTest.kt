package com.georgernstgraf.polishedrecognition.ui

import android.widget.CheckBox
import android.widget.EditText
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * #212: the AdvancedActivity tests — the fragment-geometry auto/lock
 * mechanics, the FIFO field (default 3, #172) and the prune-on-apply.
 */
@RunWith(RobolectricTestRunner::class)
class AdvancedActivityTest {

    private lateinit var store: SettingsStore

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("polished_recognition_settings", 0)

    @Before
    fun setUp() {
        store = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).settingsStore
        prefs().edit().clear().commit()
        SettingsDraft.clear()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        SettingsDraft.clear()
        File(RuntimeEnvironment.getApplication().filesDir, DictationStore.DIR_NAME)
            .deleteRecursively()
    }

    private fun launch(): AdvancedActivity =
        Robolectric.buildActivity(AdvancedActivity::class.java).setup().get()

    private fun AdvancedActivity.field(id: Int): EditText = findViewById(id)
    private fun AdvancedActivity.checkbox(id: Int): CheckBox = findViewById(id)

    @Test
    fun `the fifo field shows the default 3 and edits land in the draft`() {
        val activity = launch()
        assertThat(activity.field(R.id.workbench_fifo_size).text.toString()).isEqualTo("3")
        assertThat(SettingsDraft.workbenchFifoSize).isNull()

        activity.field(R.id.workbench_fifo_size).setText("5")
        assertThat(SettingsDraft.workbenchFifoSize).isEqualTo("5")
        assertThat(store.workbenchFifoSize).isEqualTo(3) // not written by the sub-screen
    }

    @Test
    fun `auto checkboxes write the draft and unchecking keeps the resolved value`() {
        val activity = launch()
        // Auto is the default: the field shows the resolved bootstrap value.
        assertThat(activity.checkbox(R.id.slice_auto).isChecked).isTrue()
        val resolved = activity.field(R.id.slice_seconds).text.toString()
        assertThat(resolved).isNotEmpty()
        assertThat(activity.field(R.id.slice_seconds).isEnabled).isFalse()

        activity.checkbox(R.id.slice_auto).isChecked = false

        assertThat(SettingsDraft.sliceAuto).isFalse()
        // The displayed value became the editable start AND the pending value.
        assertThat(activity.field(R.id.slice_seconds).isEnabled).isTrue()
        assertThat(SettingsDraft.sliceSeconds).isEqualTo(resolved)
        assertThat(SettingsValidation.failedChecks(SettingsDraft, store))
            .doesNotContain(SettingsValidation.CHECK_SLICE_SECONDS)
    }

    @Test
    fun `toggles write the draft without touching the store`() {
        val activity = launch()
        activity.checkbox(R.id.compress_audio).isChecked = true
        activity.checkbox(R.id.ime_live_preview).isChecked = false

        assertThat(SettingsDraft.compressAudio).isTrue()
        assertThat(SettingsDraft.imeLivePreview).isFalse()
        assertThat(store.compressAudio).isFalse()
        assertThat(store.imeLivePreview).isTrue()
    }

    /** #219: the cross-app keep checkbox follows the same draft/apply flow. */
    @Test
    fun `cross-app keep writes the draft without touching the store`() {
        val activity = launch()
        // Default ON.
        assertThat(activity.checkbox(R.id.cross_app_keep).isChecked).isTrue()

        activity.checkbox(R.id.cross_app_keep).isChecked = false

        assertThat(SettingsDraft.crossAppKeep).isFalse()
        assertThat(store.crossAppKeep).isTrue()

        // And the apply persists it.
        SettingsValidation.apply(
            RuntimeEnvironment.getApplication(),
            SettingsDraft,
            store,
            promptStore(),
            emptySet()
        )
        assertThat(store.crossAppKeep).isFalse()
    }

    /** #172: shrinking the cap prunes the dictation store ON APPLY. */
    @Test
    fun `fifo prune runs on apply when the cap shrinks`() {
        val context = RuntimeEnvironment.getApplication()
        val seeded = DictationStore(context, fifoSize = { 10 })
        repeat(4) { seeded.append("d-$it") }

        SettingsDraft.workbenchFifoSize = "2"
        SettingsValidation.apply(context, SettingsDraft, store, promptStore(), emptySet())

        assertThat(store.workbenchFifoSize).isEqualTo(2)
        assertThat(DictationStore(context).loadAll()).hasSize(2)
    }

    @Test
    fun `retention slider edits land in the draft as the position`() {
        val activity = launch()
        val slider = activity.findViewById<android.widget.SeekBar>(R.id.evidence_retention_slider)
        slider.progress = 4

        assertThat(SettingsDraft.evidenceRetentionPosition).isEqualTo(4)
    }

    private fun promptStore() = (RuntimeEnvironment.getApplication() as PolishedRecognitionApp).promptStore
}
