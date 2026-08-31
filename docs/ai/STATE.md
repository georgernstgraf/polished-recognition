# Project State

Current status as of 2026-08-31.

## Current Focus
#57 (two-line IME redesign) implemented and installed on-device; awaiting user's on-device verification. Open: #55 (release v1.2.0 tracking), #43 (follow-up tracker), #57 (verify before close).

## Completed (this cycle)
- [x] #57 round 2: voice-reactive RMS-driven pulse (`RmsAlphaMapper` + tests; floor 0.5 on silence, rise with loudness, dive 1000 ms / rise 150 ms) replacing the fixed-cycle flash — installed on OnePlus 7T (lastUpdateTime 2026-08-31 07:54).

## Pending
- [ ] **#57 on-device verify** (user, UI-only on Oplus): hybrid pulse — slow breathing (0.6↔0.9, 2 s cycle) during silence, brightening with speech (gated RMS); enlarged resume button when paused; stage text in row 1 during processing (incl. Raw mode → only "Transcribing (STT)…"); gear position/behavior.
- [ ] **#55 — release `v1.2.0`**: tag (push tag separately from branch commits) → `release.yml` (real Play upload = last untested #52 path — watch the run) → fdroiddata metadata bump → MR !40029 comment + force-push branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, verify HEAD).
- [ ] #45 leftover: CrashDialog Copy-button on-device test (`adb shell am crash com.georgernstgraf.polishedrecognition`).
- [ ] On-device verification of #48/#53 (dropdown icon-tap vs row-tap; dark-mode contrast; long-press inline edit) and #54 (auto-start on real IME) — same install session as the v1.2.0/#57 check.

## Blockers
None.

## Next Session Suggestion
After the user confirms #57 on-device, run #55: tag `v1.2.0` (now includes #57), watch `release.yml` live, then fdroiddata bump + MR update. Batch the remaining on-device verifications (#48/#53/#54/#45) into the same session.
