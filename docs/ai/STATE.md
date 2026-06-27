# Project State

Current status as of 2026-06-27.

## Current Focus
**Released 1.1.0** (`v1.1.0`, versionCode 10100) — `release.yml` triggered (Play alpha track + GitHub Release + AAB artifact). Bundles #37–#42: single editable System Prompt + Target Language Clause, STT/output whitespace trimming, rotating request/response JSON logging, build cleanup. `PRIVACY-POLICY.md` now discloses the on-device diagnostic logs.

## Completed (this cycle)
- [x] Release 1.1.0: version bump (10001→10100 / 1.0.1→1.1.0); PRIVACY-POLICY.md diagnostic-logs disclosure (date bumped); Play "What's new" in `distribution/whatsnew/whatsnew-en-GB`. Tag `v1.1.0` pushed → `release.yml` (Play alpha). **Manual follow-up: Play Console Data Safety form** (declare local transcript/provider-response log storage).
- [x] Issue #42: Build/dev housekeeping. Migrated `VoiceRecognitionActivity.onBackPressed()` to `OnBackPressedCallback`; added `testOptions.unitTests.all { it.jvmArgs("-Xshare:off") }` to clear the JVM sharing warning — `./gradlew clean test` is warning-free. New `scripts/sync-device-logs.sh` (`adb pull` logs→`tmp/`); `/tmp/` gitignored.
- [x] Issue #41: Verbatim STT/LLM response logging. New `ResponseLoggerInterceptor` (path-routed `audio/transcriptions`→`stt-response`, `chat/completions`→`llm-response`, `models` ignored) captures raw body via `peekBody`, pretty-prints (all fields, raw fallback). `PromptLogger`→`RotatingJsonLogger` (`log(baseName, content)`); `prompt.json`→`llm-prompt.json`; legacy `prompt*.json` swept. Logs error responses too. Tests: `RotatingJsonLoggerTest`, `ResponseLoggerInterceptorTest` (pure helpers), pipeline `llm-prompt.json` update.
- [x] Issue #40: Logging rework. Log the verbatim `ChatRequest` sent to the LLM (pretty JSON); moved the log call after the request build so raw mode logs nothing; `.md`→`.json`, `maxCount` 5→9; legacy `prompt*.md` swept. New tests.
- [x] Issue #39 (2nd round): Renamed placeholders to `*_clause` (`{{source_language_clause}}`, `{{target_language_clause}}`); internals `targetLanguageClauseTemplate`/`targetLanguageClause`; UI `target_language_prompt`→`target_language_clause` (key+value "Target Language Clause", ids, field). `KEY_TRANSLATE`/JSON key unchanged.
- [x] Issue #39 (1st round): Renamed placeholders to `optional_*` (since superseded by `*_clause`); `translate_prompt` UI ids → `target_language_prompt`; `PromptStore` fail-fast asset loading. Untracked `SCRATCH.md`.
- [x] Issue #37: Single editable system prompt. Merged task instructions into System Prompt; user message is now automatic `{{text}}` (removed from UI). `{{source_language_clause}}`/`{{target_language_clause}}` resolve into the system message; source-language sentence dropped entirely when Whisper returns null/blank/`"unknown"`. Removed dead `SettingsStore` prompt properties.
- [x] Issue #32: DayNight-aware recording screen + pipeline status messages.
- [x] Issue #25: Score-based settlement migration complete.

## Pending
- [~] F-Droid MR !40029 — applied maintainer's requests (`subdir: app`, removed `output:` + `UpdateCheckData:`; commit `a3347eab`, pushed 2026-06-27). Verified locally: `fdroid rewritemeta`/`lint` clean, `checkupdates` detects version without `UpdateCheckData`, `fdroid build :100` succeeds. Awaiting linsui re-review.
- [ ] F-Droid MR !39945 (Zazen Meditation Timer) — waiting for maintainer review
- [ ] Issue #20: Play Console preconditions before production track

## Blockers
None

## Next Session Suggestion
Confirm the `v1.1.0` `release.yml` run succeeded (Play alpha upload; the Play upload step is `continue-on-error` — check the Actions log + Play Console). **Update the Play Console Data Safety form** to declare local storage of transcripts + provider responses (mirrors PRIVACY-POLICY.md). On-device: pull logs via `scripts/sync-device-logs.sh` → `tmp/logs/` to verify `llm-prompt.json`/`stt-response.json`/`llm-response.json`. Then Issue #20 follow-ups or new feature work.
