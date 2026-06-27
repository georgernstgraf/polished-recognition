# Project State

Current status as of 2026-06-27.

## Current Focus
Issues #39 (2nd round) and #40 complete. Prompt placeholders renamed to a symmetric `*_clause` pattern (`{{source_language_clause}}` / `{{target_language_clause}}`); Target Language Prompt UI → "Target Language Clause" (`target_language_clause` ids); internals renamed (`targetLanguageClauseTemplate`, `targetLanguageClause`). Prompt logging reworked: writes the exact LLM `ChatRequest` as pretty-printed JSON (`prompt.json` + `prompt_1.json`…`prompt_9.json`), no log in raw mode.

## Completed (this cycle)
- [x] Issue #40: Logging rework. Log the verbatim `ChatRequest` sent to the LLM (pretty JSON, `GsonBuilder().setPrettyPrinting()`); moved the log call after the request build so raw mode logs nothing; `.md`→`.json`, `maxCount` 5→9; `PromptLogger.log(content)` is a dumb rotating writer (pipeline owns serialization); legacy `prompt*.md` swept on init. New `PromptLoggerTest` + pipeline logging tests.
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
Manual on-device verification of #37/#39/#40 (dictate with/without target language; confirm `logs/prompt.json` shows the resolved pretty-printed `ChatRequest` with the `*_clause` placeholders and a `{{text}}`-only user message; confirm raw mode writes no log), then Issue #20 (Play Console preconditions) or new feature work.
