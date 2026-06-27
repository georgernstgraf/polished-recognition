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

## 2026-06-01: Dynamic language dropdown with persistent custom languages
- **Choice**: Replace fixed language list from `LanguageMapper.supportedLanguages` with a dynamic `LanguageDropdownAdapter` combining "None (no translation)" + "English" (always present) + `settings.customLanguages` (user-entered, persisted as JSON array in SharedPreferences). Delete via "Manage saved languages" dialog with trash icon per row
- **Reason**: Users want to type any language name and have it persist across sessions. A static list of 29 languages forces users to type the same language every time. AutoCompleteTextView dropdown items cannot contain focusable children (ImageButton steals touch → row not selectable), so deletion is handled in a separate dialog, not inline in the dropdown
- **Considered**: Swipe-to-delete in dropdown (conflicts with ListView scrolling), long-press popup via reflection (fragile), trash icon in dropdown (not selectable), long-press via OnTouchListener (unreliable with ListView touch dispatch)
- **Tradeoff**: Two-step delete (Manage dialog → tap trash). Delete happens immediately without confirmation. A Toast confirms removal.

## 2026-06-01: Shortened info dialog with clickable README link
- **Choice**: Replaced verbose setup guide (Gboard warning, AnySoftKeyboard recommendation, ADB commands) with a ~200‑character message stating that prompts are in English, the target language should be typed in English, plus a clickable README link. The link uses `Html.fromHtml()` + `LinkMovementMethod` on the AlertDialog's message TextView
- **Reason**: Users found the original dialog too verbose. The setup steps belong in the README, not in an in-app dialog. The English‑language note is directly relevant to issue #14's target language feature
- **Considered**: Keeping full text (too long), removing the dialog entirely (loses helpful context)
- **Tradeoff**: Users who don't visit the README miss the setup guide. Mitigated by the README link being prominently displayed

## 2026-06-01: Always install release builds
- **Choice**: Always use `./gradlew installRelease` for deployment. Debug build uses `applicationIdSuffix = ".debug"` → separate app ID that won't match the `voice_recognition_service` system setting
- **Reason**: The debug APK has a different application ID and cannot function as the voice input provider. Release build signs with `signingConfigs.debug` so it installs without extra setup
- **Considered**: Debug build for development (breaks voice input testing)
- **Tradeoff**: Slower install due to R8 minification. Adding extra install target just for testing voice input

## 2026-06-02: GitHub Pages for Privacy Policy hosting
- **Choice**: Host Privacy Policy at `public_html/` on GitHub Pages (`georgernstgraf.github.io/polished-recognition`)
- **Reason**: Google Play requires a publicly accessible Privacy Policy URL for apps with sensitive permissions (RECORD_AUDIO, INTERNET). GitHub Pages is free, version-controlled alongside the code, and requires no external hosting.
- **Considered**: Separate privacy policy service (cost), raw.github.com link (plain text, no formatting)
- **Tradeoff**: Policy is tied to the repo — if the repo is made private or deleted, the policy URL breaks. Jekyll renders markdown to HTML automatically.

## 2026-06-02: Upload keystore for Google Play App Signing
- **Choice**: Generate a dedicated `upload.keystore` (alias: `upload`) for local signing before upload. Google Play manages the production signing key.
- **Reason**: Google Play App Signing is the recommended approach — Google holds the production key, the developer only uses an upload key. If the upload key is lost, it can be replaced via the Play Console (unlike a self-managed production key).
- **Considered**: Self-managed production keystore (irrecoverable if lost)
- **Tradeoff**: Requires internet to sign on Google's servers during distribution. Upload key must still be backed up.

