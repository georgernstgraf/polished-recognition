# Domain Knowledge

Business rules and domain relationships not obvious from code.

## Entities
- **RecognitionService**: Android system service that processes voice input. Lifecycle: `onStartListening` → capture audio → `onStopListening` → process → `callback.results()`. The system calls these in response to keyboard mic interactions.
- **OpenAI API compatibility**: Not just OpenAI — GROQ, OpenRouter, ZAI, Ollama, LM Studio all implement the same contract. Two endpoints matter: `/v1/audio/transcriptions` (STT) and `/v1/chat/completions` (LLM).
- **STT Provider**: Handles speech-to-text. Must provide a `/v1/audio/transcriptions` endpoint accepting multipart WAV uploads.
- **LLM Provider**: Handles text post-processing. Must provide a `/v1/chat/completions` endpoint accepting standard chat message arrays.

## Rules
- The service is stateless: each voice input session starts fresh. No history is kept.
- Raw mode bypasses LLM entirely — Whisper text is returned directly to the keyboard.
- Translation mode injects an additional instruction into the **system** prompt via `{{target_language_clause}}`.
- Source language detection comes from Whisper's response `language` field (ISO 639-1 code), mapped to human-readable via LanguageMapper. When the field is null/blank (or literally `"unknown"`), the `{{source_language_clause}}` sentence is dropped entirely from the system prompt.
- If Whisper's language field is null/empty, a second API call is made with `response_format=json` to extract it.
- The `target_language` setting uses human-readable English names (e.g. "German", "French"), not ISO codes.
