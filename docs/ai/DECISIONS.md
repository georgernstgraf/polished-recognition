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

## 2026-05-29: AudioRecord + WAV in-memory
- **Choice**: `AudioRecord` capturing PCM 16-bit 16kHz mono, with manual 44-byte WAV header
- **Reason**: RecognitionService needs raw audio for the STT API. `MediaRecorder` produces compressed formats (AAC) requiring conversion. In-memory WAV is ~50 lines of header byte manipulation. No temp file I/O during recording.
- **Considered**: MediaRecorder + temp file + FFmpeg conversion
- **Tradeoff**: WAV is uncompressed — larger than AAC for long recordings. Fine for short voice input (typically <30s).
