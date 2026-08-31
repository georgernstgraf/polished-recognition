# Conventions

Coding patterns, naming rules, and style agreements for this project.
Follow these without question. Do not deviate unless explicitly told.

## Naming
- Package: `com.georgernstgraf.polishedrecognition`
- Files: PascalCase.kt
- Activities: `*Activity` suffix
- Services: `*Service` suffix (only one: `PolishedRecognitionService`)
- API interfaces: `OpenAi*ApiService`
- Data classes in `api/dto/`: descriptive names from API contract

## File Layout
- `api/` — Retrofit interfaces and DTOs
- `audio/` — audio capture utilities
- `config/` — settings, provider presets, language mapping
- `pipeline/` — transcription orchestration and prompt management
- `service/` — Android Service subclasses
- `ui/` — Activities and layouts
- `assets/` — JSON config files (`prompts.json`, `provider_presets.json`)

## API Patterns
- Retrofit base URLs must end with `/v1/` (or the path prefix the provider uses)
- All API calls use `Bearer <token>` authorization header
- STT multipart: `file` part with WAV, `model` and `response_format` as text/plain parts
- LLM request: standard `{"model": "...", "messages": [...]}` JSON body
- `GET /v1/models` may return 404 for providers that don't support it — fall back to free-text model input

## Configuration
- Provider configs serialized as JSON objects in SharedPreferences
- Model lists cached as JSON arrays in SharedPreferences
- Prompt defaults loaded from `assets/prompts.json`, user edits stored in SharedPreferences
- Settings keys follow snake_case convention in SharedPreferences

