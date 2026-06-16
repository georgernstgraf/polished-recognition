# Project State

Current status as of 2026-06-16.

## Current Focus
Issue #32 complete. Recording screen is now DayNight-aware and shows pipeline stage messages. Next: Play Console preconditions (#20) or new feature work.

## Completed (this cycle)
- [x] Issue #32: DayNight-aware recording screen (7 color resources in values/colors.xml + values-night/colors.xml, all hardcoded colors in layout replaced with @color/ references). Pipeline status messages via TranscriptionStage enum + optional onStageChange callback on TranscriptionPipeline.transcribe(). Bottom elapsed_text shows "requesting 7:23 STT…" then "requesting clean up (German)…" during API calls. 16dp horizontal padding on status line.
- [x] Issue #25: Score-based settlement migration complete.
- [x] Issue #29: Quick language/raw settings pill on the recording screen.
- [x] Issue #30: Restored `./gradlew installRelease` via conditional `signingConfig`.
- [x] Issue #28: About section in SettingsActivity.

## Pending
- [ ] F-Droid MR !40029 — waiting for maintainer review
- [ ] F-Droid MR !39945 (Zazen Meditation Timer) — waiting for maintainer review
- [ ] Issue #20: Play Console preconditions before production track

## Blocker
None

## Next Session Suggestion
Issue #20 (Play Console preconditions) or start new feature work.
