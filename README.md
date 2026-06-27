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

## Installation

See the **[Installation Guide](INSTALLATION.md)** for step-by-step setup instructions (English & German). Covers Play Store installation (closed testing), configuring providers, setting the voice input service, device-specific notes, and AnySoftKeyboard.

---

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
- **Fully customizable prompts** — two editable prompts in Settings: System Prompt (LLM behavior + transcription cleanup instructions, with `{{source_language_clause}}` and `{{target_language_clause}}` variables) and Target Language Clause (translation instruction with `{{target_language}}`). The transcribed text is sent automatically as the user message. Individual prompts can be restored to defaults, or all at once.
- **Raw mode** — skip LLM post-processing, return STT text directly
- **Prompt logging** — each LLM request is written verbatim (pretty-printed JSON) to rotating log files (`prompt.json` + history `prompt_1.json` … `prompt_9.json`); not written in raw mode. Pull via `adb pull /sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/`

### Custom Prompts

The transcription pipeline has two editable prompts in Settings:

| Prompt | Template Variables | Purpose |
|--------|-------------------|---------|
| **System Prompt** | `{{source_language_clause}}`, `{{target_language_clause}}` | Controls the LLM's behavior, output constraints, and transcription cleanup instructions |
| **Target Language Clause** | `{{target_language}}` | Translation instruction inserted into the system prompt when a target language is set |

- The transcribed text is sent automatically as the user message (`{{text}}`); it is not user-editable
- `{{source_language_clause}}` resolves to a full sentence ("The STT service transcribed audio spoken in German.") and is dropped entirely when Whisper returns no language
- Prompts are stored in `SharedPreferences` and persisted across sessions
- Individual prompts can be restored to their defaults via the **Reset** button next to each field
- All prompts can be restored at once via **Restore all prompts to default**
- When **Target Language** is set to `None (no translation)`, the target language clause is omitted entirely
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

**Recording fails (no audio)?** Check that `RECORD_AUDIO` permission is granted.
The voice overlay requests it on first use.

**STT model not working?** If the model dropdown is empty, press **Validate & Fetch Models**
after entering a valid API token. The model list is fetched dynamically from the provider.

**Setup issues?** See the **[Installation Guide](INSTALLATION.md)** for device-specific help.

## Developer Documentation

Technical details (architecture, decisions, conventions, pitfalls) are
maintained in [`docs/ai/`](docs/ai/). Start with `HANDOFF.md`.

## License

MIT
