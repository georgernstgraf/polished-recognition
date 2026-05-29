# Polished Recognition

OpenAI-compatible voice input for Android keyboards. A `RecognitionService` that
captures voice from the keyboard microphone and routes it through configurable
STT and LLM providers for transcription and post-processing.

```
Keyboard mic → RecognitionService → STT (Whisper/etc) → LLM (cleanup/translate) → Text inserted
```

## How It Works

1. You press the microphone button on your keyboard
2. Our `VoiceRecognitionActivity` overlay opens (intercepting `RECOGNIZE_SPEECH`)
3. Tap the red button to start recording — tap again to stop
4. Audio is sent to your configured STT provider (Whisper, Groq, etc.)
5. Optional: the transcription is post-processed by an LLM (translate, cleanup)

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
./gradlew installDebug                               # build + install to connected device
```

### 2. Set Up Recognition Service on Device

The debug build appends `.debug` to the package name, which hides it from the
app drawer on some launchers. Launch the settings activity via ADB:

```bash
adb shell am start -n "com.georgernstgraf.polishedrecognition.debug/com.georgernstgraf.polishedrecognition.ui.SettingsActivity"
```

Then configure:
- **STT Provider** (e.g. OpenAI) → enter API token → Validate & Fetch Models → pick model
- **LLM Provider** (optional) → same procedure
- **Save**

### 3. Set as Default Voice Input

#### Standard Android (Pixel, etc.)
**Settings → System → Languages & input → Voice input → Recognition service → Polished Recognition (debug)**

#### OnePlus / OxygenOS (Android 12)
1. **Settings → Apps → Default apps → Digital assistant app → Voice input**
2. Select **Polished Recognition (debug)**

If "Voice input" is not visible after scrolling, reboot the device — OxygenOS
rebuilds the menu on boot.

### 4. Install AnySoftKeyboard

Gboard uses its own Google speech engine and cannot be intercepted. Install
[AnySoftKeyboard](https://play.google.com/store/apps/details?id=com.menny.android.anysoftkeyboard)
from the Play Store, then switch to it via the globe key (🌐) next to your spacebar.

### 5. Use It

1. Switch keyboard to **AnySoftKeyboard** (globe key → AnySoftKeyboard)
2. Press the **microphone button**
3. The voice overlay appears — tap the red button to record, tap again to stop
4. Transcribed text is inserted into the text field

Switch back to Gboard via the globe key when typing manually.

## Providers

Configured via presets or custom URLs. All providers speaking the OpenAI API
contract are supported:

| Provider        | Type | Base URL                              |
|-----------------|------|---------------------------------------|
| GROQ Whisper    | STT  | `https://api.groq.com/openai/v1/`     |
| OpenAI Whisper  | STT  | `https://api.openai.com/v1/`          |
| OpenAI          | LLM  | `https://api.openai.com/v1/`          |
| OpenRouter      | LLM  | `https://openrouter.ai/api/v1/`       |
| GROQ            | LLM  | `https://api.groq.com/openai/v1/`     |
| Ollama (local)  | LLM  | `http://localhost:11434/v1/`          |
| LM Studio       | LLM  | `http://localhost:1234/v1/`           |

Add custom providers by entering any base URL + API token.

## Build

| Command                   | Result                                  |
|---------------------------|-----------------------------------------|
| `./gradlew installDebug`  | Build debug APK, install via ADB        |
| `./gradlew assembleDebug` | Build debug APK only                    |
| `./gradlew assembleRelease` | Build release APK (minified)         |
| `./gradlew test`          | Run all unit tests                      |
| `./gradlew clean`         | Clean build artifacts                   |

### Git Hooks

```bash
ln -sf ../../scripts/pre-push .git/hooks/pre-push
```

Runs `./gradlew test` before every push.

## Troubleshooting

**App not in app drawer?** The `.debug` suffix hides it on some launchers.
Launch via ADB:
```bash
adb shell am start -n "com.georgernstgraf.polishedrecognition.debug/com.georgernstgraf.polishedrecognition.ui.SettingsActivity"
```

**"Voice input" not visible in settings?** On OnePlus/OxygenOS, reboot the device.
OxygenOS enumerates recognition services at boot time.

**Service works in settings but Gboard doesn't use it?** Gboard has its own
built-in Google speech engine. Use AnySoftKeyboard instead, which routes
through the system `RECOGNIZE_SPEECH` intent.

**Recording fails (no audio)?** Check that `RECORD_AUDIO` permission is granted.
The voice overlay requests it on first use.

## Tech Stack

| Component    | Choice                 | Reason                                   |
|--------------|------------------------|------------------------------------------|
| Language     | Kotlin                 | Standard Android                         |
| HTTP         | Retrofit + OkHttp      | Proven, flexible                         |
| JSON         | Gson                   | Lightweight                              |
| UI           | AppCompat + Material   | XML-based, no Compose overhead           |
| Storage      | SharedPreferences      | Simple, no Room for config-only app      |
| DI           | Manual (Application)   | RecognitionService conflicts with Hilt   |
| Async        | Kotlin Coroutines      | Standard                                 |

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
│   └── PromptStore.kt               # Editable prompt storage
├── service/
│   └── PolishedRecognitionService.kt # RecognitionService (legacy fallback)
└── ui/
    ├── SettingsActivity.kt          # Provider config + prompt editor
    └── VoiceRecognitionActivity.kt  # RECOGNIZE_SPEECH overlay
```

## License

MIT
