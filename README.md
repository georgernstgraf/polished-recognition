# Polished Recognition

Your keyboard already has a microphone button.  
This is what happens when you press it.

Most voice typing apps send audio to a single cloud provider.  
**Polished Recognition** is different. It gives you a **two-stage AI pipeline** —  
speech-to-text + optional LLM clean-up — connected to **any OpenAI-compatible provider**  
you choose. Bring your own API key (or run a local model) and take full control.

### How it works

1. Press the mic on your keyboard → recording starts instantly — no extra tap
2. Speak naturally — pause/resume anytime mid-sentence
3. Tap Send → audio goes to your STT provider (Whisper, GROQ, etc.)
4. The text gets polished by an LLM using your own custom prompt — clean up filler words, fix punctuation, translate to another language
5. Flawless text appears in your text field — as if you typed it perfectly

### What makes it different

- **Not a keyboard.** Works with AnySoftKeyboard, OpenBoard, Samsung Keyboard —  
  any keyboard that respects the system voice input setting. No need to learn a new typing UI.
- **BYO provider — or use a free one.** 15+ presets (OpenAI, GROQ, OpenRouter, Google AI,  
  DeepSeek, xAI, Mistral, local Ollama/LM Studio…). GROQ gives you free Whisper STT  
  and the excellent `gpt-oss-120b` LLM — a full STT+polish pipeline at zero cost.
- **Custom prompts.** Every stage of the pipeline is editable — system prompt,  
  user prompt with `{{text}}` templates, and a separate translate prompt.  
  Make the LLM format as markdown, translate to French, or just fix punctuation.
- **Pause & resume.** Get interrupted mid-dictation? Pause, handle it, resume  
  exactly where you left off — the entire recording buffers, nothing is lost.
- **Raw mode.** Skip the LLM entirely and get plain STT output. Zero latency overhead.
- **Privacy by design.** No account, no registration, no central server.  
  Your audio goes only to the provider you configured. Run Ollama locally for full offline use.
- **Searchable model picker.** Type to filter 600+ OpenRouter models by substring.  
  No infinite scrolling through dropdowns.

### The problem it solves

Gboard's voice typing is fast — until you need it in a language it doesn't support,  
or you want the text formatted before insertion, or you don't want Google touching  
your audio. Polished Recognition hands the pipeline **to you**: pick the STT model,  
pick the LLM, write the prompt, control the data.

*No budget? GROQ's free tier runs Whisper STT + gpt-oss-120b for a complete pipeline —  
transcribe, clean up, translate — all at zero cost. Just add your API key.*

## Download

[Download app-release.apk](https://github.com/georgernstgraf/polished-recognition/releases/latest/download/app-release.apk)

### Install via ADB

```bash
adb install app-release.apk
```

### Set as Voice Input

```bash
adb shell settings put secure voice_recognition_service com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.service.PolishedRecognitionService

adb shell settings get secure voice_recognition_service   # Verify
```

Then install [AnySoftKeyboard](https://play.google.com/store/apps/details?id=com.menny.android.anysoftkeyboard)
and press the microphone button.

---

## How It Works

1. You press the microphone button on your keyboard
2. Our `VoiceRecognitionActivity` overlay opens and starts recording immediately
3. Tap the **Send** button to forward the audio to the STT pipeline
4. Audio is sent to your configured STT provider (Whisper, Groq, etc.)
5. The transcribed text is post-processed by an LLM using a **fully configurable prompt** — the core feature that enables cleanup, formatting, and translation. Small flash models (e.g. `gpt-oss-120b` on GROQ) run in under a second, avoiding the latency of larger models while producing excellent results.
6. Tap **Pause** at any time to suspend recording, change settings or prompts, then tap **Resume** — recording picks up seamlessly where you left off
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

### 2. Recommended Setup (GROQ)

For the best experience, we strongly recommend using **Groq** as your API provider. Groq utilizes custom LPU hardware that runs AI models blazingly fast.

1. **Sign up for free** at [console.groq.com](https://console.groq.com) and create an API key
2. **Speed:** Groq runs OpenAI's Whisper model at up to 300x real-time speed, guaranteeing sub-second voice typing.
3. **Cost:** Groq provides a highly generous free tier (roughly 30 requests per minute) that makes typical daily personal keyboard usage **completely free**.

### 3. Set Up Recognition Service on Device

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

#### Samsung (Android 11)
On Samsung devices running Android 11, the "Voice input" option is hidden from
the settings UI. Set the recognition service via ADB:

```bash
adb shell settings put secure voice_recognition_service com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.service.PolishedRecognitionService
```

Verify with:

```bash
adb shell settings get secure voice_recognition_service
```

#### OnePlus / OxygenOS (Android 12)
1. **Settings → Apps → Default apps → Digital assistant app → Voice input**
2. Select **Polished Recognition**

If "Voice input" is not visible after scrolling, reboot the device — OxygenOS
rebuilds the menu on boot. On any device where the Voice input menu is missing,
use the ADB command shown in the Samsung section above.

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
- **Send** — forwards audio to the STT pipeline for transcription
- **Pause / Resume** — suspend dictation at any time and resume later
- **Settings gear** — tap the gear icon during recording to open settings

### Settings

When configured as both a `RecognitionActivity` and a `RecognitionService`, the
settings are available during recording via the gear icon on the voice overlay.
This allows adjusting provider configs and prompts without leaving the recording
screen.

- **Searchable model dropdowns** — type to filter 600+ models via substring matching
- **Separate URL fields** — editable base URLs for each provider
- **Test Token** — validates LLM tokens with a minimal chat request (avoids unauthenticated `/v1/models` endpoints)
- **Fetch Models** — pulls available models from any OpenAI-compatible provider
- **Fully customizable prompts** — every pipeline stage is editable in Settings: System Prompt (LLM behavior), User Prompt (transcription cleanup with `{{text}}`, `{{source_language}}`, `{{translate_prompt}}`, `{{target_language}}`), and Translate Prompt (translation instruction with `{{target_language}}`). Individual prompts can be restored to defaults, or all at once.
- **Raw mode** — skip LLM post-processing, return STT text directly
- **Prompt logging** — resolved prompts written to rotating log files (pull via `adb pull /sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/`)

### Custom Prompts

Every stage of the transcription pipeline is fully customizable through
three editable prompts in Settings:

| Prompt | Template Variables | Purpose |
|--------|-------------------|---------|
| **System Prompt** | — | Controls the LLM's behavior and output constraints |
| **User Prompt** | `{{text}}`, `{{source_language}}`, `{{translate_prompt}}`, `{{target_language}}` | Template for transcription cleanup and formatting |
| **Translate Prompt** | `{{target_language}}` | Translation instruction inserted when a target language is set |

- Prompts are stored in `SharedPreferences` and persisted across sessions
- Individual prompts can be restored to their defaults via the **Reset** button next to each field
- All prompts can be restored at once via **Restore all prompts to default**
- When **Target Language** is set to `None (no translation)`, the translate prompt is omitted entirely
- Raw mode (skip LLM) bypasses all prompts and returns the STT text directly

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

## Developer Documentation

Technical details (architecture, decisions, conventions, pitfalls) are
maintained in [`docs/ai/`](docs/ai/). Start with `HANDOFF.md`.

## License

MIT