## 2026-06-02: configChanges for rotation safety
- **Choice**: Add `android:configChanges="orientation|screenSize"` to `VoiceRecognitionActivity` in the manifest
- **Reason**: Default Activity rotation destroys and recreates the Activity, which calls `onDestroy()` → `audioRecorder.cancel()` → discards the entire PCM buffer. The user loses their recording and only gets the last fragment. `configChanges` prevents recreation; the AudioRecord thread continues uninterrupted.
- **Considered**: `android:screenOrientation="portrait"` (simpler but blocks landscape), ViewModel/AAC binding for recording state (overkill for a ~10s overlay)
- **Tradeoff**: Layout is reused as-is — no landscape-specific variant exists, but the centered layout works in both orientations.

## 2026-06-02: CI debug keystore synchronization via GitHub Secrets
- **Choice**: Export local `~/.android/debug.keystore` as base64 and store it (plus storePassword, keyAlias, keyPassword) in GitHub repository secrets. CI workflow decodes the keystore into `~/.android/debug.keystore` before running `assembleRelease`.
- **Reason**: Local and CI builds had different debug keystores, causing `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when installing CI-built APKs over locally-installed ones. No changes to `build.gradle.kts` required — `signingConfigs.getByName("debug")` on CI finds the same keystore as locally.
- **Considered**: Creating a proper release keystore with signingConfigs.create("release") (cleaner but requires build.gradle.kts change), standard Android debug keystore on CI (different key → signature mismatch)
- **Tradeoff**: Debug keystore passwords are public (`android`/`androiddebugkey`) — no real signature security. Acceptable for development builds. For production signing, a proper release keystore should be created.

## 2026-06-03: CI-only signing (no Gradle signing config) [SUPERSEDED]
- **Choice**: Remove `signingConfigs { release { ... } }` and `signingConfig` from `build.gradle.kts`. All signing done via `-Pandroid.injected.signing.*` in CI workflows.
- **Reason**: `build.yml` builds APK signed with `~/.android/debug.keystore` for GitHub distribution. `release.yml` builds AAB signed with upload keystore from secrets for Play Store. Keeping signing config in Gradle caused mismatch — `upload.keystore` didn't exist on CI runner. Matches zazentimer's approach.
- **Considered**: Environment-variable-based fallback in build.gradle.kts (complex, fragile), keeping debug keystore in Gradle (`signingConfigs.getByName("debug")`)
- **Tradeoff**: Local `./gradlew assembleRelease` produces unsigned APK. Signed APK/AAB only from CI. Local testing should use `assembleDebug` (unsigned, `.debug` suffix).
- **Superseded by**: 2026-06-03: Restore signing config with RELEASE_KEYSTORE

## 2026-06-03: Restore signing config with RELEASE_KEYSTORE
- **Choice**: Add `signingConfigs.release` back to `app/build.gradle.kts` reading `app/release.keystore` with `RELEASE_*` env var fallback. Restore CI to decode `RELEASE_KEYSTORE` secret and build with `RELEASE_*` env vars.
- **Reason**: Removing the signing config broke `installRelease` (task doesn't exist without signing config on release type). CI was also generating a fresh keytool keystore per run, producing different signatures than local builds. The old approach (decode secret + env vars) worked and produced consistent signatures.
- **Considered**: Keeping CI-only signing and adding `signingConfig = signingConfigs.debug` for local use only (inconsistent approach)
- **Tradeoff**: `app/release.keystore` must exist locally and as `RELEASE_KEYSTORE` secret in GitHub. Both are copies of `~/.android/debug.keystore`.

## 2026-06-04: Upgrade to AGP 9.1.1, Java 21, API 36
- **Choice**: Upgrade AGP from 8.7.3 to 9.1.1, Gradle from 8.9 to 9.5.1, compileSdk/targetSdk from 34 to 36, Java from 17 to 21. Remove Kotlin plugin (AGP 9.x has built-in Kotlin support).
- **Reason**: Play Store requires targetSdk ≥ 35 for new submissions. Zazentimer already uses AGP 9.1.1 + API 36 + Java 21 successfully.
- **Considered**: Targeting only API 35 (AGP 8.7.3 supports it, smaller change)
- **Tradeoff**: AGP 9.x drops the `org.jetbrains.kotlin.android` plugin — `kotlinOptions {}` block no longer available. JVM target derived from `compileOptions`.

## 2026-06-04: Internal track for CI releases (production blocked)
- **Choice**: Change `release.yml` from `tracks: production` to `tracks: internal` with `status: completed`.
- **Reason**: Production track has precondition checks (Content Rating, Data Safety, App Signing) that block commits. Internal track accepts `completed` releases without precondition validation.
- **Considered**: Keeping `status: draft` on production (still fails precondition checks)
- **Tradeoff**: Releases go live on internal testing immediately. Must manually switch back to production when preconditions are resolved.

## 2026-06-04: Direct Play Console API for debugging, GitHub Action for releases
- **Choice**: Use `scripts/upload-aab.py` for testing and debugging uploads locally. Keep `r0adkll/upload-google-play` for CI release automation.
- **Reason**: The GitHub Action returns opaque "Precondition check failed" with no details. The direct API gives exact error messages. Local API calls confirmed internal track works.
- **Considered**: Replacing the GitHub Action entirely with the Python script in CI
- **Tradeoff**: Two code paths for upload (one local, one CI). The Python script exists only for debugging; CI still uses the action.

## 2026-06-04: PNG-based launcher icon (VectorDrawable limitations)
- **Choice**: Replace the flat white-gold VectorDrawable launcher foreground with a `<bitmap>` drawable referencing a high-resolution PNG rendered from `distribution/play-store-icon.svg`.
- **Reason**: Android VectorDrawable (`<vector>`) does not support `<filter>`, `<pattern>`, `<clipPath>`, or `<feDropShadow>`. The high-quality SVG uses all of these for chrome gradients, mesh grille, and gold glow effects.
- **Considered**: Simplifying the SVG to remove filters/patterns (loses visual quality), keeping the flat vector (user rejected as "rudimentary")
- **Tradeoff**: PNGs are resolution-dependent. The source SVG in `distribution/` is the editable source if resolution changes are needed.

## 2026-06-04: Auto-select default models on fetch
- **Choice**: Add `default_model` to `ProviderPreset` and auto-select it during `SettingsActivity` model fetching if the dropdown is currently empty and the default is in the fetched list.
- **Reason**: Ensures the fastest models (e.g. `whisper-large-v3-turbo` for Groq) are automatically picked.
- **Considered**: Hard-coded UI defaults
- **Tradeoff**: JSON preset config requires one extra property mapping.

## 2026-06-06: Global UncaughtExceptionHandler with pop-up dialog
- **Choice**: Register `Thread.setDefaultUncaughtExceptionHandler` in `PolishedRecognitionApp.onCreate()` that extracts `exceptionType`, `exceptionMessage`, and full stack trace, then launches `CrashDialogActivity` (in separate `:crash` process) displaying an `AlertDialog` with these details. Process is killed via `Process.killProcess()` after the intent is sent.
- **Reason**: Unknown fatal crashes (e.g. free-text model search on Android 11, issue #8) leave the user with no feedback. A pop-up showing the exception type and stack trace helps users report actionable bug reports.
- **Considered**: Toast-only (cleared too fast, not inspectable), log-only (invisible to users), wrapping individual call sites (misses unexpected crashes elsewhere)
- **Tradeoff**: The `:crash` process adds ~1MB to the process count. Stack traces can be long — the dialog is scrollable. `System.exit(2)` ensures clean termination after the dialog is shown.

## 2026-06-09: F-Droid metadata in fastlane/ structure
- **Choice**: Store app description (title, short_description, full_description) in `fastlane/metadata/android/en-US/` in the main repo, and keep only build configuration in `fdroid/com.georgernstgraf.polishedrecognition.yml`
- **Reason**: F-Droid now requires upstream repos to contain fastlane metadata. The metadata YAML in `fdroiddata` should not contain Description — it's pulled from the upstream repo.
- **Considered**: Triple-T structure (same result, fastlane is more common)
- **Tradeoff**: Two files for store listing (fastlane) vs one YAML field. Updates require changes in our repo, not just the MR.

## 2026-06-09: fdroiddata worktrees in ~/repos/schurlix/
- **Choice**: Store the fdroiddata fork at `~/repos/schurlix/fdroiddata/` (GitLab user namespace) with git worktrees for each active MR under `~/repos/schurlix/fdroiddata-mr-<name>/`
- **Reason**: Avoids re-cloning the 76k-file repo for each MR operation. Worktrees share the `.git` object database. Fork is under `schurlix` (GitLab user) not `georgernstgraf` (GitHub user).
- **Considered**: /tmp clones (slow, ephemeral), single worktree in project directory
- **Tradeoff**: Worktrees must be manually cleaned up after MR merge. The fork is shallow (`--depth 1`) — full history requires separate `git fetch` for CI.

## 2026-06-09: glab for GitLab CLI access
- **Choice**: Use `glab` (GitLab CLI) for all GitLab operations (fork, branch, MR creation). Token stored in `~/.config/glab-cli/config.yml`.
- **Reason**: GitLab API via curl is verbose and error-prone. `glab` handles authentication and API calls consistently.
- **Considered**: Direct API calls via curl (used for MR description update), plain Git
- **Tradeoff**: Requires binary installation (downloaded from GitLab releases). The CLI's default MR creation creates intra-fork MRs; cross-project MRs need explicit API call with `source_project_id`.

## 2026-06-12: Backport zazentimer translate.ts improvements
- **Choice**: Port all improvements from `zazentimer/prisma/translate.ts` to `polished-recognition/translation-engine/translate.ts`
- **Reason**: zazentimer's version had proper session cleanup (`terminateSession` + `try/finally`), DB consistency validation at startup, external JSON for model providers, configurable settled threshold, dynamic `--help`, and `MIN_VOTE_PROFICIENCY` from db.ts. polished-recognition's was a stale fork with hardcoded providers, leaked sessions, and a "ZazenTimer" app_name bug.
- **Considered**: Incrementally fixing each difference (slower, more error-prone)
- **Tradeoff**: Changes also affect `db.ts` and `opencode_client.ts`. `export.ts` and `voting_api.tsx` still need updating (#25 not fully done).

## 2026-06-12: Score-based settlement with configurable threshold
- **Choice**: `getSettledStrings(langId, threshold = SETTLED_SCORE_THRESHOLD)` uses `e.score` instead of `e.modelCount`, filters by `score >= threshold` (default 7)
- **Reason**: A single high-proficiency model (level 5) settles a string only with score >= 7 (requires at least two models). Score rewards both model agreement AND individual quality. `modelCount >= 3` was too coarse.
- **Considered**: Keeping modelCount-based (user wanted score-based per #25)
- **Tradeoff**: Threshold 7 means fewer strings auto-settle. More translation work per language but higher confidence in results.

## 2026-06-12: terminateSession for session cleanup
- **Choice**: Add `abortSession()` + `terminateSession()` to `opencode_client.ts`, add `try/finally` blocks around retry loops in `dispatchProficiency` and `dispatchTranslate`
- **Reason**: polished-recognition used `closeSession()` without `try/finally` — sessions leaked on errors and timeouts. zazentimer's `terminateSession()` calls abort then close with proper error handling.
- **Considered**: Wrapping existing `closeSession` calls (doesn't guarantee execution on exception)
- **Tradeoff**: Two additional HTTP calls per session cleanup. Negligible compared to the session lifespan.

## 2026-06-12: External JSON for model providers
- **Choice**: `llmmodels_master.json` now contains `{ name, providers[] }` objects. `translate.ts` imports this JSON instead of hardcoding `MODEL_PROVIDERS_RAW`.
- **Reason**: Single source of truth for model→provider mapping. Same JSON file is also used by `seed.ts` to populate the DB. Keeps model config synchronized between translation orchestrator and voting dashboard.
- **Considered**: Keeping hardcoded map (simpler but out of sync with DB)
- **Tradeoff**: JSON import requires Deno `with { type: "json" }` syntax. The JSON must exist at build time.

## 2026-06-12: GitHub Actions build-* release cleanup filter
- **Choice**: Add `grep '^build-'` to the cleanup pipeline in both `build.yml` and `release.yml` so only `build-*` releases are counted/deleted (v* releases are excluded)
- **Reason**: The existing `build.yml` cleanup listed ALL releases and could accidentally delete `v*` Play Store release tags. `release.yml` had no cleanup at all, allowing build releases to accumulate.
- **Considered**: Separate `gh` list filtering by tag prefix (not supported by `gh release list`)
- **Tradeoff**: Pipeline now depends on `grep`. `gh release list --limit 100` is the API cap — works for repos with <100 releases.

## 2026-06-15: VERSION_DISPLAY + About section in Settings
- **Choice**: Added `GitHashSource` and `CommitCountSource` Gradle `ValueSource` classes (identical to zazentimer) generating `BuildConfig.GIT_HASH` and `BuildConfig.VERSION_DISPLAY`. Added about section at bottom of `SettingsActivity` showing version, commit hash, and the setup guide text. Removed the info button from `VoiceRecognitionActivity` (its content moved to settings).
- **Reason**: Users need to see the installed version for bug reports. The setup guide belongs in the configuration screen, not on the recording overlay. Removing the info button declutters the recording screen.
- **Considered**: Keeping info button on recording screen (redundant after settings about section)
- **Tradeoff**: Users must open Settings to find the README link and credits. Recording screen is cleaner with 3 buttons (cancel/pause-resume/stop) + settings gear.

## 2026-06-09: Remove signingConfigs.release for F-Droid [SUPERSEDED]
- **Choice**: Remove the entire `signingConfigs { release { ... } }` block and `signingConfig` from `release` build type in `app/build.gradle.kts`
- **Reason**: F-Droid's `fdroid build` could not find the signed APK output — `assembleRelease` produced no APK when a signing config with a keystore was active. Removing the signing config lets the build produce `app-release-unsigned.apk` which F-Droid finds and signs itself.
- **Considered**: Keeping signing config with `output:` directive (wrong path, APK naming mismatch), creating dummy keystore via `prebuild` (worked locally but F-Droid's Gradle setup changed output behavior)
- **Tradeoff**: Local `./gradlew assembleRelease` now produces unsigned APK. GitHub CI workflows (`build.yml`, `release.yml`) inject signing via `-Pandroid.injected.signing.*` properties, so releases remain signed.
- **Superseded by**: 2026-06-16: Conditional signingConfig (file-existence guarded) (#30) — restored local `installRelease` without disturbing F-Droid/CI.

## 2026-06-16: Conditional signingConfig (file-existence guarded)
- **Choice**: Define `signingConfigs.release` and assign `release.signingConfig` in `app/build.gradle.kts` only when `file("release.keystore").exists()`.
- **Reason**: Reconciles three conflicting requirements: (a) local `./gradlew installRelease` needs a signed release variant or AGP won't generate the install task (PITFALL #39); (b) `build.yml` and F-Droid expect unsigned `app-release-unsigned.apk`; (c) `release.yml` signs the AAB via `-Pandroid.injected.signing.*` independently. The keystore is gitignored, so it exists only on the dev machine and is absent in all CI/F-Droid environments — the `exists()` guard makes signing active locally and inert elsewhere.
- **Considered**: Command-line `-Pandroid.injected.signing.*` per local invocation (works but loses the one-command `installRelease` workflow documented in AGENTS.md), unconditional `signingConfigs` (breaks F-Droid — missing keystore file in their container).
- **Tradeoff**: Local `assembleRelease` now yields `app-release.apk` (signed) instead of `-unsigned`; harmless locally, and CI/F-Droid are unaffected (keystore absent → unsigned).

## 2026-06-16: Complete score-based settlement migration (#25)
- **Choice**: Unified all settlement logic to use proficiency-weighted scores instead of model counts. Added `TRANSLATION_SCORE_THRESHOLD=3` (for export eligibility) alongside existing `SETTLED_SCORE_THRESHOLD=7` (for full settlement). Introduced `byScoreThenCount` helper: sorts by cumulative score DESC first, then modelCount DESC as tiebreaker. Removed `level: { gte: 2 }` filter from `getEvaluation()`, `getTranslation()`, and `getStringSettlement()` DB queries — proficiency filtering now lives exclusively in the `translate.ts` orchestration layer (`MIN_VOTE_PROFICIENCY`).
- **Reason**: The DB layer should return all data; policy decisions about which models qualify belong in the orchestration layer. `byScoreThenCount` (score-first) replaces the old count-first sort because score rewards both agreement AND individual model quality — a single level-5 model agreeing with a level-2 model (score=7) should outrank three level-1 models agreeing (score=3). Zazentimer pioneered this approach; polished-recognition now matches.
- **Considered**: Keeping `level: { gte: 2 }` in DB queries (redundant with `translate.ts` filtering; couples DB layer to proficiency policy). Keeping count-first sort (too coarse — treats all models equally regardless of quality).
- **Tradeoff**: If votes exist from models without proficiency records (edge case), they are silently excluded by the `if (!level) continue` guard in scoring loops.

## 2026-06-16: DayNight-aware recording screen (#32)
- **Choice**: Created `values/colors.xml` (light) and `values-night/colors.xml` (dark) with 7 theme-aware color resources (`recording_overlay`, `recording_text_primary`, `recording_text_secondary`, `recording_text_hint`, `recording_divider`, `recording_pill_bg`, `recording_icon_tint`). Replaced all hardcoded color literals in `activity_voice_input.xml` and `bg_quick_settings_pill.xml`.
- **Reason**: The recording screen hardcoded `#E6000000` background and white text/icons. The theme inherits `Theme.Material3.DayNight`, so Settings correctly follows system light/dark — but the recording screen ignored it entirely. Users in light mode saw a jarring dark overlay.
- **Considered**: Using Material theme attributes (`?attr/colorBackground` etc.) — too generic for a translucent overlay that needs specific opacity values different from the standard surface colors.
- **Tradeoff**: Button action colors (red/blue/yellow/green) stay hardcoded — they are semantic action colors that work on both light and dark backgrounds.

