# Project State

Current status as of 2026-09-07 (#66 whitespace padding implemented + feel-check verified, issue CLOSED; #69 pulse-depth tuning pending S5 feel-check; v1.2.1 released).

## Current Focus
**#66 done** (code commit 4dbdfd7, docs commit + close this session): leading/trailing whitespace padding around committed text via `InsertionSpacingPolicy` + `commitWithSpacing()`. Feel-check passed on device 2026-09-07; canonical rules persisted in `DOMAIN.md` (evolving — refine from longer use).

## Completed (this cycle)
- [x] #66: `InsertionSpacingPolicy` (service/) + `commitWithSpacing()` in `PolishedVoiceInputIME` (1-char neighbors via `getTextBefore/AfterCursor(1,0)`) + `InsertionSpacingPolicyTest` (17 cases); null neighbors = "no whitespace" → space added; own whitespace suppresses doubling; NBSP covered (`isWhitespace || isSpaceChar`).
- [x] #66 feel-check on device (punctuation/field-start/field-end insertion) — passed; issue closed.
- [x] #70 (commit 4c70c45): prominent Gboard warning in README, INSTALLATION.md EN-only, keyboard list per owner.
- [x] #69 (commit c0f00ff): pulse depth 0.15 + 15% floor dwell; installed on OnePlus.
- [x] v1.2.1 release: Play alpha track + F-Droid MR !40029 single-Build-entry pipeline green.

## Pending
- [ ] #69 S5 feel-check (0.15/dwell pulse); lever if depth clamps: adaptive noise floor.
- [ ] #67: disk snapshot of paused dictation (standalone deferred `enhancement`).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] MR !40029: waiting for linsui merge (worktree `1127cbebe`).

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic uses the system `voice_recognition_service`, NOT the auxiliary IME.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard JSON logs.
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).

## Next Session Suggestion
#69 S5 feel-check, then #64 (Ogg latency timings) or #67.
