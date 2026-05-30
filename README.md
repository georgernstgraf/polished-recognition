# Polished Recognition

OpenAI-compatible voice input for Android keyboards. A `RecognitionService` that
captures voice from the keyboard microphone and routes it through configurable
STT and LLM providers for transcription and post-processing.

```
Keyboard mic → RecognitionService → STT (Whisper/etc) → LLM (cleanup/translate) → Text inserted
```

## How It Works

1. You press the microphone button on your keyboard
2. Our `VoiceRecognitionActivity` overlay opens and starts recording immediately
3. Tap the stop button to end recording
4. Audio is sent to your configured STT provider (Whisper, Groq, etc.)
5. Optional: the transcription is post-processed by an LLM (translate, cleanup)
6. Tap the gear icon during recording to open settings
7. Text is inserted into the text field

**No custom keyboard required** — works with any keyboard that uses the
`RECOGNIZE_SPEECH` intent (AnySoftKeyboard, etc.).

> **Note:** Gboard uses its own built-in Google speech engine and ignores the
> system RecognitionService. Install AnySoftKeyboard for voice input.

## Quick Start

### 1. Build & Install

```bash
git clone git@github.com:georgernstgraf/polished-recognition.git
cd polished-recognition
ln -sf ../../scripts/pre-push .git/hooks/pre-push   # optional: run tests before push
./gradlew installRelease                             # build + install via ADB
```

### 2. Set Up Recognition Service on Device

The app does not appear in the app drawer on all launchers (e.g. OnePlus/OxygenOS
hides it). Launch the settings activity via ADB:

```bash
adb shell am start -n "com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.ui.SettingsActivity"
```

Then configure:
- **STT Provider** (e.g. OpenAI) → enter API token → Validate & Fetch Models → pick model
- **LLM Provider** (optional) → same procedure
- **Save**

### 3. Set as Default Voice Input

#### Standard Android (Pixel, etc.)
**Settings → System → Languages & input → Voice input → Recognition service → Polished Recognition**

#### OnePlus / OxygenOS (Android 12)
1. **Settings → Apps → Default apps → Digital assistant app → Voice input**
2. Select **Polished Recognition**

If "Voice input" is not visible after scrolling, reboot the device — OxygenOS
rebuilds the menu on boot.

### 4. Install AnySoftKeyboard

