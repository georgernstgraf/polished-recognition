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

## 2026-05-30: AGP 8.7.3 + Gradle 8.9 upgrade
- **Choice**: Upgrade AGP from 8.2.2 to 8.7.3 and Gradle wrapper from 8.2 to 8.9
- **Reason**: Kotlin 2.1.20 generates metadata that R8 bundled with AGP 8.2.2 cannot parse (warning spam in release builds). Per Android docs, Kotlin 2.1 requires AGP ≥ 8.6 and R8 ≥ 8.6.17. AGP 8.7.3 is a stable patch release providing R8 8.7.x compatible with Kotlin 2.1 metadata. Gradle 8.9 is the minimum required by AGP 8.7.x.
- **Considered**: Downgrading Kotlin to 1.9.24 (simpler, zero side effects), upgrading to AGP 9.x (requires JDK 21, more API changes)
- **Tradeoff**: Requires Gradle wrapper upgrade and download of new distribution on first build. JDK 17 stays supported (AGP 9.x would require JDK 21).

## 2026-05-30: Sync Call for model listing (R8 workaround)
- **Choice**: Add non-suspend `listModelsSync` returning `Call<ModelsResponse>` to API services, use `Call.execute()` in `fetchSttModels`/`fetchLlmModels` instead of creating new Retrofit instances with suspend functions
- **Reason**: R8 full mode strips generic type info from the `Continuation<? super Response<ModelsResponse>>` parameter in Kotlin suspend functions. Retrofit's `HttpServiceMethod.parseAnnotations` casts this to `ParameterizedType`, causing `ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType` in release builds. Using a non-suspend `Call<ModelsResponse>` avoids the `Continuation` type resolution path entirely
- **Considered**: Adding ProGuard keep rules (`-keep class kotlin.coroutines.Continuation`), OkHttp direct call
- **Tradeoff**: `Call.execute()` blocks the thread (fine — already on `Dispatchers.IO`). Slightly more verbose than suspend single-line call. Keeps the shared `OkHttpClient` with connection pooling via `PolishedRecognitionApp.getSttApi/getChatApi`
