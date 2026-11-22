package com.georgernstgraf.polishedrecognition.ui

import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import com.georgernstgraf.polishedrecognition.network.NetworkGate
import com.georgernstgraf.polishedrecognition.network.ReconnectMonitor
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.georgernstgraf.polishedrecognition.recovery.RecoveryCoordinator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * #169 (epic #166 D5/D6/D15), #173 and #178: the screen, the compose slot, the
 * MRU ring and the icon header with its three action zones.
 *
 * #188 adds the merged ring: the journal's leftovers are pages of the same
 * ring, carry their reason and their four actions, and are retired from a
 * screen of their own.
 */
@RunWith(RobolectricTestRunner::class)
class WorkbenchActivityTest {

    private val context get() = RuntimeEnvironment.getApplication() as PolishedRecognitionApp
    private val journal get() = context.fragmentJournal

    private val leftoverSession = "20260101-1200-aaaaaaaa"

    @Before
    fun setUp() {
        context.getSharedPreferences("polished_recognition_settings", 0).edit().clear().commit()
        File(context.filesDir, DictationStore.DIR_NAME).deleteRecursively()
        File(context.filesDir, FragmentJournal.DIR_NAME).deleteRecursively()
        ShadowToast.reset()
    }

    private fun seed(vararg texts: String): DictationStore {
        val store = DictationStore(context, fifoSize = { 10 })
        texts.forEach { store.append(it) }
        return store
    }

    /**
     * #188: a journal leftover. A dead token (AUTH) is one of the reasons the
     * auto path never retries, so these tests stay network-free.
     */
    private fun seedLeftover(
        sessionId: String = leftoverSession,
        failureClass: String? = "AUTH",
        completed: Boolean = false
    ) {
        val audio = File(context.cacheDir, "seed-$sessionId.wav")
            .apply { writeBytes(ByteArray(64)) }
        journal.writePending(
            sessionId,
            FragmentJournal.FragmentRecord(index = 0, durationMs = 7_000L, preRollMs = 1_000L),
            audio
        )
        if (failureClass != null) {
            journal.markFailed(sessionId, 0, "HTTP 401", failureClass = failureClass, httpCode = 401)
        }
        if (completed) {
            journal.markDone(sessionId, 0, "roh", "fertiger Text")
            journal.markCompleted(sessionId)
        }
    }

    private fun configureSttProvider() {
        SettingsStore(context).sttProvider = SttProviderConfig(
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiToken = "t",
            model = "whisper-large-v3"
        )
    }

