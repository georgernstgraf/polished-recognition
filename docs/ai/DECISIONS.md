# Decisions

Architectural and technical decisions made in this project.
Each entry documents WHAT was decided and WHY.

## 2026-05-29: RecognitionService over IME
- **Choice**: Implement as `RecognitionService`, not a custom `InputMethodService` keyboard
- **Reason**: Works with every keyboard (Gboard, SwiftKey, Samsung, OpenBoard). Users just change one system setting (Voice Input provider). Building a full keyboard requires thousands of lines for QWERTY, autocorrect, suggestions, themes, etc. — all unrelated to voice input.
- **Considered**: Forking OpenBoard, building custom IME from scratch
- **Tradeoff**: No custom keyboard UI — depends on existing keyboard's mic button behavior

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

## 2026-05-29: Kotlin 2.1.20 Upgrade
- **Choice**: Upgrade from Kotlin 1.9.22 to 2.1.20
- **Reason**: Test dependencies (Robolectric, MockK) transitively pull kotlin-stdlib 2.1.20 which is incompatible with Kotlin 1.9.x compiler metadata format. Upgrade was required for compilation.
- **Considered**: Pinning stdlib to 1.9.x (complex, fragile, Gradle resolution conflicts)
- **Tradeoff**: Kotlin 2.x has stricter parameter handling (named args required when default params at front of constructor).

## 2026-05-29: AudioRecord + WAV in-memory
- **Choice**: `AudioRecord` capturing PCM 16-bit 16kHz mono, with manual 44-byte WAV header
- **Reason**: RecognitionService needs raw audio for the STT API. `MediaRecorder` produces compressed formats (AAC) requiring conversion. In-memory WAV is ~50 lines of header byte manipulation. No temp file I/O during recording.
- **Considered**: MediaRecorder + temp file + FFmpeg conversion
- **Tradeoff**: WAV is uncompressed — larger than AAC for long recordings. Fine for short voice input (typically <30s).

## 2026-05-29: HTTP error detail in token validation
- **Choice**: Parse OpenAI-compatible error body JSON (`error.message`) and display it in `TextInputLayout.error` (e.g. "HTTP 401: Invalid API key")
- **Reason**: Users need actionable information when token validation fails. The generic "Invalid token or no access" gives no clue whether the token, provider URL, or network is wrong.
- **Considered**: Custom error dialog, dedicated error view in layout
- **Tradeoff**: Error messages from providers vary in format — the `error.message` extraction handles OpenAI/GROQ/OpenRouter. Non-standard providers may still show generic message.

## 2026-05-30: Provider settings redesign — visible URL fields + expanded presets
- **Choice**: Add editable API URL fields to both STT and LLM sections, auto-fill from preset map on provider selection, expand LLM presets from 5 to 15 providers
- **Reason**: Users couldn't see what endpoint they connected to. "Custom provider…" silently fell back to OpenAI's URL. OpenRouter and other providers need visible/editable URLs. The preset list was too small.
- **Considered**: URL field for LLM only (decided STT should match), per-provider modal for URL entry (over-engineered)
- **Tradeoff**: More scrollable fields in the layout. URL field is always editable even for known providers — could break known-good configs. Mitigated by auto-fill on provider select.

## 2026-05-30: Split LLM validation — Fetch Models vs Test Token
- **Choice**: Replace single Validate & Fetch with two buttons: [Fetch Models] and [Test Token]. Test Token sends a minimal chat request (max_tokens=1, "ping") with the selected model.
- **Reason**: OpenRouter's /v1/models endpoint is unauthenticated — returns models for any token, even invalid ones. A separate chat call catches 401/403 after models load. Keeps token validation reliable across providers.
- **Considered**: Single button doing both calls sequentially (confusing UX — "which failed?"), skipping token verification entirely (unsafe)
- **Tradeoff**: Two buttons instead of one in LLM section. Test Token requires model selection first. Costs 1 token per test.

## 2026-05-30: Searchable model dropdowns
- **Choice**: Change model AutoCompleteTextView from inputType=none to inputType=text with completionThreshold=1
- **Reason**: OpenRouter offers 600+ models — scrolling is impractical. Built-in ArrayAdapter filtering handles this with zero code changes beyond the XML attribute.
- **Considered**: Custom SearchView + ListView, third-party searchable spinner library
- **Tradeoff**: Slightly different UX from exposed dropdown behavior. Keyboard shows on tap before the dropdown. Built-in behavior is free and proven.
- **Choice**: Upgrade AGP from 8.2.2 to 8.7.3 and Gradle wrapper from 8.2 to 8.9
- **Reason**: Kotlin 2.1.20 generates metadata that R8 bundled with AGP 8.2.2 cannot parse (warning spam in release builds). Per Android docs, Kotlin 2.1 requires AGP ≥ 8.6 and R8 ≥ 8.6.17. AGP 8.7.3 is a stable patch release providing R8 8.7.x compatible with Kotlin 2.1 metadata. Gradle 8.9 is the minimum required by AGP 8.7.x.
- **Considered**: Downgrading Kotlin to 1.9.24 (simpler, zero side effects), upgrading to AGP 9.x (requires JDK 21, more API changes)
- **Tradeoff**: Requires Gradle wrapper upgrade and download of new distribution on first build. JDK 17 stays supported (AGP 9.x would require JDK 21).

