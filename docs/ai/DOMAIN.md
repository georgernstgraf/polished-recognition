# Domain Knowledge

Business rules and domain relationships not obvious from code.

## Entities
- **RecognitionService**: Android system service that processes voice input. Lifecycle: `onStartListening` → capture audio → `onStopListening` → process → `callback.results()`. The system calls these in response to keyboard mic interactions.
- **OpenAI API compatibility**: Not just OpenAI — GROQ, OpenRouter, ZAI, Ollama, LM Studio all implement the same contract. Two endpoints matter: `/v1/audio/transcriptions` (STT) and `/v1/chat/completions` (LLM).
- **STT Provider**: Handles speech-to-text. Must provide a `/v1/audio/transcriptions` endpoint accepting multipart audio uploads — WAV (`audio/wav`) always; Ogg/Opus (`audio/ogg`) when the user enables `compress_audio`.
- **LLM Provider**: Handles text post-processing. Must provide a `/v1/chat/completions` endpoint accepting standard chat message arrays.

## Rules
- The service is stateless: each voice input session starts fresh. No history is kept.
- Raw mode bypasses LLM entirely — Whisper text is returned directly to the keyboard.
- Translation mode injects an additional instruction into the **system** prompt via `{{target_language_clause}}`.
- Source language detection comes from Whisper's response `language` field (ISO 639-1 code), mapped to human-readable via LanguageMapper. When the field is null/blank (or literally `"unknown"`), the `{{source_language_clause}}` sentence is dropped entirely from the system prompt.
- If Whisper's language field is null/empty, a second API call is made with `response_format=json` to extract it.
- The `target_language` setting uses human-readable English names (e.g. "German", "French"), not ISO codes.
- Whisper hallucination handling (#49): the default system prompt strips only **trailing** hallucinations (e.g. "Thank you." after silence); an empty string is returned only if the entire transcription is a hallucination. Real dictation content before the hallucination is preserved.
- Audio upload format (#60): WAV (16 kHz mono 16-bit) is the default and the always-working baseline. `compress_audio` opt-in transcodes to Ogg/Opus 24 kbps purely to shrink uploads on slow connections; the transcoder is best-effort — any failure silently falls back to WAV. The conditioning (high-pass + peak normalization, amplify-only) is a comfort feature, not a requirement: the STT endpoint remains the authority on audio quality.
- **Insertion spacing rules (#66, feel-check verified 2026-09-07) — evolving, refine from longer use.** The transcription committed into the editor is padded so words never glue onto the surrounding text: a **leading space** when no whitespace precedes the cursor (field start, letters, punctuation like `.,?;)` — the original #66 complaint), a **trailing space** when no whitespace follows (including field end). Details: (a) any whitespace counts — space, tab, newline, and NBSP/U+00A0; (b) a `null` neighbor from `getTextBefore/AfterCursor` counts as "no whitespace" → the space is still added (a missing separator is worse than a doubled one; some apps don't support the text read); (c) whitespace already inside the transcription itself suppresses padding on that side (never doubled). Owner intent: these are UX rules, not spec frozen in stone — refine if longer use reveals edge cases. Sole implementation: `InsertionSpacingPolicy` (service/), tested in `InsertionSpacingPolicyTest`; changes to the rules must update both.
