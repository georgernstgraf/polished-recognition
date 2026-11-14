package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executor

/**
 * #101 — API tokens must never leave the device through OS backup.
 *
 * The mechanism is a separate preferences FILE (`SettingsStore.SECRETS_PREFS_NAME`)
 * because backup exclusion works per file, not per key: the provider configs keep
 * endpoint/model/display name in the backed-up settings file and only the token moves
 * out. These tests pin the split, the read-back, the blank-token case and the
 * migration of pre-#101 installations, whose tokens still sit inside the settings JSON.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreSecretsTest {

    private val directExecutor = Executor { it.run() }

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun freshStore() = SettingsStore(ctx, directExecutor)

    private fun settingsPrefs() = ctx.getSharedPreferences("polished_recognition_settings", 0)

    private fun secretsPrefs() = ctx.getSharedPreferences(SettingsStore.SECRETS_PREFS_NAME, 0)

    /** The Robolectric sandbox shares prefs across tests in this class. */
    @Before
    fun clearPrefs() {
        settingsPrefs().edit().clear().commit()
        secretsPrefs().edit().clear().commit()
    }

    private fun stt(token: String) = SttProviderConfig(
        displayName = "gregor",
        baseUrl = "https://stt.example/v1",
        apiToken = token,
        model = "whisper-large-v3"
    )

    private fun llm(token: String) = LlmProviderConfig(
        displayName = "local",
        baseUrl = "https://llm.example/v1",
        apiToken = token,
        model = "gpt-oss-120b"
    )

    @Test
    fun `stt token is written to the secret file, never into the backed-up settings`() {
        freshStore().sttProvider = stt("sk-stt-secret")

        assertThat(secretsPrefs().getString("stt_token", null)).isEqualTo("sk-stt-secret")
        val backedUpJson = settingsPrefs().getString("stt_provider", null)
        assertThat(backedUpJson).isNotNull()
        assertThat(backedUpJson).doesNotContain("sk-stt-secret")
        // everything else stays restorable from the backup
        assertThat(backedUpJson).contains("https://stt.example/v1")
        assertThat(backedUpJson).contains("whisper-large-v3")
    }

    @Test
    fun `llm token is written to the secret file, never into the backed-up settings`() {
        freshStore().llmProvider = llm("sk-llm-secret")

        assertThat(secretsPrefs().getString("llm_token", null)).isEqualTo("sk-llm-secret")
        assertThat(settingsPrefs().getString("llm_provider", null))
            .doesNotContain("sk-llm-secret")
    }

    @Test
    fun `the token is read back through the store`() {
        freshStore().sttProvider = stt("sk-stt-secret")

        val config = freshStore().sttProvider
        assertThat(config).isNotNull()
        assertThat(config!!.apiToken).isEqualTo("sk-stt-secret")
        assertThat(config.baseUrl).isEqualTo("https://stt.example/v1")
    }

    @Test
    fun `a blank token removes the secret instead of storing an empty one`() {
        val store = freshStore()
        store.sttProvider = stt("sk-stt-secret")
        store.sttProvider = stt("")

        assertThat(secretsPrefs().contains("stt_token")).isFalse()
        assertThat(store.sttProvider!!.apiToken).isEmpty()
    }

    @Test
    fun `clearing a provider removes its secret`() {
        val store = freshStore()
        store.sttProvider = stt("sk-stt-secret")
        store.sttProvider = null

        assertThat(secretsPrefs().contains("stt_token")).isFalse()
        assertThat(store.sttProvider).isNull()
    }

    @Test
    fun `a legacy embedded token is migrated and scrubbed from the backed-up file`() {
        // pre-#101 shape: the token lives inside the settings JSON
        settingsPrefs().edit()
            .putString("stt_provider", Gson().toJson(stt("sk-legacy-token")))
            .commit()

        val config = freshStore().sttProvider
        assertThat(config!!.apiToken).isEqualTo("sk-legacy-token")

        // moved into the excluded file …
        assertThat(secretsPrefs().getString("stt_token", null)).isEqualTo("sk-legacy-token")
        // … and the backed-up copy no longer carries it
        assertThat(settingsPrefs().getString("stt_provider", null))
            .doesNotContain("sk-legacy-token")
    }

    @Test
    fun `the secret file wins over a stale legacy token`() {
        freshStore().sttProvider = stt("sk-new-token")
        // a stale JSON with an embedded token must not resurrect it
        settingsPrefs().edit()
            .putString("stt_provider", Gson().toJson(stt("sk-stale-token")))
            .commit()

        assertThat(freshStore().sttProvider!!.apiToken).isEqualTo("sk-new-token")
        assertThat(settingsPrefs().getString("stt_provider", null))
            .doesNotContain("sk-stale-token")
    }
}
