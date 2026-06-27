# Project State

Current status as of 2026-06-27.

## Current Focus
Issue #39 complete. Prompt template variables renamed for clarity (`{{optional_source_language_info}}` / `{{optional_target_language_wish}}`); Target Language Prompt UI ids renamed to `target_language_prompt`; `PromptStore` now fails fast on a missing/corrupt `prompts.json`. `SCRATCH.md` untracked.

## Completed (this cycle)
- [x] Issue #39: Rename prompt template variables (`{{source_language}}`->`{{optional_source_language_info}}`, `{{translate_prompt}}`->`{{optional_target_language_wish}}`); rename `translate_prompt` UI ids to `target_language_prompt` (string + 2 ids + Kotlin field); replace `PromptStore` stale string fallback with fail-fast asset loading (single source of truth). Untracked `SCRATCH.md`.
- [x] Issue #37: Single editable system prompt. Merged task instructions into System Prompt; user message is now automatic `{{text}}` (removed from UI). `{{optional_source_language_info}}`/`{{optional_target_language_wish}}` resolve into the system message; source-language sentence dropped entirely when Whisper returns null/blank/`"unknown"`. Renamed Translate Prompt → "Target Language Prompt". Removed dead `SettingsStore` prompt properties.
- [x] Issue #32: DayNight-aware recording screen + pipeline status messages.
- [x] Issue #25: Score-based settlement migration complete.

## Pending
- [ ] F-Droid MR !40029 — waiting for maintainer review
- [ ] F-Droid MR !39945 (Zazen Meditation Timer) — waiting for maintainer review
- [ ] Issue #20: Play Console preconditions before production track

## Blockers
None

## Next Session Suggestion
Manual on-device verification of #37/#39 (dictate with/without target language; check `prompt.md` log shows resolved system message with the renamed placeholders + `{{text}}`-only user message), then Issue #20 (Play Console preconditions) or new feature work.
