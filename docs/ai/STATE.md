# Project State

Current status as of 2026-06-26.

## Current Focus
Issue #37 complete. Prompt setup simplified to two editable prompts (System Prompt + Target Language Prompt) with an automatic `{{text}}`-only user message.

## Completed (this cycle)
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
Manual on-device verification of #37 (dictate with/without target language; check `prompt.md` log shows resolved system message + `{{text}}`-only user message), then Issue #20 (Play Console preconditions) or new feature work.
