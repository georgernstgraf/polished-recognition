package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private lateinit var store: SettingsStore

    /** Runs the learner inline so assertions see the result immediately. */
    private val directExecutor = Executor { it.run() }

    private fun freshStore() = SettingsStore(RuntimeEnvironment.getApplication(), directExecutor)

    private fun knownAppsPrefs() =
        RuntimeEnvironment.getApplication().getSharedPreferences("known_apps", 0)

    private fun seedKnownApp(pkg: String, lastSeenMs: Long, wrapWidth: Int? = null) {
        val type = object : TypeToken<MutableMap<String, KnownApp>>() {}.type
        val current: MutableMap<String, KnownApp> =
            knownAppsPrefs().getString("known_apps", null)
                ?.let { Gson().fromJson<MutableMap<String, KnownApp>>(it, type) }
                ?: mutableMapOf()
        current[pkg] = KnownApp("Seeded", lastSeenMs, wrapWidth)
        knownAppsPrefs().edit().putString("known_apps", Gson().toJson(current)).commit()
    }

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
            .edit().clear().commit()
        knownAppsPrefs().edit().clear().commit()
        store = freshStore()
    }

    @Test
    fun `save and load SttProviderConfig round-trips all fields`() {
        val config = SttProviderConfig(
            displayName = "Test STT",
            baseUrl = "https://example.com/v1/",
            apiToken = "test-token",
            model = "test-model"
        )
        store.sttProvider = config
        val loaded = store.sttProvider
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.displayName).isEqualTo("Test STT")
        assertThat(loaded.baseUrl).isEqualTo("https://example.com/v1/")
        assertThat(loaded.apiToken).isEqualTo("test-token")
        assertThat(loaded.model).isEqualTo("test-model")
    }

    @Test
    fun `save and load LlmProviderConfig round-trips all fields`() {
        val config = LlmProviderConfig(
            displayName = "Test LLM",
            baseUrl = "https://example.com/v1/",
            apiToken = "llm-token",
            model = "gpt-4"
        )
        store.llmProvider = config
        val loaded = store.llmProvider
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.displayName).isEqualTo("Test LLM")
        assertThat(loaded.baseUrl).isEqualTo("https://example.com/v1/")
        assertThat(loaded.apiToken).isEqualTo("llm-token")
        assertThat(loaded.model).isEqualTo("gpt-4")
    }

    @Test
    fun `rawMode defaults to false`() {
        assertThat(store.rawMode).isFalse()
    }

    @Test
    fun `rawMode save and load true`() {
        store.rawMode = true
        assertThat(store.rawMode).isTrue()
    }

    @Test
    fun `targetLanguage defaults to null`() {
        assertThat(store.targetLanguage).isNull()
    }

    @Test
    fun `targetLanguage save and load`() {
        store.targetLanguage = "German"
        assertThat(store.targetLanguage).isEqualTo("German")
    }

    @Test
    fun `set and get STT models keyed per URL`() {
        store.setSttModels("https://a.example.com/v1", listOf("model-a1", "model-a2"))
        store.setSttModels("https://b.example.com/v1", listOf("model-b1"))
        assertThat(store.getSttModels("https://a.example.com/v1"))
            .containsExactly("model-a1", "model-a2").inOrder()
        assertThat(store.getSttModels("https://b.example.com/v1")).containsExactly("model-b1")
    }

    @Test
    fun `getSttModels returns empty for unknown URL`() {
        assertThat(store.getSttModels("https://none.example.com")).isEmpty()
    }

    @Test
    fun `overwriting URL updates models in place`() {
        store.setSttModels("https://a.example.com/v1", listOf("old"))
        store.setSttModels("https://a.example.com/v1", listOf("new"))
        assertThat(store.getSttModels("https://a.example.com/v1")).containsExactly("new")
        assertThat(store.getSttModels("https://b.example.com/v1")).isEmpty()
    }

    @Test
    fun `set and get LLM models keyed per URL`() {
        store.setLlmModels("https://llm.example.com/v1", listOf("x", "y", "z"))
        assertThat(store.getLlmModels("https://llm.example.com/v1")).containsExactly("x", "y", "z").inOrder()
        assertThat(store.getLlmModels("https://other.example.com")).isEmpty()
    }

    @Test
    fun `stt and llm caches are independent`() {
        store.setSttModels("https://same.example.com/v1", listOf("stt-model"))
        store.setLlmModels("https://same.example.com/v1", listOf("llm-model"))
        assertThat(store.getSttModels("https://same.example.com/v1")).containsExactly("stt-model")
        assertThat(store.getLlmModels("https://same.example.com/v1")).containsExactly("llm-model")
    }

    @Test
    fun `expired cache entry is pruned and returns empty`() {
        store.setSttModels("https://old.example.com/v1", listOf("stale"))
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
        // Age the stored timestamp beyond the 6-week TTL.
        val json = prefs.getString("stt_model_lists", null)!!
        val aged = json.replace("\"timestamp\":", "\"timestamp\":${System.currentTimeMillis() - SettingsStore.MODEL_CACHE_TTL_MS - 1000},\"ignored\":")
        prefs.edit().putString("stt_model_lists", aged).commit()

        assertThat(store.getSttModels("https://old.example.com/v1")).isEmpty()
        // Pruned — a fresh store no longer has it either.
        val fresh = SettingsStore(RuntimeEnvironment.getApplication())
        assertThat(fresh.getSttModels("https://old.example.com/v1")).isEmpty()
    }

    @Test
    fun `fresh cache entry survives read`() {
        store.setSttModels("https://a.example.com/v1", listOf("m"))
        assertThat(store.getSttModels("https://a.example.com/v1")).containsExactly("m")
    }

    @Test
    fun `legacy model list migrates under saved provider baseUrl`() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
        store.sttProvider = SttProviderConfig(displayName = "Groq", baseUrl = "https://api.groq.com/openai/v1", apiToken = "t", model = "whisper-large-v3")
        prefs.edit().putString("stt_model_list", "[\"whisper-large-v3\",\"whisper-large-v3-turbo\"]").commit()

        assertThat(store.getSttModels("https://api.groq.com/openai/v1"))
            .containsExactly("whisper-large-v3", "whisper-large-v3-turbo").inOrder()
        // Legacy key removed after migration.
        assertThat(prefs.getString("stt_model_list", null)).isNull()
    }

    @Test
    fun `legacy model list without saved provider is dropped`() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
        prefs.edit().putString("stt_model_list", "[\"orphan\"]").commit()

        assertThat(store.getSttModels("https://any.example.com")).isEmpty()
        assertThat(prefs.getString("stt_model_list", null)).isNull()
    }

    @Test
    fun `sttProvider null removes key`() {
        store.sttProvider = SttProviderConfig(displayName = "A", baseUrl = "url", apiToken = "tok", model = "mod")
        store.sttProvider = null
        assertThat(store.sttProvider).isNull()
    }

    @Test
    fun `new SettingsStore reads previously saved data`() {
        store.sttProvider = SttProviderConfig(displayName = "X", baseUrl = "url", apiToken = "tok", model = "mod")

        val fresh = SettingsStore(RuntimeEnvironment.getApplication())
        assertThat(fresh.sttProvider!!.displayName).isEqualTo("X")
    }

    @Test
    fun `customLanguages defaults to empty`() {
        assertThat(store.customLanguages).isEmpty()
    }

    @Test
    fun `customLanguages save and load`() {
        val languages = listOf("German", "French", "Italian")
        store.customLanguages = languages
        assertThat(store.customLanguages).containsExactly("German", "French", "Italian").inOrder()
    }

    @Test
    fun `customLanguages round-trips empty list`() {
        store.customLanguages = emptyList()
        assertThat(store.customLanguages).isEmpty()
    }

    @Test
    fun `wrapWidth defaults to 80`() {
        assertThat(store.wrapWidth).isEqualTo(80)
    }

    @Test
    fun `wrapWidth save and load round-trips`() {
        store.wrapWidth = 200
        assertThat(store.wrapWidth).isEqualTo(200)
        store.wrapWidth = 0
        assertThat(store.wrapWidth).isEqualTo(0)
    }

    @Test
    fun `wrapWidthFor falls back to global for null and unknown packages`() {
        store.wrapWidth = 35
        assertThat(store.wrapWidthFor(null)).isEqualTo(35)
        assertThat(store.wrapWidthFor("com.unknown.app")).isEqualTo(35)
    }

    @Test
    fun `wrapWidthFor returns the per-app override`() {
        store.wrapWidth = 35
        seedKnownApp("com.microsoft.office.outlook", System.currentTimeMillis(), wrapWidth = 120)
        val fresh = freshStore()
        assertThat(fresh.wrapWidthFor("com.microsoft.office.outlook")).isEqualTo(120)
        assertThat(fresh.wrapWidthFor("com.whatsapp")).isEqualTo(35)
    }

    @Test
    fun `wrapWidthFor returns 0 to disable wrapping for an app`() {
        seedKnownApp("com.whatsapp", System.currentTimeMillis(), wrapWidth = 0)
        val fresh = freshStore()
        assertThat(fresh.wrapWidthFor("com.whatsapp")).isEqualTo(0)
    }

    @Test
    fun `recordKnownApp learns a new app with package fallback label`() {
        store.recordKnownApp("com.example.chat")
        val learned = store.knownAppsByRecency()
        assertThat(learned).hasSize(1)
        assertThat(learned[0].first).isEqualTo("com.example.chat")
        // Not installed under Robolectric -> label falls back to the package.
        assertThat(learned[0].second.label).isEqualTo("com.example.chat")
    }

    @Test
    fun `recordKnownApp ignores own package and blank input`() {
        val app = RuntimeEnvironment.getApplication()
        store.recordKnownApp(app.packageName)
        store.recordKnownApp("")
        assertThat(store.knownAppsByRecency()).isEmpty()
    }

    @Test
    fun `recordKnownApp throttles rewrites within 24 hours`() {
        store.recordKnownApp("com.example.chat")
        val firstSeen = store.knownAppsByRecency()[0].second.lastSeenMs
        store.recordKnownApp("com.example.chat")
        assertThat(store.knownAppsByRecency()[0].second.lastSeenMs).isEqualTo(firstSeen)
    }

    @Test
    fun `recordKnownApp rewrites after 24 hours and keeps the override`() {
        val old = System.currentTimeMillis() - SettingsStore.KNOWN_APP_REWRITE_INTERVAL_MS - 1000
        seedKnownApp("com.example.chat", old, wrapWidth = 120)
        val fresh = freshStore()
        fresh.recordKnownApp("com.example.chat")
        val learned = fresh.knownAppsByRecency()[0].second
        assertThat(learned.lastSeenMs).isGreaterThan(old)
        assertThat(learned.wrapWidth).isEqualTo(120)
    }

    @Test
    fun `saveKnownAppWidths sets and clears overrides without dropping learned apps`() {
        seedKnownApp("com.a", System.currentTimeMillis())
        seedKnownApp("com.b", System.currentTimeMillis())
        val fresh = freshStore()
        fresh.saveKnownAppWidths(mapOf("com.a" to 120, "com.b" to null))
        assertThat(fresh.wrapWidthFor("com.a")).isEqualTo(120)
        assertThat(fresh.wrapWidthFor("com.b")).isEqualTo(fresh.wrapWidth)
        assertThat(fresh.knownAppsByRecency()).hasSize(2)
    }

    @Test
    fun `forgetKnownApp removes the app and its override`() {
        seedKnownApp("com.a", System.currentTimeMillis(), wrapWidth = 120)
        val fresh = freshStore()
        fresh.forgetKnownApp("com.a")
        assertThat(fresh.knownAppsByRecency()).isEmpty()
        assertThat(fresh.wrapWidthFor("com.a")).isEqualTo(fresh.wrapWidth)
    }

    @Test
    fun `known apps are ordered most recently used first`() {
        val now = System.currentTimeMillis()
        seedKnownApp("com.old", now - 10_000)
        seedKnownApp("com.new", now)
        val fresh = freshStore()
        assertThat(fresh.knownAppsByRecency().map { it.first })
            .containsExactly("com.new", "com.old").inOrder()
    }

    @Test
    fun `recording an app moves it to the top even inside the 24 hour throttle`() {
        val now = System.currentTimeMillis()
        seedKnownApp("com.recent", now)
        seedKnownApp("com.other", now - 5_000)
        val fresh = freshStore()
        // Both are within 24 h, so no rewrite is scheduled — the order must
        // still reflect the just-used app (#102).
        fresh.recordKnownApp("com.other")
        assertThat(fresh.knownAppsByRecency().map { it.first })
            .containsExactly("com.other", "com.recent").inOrder()
    }

    @Test
    fun `recording the target at dictation start makes a new app the top entry immediately`() {
        // #102 amendment: the entry points record the target when the dictation
        // starts, so the currently active app is on top before any insertion.
        seedKnownApp("com.previous", System.currentTimeMillis())
        val fresh = freshStore()
        fresh.recordKnownApp("com.target")
        assertThat(fresh.knownAppsByRecency().map { it.first })
            .containsExactly("com.target", "com.previous").inOrder()
    }

    @Test
    fun `repeated use restores the last-used app to the top`() {
        val now = System.currentTimeMillis()
        seedKnownApp("com.a", now)
        seedKnownApp("com.b", now)
        val fresh = freshStore()
        fresh.recordKnownApp("com.a")
        fresh.recordKnownApp("com.b")
        fresh.recordKnownApp("com.a")
        assertThat(fresh.knownAppsByRecency().map { it.first })
            .containsExactly("com.a", "com.b").inOrder()
    }

    @Test
    fun `forgetting an app also drops it from the in-memory MRU order`() {
        val now = System.currentTimeMillis()
        seedKnownApp("com.a", now)
        seedKnownApp("com.b", now - 5_000)
        val fresh = freshStore()
        fresh.recordKnownApp("com.a")
        fresh.forgetKnownApp("com.a")
        assertThat(fresh.knownAppsByRecency().map { it.first })
            .containsExactly("com.b")
    }

    @Test
    fun `known apps persist across store instances`() {
        store.recordKnownApp("com.example.chat")
        val fresh = freshStore()
        assertThat(fresh.knownAppsByRecency().map { it.first }).containsExactly("com.example.chat")
    }

    /** #116 Phase 2: the per-provider latency-profile storage. */

    @Test
    fun `stt profile round-trips samples keyed by baseUrl model and mediaType`() {
        store.recordSttSample("https://a/v1/", "whisper", "audio/ogg", 21_000, 700)
        store.recordSttSample("https://a/v1/", "whisper", "audio/wav", 21_000, 900)
        store.recordSttSample("https://b/v1/", "whisper", "audio/ogg", 21_000, 5_000)

        val ogg = store.sttProfile("https://a/v1/", "whisper", "audio/ogg")!!
        assertThat(ogg.samples).hasSize(1)
        assertThat(ogg.samples[0].durationMs).isEqualTo(21_000)
        assertThat(ogg.samples[0].elapsedMs).isEqualTo(700)
        // media types and endpoints never mix
        assertThat(store.sttProfile("https://a/v1/", "whisper", "audio/wav")!!.samples[0].elapsedMs)
            .isEqualTo(900)
        assertThat(store.sttProfile("https://b/v1/", "whisper", "audio/ogg")!!.samples[0].elapsedMs)
            .isEqualTo(5_000)
        assertThat(store.sttProfile("https://a/v1/", "other-model", "audio/ogg")).isNull()
    }

    @Test
    fun `stt profile keeps only the newest sample window`() {
        repeat(SttLatencyProfile.MAX_SAMPLES + 10) { i ->
            store.recordSttSample("https://a/v1/", "m", "audio/ogg", 1_000L + i, i.toLong())
        }
        val samples = store.sttProfile("https://a/v1/", "m", "audio/ogg")!!.samples
        assertThat(samples).hasSize(SttLatencyProfile.MAX_SAMPLES)
        assertThat(samples.last().durationMs).isEqualTo(1_000L + SttLatencyProfile.MAX_SAMPLES + 9)
        assertThat(samples.first().durationMs).isEqualTo(1_010L)
    }

    @Test
    fun `stt profile applied seconds round-trips`() {
        store.setSttProfileAppliedSeconds("https://a/v1/", "m", "audio/ogg", 13.5)
        val fresh = freshStore()
        assertThat(fresh.sttProfile("https://a/v1/", "m", "audio/ogg")!!.lastAppliedSeconds)
            .isEqualTo(13.5)
    }

    @Test
    fun `clearSttProfiles wipes every learned profile`() {
        store.recordSttSample("https://a/v1/", "m", "audio/ogg", 1_000, 100)
        store.clearSttProfiles()
        assertThat(store.sttProfile("https://a/v1/", "m", "audio/ogg")).isNull()
    }

    @Test
    fun `profiles written before the version guard are discarded (#119)`() {
        // Simulate a pre-#119 store: samples present, no version key. Their
        // durationMs came from the corrupt WAV pre-roll header and poisoned
        // the auto-sizer's fit, so they must be dropped exactly once.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("polished_recognition_settings", 0)
            .edit()
            .putString(
                "stt_profiles",
                "{\"https://a/v1/|m|audio/ogg\":{\"samples\":[{\"durationMs\":1000,\"elapsedMs\":900}]}}"
            )
            .remove("stt_profiles_version")
            .commit()

        assertThat(store.sttProfile("https://a/v1/", "m", "audio/ogg")).isNull()
        // the guard stamps the version, so a new sample survives the read
        store.recordSttSample("https://a/v1/", "m", "audio/ogg", 21_000, 700)
        assertThat(store.sttProfile("https://a/v1/", "m", "audio/ogg")!!.samples).hasSize(1)
    }

    @Test
    fun `fragmentSecondsOverride defaults to null and round-trips`() {
        assertThat(store.fragmentSecondsOverride).isNull()
        store.fragmentSecondsOverride = 30f
        assertThat(store.fragmentSecondsOverride).isEqualTo(30f)
        store.fragmentSecondsOverride = null
        assertThat(store.fragmentSecondsOverride).isNull()
    }

    /** #116 Phase 2: the sizer resolves override > profile fit > null. */

    @Test
    fun `sizer returns the manual override and skips the profile`() {
        store.fragmentSecondsOverride = 30f
        repeat(SttLatencyProfile.MIN_SAMPLES + 5) {
            store.recordSttSample("https://a/v1/", "m", "audio/ogg", 21_000, 700)
        }
        assertThat(SttFragmentSizer(store).fragmentSeconds()).isEqualTo(30.0)
    }

    @Test
    fun `sizer returns null without a provider or a ready profile`() {
        assertThat(SttFragmentSizer(store).fragmentSeconds()).isNull()
        store.sttProvider = SttProviderConfig(displayName = "A", baseUrl = "https://a/v1/", apiToken = "t", model = "m")
        store.compressAudio = true // key the samples on the OGG media type
        repeat(SttLatencyProfile.MIN_SAMPLES - 1) {
            store.recordSttSample("https://a/v1/", "m", "audio/ogg", 21_000, 700)
        }
        assertThat(SttFragmentSizer(store).fragmentSeconds()).isNull()
    }

    @Test
    fun `sizer derives from the profile and persists the applied anchor`() {
        store.sttProvider = SttProviderConfig(displayName = "A", baseUrl = "https://a/v1/", apiToken = "t", model = "m")
        store.compressAudio = true // the sizer keys the profile on the OGG media type
        // t = 0.06 + 0.02·S → derived (1.0 − 0.09)/0.02 = 45.5 s
        repeat(SttLatencyProfile.MIN_SAMPLES) { i ->
            val s = 10.0 + i * 3.0
            store.recordSttSample("https://a/v1/", "m", "audio/ogg",
                (s * 1000).toLong(), ((0.06 + 0.02 * s) * 1000).toLong())
        }
        assertThat(SttFragmentSizer(store).fragmentSeconds()).isWithin(1e-9).of(45.5)
        // the applied value was persisted — the next session's stepwise
        // adaptation is anchored here (±50 %, tested in SttLatencyProfileTest)
        assertThat(store.sttProfile("https://a/v1/", "m", "audio/ogg")!!.lastAppliedSeconds)
            .isWithin(1e-9).of(45.5)
    }
}