## UI Patterns
- **Settings + CrashDialog are theme-free** (since #45): `SettingsActivity` + `CrashDialogActivity` extend plain `android.app.Activity`, use `Theme.PolishedRecognition.Plain` → `Theme.DeviceDefault.DayNight`, and contain only plain platform Views (`EditText`, `CheckBox`, `Button`, `AutoCompleteTextView`, `ImageButton`). NEVER reintroduce `TextInputLayout`/`TextInputEditText`/`MaterialCheckBox`/`Widget.Material3.Button.*`/`?attr/textAppearance*`/`?attr/...` (bare) into `activity_settings.xml`, `dialog_crash.xml`, `item_language_dropdown.xml`, or `item_manage_language.xml` — they crash under `Theme.DeviceDefault`. Use `?android:attr/...` (platform), `@android:style/...`, or explicit `textSize`/`textColor`. `VoiceRecognitionActivity` + `MicrophonePermissionActivity` KEEP Material (out of scope).
- **Settings validation errors** (since #45): call `editText.error = msg` (or `autoCompleteTextView.error = msg`) — shows a red warning icon + popup. Add a `TextWatcher` that clears `error = null` on text change so the icon disappears as the user corrects. Do NOT use `TextInputLayout.error`/`helperText` (no `TextInputLayout` exists in Settings anymore). Success states use `Toast` (the Material `helperText = "Token valid"` green text is gone).
- **Settings token reveal** (since #45): each API-token `EditText` is paired with an `ImageButton` (`stt_token_toggle`/`llm_token_toggle`) whose `OnClickListener` calls `togglePassword(field, toggle)` — flips `PasswordTransformationMethod` + swaps `ic_eye`↔`ic_eye_off` + moves the cursor to the end. Do NOT re-add Material's `app:endIconMode="password_toggle"` (no `TextInputLayout`).
- **Settings provider/target-language dropdowns** (since #45): bare `AutoCompleteTextView` with `inputType="none"` needs `setOnClickListener { showDropDown() }` + `threshold = Int.MAX_VALUE` in `setupDropdowns()` (Material's exposed-dropdown tap hook is gone). Keep XML `focusable="false"`+`cursorVisible="false"`+`clickable="true"`. Model dropdowns keep `inputType="text"` + `completionThreshold="1"` (searchable).
- Transcription errors in `RecognitionService` and `VoiceRecognitionActivity` must show a `Toast` with the error detail — `listener.error()` alone sends an opaque error code to the keyboard that the user cannot see.
- For custom filtering on `AutoCompleteTextView`, use `BaseAdapter` + `Filterable` with a directly owned `displayItems` list. Never extend `ArrayAdapter` with a custom `Filter` that calls `clear()`/`addAll()` — these modify the internal `mOriginalValues` (not `mObjects`), corrupting state and causing crashes on text input.

## Prompt Variables
The transcription pipeline resolves the following template variables at runtime. The **system** prompt is the single editable instruction surface; the **user** message is an automatic, non-editable carrier containing only `{{text}}`.
- `{{text}}` — raw Whisper transcription output (resolved into the user message; also the only content of the `user` prompt template)
- `{{source_language_clause}}` — resolved into the **system** prompt as a full sentence (`"The STT service transcribed audio spoken in <Name>."`) or **empty** (whole sentence dropped) when Whisper returns null/blank/`"unknown"`
- `{{target_language}}` — the user's chosen output language (resolved into the translate prompt)
- `{{target_language_clause}}` — resolved into the **system** prompt; empty string if no translation, otherwise the resolved translate prompt

## Build & Installation

- Always install via `./gradlew installRelease`. The release build type uses `signingConfigs.release` pointing to `app/release.keystore` (copy of `~/.android/debug.keystore`) for the signing key, so it installs without extra setup. `installDebug` installs a separate `.debug` suffix APK that bypasses the RecognitionService — never use it for testing voice input.
- The debug build type sets `applicationIdSuffix = ".debug"`, creating a different application ID. The system's `voice_recognition_service` setting points to the release application ID, so the debug APK will never work as a voice input provider.
- To ensure CI builds produce APKs with the same signature as local builds, store `~/.android/debug.keystore` (base64-encoded) plus storePassword/keyAlias/keyPassword as GitHub Secrets (`RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). The CI workflow decodes the keystore into `app/release.keystore` and passes passwords via env vars to `assembleRelease`. The `signingConfigs.release` block in `app/build.gradle.kts` reads env vars, falling back to `android`/`androiddebugkey`.
- Stack: AGP 9.1.1, Gradle 9.5.1, JDK 21, compileSdk/targetSdk 36, minSdk 30. No Kotlin plugin (AGP 9.x has built-in Kotlin support). JVM target derived from `compileOptions { targetCompatibility = VERSION_21 }`.
- The `release.yml` workflow targets the `internal` track with `status: completed`. Production track is blocked by Play Console preconditions. Switch to `tracks: production` when preconditions are resolved.
- Test framework: JUnit 4 (`@Test`, `@Before`, `@After`), no JUnit 5
- Mocking: MockK 1.14.4 (`mockk(relaxed=true)`, `coEvery { ... } returns ...`, `slot<T>()`)
- Assertions: Google Truth 1.4.4 (`Truth.assertThat(...)`)
- Android unit tests: `@RunWith(RobolectricTestRunner::class)`, `RuntimeEnvironment.getApplication()` for Context
- Coroutine tests: `runBlocking { }` (sync wrapper for suspend fn tests), no `runTest` needed
- Test classes mirror source directory structure exactly
- Test methods use backtick descriptive names: `` `STT HTTP error returns failure` ``
- Integration tests: separate `integration/` package, read `.env` for API keys, `assumeTrue` to skip when keys absent
- Test resources: `src/test/resources/` for audio fixtures, `src/test/resources/robolectric.properties` for SDK config

## F-Droid
- **Active submission MR:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40029 (New App: Polished Recognition). Use `glab` CLI (authenticated as `schurlix`) for all GitLab API access — `gh` only works for GitHub.
- F-Droid metadata YAML (`fdroid/*.yml`) must NOT contain `Description:` — store text goes in `fastlane/metadata/android/<locale>/`
- `AutoUpdateMode` uses `Version` (not `VersionTag`) with `UpdateCheckMode: Tags`
- `UpdateCheckData` format: `file|versionCode_regex|.|versionName_regex` — exactly 4 pipe-separated parts, backslashes must be preserved (use Python/heredoc, not sed)
- `versionCode` and `versionName` must be static in `build.gradle.kts` for F-Droid regex extraction
- New MRs must use the "App Inclusion" template with all checkboxes
- Only one app per MR (don't include other metadata changes in the same branch)
- Fastlane `short_description.txt` must be < 80 characters (F-Droid enforces this).
- **The app is NOT on-device ML.** It uses user-configured OpenAI-compatible cloud APIs (Groq, OpenAI, OpenRouter, etc.) for STT + optional LLM refinement. The `INTERNET` permission is for these user-initiated API calls — no analytics/tracking. Testers on headless emulators see no app traffic because no API key is configured; be explicit about this in review responses.

## Documentation
- When editing `docs/privacy-policy.md`, always update the "Last updated" date to today's date.

## Documentation
- README.md is **IME-first** (since #58): the voice keyboard is the headline mode, the RecognitionService/overlay is secondary. Keep that positioning when editing.
- README screenshots live in `docs/img/` (raw 1080×2400 `adb exec-out screencap` PNGs, embedded via relative paths). Re-capture from a current release build when UI changes; do NOT reuse `distribution/*.png` (stale pre-#48/#49 UI).
- User-facing terminology in docs: **"Raw mode"** = skip LLM entirely (checkbox); **"Polish only"** = target-language "None" option (LLM polishes, no translation). Never conflate the two.