## 2026-06-16: Pipeline stage callback for status messages (#32)
- **Choice**: Changed `TranscriptionStage` from an enum to a sealed class: `object RequestingStt` (no data) and `data class RequestingLlm(val wordCount: Int)`. Added optional `onStageChange: ((TranscriptionStage) -> Unit)? = null` callback parameter to `TranscriptionPipeline.transcribe()`. The activity passes a callback that updates the bottom `elapsed_text` via `runOnUiThread`.
- **Reason**: The pipeline was a single suspend function with no stage visibility. The UI could only show "Processing…" for the entire duration. Users had no feedback during potentially long STT/LLM calls. The LLM message includes the Whisper transcription word count (e.g., "~ 123 words") so users can gauge expected processing time. Sealed class chosen over enum so `RequestingLlm` can carry the word count computed in the pipeline.
- **Considered**: Splitting `transcribe()` into two public methods called sequentially from the activity (changes the API more drastically; harder to maintain atomic error handling). Using a Flow/Channel (overkill for 2 stage transitions). Using `data object` for `RequestingStt` (not supported by the project's Kotlin version; plain `object` suffices).
- **Tradeoff**: The callback runs on the IO thread (inside `withContext`); the activity must marshal UI updates via `runOnUiThread`. The `null` default ensures existing callers (tests) are unaffected.

## 2026-06-26: Single editable system prompt, automatic user message (#37)
- **Choice**: Collapse three editable prompts (system, user template, translate) to two: a single **System Prompt** that absorbs all behavioral + task instructions, and a renamed **Target Language Prompt**. The user message becomes an automatic, non-editable `{{text}}` carrier (no longer surfaced in the UI). `{{source_language}}` and `{{translate_prompt}}` now resolve into the **system** message instead of the user message.
- **Reason**: The old user template conflated task instructions with data delivery, creating two competing instruction surfaces. Moving all instructions to the system role preserves the system-level precedence/injection-safety boundary (important because arbitrary spoken content flows through) while exposing only one editable instruction field. The transcribed text as a pure user message gives a clean sandbox.
- **Considered**: Option A (eliminate system role, one user message — weakest injection boundary); Option C (keep two fields, just tidy roles — didn't reduce field count).
- **Tradeoff**: The source-language sentence wording lives in code (`TranscriptionPipeline`), not in the editable prompt — users can move/delete the `{{source_language}}` token but not reword its sentence (consistent with how `{{translate_prompt}}` already worked).

## 2026-06-26: Drop source-language sentence when Whisper is unsure (#37)
- **Choice**: `{{source_language}}` resolves to the full sentence `"The STT service transcribed audio spoken in <Name>."` when Whisper returns a language, or to an **empty string** (whole sentence dropped) when `whisper.language` is null/blank or equals `"unknown"` (case-insensitive). `capitalizeWords` only runs when a language is known, so the stray `"unknown"` literal never reaches the prompt anymore.
- **Reason**: Whisper frequently fails to identify the language; the old code then produced *"…transcribed audio spoken in unknown."* — useless noise. Treating `{{source_language}}` as a clause (sentence-or-empty), placed on its own line like `{{translate_prompt}}`, makes removal 100% robust without parsing free-text sentence boundaries in an editable prompt.
- **Considered**: Bare-name substitution + line-based removal (requires the sentence on its own line; breaks if user merges lines); sentence-parse heuristic (fragile).
- **Tradeoff**: `PromptStore.get(KEY_USER)` bypasses SharedPreferences and always returns the asset default (`{{text}}`), so stale stored user-template values from upgraders are ignored — no migration needed.

## 2026-06-27: Rename prompt template variables for clarity (#39)
- **Choice**: Renamed the two system-prompt placeholders to convey their optional/clause behavior: `{{source_language}}` -> `{{optional_source_language_info}}` and `{{translate_prompt}}` -> `{{optional_target_language_wish}}`. Renamed the Target Language Prompt UI identifiers from `translate_prompt` to `target_language_prompt` (string name, two view ids, Kotlin field) to match the field's purpose.
- **Reason**: The old names hid that both resolve to optional, whole-clause-or-empty values dropped when not applicable. The `optional_` prefix makes the drop-on-empty contract visible at the call site and in the editable prompt. The UI ids still carried the legacy `translate_prompt` name even though the field had already been renamed "Target Language Prompt" (#37).
- **Considered**: Also renaming `KEY_TRANSLATE` / JSON key `"translate"` — rejected (changes the SharedPreferences key on existing installs, needs a migration for no functional gain). Copying the placeholder name verbatim into the UI ids (`optional_target_language_wish`) — rejected; the ids describe the editable field, not the system-prompt placeholder, so `target_language_prompt` is semantically correct.
- **Tradeoff**: Historical DECISIONS/HISTORY entries still mention the old placeholder names; left intact as a historical record rather than rewritten.

## 2026-06-27: Fail-fast prompt default loading (#39)
- **Choice**: Removed `PromptStore.loadDefaults`'s hardcoded string fallback. Asset loading (`prompts.json`) now fails fast on a missing/corrupt file (`error("prompts.json parsed to null")` for the null-return case; exceptions propagate) instead of silently substituting stale built-in strings.
- **Reason**: The fallback was a second source of truth that had already drifted from `prompts.json` (stale wording). It also gave incomplete protection: `gson.fromJson` returning `null` slipped past the `catch` and NPE'd later. A single source of truth removes the drift class of bug entirely. The unit test (`PromptStoreTest`) loads the real merged asset via Robolectric, so a malformed `prompts.json` fails `./gradlew test` (and the pre-push hook) before it can ship.
- **Considered**: Keep the fallback but sync it to `prompts.json` — rejected (reintroduces dual-source drift the next time the prompt is edited). Keep it for graceful degradation — rejected (a stale, untested default silently degrading is worse than a loud, debuggable failure).
- **Tradeoff**: A missing/corrupt `prompts.json` now crashes the lazy `PromptStore` construction (first transcription) instead of limping on. Mitigated by the test/pre-push guardrail and by the file being a committed, unfiltered asset.

## 2026-06-27: Rename prompt placeholders to symmetric *_clause (#39, 2nd round)
- **Choice**: Renamed the two system-prompt placeholders to a parallel `*_clause` pattern: `{{optional_source_language_info}}` -> `{{source_language_clause}}` and `{{optional_target_language_wish}}` -> `{{target_language_clause}}`. Renamed internals: `PromptStore.translatePromptTemplate` -> `targetLanguageClauseTemplate`, `TranscriptionPipeline` local `translatePrompt` -> `targetLanguageClause`. Renamed the Target Language Prompt UI identifiers `target_language_prompt` -> `target_language_clause` (string key + value "Target Language Clause", 2 view ids, Kotlin field `targetLanguageClauseField`).
- **Reason**: "clause" directly expresses that both resolve to an optional, whole-clause-or-empty value dropped when not applicable — more intuitive than the earlier `optional_*` prefix. Symmetric `*_clause` naming keeps the two placeholders parallel. The internals and UI ids still carried legacy "translate"/"prompt" wording that no longer matched the field's role.
- **Considered**: Keep the `optional_*` naming from the first #39 round — rejected; "clause" reads better and the user wanted the pair parallel. Rename `KEY_TRANSLATE` / JSON key `"translate"` — rejected again (SharedPreferences key change on existing installs).
- **Tradeoff**: Historical DECISIONS/HISTORY/STATE entries still mention the intermediate `optional_*` (and older `source_language`/`translate_prompt`) names; left intact as a record.

## 2026-06-27: Log exact LLM request as pretty JSON, skip raw mode (#40)
- **Choice**: `PromptLogger` now writes the verbatim `ChatRequest` sent to the LLM, pretty-printed (2-space indent, `GsonBuilder().setPrettyPrinting()`), to `prompt.json` (+ rotating history `prompt_1.json` … `prompt_9.json`, `maxCount` 5 -> 9). The log call moved from before the raw-mode early-return to after the `ChatRequest` is built, so raw mode (which never calls the LLM) produces no log. `PromptLogger.log(content: String)` is now a dumb rotating writer; `TranscriptionPipeline` owns serialization. Legacy `prompt*.md` files are swept on construction.
- **Reason**: The old behavior logged a markdown rendering of the resolved system/user strings even in raw mode (a prompt never sent) — misleading. Logging the actual `ChatRequest` JSON gives an exact, debuggable record of what went to the provider; pretty-printing makes it human-readable.
- **Considered**: Keep logging in raw mode — rejected (it documents a request that never happens). Log compact JSON byte-identical to the wire body — rejected (less readable; the data is identical, only whitespace differs). Reuse Retrofit's converter for serialization — default Gson matches it, so a standalone `GsonBuilder()` is equivalent and simpler.
- **Tradeoff**: The log is no longer byte-identical to the wire body (pretty vs compact); the test guards this by serializing the same captured `ChatRequest` with the same pretty Gson. Rotation `maxCount` 5 -> 9 retains more history.
