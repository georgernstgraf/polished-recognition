package com.georgernstgraf.polishedrecognition.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class SttProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val apiToken: String,
    val model: String
)

data class LlmProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val apiToken: String,
    val model: String
)

data class CachedModels(
    val timestamp: Long,
    val models: List<String>
)

/**
 * App learned by the voice input as a dictation target (#100). Persisted in
 * the separate `known_apps` preferences file (included in the OS backup) and
 * cached in memory for O(1) `lastSeen` checks on the hot insertion path.
 *
 * @param wrapWidth per-app line-wrap override: `null` = use the global width,
 *   `0` = wrapping off, `>= SettingsStore.MIN_WRAP_WIDTH` = explicit width.
 */
data class KnownApp(
    val label: String,
    val lastSeenMs: Long,
    val wrapWidth: Int? = null
)

class SettingsStore(
    context: Context,
    /**
     * Serializes all `known_apps` persistence off the main thread (#100).
     * Injectable so tests can run the learner synchronously.
     */
    private val learnerExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "known-app-writer").apply { isDaemon = true }
    }
) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val knownAppsPrefs =
        context.getSharedPreferences(KNOWN_APPS_PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * In-memory copy of the `known_apps` map, lazily loaded from disk once.
     * Mutated only under [this] lock; volatile so readers never see a torn view.
     */
    @Volatile
    private var knownAppsCache: Map<String, KnownApp>? = null

    /**
     * In-memory most-recently-used order of learned apps (#102). Bumped on
     * every successful dictation so the just-used app is always first, even
     * inside the 24 h throttle window. Not persisted on its own: across a
     * process restart the order falls back to the stored `lastSeenMs`.
     * Immutable + volatile (copy-on-write), mirroring [knownAppsCache].
     */
    @Volatile
    private var mruOrder: List<String> = emptyList()

    var sttProvider: SttProviderConfig?
        get() = getJson(STT_PROVIDER_KEY, SttProviderConfig::class.java)
        set(value) = setJson(STT_PROVIDER_KEY, value)

    var llmProvider: LlmProviderConfig?
        get() = getJson(LLM_PROVIDER_KEY, LlmProviderConfig::class.java)
        set(value) = setJson(LLM_PROVIDER_KEY, value)

    var rawMode: Boolean
        get() = prefs.getBoolean(RAW_MODE_KEY, false)
        set(value) = prefs.edit().putBoolean(RAW_MODE_KEY, value).apply()

    var compressAudio: Boolean
        get() = prefs.getBoolean(COMPRESS_AUDIO_KEY, false)
        set(value) = prefs.edit().putBoolean(COMPRESS_AUDIO_KEY, value).apply()

    var targetLanguage: String?
        get() = prefs.getString(TARGET_LANGUAGE_KEY, null)
        set(value) = prefs.edit().putString(TARGET_LANGUAGE_KEY, value).apply()

    /**
     * Output line-wrap width in characters (#81). 0 (or negative) disables
     * wrapping entirely; otherwise the final transcription text is word-wrapped
     * to this width. Default 80.
     */
    var wrapWidth: Int
        get() = prefs.getInt(WRAP_WIDTH_KEY, DEFAULT_WRAP_WIDTH)
        set(value) = prefs.edit().putInt(WRAP_WIDTH_KEY, value).apply()

    var customLanguages: List<String>
        get() = prefs.getString(CUSTOM_LANGUAGES_KEY, null)?.let {
            gson.fromJson(it, object : TypeToken<List<String>>() {}.type)
        } ?: emptyList()
        set(value) = prefs.edit().putString(CUSTOM_LANGUAGES_KEY, gson.toJson(value)).apply()

    /**
     * Manual fragment-size override in seconds (#116 Phase 2). `null` = auto
     * (the per-provider latency profile decides); a value ≥ 1 fixes the
     * fragment size and suspends the auto-sizer for every provider.
     */
    var fragmentSecondsOverride: Float?
        get() = prefs.getString(FRAGMENT_SECONDS_OVERRIDE_KEY, null)?.toFloatOrNull()
        set(value) = prefs.edit().putString(FRAGMENT_SECONDS_OVERRIDE_KEY, value?.toString()).apply()

    /**
     * Learned dictation targets (#100), most-recently-used first. Apps used in
     * this process come first in true MRU order (#102); the remaining apps fall
     * back to the stored `lastSeenMs` (descending). MRU entries no longer in the
     * known map (e.g. forgotten in the meantime) are dropped defensively.
     */
    fun knownAppsByRecency(): List<Pair<String, KnownApp>> {
        val known = loadKnownApps()
        val ordered = linkedMapOf<String, KnownApp>()
        mruOrder.forEach { pkg -> known[pkg]?.let { ordered[pkg] = it } }
        known.entries.sortedByDescending { it.value.lastSeenMs }
            .forEach { (pkg, app) -> if (!ordered.containsKey(pkg)) ordered[pkg] = app }
        return ordered.map { it.key to it.value }
    }

    /**
     * Resolved line-wrap width for [packageName] (#100): the per-app override
     * when set, otherwise the global [wrapWidth]. A `null` caller (bound
     * service without a resolvable caller) always uses the global width.
     */
    fun wrapWidthFor(packageName: String?): Int =
        if (packageName == null) wrapWidth
        else loadKnownApps()[packageName]?.wrapWidth ?: wrapWidth

    /**
     * Learns that [packageName] is a dictation target (#100). Called on the
     * insertion hot path, so the method itself only does an in-memory check
     * and — only when the last dictation in this app is older than
     * [KNOWN_APP_REWRITE_INTERVAL_MS] (or the app is new) — enqueues the
     * persistence on [learnerExecutor]. Label resolution and the JSON write
     * never touch the caller's thread; failures are logged only.
     *
     * Independently of the throttle, the app is always moved to the front of
     * the in-memory MRU order (#102), so the dropdown reflects the last-used
     * app immediately.
     */
    fun recordKnownApp(packageName: String) {
        if (packageName.isBlank() || packageName == appContext.packageName) return
        bumpMru(packageName)
        val now = System.currentTimeMillis()
        val apps = loadKnownApps()
        val existing = apps[packageName]
        if (existing != null && now - existing.lastSeenMs < KNOWN_APP_REWRITE_INTERVAL_MS) return
        learnerExecutor.execute { persistKnownApp(packageName, now) }
    }

    /** Removes a learned app (and its override) (#100); persists in background. */
    fun forgetKnownApp(packageName: String) {
        mruOrder = mruOrder.filterNot { it == packageName }
        learnerExecutor.execute {
            runCatching {
                val merged = loadKnownApps() - packageName
                knownAppsCache = merged
                knownAppsPrefs.edit().putString(KNOWN_APPS_KEY, gson.toJson(merged)).apply()
            }.onFailure { Log.w(TAG, "forgetKnownApp failed for $packageName", it) }
        }
    }

    /**
     * Persists per-app overrides edited in Settings (#100). Only the widths of
     * apps in [widths] are changed; apps learned in the background are kept.
     */
    fun saveKnownAppWidths(widths: Map<String, Int?>) {
        learnerExecutor.execute {
            runCatching {
                val merged = loadKnownApps().mapValues { (pkg, app) ->
                    if (widths.containsKey(pkg)) app.copy(wrapWidth = widths[pkg]) else app
                }
                knownAppsCache = merged
                knownAppsPrefs.edit().putString(KNOWN_APPS_KEY, gson.toJson(merged)).apply()
            }.onFailure { Log.w(TAG, "saveKnownAppWidths failed", it) }
        }
    }

    /** Moves [packageName] to the front of the in-memory MRU list (#102). */
    private fun bumpMru(packageName: String) {
        mruOrder = listOf(packageName) + mruOrder.filterNot { it == packageName }
    }

    private fun persistKnownApp(packageName: String, seenAtMs: Long) {
        runCatching {
            val label = resolveAppLabel(packageName)
            val current = loadKnownApps()
            val merged = current + (packageName to KnownApp(
                label = label,
                lastSeenMs = seenAtMs,
                wrapWidth = current[packageName]?.wrapWidth
            ))
            knownAppsCache = merged
            knownAppsPrefs.edit().putString(KNOWN_APPS_KEY, gson.toJson(merged)).apply()
        }.onFailure { Log.w(TAG, "recordKnownApp failed for $packageName", it) }
    }

    private fun resolveAppLabel(packageName: String): String = try {
        val pm = appContext.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (_: Exception) {
        packageName
    }

    @Synchronized
    private fun loadKnownApps(): Map<String, KnownApp> {
        knownAppsCache?.let { return it }
        val loaded = knownAppsPrefs.getString(KNOWN_APPS_KEY, null)?.let { json ->
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, object : TypeToken<Map<String, KnownApp>>() {}.type)
                as? Map<String, KnownApp>
        } ?: emptyMap()
        knownAppsCache = loaded
        return loaded
    }

    fun setSttModels(baseUrl: String, models: List<String>) {
        setCachedModels(STT_MODEL_LISTS_KEY, baseUrl, models)
    }

    fun getSttModels(baseUrl: String): List<String> = getCachedModels(STT_MODEL_LISTS_KEY, baseUrl)

    fun setLlmModels(baseUrl: String, models: List<String>) {
        setCachedModels(LLM_MODEL_LISTS_KEY, baseUrl, models)
    }

    fun getLlmModels(baseUrl: String): List<String> = getCachedModels(LLM_MODEL_LISTS_KEY, baseUrl)

    private fun setCachedModels(mapKey: String, baseUrl: String, models: List<String>) {
        val cache = readModelCache(mapKey)
        cache[baseUrl] = CachedModels(System.currentTimeMillis(), models)
        prefs.edit().putString(mapKey, gson.toJson(cache)).apply()
    }

    private fun getCachedModels(mapKey: String, baseUrl: String): List<String> {
        migrateLegacyModelList(mapKey)
        val cache = readModelCache(mapKey)
        val entry = cache[baseUrl] ?: return emptyList()
        if (System.currentTimeMillis() - entry.timestamp > MODEL_CACHE_TTL_MS) {
            cache.remove(baseUrl)
            prefs.edit().putString(mapKey, gson.toJson(cache)).apply()
            return emptyList()
        }
        return entry.models
    }

    private fun readModelCache(mapKey: String): MutableMap<String, CachedModels> {
        val json = prefs.getString(mapKey, null) ?: return mutableMapOf()
        return gson.fromJson(json, object : TypeToken<MutableMap<String, CachedModels>>() {}.type)
            ?: mutableMapOf()
    }

    private fun migrateLegacyModelList(mapKey: String) {
        val legacyKey = when (mapKey) {
            STT_MODEL_LISTS_KEY -> STT_MODEL_LIST_KEY
            else -> LLM_MODEL_LIST_KEY
        }
        if (prefs.getString(legacyKey, null) == null) return
        val legacyModels: List<String> =
            gson.fromJson(prefs.getString(legacyKey, null), object : TypeToken<List<String>>() {}.type)
        val baseUrl = when (mapKey) {
            STT_MODEL_LISTS_KEY -> getJson(STT_PROVIDER_KEY, SttProviderConfig::class.java)?.baseUrl
            else -> getJson(LLM_PROVIDER_KEY, LlmProviderConfig::class.java)?.baseUrl
        }
        val cache = readModelCache(mapKey)
        if (baseUrl != null && legacyModels.isNotEmpty()) {
            cache[baseUrl] = CachedModels(System.currentTimeMillis(), legacyModels)
            prefs.edit().putString(mapKey, gson.toJson(cache)).apply()
        }
        prefs.edit().remove(legacyKey).apply()
    }

    /**
     * The STT latency profile for `baseUrl + model + mediaType` (#116
     * Phase 2), or `null` when nothing was measured yet. Media types are
     * separated so OGG/WAV latencies never mix into one fit.
     */
    @Synchronized
    fun sttProfile(baseUrl: String, model: String, mediaType: String): SttProviderProfile? =
        readSttProfiles()[sttProfileKey(baseUrl, model, mediaType)]

    /**
     * Records one successful STT request as a profile sample (#116 Phase 2),
     * keeping the newest [SttLatencyProfile.MAX_SAMPLES] per profile. Called
     * from the request runner's completion hook on IO threads — hence the
     * synchronized read-modify-write.
     */
    @Synchronized
    fun recordSttSample(
        baseUrl: String,
        model: String,
        mediaType: String,
        durationMs: Long,
        elapsedMs: Long
    ) {
        val key = sttProfileKey(baseUrl, model, mediaType)
        val profiles = readSttProfiles()
        val existing = profiles[key] ?: SttProviderProfile()
        profiles[key] = existing.copy(
            samples = (existing.samples + SttProfileSample(durationMs, elapsedMs))
                .takeLast(SttLatencyProfile.MAX_SAMPLES)
        )
        writeSttProfiles(profiles)
    }

    /**
     * Records the fragment size (seconds) the auto-sizer APPLIED for a
     * profile — the anchor of the stepwise adaptation (#116 Phase 2).
     */
    @Synchronized
    fun setSttProfileAppliedSeconds(
        baseUrl: String,
        model: String,
        mediaType: String,
        seconds: Double
    ) {
        val key = sttProfileKey(baseUrl, model, mediaType)
        val profiles = readSttProfiles()
        val existing = profiles[key] ?: SttProviderProfile()
        profiles[key] = existing.copy(lastAppliedSeconds = seconds)
        writeSttProfiles(profiles)
    }

    /** Wipes every learned profile (Settings "reset" path, #116 Phase 2). */
    @Synchronized
    fun clearSttProfiles() {
        prefs.edit().remove(STT_PROFILES_KEY).apply()
    }

    private fun readSttProfiles(): MutableMap<String, SttProviderProfile> {
        // A version mismatch discards all samples: pre-#119 profiles carry the
        // bogus ~1000 ms durations measured from the corrupt WAV pre-roll
        // header, which would otherwise poison the auto-sizer's fit (#119).
        if (prefs.getInt(STT_PROFILES_VERSION_KEY, 0) != STT_PROFILES_VERSION) {
            prefs.edit()
                .remove(STT_PROFILES_KEY)
                .putInt(STT_PROFILES_VERSION_KEY, STT_PROFILES_VERSION)
                .apply()
            return mutableMapOf()
        }
        val json = prefs.getString(STT_PROFILES_KEY, null) ?: return mutableMapOf()
        return gson.fromJson(json, object : TypeToken<MutableMap<String, SttProviderProfile>>() {}.type)
            ?: mutableMapOf()
    }

    private fun writeSttProfiles(profiles: MutableMap<String, SttProviderProfile>) {
        prefs.edit()
            .putString(STT_PROFILES_KEY, gson.toJson(profiles))
            .putInt(STT_PROFILES_VERSION_KEY, STT_PROFILES_VERSION)
            .apply()
    }

    private fun sttProfileKey(baseUrl: String, model: String, mediaType: String): String =
        "$baseUrl|$model|$mediaType"

    private fun <T> getJson(key: String, clazz: Class<T>): T? {
        val json = prefs.getString(key, null) ?: return null
        return gson.fromJson(json, clazz)
    }

    private fun setJson(key: String, value: Any?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, gson.toJson(value)).apply()
        }
    }

    companion object {
        private const val TAG = "SettingsStore"
        private const val PREFS_NAME = "polished_recognition_settings"
        /**
         * Learned dictation targets live in their own file (#100) so the
         * frequent learner writes and the OS backup stay isolated from the
         * provider/prompt configuration.
         */
        private const val KNOWN_APPS_PREFS_NAME = "known_apps"
        private const val KNOWN_APPS_KEY = "known_apps"
        /** Minimum valid wrap width; 0 disables wrapping (#81/#100). */
        const val MIN_WRAP_WIDTH = 20
        /** The learner write task is only started after this idle period (#100). */
        const val KNOWN_APP_REWRITE_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val STT_PROVIDER_KEY = "stt_provider"
        private const val LLM_PROVIDER_KEY = "llm_provider"
        private const val STT_MODEL_LIST_KEY = "stt_model_list"
        private const val LLM_MODEL_LIST_KEY = "llm_model_list"
        private const val STT_MODEL_LISTS_KEY = "stt_model_lists"
        private const val LLM_MODEL_LISTS_KEY = "llm_model_lists"
        private const val RAW_MODE_KEY = "raw_mode"
        private const val COMPRESS_AUDIO_KEY = "compress_audio"
        private const val TARGET_LANGUAGE_KEY = "target_language"
        private const val CUSTOM_LANGUAGES_KEY = "custom_languages"
        private const val WRAP_WIDTH_KEY = "wrap_width"
        private const val FRAGMENT_SECONDS_OVERRIDE_KEY = "fragment_seconds_override"
        private const val STT_PROFILES_KEY = "stt_profiles"
        private const val STT_PROFILES_VERSION_KEY = "stt_profiles_version"

        /**
         * Bumped when a measurement fix invalidates stored samples. #119: the
         * WAV pre-roll byte-concat made every fragment upload decode as its
         * 1-s pre-roll, so `durationMs` was recorded as ~1000 for every
         * fragment and poisoned the auto-sizer's fit. A mismatch discards all
         * learned profiles (they re-bootstrap in ~4 min of dictation).
         */
        private const val STT_PROFILES_VERSION = 2

        /** 6 weeks in milliseconds. */
        const val MODEL_CACHE_TTL_MS = 42L * 24 * 60 * 60 * 1000

        /** Default output line-wrap width (#81); 0 disables wrapping. */
        const val DEFAULT_WRAP_WIDTH = 80
    }
}
