# Project State

Current status as of 2026-06-27.

## Current Focus
Issue #41 complete. Three rotating pretty-JSON logs now: `llm-prompt.json` (request; renamed from `prompt.json`), `stt-response.json` (raw STT response, all fields), `llm-response.json` (raw LLM response, all fields) — each with history `*_1.json`…`*_9.json`. Responses captured verbatim via an OkHttp interceptor (`ResponseLoggerInterceptor`); `PromptLogger` generalized to `RotatingJsonLogger.log(baseName, content)`.

## Completed (this cycle)
- [x] Issue #41: Verbatim STT/LLM response logging. New `ResponseLoggerInterceptor` (path-routed `audio/transcriptions`→`stt-response`, `chat/completions`→`llm-response`, `models` ignored) captures raw body via `peekBody`, pretty-prints (all fields, raw fallback). `PromptLogger`→`RotatingJsonLogger` (`log(baseName, content)`); `prompt.json`→`llm-prompt.json`; legacy `prompt*.json` swept. Logs error responses too. Tests: `RotatingJsonLoggerTest`, `ResponseLoggerInterceptorTest` (pure helpers), pipeline `llm-prompt.json` update.
- [x] Issue #40: Logging rework. Log the verbatim `ChatRequest` sent to the LLM (pretty JSON); moved the log call after the request build so raw mode logs nothing; `.md`→`.json`, `maxCount` 5→9; legacy `prompt*.md` swept. New tests.
- [x] Issue #39 (2nd round): Renamed placeholders to `*_clause` (`{{source_language_clause}}`, `{{target_language_clause}}`); internals `targetLanguageClauseTemplate`/`targetLanguageClause`; UI `target_language_prompt`→`target_language_clause` (key+value "Target Language Clause", ids, field). `KEY_TRANSLATE`/JSON key unchanged.
- [x] Issue #39 (1st round): Renamed placeholders to `optional_*` (since superseded by `*_clause`); `translate_prompt` UI ids → `target_language_prompt`; `PromptStore` fail-fast asset loading. Untracked `SCRATCH.md`.
- [x] Issue #37: Single editable system prompt. Merged task instructions into System Prompt; user message is now automatic `{{text}}` (removed from UI). `{{source_language_clause}}`/`{{target_language_clause}}` resolve into the system message; source-language sentence dropped entirely when Whisper returns null/blank/`"unknown"`. Removed dead `SettingsStore` prompt properties.
- [x] Issue #32: DayNight-aware recording screen + pipeline status messages.
- [x] Issue #25: Score-based settlement migration complete.

## Pending
- [ ] F-Droid MR !40029 — waiting for maintainer review
- [ ] F-Droid MR !39945 (Zazen Meditation Timer) — waiting for maintainer review
- [ ] Issue #20: Play Console preconditions before production track

## Blockers
None

## Next Session Suggestion
Manual on-device verification of #37/#39/#40/#41 (dictate with/without target language; confirm `logs/llm-prompt.json`, `logs/stt-response.json`, `logs/llm-response.json` show the resolved pretty JSON — full STT segments + LLM `usage` fields present; confirm raw mode writes no `llm-prompt.json`), then Issue #20 (Play Console preconditions) or new feature work.
