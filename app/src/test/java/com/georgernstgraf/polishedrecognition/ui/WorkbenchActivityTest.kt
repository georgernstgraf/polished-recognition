package com.georgernstgraf.polishedrecognition.ui

import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * #169 (epic #166 D5/D6/D15), #173 and #178: the screen, the compose slot, the
 * MRU ring and the icon header with its three action zones.
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
    private fun WorkbenchActivity.polished() = findViewById<TextView>(R.id.workbench_polished)
    private fun WorkbenchActivity.position() =
        findViewById<TextView>(R.id.workbench_position).text.toString()

    private fun WorkbenchActivity.click(id: Int) = findViewById<View>(id).performClick()

    private fun WorkbenchActivity.enabled(id: Int) = findViewById<View>(id).isEnabled

    /**
     * ◀ and ▶ enter the ring at the newest dictation. On the compose slot one
     * stored entry is enough, inside the ring two are needed (#173/#178). Tests
     * that want a stored page seed a filler entry next to it, which also keeps
     * the wrapping and the "2 / 2" labels under test.
     */
    private fun WorkbenchActivity.showNewest() {
        click(R.id.workbench_next)
    }

    private fun clipboardText(): String =
        context.getSystemService(android.content.ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text.toString()

    @Test
    fun `an empty workbench opens on the compose slot`() {
        val activity = launch()

        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.enabled(R.id.workbench_prev)).isFalse()
        assertThat(activity.enabled(R.id.workbench_next)).isFalse()
        assertThat(activity.enabled(R.id.workbench_new)).isTrue()
        assertThat(activity.enabled(R.id.workbench_polish_a)).isFalse()
        assertThat(activity.enabled(R.id.workbench_polish_b)).isFalse()
        assertThat(activity.enabled(R.id.workbench_copy_raw)).isFalse()
        assertThat(activity.enabled(R.id.workbench_copy_polished)).isFalse()
        assertThat(activity.enabled(R.id.workbench_promote_b)).isFalse()
        assertThat(activity.enabled(R.id.workbench_promote_c)).isFalse()
        assertThat(activity.enabled(R.id.workbench_clear_polished)).isFalse()
        assertThat(activity.findViewById<TextView>(R.id.workbench_empty_hint).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(activity.raw().text()).isEmpty()
        assertThat(activity.polished().text.toString()).isEmpty()
    }

    @Test
    fun `the entry page is the compose slot and the ring wraps around`() {
        seed("oldest", "middle", "newest")
        val activity = launch()

        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.raw().text()).isEmpty()
        assertThat(activity.enabled(R.id.workbench_prev)).isTrue()
        assertThat(activity.enabled(R.id.workbench_next)).isTrue()

        // ▶ enters the ring at the newest dictation
        activity.showNewest()
        assertThat(activity.position()).isEqualTo("3 / 3")
        assertThat(activity.raw().text()).isEqualTo("newest")

        // ▶ again wraps to the oldest
        activity.click(R.id.workbench_next)
        assertThat(activity.position()).isEqualTo("1 / 3")
        assertThat(activity.raw().text()).isEqualTo("oldest")

        // ◀ wraps back to the newest
        activity.click(R.id.workbench_prev)
        assertThat(activity.position()).isEqualTo("3 / 3")
        assertThat(activity.raw().text()).isEqualTo("newest")
    }

    @Test
    fun `a single stored dictation is reachable from the compose slot`() {
        seed("only one")
        val activity = launch()

        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.enabled(R.id.workbench_prev)).isTrue()
        assertThat(activity.enabled(R.id.workbench_next)).isTrue()

        activity.showNewest()

        // #173 decision 7 / #178: leaving the compose slot is a real move, so
        // one stored card is a destination.
        assertThat(activity.position()).isEqualTo("1 / 1")
        assertThat(activity.raw().text()).isEqualTo("only one")

        // Inside the ring a lone card is a self-loop: both arrows stay dead.
        assertThat(activity.enabled(R.id.workbench_prev)).isFalse()
        assertThat(activity.enabled(R.id.workbench_next)).isFalse()
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
        seed("filler", "first")
        val activity = launch()
        activity.showNewest()
        activity.raw().setText("first")

        activity.onRecordingStarted()
        activity.raw().setText("first second")
        activity.onTranscriptArrived("second")

        assertThat(DictationStore(context).loadAll().map { it.raw })
            .containsExactly("filler", "first second")
        assertThat(activity.position()).isEqualTo("2 / 2")
    }

    @Test
    fun `a stored page targets raw even when the user typed first`() {
        val store = seed("filler", "first")
        val activity = launch()
        activity.showNewest()
        val raw = activity.raw()
        raw.setText("first, edited")

        // no waiting for a change comparison — the visible page decides
        activity.onRecordingStarted()
        raw.setText("first, edited and dictated")
        activity.onTranscriptArrived("and dictated")

        assertThat(store.loadAll().map { it.raw })
            .containsExactly("filler", "first, edited and dictated")
        assertThat(store.loadAll().size).isEqualTo(2)
    }

    @Test
    fun `polished is read-only and can never take the dictation target (D10)`() {
        val store = seed("filler", "raw text")
        store.updatePolished(store.loadAll().last().id, "polished text")
        val activity = launch()
        activity.showNewest()

        val polished = activity.polished()
        assertThat(polished.text.toString()).isEqualTo("polished text")
        assertThat(polished.isFocusable).isFalse()
        assertThat(polished.requestFocus()).isFalse()

        // With Polished out of reach the only target left on a stored page is RAW.
        activity.onRecordingStarted()
        activity.raw().setText("raw text, dictated")
        activity.onTranscriptArrived("dictated")

        val saved = DictationStore(context).loadAll().first { it.raw == "raw text, dictated" }
        assertThat(saved.polished).isNull()
    }

    @Test
    fun `dictating into an old raw edits it and bumps it to the top`() {
        val store = seed("a", "b", "c")
        val activity = launch()
        // ◀ from the compose slot lands on the newest, then walks down to "a"
        repeat(3) { activity.click(R.id.workbench_prev) }
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

        activity.click(R.id.workbench_new)

        assertThat(DictationStore(context).loadAll()).isEmpty()
        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.raw().hasFocus()).isTrue()
    }

    @Test
    fun `plus on a touched slot materialises it and opens a fresh one`() {
        val activity = launch()
        activity.raw().setText("draft")

        activity.click(R.id.workbench_new)

        assertThat(DictationStore(context).loadAll().map { it.raw }).containsExactly("draft")
        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.raw().text()).isEmpty()
        assertThat(activity.polished().text.toString()).isEmpty()
    }

    @Test
    fun `promote in zone B makes polished the new raw and keeps the polished text`() {
        val store = seed("filler", "raw text")
        val id = store.loadAll().last().id
        store.updatePolished(id, "polished text")
        val activity = launch()
        activity.showNewest()

        activity.click(R.id.workbench_promote_b)

        val saved = DictationStore(context).loadAll().last()
        assertThat(saved.id).isEqualTo(id)
        assertThat(saved.raw).isEqualTo("polished text")
        assertThat(saved.polished).isEqualTo("polished text")
        assertThat(activity.raw().text()).isEqualTo("polished text")
        assertThat(activity.polished().text.toString()).isEqualTo("polished text")
        assertThat(activity.position()).isEqualTo("2 / 2")
    }

    @Test
    fun `promote in zone C does the same from below the polished field`() {
        val store = seed("filler", "raw text")
        val id = store.loadAll().last().id
        store.updatePolished(id, "promoted")
        val activity = launch()
        activity.showNewest()

        activity.click(R.id.workbench_promote_c)

        assertThat(DictationStore(context).loadAll().last().raw).isEqualTo("promoted")
        assertThat(activity.raw().text()).isEqualTo("promoted")
    }

    @Test
    fun `promote is disabled without polished text`() {
        seed("filler", "raw only")
        val activity = launch()
        activity.showNewest()

        assertThat(activity.enabled(R.id.workbench_promote_b)).isFalse()
        assertThat(activity.enabled(R.id.workbench_promote_c)).isFalse()
        // Nothing to promote: the store has no polished slot to move.
        activity.click(R.id.workbench_promote_b)

        assertThat(DictationStore(context).loadAll().last().raw).isEqualTo("raw only")
    }

    @Test
    fun `empty polished clears the slot and disables every polished action`() {
        val store = seed("filler", "raw text")
        val id = store.loadAll().last().id
        store.updatePolished(id, "polished text")
        val activity = launch()
        activity.showNewest()
        assertThat(activity.enabled(R.id.workbench_clear_polished)).isTrue()

        activity.click(R.id.workbench_clear_polished)

        assertThat(DictationStore(context).loadAll().last().polished).isNull()
        assertThat(activity.polished().text.toString()).isEmpty()
        assertThat(activity.enabled(R.id.workbench_promote_b)).isFalse()
        assertThat(activity.enabled(R.id.workbench_promote_c)).isFalse()
        assertThat(activity.enabled(R.id.workbench_copy_polished)).isFalse()
        assertThat(activity.enabled(R.id.workbench_clear_polished)).isFalse()
        // Raw and its actions are untouched by emptying Polished.
        assertThat(activity.raw().text()).isEqualTo("raw text")
        assertThat(activity.enabled(R.id.workbench_copy_raw)).isTrue()
    }

    @Test
    fun `the enable policy follows WorkbenchActions while raw is typed`() {
        val activity = launch()
        assertThat(activity.enabled(R.id.workbench_polish_a)).isFalse()

        // The empty slot has nothing to send — the disabled button is a no-op.
        activity.click(R.id.workbench_polish_a)
        assertThat(DictationStore(context).loadAll()).isEmpty()

        activity.raw().setText("typing")

        assertThat(activity.enabled(R.id.workbench_polish_a)).isTrue()
        assertThat(activity.enabled(R.id.workbench_polish_b)).isTrue()
        assertThat(activity.enabled(R.id.workbench_copy_raw)).isTrue()
        // Typed but not stored yet: nothing to promote or navigate.
        assertThat(activity.enabled(R.id.workbench_promote_b)).isFalse()
        assertThat(activity.enabled(R.id.workbench_prev)).isFalse()
    }

    @Test
    fun `a raw edit discards the polished text and persists on leaving`() {
        val store = seed("filler", "raw text")
        val id = store.loadAll().last().id
        store.updatePolished(id, "polished text")
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java).setup()
        val activity = controller.get()
        activity.showNewest()

        activity.raw().setText("raw edited")
        controller.pause()

        val saved = DictationStore(context).loadAll().first { it.id == id }
        assertThat(saved.raw).isEqualTo("raw edited")
        assertThat(saved.polished).isNull()
        assertThat(activity.polished().text.toString()).isEmpty()
    }

    @Test
    fun `a delivery never polishes on its own - a raw edit empties the polished slot`() {
        val store = seed("filler", "raw text")
        store.updatePolished(store.loadAll().last().id, "old polish")
        val activity = launch()
        activity.showNewest()
        activity.polishRunner = { _, _ -> Result.success("must not run") }
        val raw = activity.raw()

        raw.setText("raw text, typed")
        raw.onFocusChangeListener.onFocusChange(raw, false)
        idle()

        assertThat(store.loadAll().last().polished).isNull()
    }

    @Test
    fun `polish sends the raw text with the per-dictation language and stores the result`() {
        val store = seed("filler", "raw text")
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

        activity.click(R.id.workbench_polish_a)
        idle()

        assertThat(seenText).isEqualTo("raw text")
        assertThat(seenLanguage).isEqualTo("English")
        // the choice is per dictation and never written back
        assertThat(SettingsStore(context).targetLanguage).isNull()
        val saved = store.loadAll().last()
        assertThat(saved.raw).isEqualTo("raw text")
        assertThat(saved.polished).isEqualTo("polished text")
        assertThat(clipboardText()).isEqualTo("polished text")
        assertThat(activity.enabled(R.id.workbench_promote_b)).isTrue()
        assertThat(activity.enabled(R.id.workbench_copy_polished)).isTrue()
    }

    @Test
    fun `the language spinner is seeded from the global setting on both sites`() {
        SettingsStore(context).targetLanguage = "English"
        val activity = launch()

        for (id in listOf(R.id.workbench_language_a, R.id.workbench_language_b)) {
            val dropdown = activity.findViewById<AutoCompleteTextView>(id)
            assertThat(dropdown.text.toString()).isEqualTo("English")
        }
    }

    @Test
    fun `both polish sites share one language choice`() {
        seed("filler", "raw text")
        val activity = launch()
        activity.showNewest()
        var seenLanguage: String? = "unset"
        activity.polishRunner = { _, language ->
            seenLanguage = language
            Result.success("polished")
        }
        val siteA = activity.findViewById<AutoCompleteTextView>(R.id.workbench_language_a)
        val siteB = activity.findViewById<AutoCompleteTextView>(R.id.workbench_language_b)

        // The item listener both sites share is the picker; a pick in site B
        // must land in site A as well (and vice versa). Any AdapterView stands
        // in for the dropdown's list — the listener only reads its position.
        siteB.onItemClickListener!!.onItemClick(android.widget.ListView(activity), null, 1, 1L)

        assertThat(siteA.text.toString()).isEqualTo("English")
        assertThat(siteB.text.toString()).isEqualTo("English")

        activity.click(R.id.workbench_polish_b)
        idle()

        assertThat(seenLanguage).isEqualTo("English")
    }

    @Test
    fun `a failed polish keeps the previous polished text`() {
        val store = seed("filler", "raw text")
        store.updatePolished(store.loadAll().last().id, "old polish")
        val activity = launch()
        activity.showNewest()
        activity.polishRunner = { _, _ -> Result.failure(java.io.IOException("offline")) }

        activity.click(R.id.workbench_polish_a)
        idle()

        assertThat(store.loadAll().last().polished).isEqualTo("old polish")
        assertThat(activity.enabled(R.id.workbench_polish_a)).isTrue()
    }

    @Test
    fun `the second polish site cannot start a second polish in flight`() {
        seed("filler", "raw text")
        val activity = launch()
        activity.showNewest()
        val gate = CompletableDeferred<Result<String>>()
        activity.polishRunner = { _, _ -> gate.await() }

        activity.click(R.id.workbench_polish_a)
        idle()

        assertThat(activity.enabled(R.id.workbench_polish_a)).isFalse()
        assertThat(activity.enabled(R.id.workbench_polish_b)).isFalse()
        assertThat(activity.findViewById<Button>(R.id.workbench_polish_b).text.toString())
            .isEqualTo("Polishing…")

        gate.complete(Result.success("done"))
        idle()

        assertThat(activity.enabled(R.id.workbench_polish_a)).isTrue()
        assertThat(activity.findViewById<Button>(R.id.workbench_polish_a).text.toString())
            .isEqualTo("Polish")
    }

    @Test
    fun `the copy button delivers the visible field`() {
        seed("filler", "copy me")
        val activity = launch()
        activity.showNewest()

        activity.click(R.id.workbench_copy_raw)

        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("copy me")
    }

    @Test
    fun `a changed field losing focus delivers itself, an unchanged one does not`() {
        seed("filler", "original")
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

        activity.click(R.id.workbench_settings)

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