## 2026-05-30: Sync Call for model listing (R8 workaround)
- **Choice**: Add non-suspend `listModelsSync` returning `Call<ModelsResponse>` to API services, use `Call.execute()` in `fetchSttModels`/`fetchLlmModels` instead of creating new Retrofit instances with suspend functions
- **Reason**: R8 full mode strips generic type info from the `Continuation<? super Response<ModelsResponse>>` parameter in Kotlin suspend functions. Retrofit's `HttpServiceMethod.parseAnnotations` casts this to `ParameterizedType`, causing `ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType` in release builds. Using a non-suspend `Call<ModelsResponse>` avoids the `Continuation` type resolution path entirely
- **Considered**: Adding ProGuard keep rules (`-keep class kotlin.coroutines.Continuation`), OkHttp direct call
- **Tradeoff**: `Call.execute()` blocks the thread (fine — already on `Dispatchers.IO`). Slightly more verbose than suspend single-line call. Keeps the shared `OkHttpClient` with connection pooling via `PolishedRecognitionApp.getSttApi/getChatApi`

## 2026-05-30: Sync Call for transcribeAudio/chat (R8 workaround)
- **Choice**: Same sync `Call<T>` pattern extended to `transcribeAudioSync` and `chatSync` in the pipeline
- **Reason**: Same R8 `Continuation<? super Response<T>>` issue affects all Retrofit suspend functions, not just listModels. Release build transcription failed silently.
- **Considered**: ProGuard keep rules (risky, platform-dependent)
- **Tradeoff**: Pipeline code now blocks IO thread with `Call.execute()` — fine since `Dispatchers.IO` is designed for blocking I/O

## 2026-05-30: `by lazy` for Activity view fields
- **Choice**: All `lateinit var` view properties in `SettingsActivity` and `VoiceRecognitionActivity` replaced with `val by lazy { findViewById(...) }`
- **Reason**: R8 full mode inlines private methods (`bindViews`, `loadSettings`) into `onCreate()` and reorders instructions. `findViewById` assignments happen AFTER first property access, causing `lateinit property has not been initialized` crash in release builds. `by lazy` initializes on first access, immune to instruction reordering
- **Considered**: Disabling R8 optimization entirely, moving `findViewById` into `onCreate` directly (R8 still reorders within the method)
- **Tradeoff**: `by lazy` has minor thread-safety overhead (`LazyThreadSafetyMode.SYNCHRONIZED`). All view access is on main thread so overhead is negligible

## 2026-05-30: Disable `isShrinkResources`
- **Choice**: Set `isShrinkResources = false` for release builds
- **Reason**: Resource shrinker removed views from layouts that R8 couldn't trace as used, causing `findViewById` to return null for seemingly-referenced views. Disabling it adds ~50KB to APK size but eliminates a class of release-only layout bugs
- **Considered**: `tools:keep` in layout XML, resource keep rules (fragile, per-view)
- **Tradeoff**: Negligible APK size increase (~50KB)

## 2026-05-30: `ValueAnimator` blink with ease-in-out
- **Choice**: Replace Coroutine-based blink with `ValueAnimator.ofFloat(0.3f, 1.0f)` using `AccelerateDecelerateInterpolator`, `INFINITE` repeat, `REVERSE` mode
- **Reason**: CSS-like "ease-in-out" smooth pulse instead of abrupt alpha steps. `ValueAnimator` is Android-native and doesn't need a coroutine scope
- **Considered**: `ObjectAnimator` (less control over repeat/reverse), keeping coroutine approach with smoother steps (more code)
- **Tradeoff**: Removed `Job`, `delay`, `isActive` imports. Animator lifecycle tied to manual start/cancel

## 2026-05-30: `BaseAdapter` + `Filterable` for model dropdowns (replaces custom `ArrayAdapter` filter)
- **Choice**: Replaced anonymous `ArrayAdapter` with custom `Filter` in `updateModelDropdown()` with a `ModelFilterAdapter` that extends `BaseAdapter` and implements `Filterable`, directly owning its display list
- **Reason**: `ArrayAdapter.clear()` and `addAll()` silently operate on the internal `mOriginalValues` list (not `mObjects`) when `mOriginalValues` is non-null. The custom `publishResults()` never actually updated the displayed data. Additionally, `setNotifyOnChange(false)` (added to prevent `ConcurrentModificationException`) was never reset to `true`, leaving the adapter in a permanently broken state that caused crashes on Backspace. `BaseAdapter` directly owns its display list — filter results are assigned as a new list copy, avoiding all `ArrayAdapter` internal state corruption.
- **Considered**: Fixing the `ArrayAdapter` approach by directly setting `mObjects` via reflection (fragile), switching back to standard `startsWith` filter (loses `contains` matching)
- **Tradeoff**: `BaseAdapter` requires manual `getView()`, `getCount()`, `getItem()` implementation (~15 extra lines vs. `ArrayAdapter`). Zero reflection, zero side effects.

