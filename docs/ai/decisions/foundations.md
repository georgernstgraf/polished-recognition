---
type: Decisions
title: Decisions — Foundations
description: Stack, framework and platform choices the whole app rests on: manual DI, SharedPreferences storage, plain platform Views, the app shell and the test infrastructure.
tags: [knowledge, decisions, foundations]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Decisions — Foundations

Moved out of `DECISIONS.md` in #206; entries are verbatim. New decisions in this area are appended here.

## 2026-11-24: #200 — der Retention-Default wird nicht mehr beim Lesen persistiert

`SettingsStore.evidenceRetentionSeconds` schrieb bisher auch dann einen Wert in die
Prefs, wenn **nur der Default aufgelöst** wurde (kein Key, kein Legacy-Toggle):
`prefs.edit { putLong(…, 2592000) }`. Dieses Write-on-Read war die Ursache des
`SettingsStoreTest`-Flakes (`expected: 0 but was : 2592000`, 2× in ~21 Läufen).

- **Entscheidung: Default auflösen = reiner Lesevorgang.** Fehlt der Key und fehlt
  der Legacy-Toggle, gibt der Getter `evidenceRetentionDefaultSeconds` zurück,
  ohne zu schreiben. Nur die einmalige Legacy-Migration (`audio_evidence` →
  30 Tage / 0) persistiert weiterhin — sie ist ein expliziter, einmaliger Vorgang.
- **Warum.** Der Getter wird von Threads gelesen, die die Einstellung nicht
  besitzen: `AudioEvidenceStore.evict()` läuft im MIN_PRIORITY-Startup-Thread von
  `PolishedRecognitionApp` und liest die Retention als Erstes. Ein Schreibvorgang
  dort ist eine versteckte Nebenwirkung an einer Stelle, an der niemand eine
  Änderung erwartet — und in Tests eine Rennstrecke gegen `clear()`.
- **Nebenwirkung, gewollt.** Ein injizierter Default ≠ 30 Tage wird jetzt
  tatsächlich geehrt (vorher wurde er auf 30 Tage normalisiert und festgeschrieben).
  Für den Nutzer sichtbar ändert sich nichts: die Settings-Seite schreibt den Wert
  beim Speichern weiterhin explizit (`SettingsActivity`), und alle Leser sehen
  dieselbe Zahl wie vorher.
- **Diagnose-Korrektur.** Die #200-Hypothese „ein verspäteter `apply()` landet im
  nächsten Test" war falsch: Robolectric 4.14.1 legt je Testmethode ein eigenes
  `TempDirectory` an und benutzt die echte `SharedPreferencesImpl` (der Shadow ist
  leer). Sichtbar wird nur ein **gleichzeitiger** Schreiber in dieselbe
  In-Memory-Map. Der Startup-Thread heißt jetzt `app-startup-maintenance`
  (`STARTUP_MAINTENANCE_THREAD_NAME`) und wird vom Test gejoint, damit die Ursache
  künftig in einem `jstack`/Test benannt werden kann statt geraten zu werden.
- **Verification:** zwei neue Regressionstests (`reading the default does not
  persist it (#200)` in beiden Default-Varianten) waren vor dem Fix rot und sind
  danach grün; `SettingsStoreTest` 30× hintereinander, volles `:app:test`,
  `lintDebug`, `assembleRelease`, pre-push. Kein Versions-Bump.

