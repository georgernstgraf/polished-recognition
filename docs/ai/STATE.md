# Project State

Current status as of 2026-06-12.

## Current Focus
Translation engine backport from zazentimer — score-based settlement, session cleanup, DB consistency validation, JSON-driven model providers.

## Completed (this cycle)
- [x] `getSettledStrings()` now uses score-based filtering with configurable threshold (default `SETTLED_SCORE_THRESHOLD = 7`)
- [x] `translate.ts` backported from zazentimer: JSON model config, `--settled-threshold` CLI arg, `terminateSession` + `try/finally`, `validateDatabaseConsistency()`, `MIN_VOTE_PROFICIENCY` from db.ts, fixed app_name
- [x] `llmmodels_master.json` now includes `providers` arrays per model
- [x] `opencode_client.ts` gains `abortSession()` and `terminateSession()`
- [x] `db.ts` gains `SETTLED_SCORE_THRESHOLD`, `MIN_VOTE_PROFICIENCY`, `parseMasterStringsXml`, `checkModelConsistency`, `checkLanguageConsistency`, `checkMasterStringConsistency`
- [x] GitHub Actions: `build.yml` cleanup filter fixed (grep `^build-`), same cleanup added to `release.yml`
- [x] Issue #25 partial implementation committed and pushed

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