Gboard uses its own Google speech engine and cannot be intercepted. Install
[AnySoftKeyboard](https://play.google.com/store/apps/details?id=com.menny.android.anysoftkeyboard)
from the Play Store, then switch to it via the globe key (🌐) next to your spacebar.

### 5. Use It

1. Switch keyboard to **AnySoftKeyboard** (globe key → AnySoftKeyboard)
2. Press the **microphone button**
3. The voice overlay appears — recording starts immediately, tap the stop square to end
4. Transcribed text is inserted into the text field

Switch back to Gboard via the globe key when typing manually.

## Providers

Configured via presets or custom URLs. All providers speaking the OpenAI API
contract are supported:

| Provider           | Type | Base URL                                            |
|--------------------|------|-----------------------------------------------------|
| GROQ Whisper       | STT  | `https://api.groq.com/openai/v1/`                   |
| OpenAI Whisper     | STT  | `https://api.openai.com/v1/`                        |
| OpenAI             | LLM  | `https://api.openai.com/v1/`                        |
| OpenRouter         | LLM  | `https://openrouter.ai/api/v1/`                     |
| Google AI Studio   | LLM  | `https://generativelanguage.googleapis.com/v1beta/openai/` |
| GROQ               | LLM  | `https://api.groq.com/openai/v1/`                   |
| DeepSeek           | LLM  | `https://api.deepseek.com/v1/`                      |
| xAI                | LLM  | `https://api.x.ai/v1/`                              |
| Mistral            | LLM  | `https://api.mistral.ai/v1/`                        |
| Together AI        | LLM  | `https://api.together.xyz/v1/`                      |
| DeepInfra          | LLM  | `https://api.deepinfra.com/v1/`                     |
| Fireworks          | LLM  | `https://api.fireworks.ai/inference/v1/`            |
| Cerebras           | LLM  | `https://api.cerebras.ai/v1/`                       |
| Perplexity         | LLM  | `https://api.perplexity.ai/`                        |
| HuggingFace        | LLM  | `https://router.huggingface.co/v1/`                 |
| NVIDIA             | LLM  | `https://integrate.api.nvidia.com/v1/`              |
| Ollama (local)     | LLM  | `http://localhost:11434/v1/`                        |
| LM Studio (local)  | LLM  | `http://localhost:1234/v1/`                         |

Add custom providers by entering any base URL + API token. Models are fetched
dynamically from each provider's `/v1/models` endpoint.

## Features

### Voice Overlay

- **Auto-start recording** — opens directly in recording mode, no extra tap
- **Elapsed timer** — monospace clock at the bottom showing recording duration
- **Stop icon** — square button to end recording (not pause)
- **Settings gear** — tap the gear icon during recording to configure providers without stopping

### Settings

When configured as both a `RecognitionActivity` and a `RecognitionService`, the
settings are available during recording via the gear icon on the voice overlay.
This allows adjusting provider configs and prompts without leaving the recording
screen.

- **Searchable model dropdowns** — type to filter 600+ models via substring matching
- **Separate URL fields** — editable base URLs for each provider
- **Test Token** — validates LLM tokens with a minimal chat request (avoids unauthenticated `/v1/models` endpoints)
- **Fetch Models** — pulls available models from any OpenAI-compatible provider
- **Editable prompts** — system, user, and translate prompts with template variables (`{{text}}`, `{{source_language}}`, `{{target_language}}`)
- **Raw mode** — skip LLM post-processing, return STT text directly
- **Prompt logging** — resolved prompts written to rotating log files (pull via `adb pull /sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/`)

## Build

| Command                     | Result                               |
|-----------------------------|--------------------------------------|
| `./gradlew installRelease`  | Build + install release APK via ADB  |
| `./gradlew assembleRelease` | Build release APK (minified)         |
| `./gradlew test`            | Run all unit tests                   |
| `./gradlew clean`           | Clean build artifacts                |

### Git Hooks

```bash
ln -sf ../../scripts/pre-push .git/hooks/pre-push
```

Runs `./gradlew test` before every push.

## Troubleshooting

**App not in app drawer?** On OnePlus/OxygenOS devices, the app does not appear
in the app drawer. Launch settings via ADB:

```bash
adb shell am start -n "com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.ui.SettingsActivity"
```

Alternatively, install AnySoftKeyboard and press the mic button — the voice
overlay will open the app regardless.

**"Voice input" not visible in settings?** On OnePlus/OxygenOS, reboot the device.
OxygenOS enumerates recognition services at boot time.

**Service works in settings but Gboard doesn't use it?** Gboard has its own
built-in Google speech engine. Use AnySoftKeyboard instead, which routes
through the system `RECOGNIZE_SPEECH` intent.

**Recording fails (no audio)?** Check that `RECORD_AUDIO` permission is granted.
The voice overlay requests it on first use.

**STT model not working?** If the model dropdown is empty, press **Validate & Fetch Models**
after entering a valid API token. The model list is fetched dynamically from the provider.

## Tech Stack

| Component    | Choice                     | Reason                                   |
|--------------|----------------------------|------------------------------------------|
| Language     | Kotlin                     | Standard Android                         |
| HTTP         | Retrofit + OkHttp          | Proven, flexible                         |
| JSON         | Gson                       | Lightweight                              |
| UI           | AppCompat + Material       | XML-based, no Compose overhead           |
| Storage      | SharedPreferences          | Simple, no Room for config-only app      |
| DI           | Manual (Application class) | RecognitionService conflicts with Hilt   |
| Async        | Kotlin Coroutines          | Structured concurrency                   |
| Animation    | ValueAnimator              | Smooth CSS-like ease-in-out blink        |
| Logging      | File-based (rotating)      | Prompt logs writable to external storage |

**No Hilt, no Room, no WorkManager, no Compose.** Deliberately minimal.

## Project Structure

```
app/src/main/java/com/georgernstgraf/polishedrecognition/
├── PolishedRecognitionApp.kt        # Application — manual DI wiring
├── api/
│   ├── OpenAiSttApiService.kt       # Generic STT interface
│   ├── OpenAiChatApiService.kt      # Generic LLM interface
│   └── dto/ApiDtos.kt               # Request/response DTOs
├── audio/
│   └── AudioRecorder.kt             # AudioRecord → WAV, RMS feedback
├── config/
│   ├── ProviderPresets.kt           # Loads provider_presets.json
│   ├── SettingsStore.kt             # SharedPreferences wrapper
│   └── LanguageMapper.kt            # ISO 639-1 → human-readable names
├── pipeline/
│   ├── TranscriptionPipeline.kt     # STT → LLM orchestrator
│   ├── PromptStore.kt               # Editable prompt storage
│   └── PromptLogger.kt              # Rotating prompt log files
├── service/
│   └── PolishedRecognitionService.kt # RecognitionService (system default)
└── ui/
    ├── SettingsActivity.kt          # Provider config + prompt editor
    └── VoiceRecognitionActivity.kt  # RECOGNIZE_SPEECH overlay
```

## License

MIT