## 2026-05-30: Auto-start recording in VoiceRecognitionActivity
- **Choice**: `VoiceRecognitionActivity` starts recording immediately in `onCreate()` (or on permission grant) instead of showing "Tap mic to speak" idle state
- **Reason**: One less tap for the user. When AnySoftKeyboard launches voice input, the user's intent is already "I want to dictate" — no need for a confirmation tap
- **Considered**: Keeping the explicit tap-to-start flow (safer but slower)
- **Tradeoff**: User must tap to STOP instead of START. Stop icon (square) replaces old mic/pause icon for clarity

## 2026-05-30: Error toasts for transcription failures
- **Choice**: Show `Toast.makeText(this, errorMessage, Toast.LENGTH_LONG)` in both `PolishedRecognitionService` and `VoiceRecognitionActivity` error paths (else-branch and catch-branch)
- **Reason**: Users get no feedback when transcription fails — the keyboard just shows an error code or nothing. Detailed error messages (HTTP status, exception text) help users diagnose issues (wrong token, network down, bad model)
- **Considered**: Dialog, snackbar (both require activity context which is unavailable in RecognitionService)
- **Tradeoff**: Toast might show behind keyboard UI. Counter: it's visible in the notification shade and toasts work from Service context

## 2026-05-30: Try-and-toast for voice recognition service setting
- **Choice**: The "Set as Voice Input" button in Settings only attempts `Settings.Secure.putString("voice_recognition_service", ...)` and shows a Toast with the result — no dialog, no system settings opener, no cancel button
- **Reason**: `WRITE_SECURE_SETTINGS` is a `signature|privileged` permission — regular apps cannot obtain it via manifest. The putString call fails with SecurityException on normal devices. On success (rooted/custom ROM), a toast confirms; on failure, the toast shows the error or ADB fallback command
- **Considered**: Showing a help dialog with manual setup steps (overly complex, buried the simple try), opening system settings (no voice input option on Samsung Android 11)
- **Tradeoff**: Users who never connect via ADB always see the error toast. Counter: the info dialog on the recording screen and README contain the full setup guide

## 2026-05-30: Info dialog on recording screen (separated from settings action)
- **Choice**: Added an info icon (i) button left of the settings gear on the recording overlay. Tapping it shows an AlertDialog (OK only) with consolidated setup guide: Gboard warning, AnySoftKeyboard recommendation, ADB commands, manual setup steps, and mention of the Settings "Set as Voice Input" button
- **Reason**: Separates informational content (setup guide) from actionable intent (setting the service). The recording screen is the natural place for "how do I set this up" help
- **Considered**: Keeping help text in the Settings button dialog (confusing — settings button now opens Settings), help icon on Settings page (no room, recording screen is the entry point)
- **Tradeoff**: Two icons in the top-right corner. Info icon uses a custom VectorDrawable (OEMs don't have a standard white info icon)

## 2026-05-30: Pause/resume recording with three-button layout
- **Choice**: Replaced single mic button with three-button layout (Cancel / Pause-Resume / Stop) and moved blinking animation from mic to status text. Pause stops AudioRecord while keeping PCM buffer; resume appends to the same buffer. Stop extracts the full buffer for STT. `android:noHistory="true"` removed from manifest so activity survives navigation to Settings.
- **Reason**: Users need to change settings (e.g. target language) mid-session without losing recording. Pause → Settings → Save → Resume → Stop must use the new settings for transcription. `TranscriptionPipeline` reads settings at transcription time, not recording time, so changes apply automatically. `noHistory` was destroying the activity on navigation, losing the buffer — removed.
- **Considered**: `startActivityForResult` with auto-resume (complex lifecycle, overkill), saving PCM to temp file on pause (additional I/O, fragile)
- **Tradeoff**: Activity stays in back stack during Settings — if system kills it under memory pressure, buffer is lost. Counter: voice input sessions are short, and system kill in this window is unlikely.

## 2026-05-30: Blink animation on status text instead of mic button
- **Choice**: `ValueAnimator` on `statusText.alpha` instead of `micButton.alpha`
- **Reason**: Three-button layout removed the large mic button. Blinking "Recording…" text at 28sp provides the visual feedback that recording is active, matching iOS voice memos UX pattern
- **Considered**: Adding a separate indicator dot (extra layout element)
- **Tradeoff**: Status text loses blink during pause — user sees static "Paused" text. Clear enough for the paused state distinction.

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