## 2026-10-05: In-app help is one self-contained HTML page, bundled offline (#107)
- **Choice**: The help lives in a single, self-contained `docs/help.html` (inline CSS + JS + inline-SVG icons, no external/CDN deps except the shared `marketing/mastodon-demo.gif`). The **project site** serves it as the "Help" tab, and the **app** bundles the same file and shows it in a new `HelpActivity` (`WebView`, `javaScriptEnabled = true`, only local assets). The Gradle task `stageHelpAssets` copies `docs/help.html` + its images into the APK assets (Variant API `addGeneratedSourceDirectory`), so the text is authored once. Entry points: a "Help" chip left of the About heading, and a **long-press on the IME gear**.
- **Reason**: The help is interactive (a tap-vs-press-and-hold keyboard demo), which Markdown expresses only awkwardly — and the site injects rendered Markdown via `innerHTML`, where `<script>` tags never execute. A real HTML page is the natural medium, removes the `marked` dependency on the app side, and works fully offline in the WebView.
- **Trade-offs**: The help is a standalone page (not a Markdown-rendered tab); authoring prose in HTML is more verbose than Markdown. The gear's long-press no longer shows the #88 press-hold hint — it opens Help instead (`ImeHintPolicy` + test updated, `ime_settings_hint` removed); the other five buttons keep their hints. The delete-word button remains the only other gesture-taken button (hold = clear field).
- **Also**: `INSTALLATION.md` gained a dedicated **ADB Commands** section (settings launch, voice-input set/verify, mic grant, log pull); the scattered ADB blocks in §3/§5 were reduced to references. README's stale "English & German" claim corrected.

## 2026-09-21: Settings grouped by content — Output / Provider / Prompts (#92)
- **Choice**: `activity_settings.xml` reordered into three frequency-ordered groups: **Output Control** (RAW → target language → wrap width → compress_audio, owner-confirmed placement) → **Provider Control** (STT block first, then LLM, pipeline-flow order) → **Prompts** (system + target-language-clause editors + new read-only Source Language Clause note) → unchanged footer (IME setup, save, About). `processing_section` string retitled to "Output Control"; new `provider_control_section` + `source_language_clause_title/info` strings. No Kotlin change — all 34 view IDs preserved verbatim, save/load/validation untouched.
- **Reason**: Owner request — most-changed items at top; tokens together; prompts last with the immutable clause (`{{source_language_clause}}`, dropped on null/blank/`"unknown"`) documented in-UI since it is the only non-configurable prompt part.
- **Tradeoff**: `stt_section`/`llm_section` headers kept as sub-headers under Provider Control (two header levels, same 18sp style). `target_language_clause` bottom margin 8dp→16dp to separate it from the new note. Tests + `assembleRelease` green (`28d3821`). Verified on-device 2026-09-21 (`installRelease` on OnePlus 7T + 4 screenshots: Output → Provider/STT → LLM/Prompts → clause note/footer, all as planned).

## 2026-05-29: No Hilt (manual DI)
- **Choice**: Manual dependency injection via `PolishedRecognitionApp` Application class
- **Reason**: `RecognitionService` cannot be `@AndroidEntryPoint`. Hilt with RecognitionService requires complex workarounds. The app has ~5 singletons — manual wiring is ~30 lines vs. KSP compilation overhead.
- **Considered**: Hilt with custom EntryPoint, Koin (lighter DI)
- **Tradeoff**: No compile-time DI validation. Manual wiring is acceptable at this scale.

## 2026-05-29: No Room (SharedPreferences only)
- **Choice**: SharedPreferences for all persistent config
- **Reason**: RecognitionService doesn't store transcriptions — it captures audio, returns text, and forgets. Provider configs and prompts are small key-value pairs. Room requires entities, DAOs, migrations, KSP — overkill.
- **Considered**: Room, DataStore
- **Tradeoff**: No type-safe queries, no migrations, no large data support. Fine for <10 config keys.

## 2026-05-29: Provider-agnostic API layer
- **Choice**: Two generic Retrofit interfaces (`OpenAiSttApiService`, `OpenAiChatApiService`) parametrized by dynamic baseUrl
- **Reason**: GROQ, OpenAI, OpenRouter, ZAI, Ollama, LM Studio all speak identical OpenAI API contracts. 2 interfaces replace 4+ provider-specific ones. Adding a new provider requires zero code changes — just an entry in `provider_presets.json`.
- **Considered**: Per-provider Retrofit interfaces (like aitranscribe-android), abstract provider interface
- **Tradeoff**: Slight overhead of Retrofit instance caching per baseUrl. Negligible for Keyboard use case (1-2 providers at a time).