    private fun launch(): WorkbenchActivity {
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java)
        controller.get().ioDispatcher = Dispatchers.Unconfined
        return controller.setup().get().also { idle() }
    }

    private fun EditText.text() = text.toString()

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun WorkbenchActivity.raw() = findViewById<EditText>(R.id.workbench_raw)
    private fun WorkbenchActivity.polished() = findViewById<TextView>(R.id.workbench_polished)
    private fun WorkbenchActivity.position() =
        findViewById<TextView>(R.id.workbench_position).text.toString()

    private fun WorkbenchActivity.stamp() =
        findViewById<TextView>(R.id.workbench_stamp).text.toString()

    private fun WorkbenchActivity.click(id: Int) = findViewById<View>(id).performClick()

    private fun WorkbenchActivity.enabled(id: Int) = findViewById<View>(id).isEnabled

    private fun WorkbenchActivity.visible(id: Int) =
        findViewById<View>(id).visibility == View.VISIBLE

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

    // ------------------------------------------------ #188: the merged ring

    /**
     * #185's "an empty journal shows the hint and no rows", carried over: an
     * empty journal adds nothing to the ring and keeps the entry page.
     */
    @Test
    fun `an empty journal adds no page to the ring`() {
        val activity = launch()

        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.enabled(R.id.workbench_prev)).isFalse()
        assertThat(activity.enabled(R.id.workbench_next)).isFalse()
        assertThat(activity.visible(R.id.workbench_leftover_actions)).isFalse()
        assertThat(activity.stamp()).isEmpty()
    }

    @Test
    fun `a leftover page shows its reason and a raw field that cannot take focus`() {
        seedLeftover()
        val activity = launch()

        activity.showNewest()

        assertThat(activity.position()).isEqualTo("1 / 1")
        // the when-line: the start plus seconds, fragments and the reason
        assertThat(activity.stamp()).contains("7 s")
        assertThat(activity.stamp()).contains("1 fragment")
        assertThat(activity.stamp()).contains("STT token rejected")
        assertThat(activity.raw().text()).isEmpty()
        assertThat(activity.raw().isEnabled).isFalse()
        assertThat(activity.raw().isFocusable).isFalse()
        assertThat(activity.raw().requestFocus()).isFalse()
        assertThat(activity.findViewById<TextView>(R.id.workbench_empty_hint).text.toString())
            .isEqualTo("Not transcribed yet — Retry STT or start a new dictation")
        assertThat(activity.visible(R.id.workbench_leftover_actions)).isTrue()
    }

    @Test
    fun `navigating from a leftover to a stored page restores the editable field`() {
        // the leftover is seeded FIRST, so it is the older page (and its session
        // id orders before any dictation id on a same-millisecond tie)
        seedLeftover()
        val store = seed("stored text")
        val activity = launch()
        activity.showNewest()
        activity.click(R.id.workbench_prev)
        assertThat(activity.raw().isEnabled).isFalse()

        activity.click(R.id.workbench_prev)

        assertThat(activity.position()).isEqualTo("2 / 2")
        assertThat(activity.raw().isEnabled).isTrue()
        assertThat(activity.raw().isFocusable).isTrue()
        assertThat(activity.raw().text()).isEqualTo("stored text")
        assertThat(store.loadAll().map { it.raw }).containsExactly("stored text")
    }

    @Test
    fun `a delivery while a leftover page is shown creates a new dictation`() {
        seedLeftover()
        val activity = launch()
        activity.showNewest()
        val raw = activity.raw()

        activity.onRecordingStarted()
        activity.onTranscriptArrived("diktiert")

        // the leftover page cannot take an edit — the transcript becomes a page
        assertThat(DictationStore(context).loadAll().map { it.raw }).containsExactly("diktiert")
        assertThat(raw.text()).isEqualTo("diktiert")
        assertThat(activity.position()).isEqualTo("2 / 2")
        assertThat(journal.recoverableSessions().map { it.sessionId })
            .containsExactly(leftoverSession)
    }

    @Test
    fun `the position label counts stored and leftover pages`() {
        seed("eins", "zwei")
        seedLeftover()
        val activity = launch()

        // walk the whole ring: three pages, exactly one of them a leftover
        val labels = mutableListOf<String>()
        var leftoverPages = 0
        activity.showNewest()
        repeat(3) {
            labels += activity.position()
            if (!activity.raw().isEnabled) leftoverPages++
            activity.click(R.id.workbench_prev)
        }

        assertThat(labels).containsExactly("3 / 3", "2 / 3", "1 / 3").inOrder()
        assertThat(leftoverPages).isEqualTo(1)
    }

    /**
     * #185's "an unfinished session offers a re-run — disabled without a
     * provider", carried over: the retry lives on the page now.
     */
    @Test
    fun `an unfinished leftover offers retry - disabled without a provider`() {
        seedLeftover()
        val activity = launch()
        activity.showNewest()

        assertThat(activity.visible(R.id.workbench_retry_stt)).isTrue()
        assertThat(activity.enabled(R.id.workbench_retry_stt)).isFalse()
        assertThat(activity.visible(R.id.workbench_restore_text)).isFalse()
        assertThat(activity.enabled(R.id.workbench_dismiss_leftover)).isTrue()
        assertThat(activity.enabled(R.id.workbench_share_audio)).isTrue()
    }

    @Test
    fun `a retry tap asks the coordinator for exactly that session`() {
        configureSttProvider()
        seedLeftover()
        val activity = launch()
        activity.showNewest()
        var asked: String? = null
        activity.retryRunner = { sessionId ->
            asked = sessionId
            true
        }

        assertThat(activity.enabled(R.id.workbench_retry_stt)).isTrue()
        activity.click(R.id.workbench_retry_stt)

        assertThat(asked).isEqualTo(leftoverSession)
    }

    @Test
    fun `a refused retry says so instead of doing nothing`() {
        configureSttProvider()
        seedLeftover()
        val activity = launch()
        activity.showNewest()
        activity.retryRunner = { false }

        activity.click(R.id.workbench_retry_stt)

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("A recovery is already running")
    }

    /**
     * #185's "a completed session offers the stored text instead of a re-run",
     * carried over.
     */
    @Test
    fun `a completed leftover offers restore instead of retry`() {
        seedLeftover(failureClass = null, completed = true)
        val activity = launch()
        activity.showNewest()

        assertThat(activity.visible(R.id.workbench_restore_text)).isTrue()
        assertThat(activity.enabled(R.id.workbench_restore_text)).isTrue()
        assertThat(activity.visible(R.id.workbench_retry_stt)).isFalse()
        assertThat(activity.stamp()).contains("transcribed — text available")

        activity.click(R.id.workbench_restore_text)
        idle()

        assertThat(DictationStore(context).loadAll().map { it.raw }).containsExactly("fertiger Text")
        assertThat(activity.raw().text()).isEqualTo("fertiger Text")
        assertThat(journal.recoverableSessions()).isEmpty()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Text restored to the Workbench")
    }

    /**
     * #185's "dismissing asks first, then records the reason and removes the
     * row", carried over — the page disappears instead of a row.
     */
    @Test
    fun `dismissing a leftover asks first, then records the reason and drops the page`() {
        seedLeftover(failureClass = "TERMINAL")
        val activity = launch()
        activity.showNewest()

        activity.click(R.id.workbench_dismiss_leftover)
        idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        assertThat(journal.recoverableSessions()).isEmpty()
        val marker = journal.readSession(leftoverSession)!!
        assertThat(marker.dismissReason).isEqualTo(FragmentJournal.REASON_USER_DISCARDED)
        assertThat(marker.dismissedAtMs).isNotNull()
        // only the tombstone is left — the audio is gone
        assertThat(journal.sessionDir(leftoverSession).listFiles()!!.map { it.name })
            .containsExactly(FragmentJournal.SESSION_FILE)
        assertThat(activity.position()).isEqualTo("new")
        assertThat(activity.visible(R.id.workbench_leftover_actions)).isFalse()
    }

    @Test
    fun `a failed run lands as a toast naming the failure`() {
        seedLeftover()
        val activity = launch()
        activity.showNewest()

        context.recoveryCoordinator.onFinished!!.invoke(
            RecoveryCoordinator.Outcome(
                recovered = 0,
                failures = listOf(RecoveryCoordinator.Failure(leftoverSession, "HTTP 500")),
                skipped = 0
            )
        )
        idle()

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Re-run failed: HTTP 500")
    }

    // ------------------------------------------- #186: offline dictations

    /**
     * #186: a dictation recorded without a network is a page with a reason, not
     * a failure screen — the wave-2 stamp line carries it, so no new UI.
     */
    @Test
    fun `a leftover recorded offline shows that it is saved`() {
        seedLeftover(failureClass = "OFFLINE")
        val activity = launch()

        activity.showNewest()

        assertThat(activity.position()).isEqualTo("1 / 1")
        assertThat(activity.stamp()).contains("7 s")
        assertThat(activity.stamp()).contains("Offline — saved, will transcribe when online")
        assertThat(activity.visible(R.id.workbench_leftover_actions)).isTrue()
    }

    /**
     * #186: the foreground-only reconnect. The monitor is injected because the
     * real one needs live Android network callbacks; the fake keeps the ONE
     * rule under test (`NetworkGate.reconnectFires`) and only pretends to be
     * the platform, so what this pins is the WIRING: one validated edge → one
     * recovery ask, and a paused screen stops listening.
     */
    @Test
    fun `a reconnect asks for a recovery once and leaving the screen stops the monitor`() {
        val monitor = FakeReconnectMonitor()
        val controller = Robolectric.buildActivity(WorkbenchActivity::class.java)
        controller.get().ioDispatcher = Dispatchers.Unconfined
        controller.get().reconnectMonitor = monitor
        val activity = controller.setup().get().also { idle() }
        val onEntry = activity.recoverRequests

        // the entry path already recovered; now the network comes back
        monitor.validated()
        idle()
        assertThat(activity.recoverRequests).isEqualTo(onEntry + 1)

        // ConnectivityManager repeats onCapabilitiesChanged for the same
        // network — a repeat is not another reconnect
        monitor.validated()
        idle()
        assertThat(activity.recoverRequests).isEqualTo(onEntry + 1)

        controller.pause()
        assertThat(monitor.running).isFalse()

        // nothing fires while the screen is away, even for a real new edge
        monitor.lost()
        monitor.validated()
        idle()
        assertThat(activity.recoverRequests).isEqualTo(onEntry + 1)
    }

    /**
     * The test double for [ReconnectMonitor]: it applies the production edge
     * rule ([NetworkGate.reconnectFires]) instead of a private one, so the
     * "exactly once" property is the real rule and not a re-invention.
     */
    private class FakeReconnectMonitor : ReconnectMonitor {
        private var onValidated: (() -> Unit)? = null
        private var previouslyValidated = false
        var running = false
            private set

        override fun start(onValidated: () -> Unit) {
            running = true
            previouslyValidated = false
            this.onValidated = onValidated
        }

        override fun stop() {
            running = false
            onValidated = null
        }

        /** One `onCapabilitiesChanged` reporting a validated network. */
        fun validated() {
            if (!running) return
            val fires = NetworkGate.reconnectFires(
                validated = true,
                previouslyValidated = previouslyValidated
            )
            previouslyValidated = true
            if (fires) onValidated?.invoke()
        }

        /** The default network went away (`onLost`). */
        fun lost() {
            previouslyValidated = false
        }
    }
}
