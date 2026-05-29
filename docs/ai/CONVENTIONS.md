# Conventions

Coding patterns, naming rules, and style agreements for this project.
Follow these without question. Do not deviate unless explicitly told.

## Naming
- Package: `com.georgernstgraf.polishedrecognition`
- Files: PascalCase.kt
- Activities: `*Activity` suffix
- Services: `*Service` suffix (only one: `PolishedRecognitionService`)
- API interfaces: `OpenAi*ApiService`
- Data classes in `api/dto/`: descriptive names from API contract

## File Layout
- `api/` — Retrofit interfaces and DTOs
- `audio/` — audio capture utilities
- `config/` — settings, provider presets, language mapping
- `pipeline/` — transcription orchestration and prompt management
- `service/` — Android Service subclasses
- `ui/` — Activities and layouts
- `assets/` — JSON config files (`prompts.json`, `provider_presets.json`)

## API Patterns
- Retrofit base URLs must end with `/v1/` (or the path prefix the provider uses)
- All API calls use `Bearer <token>` authorization header
- STT multipart: `file` part with WAV, `model` and `response_format` as text/plain parts
- LLM request: standard `{"model": "...", "messages": [...]}` JSON body
- `GET /v1/models` may return 404 for providers that don't support it — fall back to free-text model input

## Configuration
- Provider configs serialized as JSON objects in SharedPreferences
- Model lists cached as JSON arrays in SharedPreferences
- Prompt defaults loaded from `assets/prompts.json`, user edits stored in SharedPreferences
- Settings keys follow snake_case convention in SharedPreferences

## Prompt Variables
The transcription pipeline resolves the following template variables at runtime:
- `{{text}}` — raw Whisper transcription output
- `{{source_language}}` — human-readable language name (e.g. "English") from Whisper `language` field
- `{{target_language}}` — the user's chosen output language
- `{{translate_prompt}}` — empty string if no translation, otherwise the resolved translate prompt
