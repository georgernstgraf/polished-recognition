package com.georgernstgraf.polishedrecognition.ui

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.dictation.Dictation
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * #169 (epic #166 D5/D6/D15) and #173: the screen, the compose slot, the MRU
 * ring and entry creation.
 */
@RunWith(RobolectricTestRunner::class)
class WorkbenchActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        File(context.filesDir, DictationStore.DIR_NAME).deleteRecursively()
    }

    private fun seed(vararg texts: String): DictationStore {
        val store = DictationStore(context, fifoSize = { 10 })
        texts.forEach { store.append(it) }
        return store
    }

    private fun launch() = Robolectric.buildActivity(WorkbenchActivity::class.java).setup().get()

    private fun EditText.text() = text.toString()

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun WorkbenchActivity.raw() = findViewById<EditText>(R.id.workbench_raw)
    private fun WorkbenchActivity.polished() = findViewById<EditText>(R.id.workbench_polished)
    private fun WorkbenchActivity.position() =
        findViewById<TextView>(R.id.workbench_position).text.toString()

    /** From the entry page both arrows enter the ring at the newest dictation. */
    private fun WorkbenchActivity.showNewest() {
        findViewById<Button>(R.id.workbench_next).performClick()
    }

    private fun clipboardText(): String =
        context.getSystemService(android.content.ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text.toString()

    @Test
    fun `an empty workbench opens on the compose slot`() {
        val activity = launch()

        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.findViewById<Button>(R.id.workbench_prev).isEnabled).isFalse()
        assertThat(activity.findViewById<Button>(R.id.workbench_next).isEnabled).isFalse()
        assertThat(activity.findViewById<TextView>(R.id.workbench_empty_hint).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(activity.raw().text()).isEmpty()
        assertThat(activity.polished().text()).isEmpty()
    }

    @Test
    fun `the entry page is the compose slot and the ring wraps around`() {
        seed("oldest", "middle", "newest")
        val activity = launch()

        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.raw().text()).isEmpty()
        assertThat(activity.findViewById<Button>(R.id.workbench_prev).isEnabled).isTrue()
        assertThat(activity.findViewById<Button>(R.id.workbench_next).isEnabled).isTrue()

        // ▶ enters the ring at the newest dictation
        activity.showNewest()
        assertThat(activity.position()).isEqualTo("3 / 3")
        assertThat(activity.raw().text()).isEqualTo("newest")

        // ▶ again wraps to the oldest
        activity.findViewById<Button>(R.id.workbench_next).performClick()
        assertThat(activity.position()).isEqualTo("1 / 3")
        assertThat(activity.raw().text()).isEqualTo("oldest")

        // ◀ wraps back to the newest
        activity.findViewById<Button>(R.id.workbench_prev).performClick()
        assertThat(activity.position()).isEqualTo("3 / 3")
        assertThat(activity.raw().text()).isEqualTo("newest")
    }

    @Test
    fun `a first transcript creates the dictation - opening the screen does not`() {
        val activity = launch()
        val store = DictationStore(context, fifoSize = { 3 })
        assertThat(store.loadAll()).isEmpty()

        activity.onRecordingStarted()
        activity.raw().setText("dictated text")
        activity.onTranscriptArrived("dictated text")

        assertThat(store.loadAll().map { it.raw }).containsExactly("dictated text")
        assertThat(activity.position()).isEqualTo("1 / 1")
    }

    @Test
    fun `dictating into a stored dictation edits it instead of adding an entry`() {
        seed("first")
        val activity = launch()
        activity.showNewest()
        activity.raw().setText("first")

        activity.onRecordingStarted()
        activity.raw().setText("first second")
        activity.onTranscriptArrived("second")

        assertThat(DictationStore(context).loadAll().map { it.raw }).containsExactly("first second")
        assertThat(activity.position()).isEqualTo("1 / 1")
    }

    @Test
    fun `a stored page targets raw even when the user typed first`() {
        val store = seed("first")
        val activity = launch()
        activity.showNewest()
        val raw = activity.raw()
        raw.setText("first, edited")

        // no waiting for a change comparison — the visible page decides
        activity.onRecordingStarted()
        raw.setText("first, edited and dictated")
        activity.onTranscriptArrived("and dictated")

        assertThat(store.loadAll().map { it.raw }).containsExactly("first, edited and dictated")
        assertThat(store.loadAll().size).isEqualTo(1)
    }

    @Test
    fun `a transcript with focus in polished edits the polished text (D10)`() {
        val store = seed("raw text")
        val id = store.loadAll().single().id
        val activity = launch()
        activity.showNewest()
        val polished = activity.polished()
        polished.requestFocus()

        activity.onRecordingStarted()
        polished.setText("polished by dictation")
        activity.onTranscriptArrived("polished by dictation")

        val saved = DictationStore(context).loadAll().single()
        assertThat(saved.id).isEqualTo(id)
        assertThat(saved.raw).isEqualTo("raw text")
        assertThat(saved.polished).isEqualTo("polished by dictation")
    }

    @Test
    fun `dictating into an old raw edits it and bumps it to the top`() {
        val store = seed("a", "b", "c")
        val activity = launch()
        // ◀ from the compose slot lands on the newest, then walks down to "a"
        repeat(3) { activity.findViewById<Button>(R.id.workbench_prev).performClick() }
        assertThat(activity.raw().text()).isEqualTo("a")

        activity.onRecordingStarted()
        activity.raw().setText("a extended")
        activity.onTranscriptArrived(" extended")

        assertThat(store.loadAll().map { it.raw })
            .containsExactly("b", "c", "a extended").inOrder()
        assertThat(activity.position()).isEqualTo("3 / 3")
        assertThat(activity.raw().text()).isEqualTo("a extended")
    }

    @Test
    fun `a new transcript beyond the cap evicts the oldest`() {
        seed("a", "b", "c")
        val activity = launch()

        activity.onRecordingStarted()
        activity.onTranscriptArrived("d")

        assertThat(DictationStore(context).loadAll().map { it.raw })
            .containsExactly("b", "c", "d").inOrder()
    }

    @Test
    fun `a compose slot with content is materialised on pause`() {
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java).setup()
        controller.get().raw().setText("typed draft")

        controller.pause()

        assertThat(DictationStore(context).loadAll().map { it.raw })
            .containsExactly("typed draft")
    }

    @Test
    fun `plus on a pristine slot creates nothing and only focuses raw`() {
        val activity = launch()

        activity.findViewById<Button>(R.id.workbench_new).performClick()

        assertThat(DictationStore(context).loadAll()).isEmpty()
        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.raw().hasFocus()).isTrue()
    }

    @Test
    fun `plus on a touched slot materialises it and opens a fresh one`() {
        val activity = launch()
        activity.raw().setText("draft")

        activity.findViewById<Button>(R.id.workbench_new).performClick()

        assertThat(DictationStore(context).loadAll().map { it.raw }).containsExactly("draft")
        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.raw().text()).isEmpty()
        assertThat(activity.polished().text()).isEmpty()
    }

    @Test
    fun `the accordion collapses to a one-line preview and expands again`() {
        seed("shown text")
        val activity = launch()
        activity.showNewest()
        val raw = activity.raw()
        val preview = activity.findViewById<TextView>(R.id.workbench_raw_preview)
        val toggle = activity.findViewById<View>(R.id.workbench_raw_toggle)

        assertThat(raw.visibility).isEqualTo(View.VISIBLE)
        assertThat(preview.visibility).isEqualTo(View.GONE)

        toggle.performClick()

        assertThat(raw.visibility).isEqualTo(View.GONE)
        assertThat(preview.visibility).isEqualTo(View.VISIBLE)
        assertThat(preview.text.toString()).isEqualTo("shown text")

        toggle.performClick()

        assertThat(raw.visibility).isEqualTo(View.VISIBLE)
        assertThat(preview.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `focus expands a collapsed section`() {
        seed("shown text")
        val activity = launch()
        activity.showNewest()
        activity.findViewById<View>(R.id.workbench_polished_toggle).performClick()
        val polished = activity.polished()
        assertThat(polished.visibility).isEqualTo(View.GONE)

        // A hidden field cannot take a tap, but focus can still arrive (window
        // focus restore, IME) — and focus always expands (decision 8).
        polished.onFocusChangeListener.onFocusChange(polished, true)

        assertThat(polished.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `a raw edit discards the polished text and persists on leaving`() {
        val store = seed("raw text")
        val id = store.loadAll().single().id
        store.updatePolished(id, "polished text")
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java).setup()
        val activity = controller.get()
        activity.showNewest()

        activity.raw().setText("raw edited")
        controller.pause()

        val saved = DictationStore(context).loadAll().single()
        assertThat(saved.raw).isEqualTo("raw edited")
        assertThat(saved.polished).isNull()
    }

    @Test
    fun `a delivery never polishes on its own - a raw edit empties the polished slot`() {
        val store = seed("raw text")
        store.updatePolished(store.loadAll().single().id, "old polish")
        val activity = launch()
        activity.showNewest()
        activity.polishRunner = { _, _ -> Result.success("must not run") }
        val raw = activity.raw()

        raw.setText("raw text, typed")
        raw.onFocusChangeListener.onFocusChange(raw, false)
        idle()

        assertThat(store.loadAll().single().polished).isNull()
    }

    @Test
    fun `re-polish sends the raw text with the per-dictation language and stores the result`() {
        val store = seed("raw text")
        SettingsStore(context).targetLanguage = null
        val activity = launch()
        activity.showNewest()
        var seenText: String? = null
        var seenLanguage: String? = "unset"
        activity.polishRunner = { text, language ->
            seenText = text
            seenLanguage = language
            Result.success("polished text")
        }
        // index 1 is "English" in LanguageOptions.buildLanguageList
        activity.onLanguageSelected(1)

        activity.findViewById<Button>(R.id.workbench_repolish).performClick()
        idle()

        assertThat(seenText).isEqualTo("raw text")
        assertThat(seenLanguage).isEqualTo("English")
        // the choice is per dictation and never written back
        assertThat(SettingsStore(context).targetLanguage).isNull()
        val saved = store.loadAll().single()
        assertThat(saved.raw).isEqualTo("raw text")
        assertThat(saved.polished).isEqualTo("polished text")
        assertThat(clipboardText()).isEqualTo("polished text")
    }

    @Test
    fun `the language spinner is seeded from the global setting`() {
        SettingsStore(context).targetLanguage = "English"
        val activity = launch()

        val dropdown = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.workbench_language)
        assertThat(dropdown.text.toString()).isEqualTo("English")
    }

    @Test
    fun `a failed re-polish keeps the previous polished text`() {
        val store = seed("raw text")
        store.updatePolished(store.loadAll().single().id, "old polish")
        val activity = launch()
        activity.showNewest()
        activity.polishRunner = { _, _ -> Result.failure(java.io.IOException("offline")) }

        activity.findViewById<Button>(R.id.workbench_repolish).performClick()
        idle()

        assertThat(store.loadAll().single().polished).isEqualTo("old polish")
        assertThat(activity.findViewById<Button>(R.id.workbench_repolish).isEnabled).isTrue()
    }

    @Test
    fun `the copy button delivers the visible field`() {
        seed("copy me")
        val activity = launch()
        activity.showNewest()

        activity.findViewById<Button>(R.id.workbench_copy_raw).performClick()

        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("copy me")
    }

    @Test
    fun `a changed field losing focus delivers itself, an unchanged one does not`() {
        seed("original")
        val activity = launch()
        activity.showNewest()
        val raw = activity.raw()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)

        // unchanged → nothing delivered
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sentinel", "sentinel"))
        raw.onFocusChangeListener.onFocusChange(raw, false)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("sentinel")

        // changed → delivered
        raw.setText("edited text")
        raw.onFocusChangeListener.onFocusChange(raw, false)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("edited text")
    }

    @Test
    fun `the settings button opens the existing settings screen`() {
        val activity = launch()

        activity.findViewById<Button>(R.id.workbench_settings).performClick()

        val next = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertThat(next.component?.className).isEqualTo(SettingsActivity::class.java.name)
    }

    @Test
    fun `the fifo depth comes from the settings store`() {
        SettingsStore(context).workbenchFifoSize = 2
        val store = DictationStore(context, fifoSize = { SettingsStore(context).workbenchFifoSize })
        repeat(3) { store.append("d-$it") }

        assertThat(store.loadAll().map { it.raw }).containsExactly("d-1", "d-2")
    }

    @Test
    fun `a rotated screen starts on a fresh compose slot and keeps the typed entry`() {
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java).setup()
        controller.get().raw().setText("typed before rotation")

        controller.pause().stop().destroy()
        val recreated = Robolectric.buildActivity(WorkbenchActivity::class.java).setup().get()

        assertThat(DictationStore(context).loadAll().map { it.raw })
            .containsExactly("typed before rotation")
        assertThat(recreated.position()).isEqualTo("new")
        assertThat(recreated.raw().text()).isEmpty()
    }
}
