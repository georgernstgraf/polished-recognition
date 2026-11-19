package com.georgernstgraf.polishedrecognition.ui

import android.widget.Button
import android.widget.CheckBox
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

/** #169 (epic #166 D5/D6/D15): the screen, the FIFO navigation and entry creation. */
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

    private fun clipboardText(): String =
        context.getSystemService(android.content.ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text.toString()

    @Test
    fun `an empty workbench shows no navigation and no dictation`() {
        val activity = launch()

        assertThat(activity.findViewById<TextView>(R.id.workbench_position).text.toString())
            .isEqualTo("0 / 0")
        assertThat(activity.findViewById<Button>(R.id.workbench_prev).isEnabled).isFalse()
        assertThat(activity.findViewById<Button>(R.id.workbench_next).isEnabled).isFalse()
        assertThat(activity.findViewById<TextView>(R.id.workbench_empty_hint).visibility)
            .isEqualTo(android.view.View.VISIBLE)
        assertThat(activity.findViewById<EditText>(R.id.workbench_raw).text()).isEmpty()
        assertThat(activity.findViewById<EditText>(R.id.workbench_polished).text()).isEmpty()
    }

    @Test
    fun `the newest dictation is shown and the arrows follow the fifo`() {
        seed("oldest", "middle", "newest")
        val activity = launch()

        assertThat(activity.findViewById<TextView>(R.id.workbench_position).text.toString())
            .isEqualTo("3 / 3")
        assertThat(activity.findViewById<EditText>(R.id.workbench_raw).text()).isEqualTo("newest")
        assertThat(activity.findViewById<Button>(R.id.workbench_next).isEnabled).isFalse()
        assertThat(activity.findViewById<Button>(R.id.workbench_prev).isEnabled).isTrue()

        activity.findViewById<Button>(R.id.workbench_prev).performClick()
        assertThat(activity.findViewById<TextView>(R.id.workbench_position).text.toString())
            .isEqualTo("2 / 3")
        assertThat(activity.findViewById<EditText>(R.id.workbench_raw).text()).isEqualTo("middle")

        activity.findViewById<Button>(R.id.workbench_prev).performClick()
        assertThat(activity.findViewById<Button>(R.id.workbench_prev).isEnabled).isFalse()
        assertThat(activity.findViewById<EditText>(R.id.workbench_raw).text()).isEqualTo("oldest")

        activity.findViewById<Button>(R.id.workbench_next).performClick()
        assertThat(activity.findViewById<EditText>(R.id.workbench_raw).text()).isEqualTo("middle")
    }

    @Test
    fun `a first transcript creates the dictation - opening the screen does not`() {
        val activity = launch()
        val store = DictationStore(context, fifoSize = { 3 })
        assertThat(store.loadAll()).isEmpty()

        activity.onRecordingStarted()
        activity.findViewById<EditText>(R.id.workbench_raw).setText("dictated text")
        activity.onTranscriptArrived("dictated text")

        assertThat(store.loadAll().map { it.raw }).containsExactly("dictated text")
        assertThat(activity.findViewById<TextView>(R.id.workbench_position).text.toString())
            .isEqualTo("1 / 1")
    }

    @Test
    fun `each new dictation adds an entry instead of overwriting the shown one`() {
        seed("first")
        val activity = launch()

        activity.onRecordingStarted()
        activity.findViewById<EditText>(R.id.workbench_raw).setText("first second")
        activity.onTranscriptArrived("second")

        assertThat(DictationStore(context).loadAll().map { it.raw })
            .containsExactly("first", "second").inOrder()
        assertThat(activity.findViewById<EditText>(R.id.workbench_raw).text()).isEqualTo("second")
    }

    @Test
    fun `a transcript after a manual raw edit continues that edit`() {
        val store = seed("first")
        val activity = launch()
        val raw = activity.findViewById<EditText>(R.id.workbench_raw)
        raw.setText("first, edited")

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
        val polished = activity.findViewById<EditText>(R.id.workbench_polished)
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
    fun `a new transcript beyond the cap evicts the oldest`() {
        seed("a", "b", "c")
        val activity = launch()

        activity.onRecordingStarted()
        activity.onTranscriptArrived("d")

        assertThat(DictationStore(context).loadAll().map { it.raw })
            .containsExactly("b", "c", "d").inOrder()
    }

    @Test
    fun `a raw edit discards the polished text and persists on leaving`() {
        val store = seed("raw text")
        val id = store.loadAll().single().id
        store.updatePolished(id, "polished text")
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<EditText>(R.id.workbench_raw).setText("raw edited")
        controller.pause()

        val saved = DictationStore(context).loadAll().single()
        assertThat(saved.raw).isEqualTo("raw edited")
        assertThat(saved.polished).isNull()
    }

    @Test
    fun `the auto-llm checkbox reflects and persists the setting`() {
        val activity = launch()
        val box = activity.findViewById<CheckBox>(R.id.workbench_auto_llm)
        activity.polishRunner = { _, _ -> Result.success("polished") }

        assertThat(box.isChecked).isFalse()
        box.performClick()

        assertThat(box.isChecked).isTrue()
        assertThat(SettingsStore(context).workbenchAutoLlm).isTrue()
    }

    @Test
    fun `switching auto-llm on polishes the current dictation and delivers it`() {
        val store = seed("raw text")
        val activity = launch()
        activity.polishRunner = { _, _ -> Result.success("polished text") }

        activity.findViewById<CheckBox>(R.id.workbench_auto_llm).performClick()
        idle()

        assertThat(store.loadAll().single().polished).isEqualTo("polished text")
        assertThat(clipboardText()).isEqualTo("polished text")
    }

    @Test
    fun `auto-llm polishes a new dictation when it is on`() {
        SettingsStore(context).workbenchAutoLlm = true
        val activity = launch()
        activity.polishRunner = { text, _ -> Result.success("P:$text") }

        activity.onRecordingStarted()
        activity.onTranscriptArrived("hello")
        idle()

        assertThat(DictationStore(context).loadAll().single().polished).isEqualTo("P:hello")
    }

    @Test
    fun `re-polish sends the raw text with the per-dictation language and stores the result`() {
        val store = seed("raw text")
        SettingsStore(context).targetLanguage = null
        val activity = launch()
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

        activity.findViewById<Button>(R.id.workbench_copy_raw).performClick()

        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("copy me")
    }

    @Test
    fun `a changed field losing focus delivers itself, an unchanged one does not`() {
        seed("original")
        val activity = launch()
        val raw = activity.findViewById<EditText>(R.id.workbench_raw)
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
}
