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
- When setting `TextInputLayout.error` from validation logic, always add a `TextWatcher` to the corresponding `TextInputEditText` that clears both `error = null` and `helperText = null` on text change. This ensures the red error state disappears as the user corrects their input.
- Transcription errors in `RecognitionService` and `VoiceRecognitionActivity` must show a `Toast` with the error detail — `listener.error()` alone sends an opaque error code to the keyboard that the user cannot see.
- For custom filtering on `AutoCompleteTextView`, use `BaseAdapter` + `Filterable` with a directly owned `displayItems` list. Never extend `ArrayAdapter` with a custom `Filter` that calls `clear()`/`addAll()` — these modify the internal `mOriginalValues` (not `mObjects`), corrupting state and causing crashes on text input.

## Prompt Variables
The transcription pipeline resolves the following template variables at runtime:
- `{{text}}` — raw Whisper transcription output
- `{{source_language}}` — human-readable language name (e.g. "English") from Whisper `language` field
- `{{target_language}}` — the user's chosen output language
- `{{translate_prompt}}` — empty string if no translation, otherwise the resolved translate prompt

## Build & Installation

- Always install via `./gradlew installRelease`. The release build type uses `signingConfigs.debug` for the signing key, so it installs without extra setup. `installDebug` installs a separate `.debug` suffix APK that bypasses the RecognitionService — never use it for testing voice input.
- The debug build type sets `applicationIdSuffix = ".debug"`, creating a different application ID. The system's `voice_recognition_service` setting points to the release application ID, so the debug APK will never work as a voice input provider.
- To ensure CI builds produce APKs with the same signature as local builds, store `~/.android/debug.keystore` (base64-encoded) plus storePassword/keyAlias/keyPassword as GitHub Secrets. The CI workflow decodes the keystore into `~/.android/debug.keystore` before `assembleRelease`. No `build.gradle.kts` changes needed.
- Test framework: JUnit 4 (`@Test`, `@Before`, `@After`), no JUnit 5
- Mocking: MockK 1.14.4 (`mockk(relaxed=true)`, `coEvery { ... } returns ...`, `slot<T>()`)
- Assertions: Google Truth 1.4.4 (`Truth.assertThat(...)`)
- Android unit tests: `@RunWith(RobolectricTestRunner::class)`, `RuntimeEnvironment.getApplication()` for Context
- Coroutine tests: `runBlocking { }` (sync wrapper for suspend fn tests), no `runTest` needed
- Test classes mirror source directory structure exactly
- Test methods use backtick descriptive names: `` `STT HTTP error returns failure` ``
- Integration tests: separate `integration/` package, read `.env` for API keys, `assumeTrue` to skip when keys absent
- Test resources: `src/test/resources/` for audio fixtures, `src/test/resources/robolectric.properties` for SDK config
