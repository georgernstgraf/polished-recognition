package com.georgernstgraf.polishedrecognition.config

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.georgernstgraf.polishedrecognition.dictation.DictationStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class LlmProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val apiToken: String,
    val model: String,
    /**
     * #153: the reasoning effort sent as `reasoning_effort` on every LLM chat
     * request — `null` = send nothing (the provider's own default applies).
     * A value may only be stored when the configured model's fetched
     * capability list advertises it (the ProvidersActivity UI enforces that
     * invariant, [SettingsValidation.apply] normalizes defensively).
     */
    val reasoningEffort: String? = null
)

data class CachedModels(
    val timestamp: Long,
    val models: List<String>,
    /**
     * #153: per-model reasoning effort levels as communicated by the
     * provider's `/models` response (`capabilities.reasoning.effort`), keyed
     * by model id. Nullable on purpose: a pre-#153 cache JSON carries no such
     * member and Gson bypasses the Kotlin default (deserializes a non-null
     * declaration to null — see the #101 comment on [readProvider]), so every
     * reader normalizes through `orEmpty()`.
     */
    val reasoningEfforts: Map<String, List<String>>? = null
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
    },
    /**
     * Default of the audio-evidence RETENTION horizon (#142): 0 seconds (no
     * retention) in release — it persists dictated audio on disk; the
     * owner's test builds default to 30 days — wired from
     * `BuildConfig.AUDIO_EVIDENCE_RETENTION_DEFAULT_SECONDS` in
     * `PolishedRecognitionApp`. Injectable so the Settings tests can pin it.
     */
    private val evidenceRetentionDefaultSeconds: Long = 0L
) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * #101: API tokens — EXCLUDED from OS backup both ways (see
     * `res/xml/backup_rules.xml` + `res/xml/data_extraction_rules.xml`). A
     * separate file, not a separate key: backup exclusion works per FILE.
     */
    private val secretsPrefs =
        context.getSharedPreferences(SECRETS_PREFS_NAME, Context.MODE_PRIVATE)
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

    /**
     * #101: the provider configs keep EVERYTHING in the backed-up settings
     * file (endpoint, model, display name — so a device change still restores
     * a usable setup) except the **API token**, which lives in the separate
     * [SECRETS_PREFS_NAME] file. Both backup mechanisms exclude that file
     * (`res/xml/backup_rules.xml` for API ≤ 30,
     * `res/xml/data_extraction_rules.xml` for API 31+), so a token never
     * leaves the device via Google Auto Backup or device-to-device transfer.
     *
     * A pre-#101 install still carries the token INSIDE the settings JSON —
     * reading it migrates the token into the secret file and rewrites the
     * backed-up JSON without it ([readProvider]).
     *
     * The split happens on the JSON tree, never via `data class copy()`: a
     * legacy JSON without an `id` member deserializes to a null `id` field
     * (Gson bypasses the Kotlin default), and `copy()` would throw on the
     * non-null parameter check.
     */
    var sttProvider: SttProviderConfig?
        get() = readProvider(STT_PROVIDER_KEY, STT_TOKEN_KEY, SttProviderConfig::class.java)
        set(value) = writeProvider(STT_PROVIDER_KEY, STT_TOKEN_KEY, value)

    var llmProvider: LlmProviderConfig?
        get() = readProvider(LLM_PROVIDER_KEY, LLM_TOKEN_KEY, LlmProviderConfig::class.java)
        set(value) = writeProvider(LLM_PROVIDER_KEY, LLM_TOKEN_KEY, value)

    /**
     * #153: a stable per-install session id, sent as `x-opencode-session` —
     * exclusively on requests to the OpenCode GO endpoint
     * (`opencode.ai/inference/go/…`), which answers HTTP 400 without it
     * (device finding 2026-11-26). Created once and persisted here; the
     * workspace is already identified by the API key, so this is a
     * routing/prompt-cache hint, not an additional identity. Not a
     * #200-style hot-path getter: the first read happens when the chat
     * client is built.
     */
    val opencodeSessionId: String
        get() {
            prefs.getString(OPENCODE_SESSION_ID_KEY, null)?.let { return it }
            val created = java.util.UUID.randomUUID().toString()
            prefs.edit { putString(OPENCODE_SESSION_ID_KEY, created) }
            return created
        }

    var rawMode: Boolean
        get() = prefs.getBoolean(RAW_MODE_KEY, false)
        set(value) = prefs.edit { putBoolean(RAW_MODE_KEY, value) }

    var compressAudio: Boolean
        get() = prefs.getBoolean(COMPRESS_AUDIO_KEY, false)
        set(value) = prefs.edit { putBoolean(COMPRESS_AUDIO_KEY, value) }

    /**
     * #205 D1: the LIVE PREVIEW kill switch — the dictation grows in the target
     * field through the composing region while it is transcribed. Read once at
     * session start; OFF means zero composing writes and a terminal step that
     * is byte-identical to the world before the feature. Default ON.
     */
    var imeLivePreview: Boolean
        get() = prefs.getBoolean(IME_LIVE_PREVIEW_KEY, true)
        set(value) = prefs.edit { putBoolean(IME_LIVE_PREVIEW_KEY, value) }

    /**
     * #166 D2: how many Workbench dictations the FIFO keeps (1..10, default 3).
     * Settable in the Advanced settings section (#172); read by
     * [com.georgernstgraf.polishedrecognition.dictation.DictationStore] on
     * every append and prune, so a change applies immediately.
     */
    var workbenchFifoSize: Int
        get() = prefs.getInt(WORKBENCH_FIFO_SIZE_KEY, DictationStore.DEFAULT_FIFO_SIZE)
            .coerceIn(DictationStore.MIN_FIFO_SIZE, DictationStore.MAX_FIFO_SIZE)
        set(value) = prefs.edit {
            putInt(
                WORKBENCH_FIFO_SIZE_KEY,
                value.coerceIn(DictationStore.MIN_FIFO_SIZE, DictationStore.MAX_FIFO_SIZE)
            )
        }

    /**
     * #142: audio-evidence RETENTION HORIZON in seconds — how long a finished
     * dictation's forensic bundle (`files/audio/<sessionId>/` = `uploads/` +
     * `session.json`) stays on the device before the age-based eviction
     * deletes it. **0 = no retention** (nothing is written, the complete
     * off). Replaces the #127 boolean toggle; the legacy `audio_evidence`
     * pref migrates: `true` → 30 days (the wide-diagnostic intent), `false`
     * → 0. Default OFF in release (privacy — dictated audio on disk); the
     * owner's test builds default ON (30 days).
     *
     * **#200: this getter is a PURE READ unless a legacy key is migrated.**
     * Merely resolving the default must not materialise
     * [EVIDENCE_RETENTION_KEY], because the getter is read from threads that
     * do not own the setting (`AudioEvidenceStore.evict()` on the app's
     * startup maintenance thread). A write there raced the unit tests'
     * `clear()` and resurrected the debug default — `SettingsStoreTest`
     * flaked with `expected: 0 but was : 2592000`.
     */
    var evidenceRetentionSeconds: Long
        get() {
            if (prefs.contains(EVIDENCE_RETENTION_KEY)) {
                return prefs.getLong(EVIDENCE_RETENTION_KEY, 0L).coerceAtLeast(0L)
            }
            // No legacy toggle either: honour the build-type default WITHOUT
            // persisting it — the default stays live until the user chooses
            // (#200).
            if (!prefs.contains(AUDIO_EVIDENCE_KEY)) {
                return evidenceRetentionDefaultSeconds.coerceAtLeast(0L)
            }
            // One-time legacy migration (#127 → #142): the old boolean toggle
            // maps ON → 30 days, OFF → 0; persisted so the migration is done.
            val migrated = if (prefs.getBoolean(AUDIO_EVIDENCE_KEY, false)) {
                THIRTY_DAYS_SECONDS
            } else {
                0L
            }
            prefs.edit { putLong(EVIDENCE_RETENTION_KEY, migrated) }
            return migrated
        }
        set(value) = prefs.edit {
            putLong(EVIDENCE_RETENTION_KEY, value.coerceAtLeast(0L))
        }

    /**
     * Derived master gate (the #127 contract, kept for the existing wiring —
     * controller, transcriber): evidence is collected exactly when
     * a retention horizon is set.
     */
    val audioEvidence: Boolean
        get() = evidenceRetentionSeconds > 0L

    var targetLanguage: String?
        get() = prefs.getString(TARGET_LANGUAGE_KEY, null)
        set(value) = prefs.edit { putString(TARGET_LANGUAGE_KEY, value) }

    /**
     * Output line-wrap width in characters (#81). 0 (or negative) disables
     * wrapping entirely; otherwise the final transcription text is word-wrapped
     * to this width. Default 80.
     */
    var wrapWidth: Int
        get() = prefs.getInt(WRAP_WIDTH_KEY, DEFAULT_WRAP_WIDTH)
        set(value) = prefs.edit { putInt(WRAP_WIDTH_KEY, value) }

    var customLanguages: List<String>
        get() = prefs.getString(CUSTOM_LANGUAGES_KEY, null)?.let {
            gson.fromJson(it, object : TypeToken<List<String>>() {}.type)
        } ?: emptyList()
        set(value) = prefs.edit { putString(CUSTOM_LANGUAGES_KEY, gson.toJson(value)) }

    /**
     * Manual SLICE override in seconds (#124). `null` = auto (the
     * per-provider cost model decides); a value ≥ 1 fixes the slice — and
     * therefore a fragment's MAXIMUM length — for every provider.
     *
     * Migration (#124): the old `fragment_seconds_override` was the nominal
     * boundary of the pre-#124 model, whose real fragment was `2 ×` that
     * (search = S, late half). It is read once, doubled, and written here so
     * the measured behaviour does not jump when the model is renamed.
     */
    var sliceSecondsOverride: Float?
        get() {
            prefs.getString(SLICE_SECONDS_OVERRIDE_KEY, null)?.toFloatOrNull()?.let { return it }
            val legacy = prefs.getString(LEGACY_FRAGMENT_SECONDS_OVERRIDE_KEY, null)
                ?.toFloatOrNull() ?: return null
            val migrated = legacy * 2f
            prefs.edit {
                putString(SLICE_SECONDS_OVERRIDE_KEY, migrated.toString())
                remove(LEGACY_FRAGMENT_SECONDS_OVERRIDE_KEY)
            }
            return migrated
        }
        set(value) = prefs.edit {
            putString(SLICE_SECONDS_OVERRIDE_KEY, value?.toString())
            remove(LEGACY_FRAGMENT_SECONDS_OVERRIDE_KEY)
        }

    /**
     * Manual search-window percentage override (#124): the last N % of the
     * slice in which a cut may land. `null` = auto (the adaptive forced-rate
     * rule decides).
     */
    var searchPercentOverride: Float?
        get() = prefs.getString(SEARCH_PERCENT_OVERRIDE_KEY, null)?.toFloatOrNull()
        set(value) = prefs.edit { putString(SEARCH_PERCENT_OVERRIDE_KEY, value?.toString()) }

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
                knownAppsPrefs.edit { putString(KNOWN_APPS_KEY, gson.toJson(merged)) }
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
                knownAppsPrefs.edit { putString(KNOWN_APPS_KEY, gson.toJson(merged)) }
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
            knownAppsPrefs.edit { putString(KNOWN_APPS_KEY, gson.toJson(merged)) }
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

    /**
     * #153: [reasoningEfforts] carries the per-model effort levels the
     * provider communicated in the same fetch; an empty map (the default)
     * keeps the STT-style call sites and older tests unchanged.
     */
    fun setLlmModels(
        baseUrl: String,
        models: List<String>,
        reasoningEfforts: Map<String, List<String>> = emptyMap()
    ) {
        setCachedModels(LLM_MODEL_LISTS_KEY, baseUrl, models, reasoningEfforts)
    }

    fun getLlmModels(baseUrl: String): List<String> = getCachedModels(LLM_MODEL_LISTS_KEY, baseUrl)

    /**
     * #153: the effort levels the provider advertised for each model at this
     * endpoint, or an empty map when the cache holds no capability info
     * (pre-#153 cache, a provider without the extension, or an expired
     * entry). The expiry behaves exactly like [getLlmModels] — an expired
     * entry is dropped for both readers alike.
     */
    fun getLlmReasoningEfforts(baseUrl: String): Map<String, List<String>> {
        migrateLegacyModelList(LLM_MODEL_LISTS_KEY)
        val cache = readModelCache(LLM_MODEL_LISTS_KEY)
        val entry = cache[baseUrl] ?: return emptyMap()
        if (System.currentTimeMillis() - entry.timestamp > MODEL_CACHE_TTL_MS) {
            cache.remove(baseUrl)
            prefs.edit { putString(LLM_MODEL_LISTS_KEY, gson.toJson(cache)) }
            return emptyMap()
        }
        return entry.reasoningEfforts.orEmpty()
    }

    private fun setCachedModels(
        mapKey: String,
        baseUrl: String,
        models: List<String>,
        reasoningEfforts: Map<String, List<String>> = emptyMap()
    ) {
        val cache = readModelCache(mapKey)
        cache[baseUrl] = CachedModels(
            timestamp = System.currentTimeMillis(),
            models = models,
            reasoningEfforts = reasoningEfforts.ifEmpty { null }
        )
        prefs.edit { putString(mapKey, gson.toJson(cache)) }
    }

    private fun getCachedModels(mapKey: String, baseUrl: String): List<String> {
        migrateLegacyModelList(mapKey)
        val cache = readModelCache(mapKey)
        val entry = cache[baseUrl] ?: return emptyList()
        if (System.currentTimeMillis() - entry.timestamp > MODEL_CACHE_TTL_MS) {
            cache.remove(baseUrl)
            prefs.edit { putString(mapKey, gson.toJson(cache)) }
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
            STT_MODEL_LISTS_KEY -> sttProvider?.baseUrl
            else -> llmProvider?.baseUrl
        }
        val cache = readModelCache(mapKey)
        if (baseUrl != null && legacyModels.isNotEmpty()) {
            cache[baseUrl] = CachedModels(System.currentTimeMillis(), legacyModels)
            prefs.edit { putString(mapKey, gson.toJson(cache)) }
        }
        prefs.edit { remove(legacyKey) }
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
     * #124: records one LOCAL ENCODE cost sample (fragment duration vs
     * encode time) for a profile. Fed by the preparer's commit hook; the
     * ratio is what the slice search needs to price OGG correctly.
     */
    @Synchronized
    fun recordEncodeSample(
        baseUrl: String,
        model: String,
        mediaType: String,
        audioMs: Long,
        encodeMs: Long
    ) {
        if (audioMs <= 0 || encodeMs < 0) return
        val key = sttProfileKey(baseUrl, model, mediaType)
        val profiles = readSttProfiles()
        val existing = profiles[key] ?: SttProviderProfile()
        profiles[key] = existing.copy(
            encodeMsTotal = existing.encodeMsTotal + encodeMs,
            encodeAudioMsTotal = existing.encodeAudioMsTotal + audioMs
        )
        writeSttProfiles(profiles)
    }

    /**
     * #124: counts one searched cut for a profile — `forced` when the search
     * window held no silence candidate. Drives the adaptive search-window
     * percentage.
     */
    @Synchronized
    fun recordSeam(
        baseUrl: String,
        model: String,
        mediaType: String,
        forced: Boolean
    ) {
        val key = sttProfileKey(baseUrl, model, mediaType)
        val profiles = readSttProfiles()
        val existing = profiles[key] ?: SttProviderProfile()
        profiles[key] = existing.copy(
            seamsTotal = existing.seamsTotal + 1,
            seamsForced = existing.seamsForced + if (forced) 1 else 0
        )
        writeSttProfiles(profiles)
    }

    /**
     * #124: persists the search-window percentage the adaptive rule moved to
     * and RE-ANCHORS the measurement window at the current seam counters
     * (only seams after the step count towards the next one).
     */
    @Synchronized
    fun setSttProfileSearchPercent(
        baseUrl: String,
        model: String,
        mediaType: String,
        percent: Double
    ) {
        val key = sttProfileKey(baseUrl, model, mediaType)
        val profiles = readSttProfiles()
        val existing = profiles[key] ?: SttProviderProfile()
        profiles[key] = existing.copy(
            searchPercent = percent,
            seamsTotalAtStep = existing.seamsTotal,
            seamsForcedAtStep = existing.seamsForced
        )
        writeSttProfiles(profiles)
    }

    /** Wipes every learned profile (Settings "reset" path, #116 Phase 2). */
    @Synchronized
    fun clearSttProfiles() {
        prefs.edit { remove(STT_PROFILES_KEY) }
    }

    private fun readSttProfiles(): MutableMap<String, SttProviderProfile> {
        // A version mismatch discards all samples: pre-#119 profiles carry the
        // bogus ~1000 ms durations measured from the corrupt WAV pre-roll
        // header, which would otherwise poison the auto-sizer's fit (#119).
        if (prefs.getInt(STT_PROFILES_VERSION_KEY, 0) != STT_PROFILES_VERSION) {
            prefs.edit {
                remove(STT_PROFILES_KEY)
                putInt(STT_PROFILES_VERSION_KEY, STT_PROFILES_VERSION)
            }
            return mutableMapOf()
        }
        val json = prefs.getString(STT_PROFILES_KEY, null) ?: return mutableMapOf()
        return gson.fromJson(json, object : TypeToken<MutableMap<String, SttProviderProfile>>() {}.type)
            ?: mutableMapOf()
    }

    private fun writeSttProfiles(profiles: MutableMap<String, SttProviderProfile>) {
        prefs.edit {
            putString(STT_PROFILES_KEY, gson.toJson(profiles))
            putInt(STT_PROFILES_VERSION_KEY, STT_PROFILES_VERSION)
        }
    }

    private fun sttProfileKey(baseUrl: String, model: String, mediaType: String): String =
        "$baseUrl|$model|$mediaType"

    private fun <T> getJson(key: String, clazz: Class<T>): T? {
        val json = prefs.getString(key, null) ?: return null
        return gson.fromJson(json, clazz)
    }

    private fun setJson(key: String, value: Any?) {
        if (value == null) {
            prefs.edit { remove(key) }
        } else {
            prefs.edit { putString(key, gson.toJson(value)) }
        }
    }

    /**
     * #101: reads a provider config whose stored JSON carries no token. The
     * token comes from the backup-excluded file; a pre-#101 JSON that still
     * embeds one is migrated on the spot and the stored copy is scrubbed, so
     * no token ever survives in a file the OS backs up.
     *
     * #214: resolution order is the per-type active token first (the last
     * applied credential — it must keep winning at request time even when a
     * validated vault entry for the same URL is older), then the per-URL
     * vault. A pre-#214 install (only the per-type key exists) is migrated by
     * seeding the vault with the token that has been in effect for this URL,
     * so the first provider switch already finds it.
     */
    private fun <T> readProvider(key: String, secretKey: String, clazz: Class<T>): T? {
        val raw = prefs.getString(key, null) ?: return null
        val obj = JsonParser().parse(raw).asJsonObject
        val legacyToken = obj.get("apiToken")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val canonical = canonicalProviderUrl(
            obj.get("baseUrl")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        )
        val vaultToken = if (canonical.isEmpty()) null else readTokenVault()[canonical]
        val storedToken = secretsPrefs.getString(secretKey, null) ?: vaultToken
        if (legacyToken.isNotEmpty()) {
            if (storedToken == null) storeToken(secretKey, legacyToken)
            val scrubbed = JsonParser().parse(raw).asJsonObject
            scrubbed.addProperty("apiToken", "")
            prefs.edit { putString(key, gson.toJson(scrubbed)) }
        }
        val token = storedToken ?: legacyToken
        if (canonical.isNotEmpty() && token.isNotEmpty() && !readTokenVault().containsKey(canonical)) {
            seedVaultToken(canonical, token)
        }
        obj.addProperty("apiToken", token)
        return gson.fromJson(obj, clazz)
    }

    /**
     * #101: writes a provider config with the token split out — the settings
     * file keeps the restorable half, the secret file the credential. An
     * empty token removes the secret instead of storing a blank one.
     *
     * #214: the token is ALSO filed into the per-URL vault (the credential
     * last in effect for this endpoint). Clearing it deliberately drops the
     * vault entry for that URL — unless the OTHER provider type still points
     * at the same endpoint, whose credential memory must survive.
     */
    private fun <T> writeProvider(key: String, secretKey: String, value: T?) {
        val otherCanonical = canonicalProviderUrl(storedBaseUrlOf(otherProviderKey(key)).orEmpty())
        if (value == null) {
            val oldCanonical = canonicalProviderUrl(storedBaseUrlOf(key).orEmpty())
            prefs.edit { remove(key) }
            storeToken(secretKey, "")
            if (oldCanonical.isNotEmpty() && oldCanonical != otherCanonical) {
                seedVaultToken(oldCanonical, "")
            }
            return
        }
        val obj = gson.toJsonTree(value).asJsonObject
        val token = obj.get("apiToken")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val canonical = canonicalProviderUrl(
            obj.get("baseUrl")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        )
        obj.addProperty("apiToken", "")
        prefs.edit { putString(key, gson.toJson(obj)) }
        storeToken(secretKey, token)
        if (canonical.isNotEmpty() && (token.isNotEmpty() || canonical != otherCanonical)) {
            seedVaultToken(canonical, token)
        }
    }

    /** The other provider type's key — the vault is shared between STT and LLM (#214). */
    private fun otherProviderKey(key: String): String =
        if (key == STT_PROVIDER_KEY) LLM_PROVIDER_KEY else STT_PROVIDER_KEY

    /** The raw (unresolved) `baseUrl` of the stored provider JSON, if any (#214). */
    private fun storedBaseUrlOf(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return JsonParser().parse(raw).asJsonObject
            .get("baseUrl")?.takeIf { !it.isJsonNull }?.asString
    }

    /**
     * #214: the token remembered for this provider URL in the vault, or
     * `null`. Trailing-slash and case differences resolve to the same entry.
     * Read by the Providers screen when the endpoint URL changes: the field
     * is refilled from here (or emptied) — an unvalidated token the user
     * fiddled in never survives a provider switch (owner decision #214).
     */
    fun vaultTokenFor(baseUrl: String): String? {
        val canonical = canonicalProviderUrl(baseUrl)
        if (canonical.isEmpty()) return null
        return readTokenVault()[canonical]
    }

    @Synchronized
    private fun readTokenVault(): LinkedHashMap<String, String> {
        val json = secretsPrefs.getString(TOKEN_VAULT_KEY, null) ?: return LinkedHashMap()
        return gson.fromJson(json, object : TypeToken<LinkedHashMap<String, String>>() {}.type)
            ?: LinkedHashMap()
    }

    /** Files (or drops, when blank) one vault entry, keeping the cap. */
    @Synchronized
    private fun seedVaultToken(canonicalUrl: String, token: String) {
        val vault = readTokenVault()
        if (token.isEmpty()) vault.remove(canonicalUrl) else vault[canonicalUrl] = token
        while (vault.size > TOKEN_VAULT_CAPACITY) vault.remove(vault.keys.first())
        secretsPrefs.edit { putString(TOKEN_VAULT_KEY, gson.toJson(vault)) }
    }

    /** Writes (or removes, when blank) a token in the backup-excluded file. */
    private fun storeToken(secretKey: String, token: String) {
        secretsPrefs.edit {
            if (token.isEmpty()) remove(secretKey) else putString(secretKey, token)
        }
    }

    companion object {
        private const val TAG = "SettingsStore"
        private const val PREFS_NAME = "polished_recognition_settings"

        /**
         * #214: the canonical vault key for a provider URL — trimmed, scheme +
         * host lowercased (DNS is case-insensitive), trailing slashes stripped.
         * `https://Api.Example/v1` and `https://api.example/v1/` are the SAME
         * provider and share one token. The path is kept verbatim (it can be
         * case-sensitive on self-hosted endpoints).
         */
        fun canonicalProviderUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd <= 0) return trimmed
            val rest = trimmed.substring(schemeEnd + 3)
            val pathStart = rest.indexOf('/')
            val authority = (if (pathStart >= 0) rest.substring(0, pathStart) else rest).lowercase()
            val path = if (pathStart >= 0) rest.substring(pathStart) else ""
            return trimmed.substring(0, schemeEnd).lowercase() + "://" + authority + path
        }

        /**
         * #101: the backup-excluded file holding the API tokens — the
         * per-type active credentials AND, since #214, the per-URL vault.
         * **The value is load-bearing outside this class** — the two backup
         * rule files in `res/xml/` exclude `<name>.xml` by exactly this
         * name, and `BackupRulesTest` pins the three of them together.
         */
        const val SECRETS_PREFS_NAME = "polished_recognition_secrets"

        private const val STT_TOKEN_KEY = "stt_token"
        private const val LLM_TOKEN_KEY = "llm_token"

        /**
         * #214: the per-URL token vault inside the secrets file — a JSON map
         * `canonicalProviderUrl → token` shared by the STT and LLM provider
         * types (same endpoint = same credential). Lives in
         * [SECRETS_PREFS_NAME], so the existing backup exclusions keep every
         * entry off the Google cloud backup and device transfer.
         */
        private const val TOKEN_VAULT_KEY = "token_vault"

        /** The vault never grows past this many endpoints (oldest dropped). */
        private const val TOKEN_VAULT_CAPACITY = 20

        /** The #142 legacy-toggle migration target: ON → 30 days. */
        const val THIRTY_DAYS_SECONDS = 30L * 24 * 60 * 60
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
        private const val OPENCODE_SESSION_ID_KEY = "opencode_session_id"
        private const val STT_MODEL_LIST_KEY = "stt_model_list"
        private const val LLM_MODEL_LIST_KEY = "llm_model_list"
        private const val STT_MODEL_LISTS_KEY = "stt_model_lists"
        private const val LLM_MODEL_LISTS_KEY = "llm_model_lists"
        private const val RAW_MODE_KEY = "raw_mode"
        private const val IME_LIVE_PREVIEW_KEY = "ime_live_preview"
        private const val COMPRESS_AUDIO_KEY = "compress_audio"
        private const val WORKBENCH_FIFO_SIZE_KEY = "workbench_fifo_size"
        private const val AUDIO_EVIDENCE_KEY = "audio_evidence"
        private const val EVIDENCE_RETENTION_KEY = "evidence_retention_seconds"
        private const val TARGET_LANGUAGE_KEY = "target_language"
        private const val CUSTOM_LANGUAGES_KEY = "custom_languages"
        private const val WRAP_WIDTH_KEY = "wrap_width"
        private const val SLICE_SECONDS_OVERRIDE_KEY = "slice_seconds_override"
        private const val SEARCH_PERCENT_OVERRIDE_KEY = "search_percent_override"

        /** Pre-#124 key: the nominal fragment size, migrated ×2 into the slice. */
        private const val LEGACY_FRAGMENT_SECONDS_OVERRIDE_KEY = "fragment_seconds_override"
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