## 2026-05-29: No Compose (XML-based Settings UI)
- **Choice**: AppCompat + Material with XML-based `activity_settings.xml`
- **Reason**: One settings screen (provider configs + prompts). Compose would add ~2MB and 6+ dependencies (BOM, ui, material3, activity-compose, navigation, tooling). XML Views are built into Android with zero dependencies.
- **Considered**: Jetpack Compose (consistent with aitranscribe-android)
- **Tradeoff**: Less modern UI. No animation. No recomposition. Perfectly adequate for a configuration screen.

## 2026-05-29: Test Infrastructure — JUnit 4 + MockK + Truth + Robolectric
- **Choice**: JUnit 4 (not JUnit 5), MockK 1.14.4 for mocking, Google Truth 1.4.4 for assertions, Robolectric 4.14.1 for Android-aware unit tests, coroutines-test for pipeline tests
- **Reason**: Aligned with zazentimer's proven test stack. JUnit 4 is simpler than JUnit Platform setup in Gradle. MockK handles suspend functions cleanly with `coEvery`. Robolectric enables testing SharedPreferences and assets without a device. Truth produces readable assertion failures.
- **Considered**: JUnit Jupiter (like aitranscribe-android), Mockito, Kotest
- **Tradeoff**: No parameterized tests (JUnit 4 limitation). No test fixtures plugin yet. Counter: test set is small (7 files, 57 cases).

## 2026-05-29: Real API Integration Tests with GROQ
- **Choice**: Separate `GroqApiIntegrationTest` class that calls GROQ Whisper STT and LLM APIs with real credentials from `.env`
- **Reason**: Token validation, model listing, and end-to-end STT+LLM pipeline are only fully testable against the real API. Tests are skippable when `.env` is missing (CI-safe) via `assumeTrue`.
- **Considered**: Mock-only testing, WireMock with recorded responses
- **Tradeoff**: Requires valid GROQ API token to run. Tests are slow (network latency). Counter: only 5 integration tests; all 48 unit tests pass without network.

