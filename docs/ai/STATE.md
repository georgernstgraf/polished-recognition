# Project State

Current status as of 2026-06-16.

## Current Focus
Issue #29 (quick-settings pill on recording screen) — DONE and verified on device. Next: finish #25 remaining items.

## Completed (this cycle)
- [x] Issue #29: Quick language/raw settings pill on the recording screen. Language `AutoCompleteTextView` now opens on the first tap (`focusable=false` + `focusableInTouchMode=false`, `setOnClickListener { showDropDown() }`, `threshold = Int.MAX_VALUE`, `inputType="none"`) — HTML `<select>`-like. Adapter is re-created in `refreshQuickSettings()` (called from `onResume`) so the option list repopulates after a Settings detour. RAW checkbox restore guarded by `silenceRawListener`. Layout spacing widened (status→pill 32dp, pill→buttons 48dp).
- [x] Issue #30: Restored `./gradlew installRelease` via a conditional `signingConfig` guarded by `file("release.keystore").exists()`. Safe for build.yml/release.yml/F-Droid (keystore absent there → unsigned output).
- [x] Issue #28: About section in SettingsActivity with `BuildConfig.VERSION_DISPLAY` / `GIT_HASH`.

## Pending
- [ ] Issue #25 remaining: `export.ts` (`--min-score`, `getBestTranslation`), `voting_api.tsx` (`score >= 7`), `getAggregatedTranslations()` in `db.ts` (still count-based)
- [ ] F-Droid MR !40029 — waiting for maintainer review
- [ ] F-Droid MR !39945 (Zazen Meditation Timer) — waiting for maintainer review
- [ ] Issue #20: Play Console preconditions before production track

## Blocker
None

## Next Session Suggestion
Finish #25: update `export.ts` with `--min-score` and `getBestTranslation`; update `voting_api.tsx` hardcoded `modelCount >= 3` → `score >= 7`; convert `getAggregatedTranslations()` in `db.ts` to score-based.
