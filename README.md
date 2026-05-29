# Polished Recognition

OpenAI-compatible voice input for any Android keyboard. A `RecognitionService` that captures voice from the keyboard microphone and routes it through configurable STT and LLM providers for transcription and post-processing.

## How It Works

```
[Keyboard mic] → [RecognitionService] → [Whisper STT] → [LLM cleanup/translate] → [Text inserted]
```

* No custom keyboard required — works with Gboard, SwiftKey, OpenBoard, and any other keyboard
* Provider-agnostic — any OpenAI-compatible `/v1/audio/transcriptions` and `/v1/chat/completions` endpoint
* Editable prompts with template variables (`{{text}}`, `{{source_language}}`, `{{target_language}}`)

## Providers

Configured via presets or custom URLs. All providers speaking the OpenAI API contract are supported:

| Provider | Type | Base URL |
|----------|------|----------|
| GROQ Whisper | STT | `https://api.groq.com/openai/v1/` |
| OpenAI Whisper | STT | `https://api.openai.com/v1/` |
| OpenAI | LLM | `https://api.openai.com/v1/` |
| OpenRouter | LLM | `https://openrouter.ai/api/v1/` |
| GROQ | LLM | `https://api.groq.com/openai/v1/` |
| Ollama (local) | LLM | `http://localhost:11434/v1/` |
| LM Studio (local) | LLM | `http://localhost:1234/v1/` |

Add custom providers by entering any base URL + API token.

## Features

* **Provider validation** — `GET /v1/models` validates tokens and fetches available models
* **Raw mode** — skip LLM post-processing, return Whisper text directly
* **Translation** — configure a target language; the LLM translates the output
* **Editable prompts** — system, user template, and translate prompt, with restore-to-default
* **Foreground notification** — keeps the service alive during voice recognition

## Quick Start

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installation:
1. Open the app and configure STT + LLM providers with API tokens
2. Use **Validate & Fetch Models** to verify tokens and load available models
3. Go to **Settings → System → Languages & Input → Voice Input**
4. Select **Polished Recognition** as the voice input provider
5. Open any app, tap the keyboard microphone, speak, and the processed text appears

## Build

| Command | Result |
|---------|--------|
| `./gradlew assembleDebug` | Debug APK |
| `./gradlew assembleRelease` | Release APK (minified) |
| `./gradlew clean` | Clean build artifacts |

## Tech Stack

| Component | Choice | Reason |
|-----------|--------|--------|
| Language | Kotlin | Standard Android |
| HTTP | Retrofit + OkHttp | Proven, flexible |
| JSON | Gson | Lightweight |
| Settings UI | AppCompat + Material | XML-based, no Compose overhead |
| Storage | SharedPreferences | Simple, no Room for config-only app |
| DI | Manual (Application class) | RecognitionService conflicts with Hilt |
| Async | Kotlin Coroutines | Standard |

**No Hilt, no Room, no WorkManager, no Compose.** Deliberately minimal — the RecognitionService needs no persistent data and no complex UI framework.

## Project Structure

```
app/src/main/java/com/georgernstgraf/polishedrecognition/
├── PolishedRecognitionApp.kt        # Application — manual DI wiring
├── api/
│   ├── OpenAiSttApiService.kt       # Generic STT interface (all providers)
│   ├── OpenAiChatApiService.kt      # Generic LLM interface (all providers)
│   └── dto/ApiDtos.kt               # Request/response DTOs
├── audio/
│   └── AudioRecorder.kt             # AudioRecord → WAV
├── config/
│   ├── ProviderPresets.kt           # Loads provider_presets.json
│   ├── SettingsStore.kt             # SharedPreferences wrapper
│   └── LanguageMapper.kt            # ISO 639-1 → human-readable language names
├── pipeline/
│   ├── TranscriptionPipeline.kt     # STT → LLM orchestrator
│   └── PromptStore.kt               # Editable prompt storage
├── service/
│   └── PolishedRecognitionService.kt # RecognitionService implementation
└── ui/
    └── SettingsActivity.kt          # Provider config + prompt editor
```

## License

MIT
