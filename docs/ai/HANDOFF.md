# Hand Off

## Active
- Issue #25 — Replace modelCount-based settlement with score-based threshold
  - `translate.ts` and `db.ts` done (committed). Remaining:
    - `export.ts`: add `--min-score` flag and use `getBestTranslation`
    - `voting_api.tsx`: replace hardcoded `modelCount >= 3` with `score >= 7`
    - `db.ts`: `getAggregatedTranslations()` still uses count-based settlement
- F-Droid MR !40029 — Polished Recognition — `fdroid/fdroiddata` — waiting for maintainer review
  - Branch: `add-polished-recognition` (worktree: `~/repos/schurlix/fdroiddata-mr-polished-recognition`)
- F-Droid MR !39945 — Zazen Meditation Timer — `fdroid/fdroiddata` — reopened with review feedback addressed
  - Worktree: `~/repos/schurlix/fdroiddata-mr-zazentimer` (branch: `mr-zazentimer`)

## Completed
Issues #30, #29, #28, #27, #26, #19, #18, #17, #16, #15, #14, #5, #1 closed.
Issue #25 partial: `translate.ts` full backport, `db.ts` score-based settlement, CI cleanup filters fixed.

Last updated: 2026-06-16.
