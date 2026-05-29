# Architecture

Living structural map of the system as of 2026-05-29.

## Overview

Polished Recognition is an Android `RecognitionService` that provides
OpenAI-compatible voice input for any Android keyboard. It captures
audio via AudioRecord, transcribes via any `/v1/audio/transcriptions`
endpoint, and optionally post-processes via any `/v1/chat/completions`
endpoint. Results are returned to the keyboard through the standard
Android speech recognition callback.

## Components

| Component | Package | Role |
|-----------|---------|------|
| `PolishedRecognitionApp` | root | Application class — manual DI, OkHttpClient singleton, Retrofit cache per baseUrl |
| `PolishedRecognitionService` | service | Extends `RecognitionService` — foreground notification, audio capture, pipeline call, callback.results() |
| `TranscriptionPipeline` | pipeline | Orchestrates STT → (optional) LLM flow. Resolves prompt templates at runtime |
| `PromptStore` | pipeline | Loads prompt defaults from `assets/prompts.json`, persists edits in SharedPreferences |
| `OpenAiSttApiService` | api | Generic Retrofit interface: `POST audio/transcriptions`, `GET models` |
| `OpenAiChatApiService` | api | Generic Retrofit interface: `POST chat/completions`, `GET models` |
| `AudioRecorder` | audio | AudioRecord wrapper: PCM 16kHz mono → WAV ByteArray |
| `SettingsStore` | config | SharedPreferences: provider configs, raw mode, target language, cached model lists |
| `ProviderPresetLoader` | config | Loads and queries `assets/provider_presets.json` |
| `LanguageMapper` | config | Maps ISO 639-1 codes to human-readable names |
| `SettingsActivity` | ui | XML-based: provider dropdowns, token fields, validate buttons, raw/translate toggles, prompt editors, restore defaults |

## Data Flows

- Keyboard mic tap → System → `PolishedRecognitionService.onStartListening()` → `AudioRecorder.start()`
- Keyboard mic release → System → `PolishedRecognitionService.onStopListening()` → `AudioRecorder.stop()` → WAV bytes → temp file → `TranscriptionPipeline.transcribe()`
- Pipeline: WAV file → `OpenAiSttApiService.transcribeAudio()` → STT text → (raw mode: return text) → resolve prompts → `OpenAiChatApiService.chat()` → LLM text
- Result → `callback.results()` → System → Keyboard → `InputConnection.commitText()`

## Knowledge Files (`docs/ai/`)

| File | Purpose | Update mode |
|------|---------|------------|
| HANDOFF.md | Open tasks for next session | Overwrite |
| DECISIONS.md | Chronological record of choices | Append |
| ARCHITECTURE.md | Living structural map | Overwrite on change |
| CONVENTIONS.md | Ongoing rules to follow | Append |
| PITFALLS.md | Hard-won failure knowledge | Append |
| DOMAIN.md | Business/domain rules | Append |
| STATE.md | Current project status | Overwrite |
