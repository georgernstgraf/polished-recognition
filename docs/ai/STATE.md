# Project State

Current status as of 2026-06-15.

## Current Focus
Polished Recognition Android app — About section with VERSION_DISPLAY.

## Completed (this cycle)
- [x] Issue #30: Restored `./gradlew installRelease` via conditional `signingConfig` in `app/build.gradle.kts`, guarded by `file("release.keystore").exists()`. Safe for build.yml/release.yml/F-Droid (keystore absent there → unsigned output). Local APK now `app-release.apk` (signed).
- [x] Issue #28: Added about section to SettingsActivity with `BuildConfig.VERSION_DISPLAY`, `BuildConfig.GIT_HASH`, and setup guide text (moved from VoiceRecognitionActivity info dialog)
- [x] Removed info button from VoiceRecognitionActivity (layout + code) — recording screen now has only settings, cancel, pause/resume, stop
- [x] Added `GitHashSource` and `CommitCountSource` Gradle ValueSource classes (like zazentimer) to `app/build.gradle.kts`

## Pending
- [ ] Issue #25 remaining: `export.ts` and `voting_api.tsx` need score-based threshold updates
- [ ] Issue #25 remaining: `getAggregatedTranslations()` in db.ts still uses count-based settlement
- [ ] F-Droid MR !40029 — waiting for maintainer review
- [ ] F-Droid MR !39945 — waiting for maintainer review
- [ ] Issue #20: Play Console preconditions before production track

## Blocker
None

## Next Session Suggestion
Finish issue #25: update `export.ts` with `--min-score` and `getBestTranslation`, update `voting_api.tsx` hardcoded `modelCount >= 3` → `score >= 7`.
