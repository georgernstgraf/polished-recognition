# Project State

Current status as of 2026-06-16.

## Current Focus
Issue #25 fully complete. Translation engine fully migrated to score-based settlement. Next: Play Console preconditions (#20) or new feature work.

## Completed (this cycle)
- [x] Issue #25: Score-based settlement migration complete. `db.ts`: added `TRANSLATION_SCORE_THRESHOLD=3` + `byScoreThenCount` helper, removed `level: {gte: 2}` filter from 3 queries (proficiency filtering now lives in `translate.ts` orchestration layer), renamed `getBestTranslation`→`getTranslation` with `minScore` param, fixed `llm_modelsId`→`modelId` field bug, `getStringSettlement` now score-based with `threshold` param. `export.ts`: filters winners by `score >= TRANSLATION_SCORE_THRESHOLD`. `voting_api.tsx`: all `modelCount >= 3`→`score >= SETTLED_SCORE_THRESHOLD`, `/strings/:sid` sort/display now uses `score` not `voteCount`.
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
Issue #20 (Play Console preconditions) or start new feature work. Translation engine is fully score-based and consistent with zazentimer.