## 2026-09-01: Remove VoiceRecognitionActivity + drop Material AND appcompat (#61) — supersedes 2026-05-30 "alternate entry point"
- **Choice**: Deleted `VoiceRecognitionActivity` + `activity_voice_input.xml` + its manifest block (`RECOGNIZE_SPEECH` intent filter). Relocated its `NONE_TARGET_LANGUAGE` ("Polish only") + `buildLanguageList` to new `config/LanguageOptions` (distinct from `CustomLanguages.NONE_TARGET_LANGUAGE` = "None (polish only)" — kept separate, short string for the narrow IME bar). Dropped **both** `com.google.android.material` and `androidx.appcompat` dependencies (+ proguard keep rules + `DynamicColors`); `MicrophonePermissionActivity` → plain `Activity` with `onRequestPermissionsResult`; theme family reduced to `Theme.PolishedRecognition.Plain` (→ `Theme.DeviceDefault.DayNight`) with `.Transparent` variant; deleted Material3 base theme, `bg_quick_settings_pill`, `recording_text_secondary`, `recording_text_hint` (both light + night).
- **Reason**: Both real integrations bypass the activity (AnySoftKeyboard uses the auxiliary voice IME; HeliBoard mic uses the bound service — #62). No user-reachable path opens it; it was the only Material consumer left. AppCompat audit showed only `MicrophonePermissionActivity` extended `AppCompatActivity` and no layout used appcompat widgets, so both libs went in one sweep (owner-approved).
- **Tradeoff**: Third-party `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` callers now get `ActivityNotFoundException` (accepted, none known). The #60 adb overlay test harness is gone — future STT verification goes through the IME path.

## 2026-05-30: `by lazy` for Activity view fields
- **Choice**: All `lateinit var` view properties in `SettingsActivity` and `VoiceRecognitionActivity` replaced with `val by lazy { findViewById(...) }`
- **Reason**: R8 full mode inlines private methods (`bindViews`, `loadSettings`) into `onCreate()` and reorders instructions. `findViewById` assignments happen AFTER first property access, causing `lateinit property has not been initialized` crash in release builds. `by lazy` initializes on first access, immune to instruction reordering
- **Considered**: Disabling R8 optimization entirely, moving `findViewById` into `onCreate` directly (R8 still reorders within the method)
- **Tradeoff**: `by lazy` has minor thread-safety overhead (`LazyThreadSafetyMode.SYNCHRONIZED`). All view access is on main thread so overhead is negligible

## 2026-05-30: Info dialog on recording screen (separated from settings action)
- **Choice**: Added an info icon (i) button left of the settings gear on the recording overlay. Tapping it shows an AlertDialog (OK only) with consolidated setup guide: Gboard warning, AnySoftKeyboard recommendation, ADB commands, manual setup steps, and mention of the Settings "Set as Voice Input" button
- **Reason**: Separates informational content (setup guide) from actionable intent (setting the service). The recording screen is the natural place for "how do I set this up" help
- **Considered**: Keeping help text in the Settings button dialog (confusing — settings button now opens Settings), help icon on Settings page (no room, recording screen is the entry point)
- **Tradeoff**: Two icons in the top-right corner. Info icon uses a custom VectorDrawable (OEMs don't have a standard white info icon)

## 2026-05-30: AppCompatActivity required for MaterialButton backgroundTint
- **Choice**: `VoiceRecognitionActivity` changed from `Activity` to `AppCompatActivity`
- **Reason**: Plain `Activity` does not resolve MaterialComponents `<Button>` decorations — `app:backgroundTint` is silently ignored. `AppCompatActivity` enables the MaterialComponents `AppCompatViewInflater` which inflates `<Button>` as `MaterialButton` and respects `app:backgroundTint`, `app:icon`, `app:iconSize`, `app:cornerRadius`
- **Considered**: Using `android:backgroundTint` instead (also ignored by platform Button), setting background drawables manually (fragile)
- **Tradeoff**: Slightly larger APK from AppCompat dependency. Already present via SettingsActivity

## 2026-05-30: Circular icon-only MaterialButton with space-around layout
- **Choice**: Three 56dp circular `MaterialButton` with centered 32dp icons, arranged via four equal-weight spacer Views (CSS `space-around`). `app:iconPadding="0dp"` ensures icons are perfectly centered within the circle
- **Reason**: Three colored circles with clear icons provide instant visual recognition on a fast-interaction overlay. The space-around layout evenly distributes the buttons across the screen width regardless of device size
- **Considered**: Text labels, horizontal gravity=center (crowded), OutlinedButton style (low contrast on dark overlay)
- **Tradeoff**: Icon-only means users must learn the meaning of each icon. Mitigated by consistent Material Design icon language (X=cancel, ‖=pause, ↻=resume, ✈=send)

## 2026-06-01: Shortened info dialog with clickable README link
- **Choice**: Replaced verbose setup guide (Gboard warning, AnySoftKeyboard recommendation, ADB commands) with a ~200‑character message stating that prompts are in English, the target language should be typed in English, plus a clickable README link. The link uses `Html.fromHtml()` + `LinkMovementMethod` on the AlertDialog's message TextView
- **Reason**: Users found the original dialog too verbose. The setup steps belong in the README, not in an in-app dialog. The English‑language note is directly relevant to issue #14's target language feature
- **Considered**: Keeping full text (too long), removing the dialog entirely (loses helpful context)
- **Tradeoff**: Users who don't visit the README miss the setup guide. Mitigated by the README link being prominently displayed

## 2026-06-06: Global UncaughtExceptionHandler with pop-up dialog
- **Choice**: Register `Thread.setDefaultUncaughtExceptionHandler` in `PolishedRecognitionApp.onCreate()` that extracts `exceptionType`, `exceptionMessage`, and full stack trace, then launches `CrashDialogActivity` (in separate `:crash` process) displaying an `AlertDialog` with these details. Process is killed via `Process.killProcess()` after the intent is sent.
- **Reason**: Unknown fatal crashes (e.g. free-text model search on Android 11, issue #8) leave the user with no feedback. A pop-up showing the exception type and stack trace helps users report actionable bug reports.
- **Considered**: Toast-only (cleared too fast, not inspectable), log-only (invisible to users), wrapping individual call sites (misses unexpected crashes elsewhere)
- **Tradeoff**: The `:crash` process adds ~1MB to the process count. Stack traces can be long — the dialog is scrollable. `System.exit(2)` ensures clean termination after the dialog is shown.

## 2026-06-16: DayNight-aware recording screen (#32)
- **Choice**: Created `values/colors.xml` (light) and `values-night/colors.xml` (dark) with 7 theme-aware color resources (`recording_overlay`, `recording_text_primary`, `recording_text_secondary`, `recording_text_hint`, `recording_divider`, `recording_pill_bg`, `recording_icon_tint`). Replaced all hardcoded color literals in `activity_voice_input.xml` and `bg_quick_settings_pill.xml`.
- **Reason**: The recording screen hardcoded `#E6000000` background and white text/icons. The theme inherits `Theme.Material3.DayNight`, so Settings correctly follows system light/dark — but the recording screen ignored it entirely. Users in light mode saw a jarring dark overlay.
- **Considered**: Using Material theme attributes (`?attr/colorBackground` etc.) — too generic for a translucent overlay that needs specific opacity values different from the standard surface colors.
- **Tradeoff**: Button action colors (red/blue/yellow/green) stay hardcoded — they are semantic action colors that work on both light and dark backgrounds.

## 2026-08-23: Settings + CrashDialog theme-free (plain Views) (#45)
- **Choice**: `SettingsActivity` + `CrashDialogActivity` moved off Material/AppCompat onto plain platform Views under a new `Theme.PolishedRecognition.Plain` → `@android:style/Theme.DeviceDefault.DayNight` (+ `windowNoTitle`/`windowActionBar=false`; `.Transparent` variant for crash). All 10 `TextInputLayout`+`TextInputEditText`/`AutoCompleteTextView` → plain `EditText`/`AutoCompleteTextView`; `MaterialCheckBox`→`CheckBox`; 6× `Widget.Material3.Button.*` → plain `Button` (secondary = `Widget.DeviceDefault.Button.Borderless`); `?attr/textAppearance*` → explicit `textSize`/`textStyle` + `?android:attr/textColor*` (platform, DayNight-safe). Both Activities: `AppCompatActivity`→`Activity`; `androidx.appcompat.app.AlertDialog`→`android.app.AlertDialog`. The Material `material` dependency **stays** in `build.gradle.kts` — `VoiceRecognitionActivity` + `MicrophonePermissionActivity` still use Material (`MaterialButton` per the 2026-05-30 decision) and are out of scope.
- **Token reveal**: kept a manual eye `ImageButton` per token `EditText` (Material's `password_toggle` end-icon is gone). `togglePassword()` flips `PasswordTransformationMethod` + swaps `ic_eye`↔`ic_eye_off` (two new VectorDrawables).
- **Errors**: `setError(msg)` on the `EditText`/`AutoCompleteTextView` (popup + red icon) replaces `TextInputLayout.error`; dropped Material `helperText` (success already Toasts). TextWatchers clear `error = null` on edit.
- **Provider dropdowns (`inputType=none`)**: Material's exposed-dropdown arrow + tap hook is gone, so Kotlin wires `setOnClickListener { showDropDown() }` + `threshold = Int.MAX_VALUE` (PITFALLS #37). Same hook applied to the target-language dropdown for tap-to-open parity.
- **Reason**: the user wanted Settings "so schlicht wie möglich" (as plain as possible), matching the IME's theme-free approach. Unlike the IME (where the service context lacks Material and theme-free was *required*), Settings is an Activity with a working Material theme — this is **purely aesthetic**, accepted consciously.
- **Considered**: `Theme.DeviceDefault.Light.NoActionBar` (issue's literal text) — rejected because it loses dark mode (the issue's own "DayNight still works" note contradicts `Light`); `Theme.DeviceDefault.DayNight` exists in API 36 and preserves the existing DayNight behavior. Manual error `TextView` per field — rejected (4 extra views in an already-long layout; `setError()` popup is acceptable). Drop the token eye toggle — rejected (user wanted to keep it). `setError()` + a dedicated error `TextView` — rejected (overkill).
- **Tradeoff**: `setError()` popup is less prominent than Material's below-field red text — accepted per user choice. Provider dropdown lost the Material dropdown-arrow indicator (tap still opens via the hook). `helperText = "Token valid"` green confirmation is gone — only Toasts now. Crash dialog buttons lose the Material `OutlinedButton`/filled distinction — both are plain `Button`s (Copy = `Borderless`, Close = default). On-device verify still pending at commit time.

## 2026-11-14: #101 — API tokens never leave the device; the rest of the configuration still does

- **Choice**: (a) **Tokens are EXCLUDED from OS backup** (owner decision 2026-11-14). Backup exclusion works per FILE, so the tokens move into their own preferences file `polished_recognition_secrets` (`SettingsStore.SECRETS_PREFS_NAME`) while EVERYTHING else stays in `polished_recognition_settings` — a device change restores endpoints, models, prompts, language and learned apps; only the credentials are re-entered. (b) **Both mechanisms are wired**: `android:fullBackupContent="@xml/backup_rules"` (API ≤ 30) and `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+), each excluding `polished_recognition_secrets.xml` — cloud backup AND device-to-device transfer, because "token leaves the device" is the hazard, not "token leaves via Google". (c) **The split is done on the JSON tree, not via `data class copy()`** — see the pitfall below.
- **Reason**: the app ships `allowBackup="true"` with no rules, so the single settings file (tokens included) went to the user's Google account daily and came back on a new phone. Privacy vs convenience is not a binary here: the restorable half is genuinely useful, the credential is not.
- **Migration**: a pre-#101 install still carries the token INSIDE `stt_provider`/`llm_provider`. Every read migrates it into the secret file and rewrites the stored JSON with `apiToken = ""` (`readProvider`), so the backed-up copy is scrubbed on first use — before the next nightly backup. The secret file wins over a stale embedded token.
- **Tradeoff**: after a device change the user must paste the token once. Accepted (owner). An empty token removes the secret key instead of storing a blank one, so "token cleared" and "never set" stay indistinguishable — the same semantics the JSON had.
- **Verification**: `SettingsStoreSecretsTest` (7 Robolectric tests: split, read-back, blank-token removal, provider clearing, legacy migration + scrub, secret-wins-over-stale) and `BackupRulesTest` (4 static tests pinning manifest + both rule files + the constant together, `XmlColorPolicyTest` style). 403 `:app` tests green, `lintDebug` green, `assembleRelease` green. Commit for #101.
- **Pitfall recorded**: `JsonParser().parse(...)` — this project's Gson is **2.8.5** (pulled in by `converter-gson:2.9.0`), so `JsonParser.parseString` (2.8.6+) does NOT exist. And a legacy provider JSON without an `id` member deserializes to a **null `id` field** (Gson bypasses Kotlin defaults), which makes `copy()` throw `NullPointerException: parameter id` — hence the JSON-level implementation.

