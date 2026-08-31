# Project State

Current status as of 2026-08-31 (late, verification session complete).

## Current Focus
**v1.2.0 verification batch DONE — #43/#45/#48/#53/#54/#59 all CLOSED.** All issue-driven work complete. Remaining: MR !40029 maintainer watch; the verification-round fixes (ac75231) are on master but **unreleased** — next tag should be v1.2.1 (versionCode bump required).

## Completed (this cycle)
- [x] #43 CLOSED: auxiliary voice IME verified end-to-end on device — IME bind → 18s German dictation → Groq STT (clean logprob) → qwen3.8-27b polish → `commitText` into Markor; auto-start verified (evidence under #54); release pipeline was already done under #55.
- [x] #45 CLOSED (evidence comment): CrashDialog Copy button — stacktrace pasteable into Markor.
- [x] #54 CLOSED (evidence comment): auto-start on keyboard show (IDLE + mic granted, AudioRecord + FGS notification in logcat); PAUSED persists across hide/show.
- [x] #48/#53 CLOSED (evidence comments): trash deletes without selecting; label select fixed for custom rows (commit ac75231).
- [x] #59 created + CLOSED: gear/hint task affinity, rows-only pulse, explicit dropdown label select, theme-aware IME icons — commit `ac75231`, owner-verified on device (dropdown + gear confirmed; icons treated as accepted).
- [x] Knowledge persisted: PITFALLS (+4, #113 refined), DECISIONS (+1), HISTORY (+1 superseded), HANDOFF/STATE rewritten.

## Pending
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump; fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f`.
- [ ] Cut v1.2.1 (versionCode bump) to ship the ac75231 verification-round fixes.

## Blockers
None.

## Next Session Suggestion
Either cut v1.2.1 (bump versionCode → tag → release.yml → fdroiddata bump + MR comment), or wait for linsui's MR !40029 response first and bundle both.
