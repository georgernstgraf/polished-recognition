# Project State

Current status as of 2026-09-07 (#72 AND #73 CLOSED — gear→Settings flow owner-verified; field-start blank fixed and feel-checked. Open: #69 S5 feel-check, #67, #64, MR !40029 watch).

## Current Focus
**#73 done** (commits 3a4a7c2 + dbc899f): `InsertionSpacingPolicy` no longer adds a leading blank at genuine field start (empty `before`); null neighbors keep the conservative blank (owner decision). Owner feel-check in Markor passed. Insertion-spacing rules now canonical in DOMAIN.md ("evolving — refine from longer use").

## Completed (this cycle)
- [x] #72: gear→keyboard-switch→Settings flow, owner smoke test on OnePlus PASS, closed.
- [x] #73: field-start leading blank removed (null→blank kept), owner feel-check PASS, closed.

## Pending
- [ ] #69 S5 feel-check (0.15/dwell pulse); lever if depth clamps: adaptive noise floor.
- [ ] #67: disk snapshot of paused dictation (standalone deferred `enhancement`).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] MR !40029: waiting for linsui merge (worktree `1127cbebe`).
- [ ] Insertion-spacing watch: owner refinements from longer use — DOMAIN.md is the rule reference; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only; adb IME writes blocked (verified 2026-09-07), reads work.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic uses the system `voice_recognition_service`, NOT the auxiliary IME. HeliBoard is currently the default keyboard on the OnePlus.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard JSON logs.
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).

## Next Session Suggestion
#69 S5 feel-check, then #64 (Ogg latency timings) or #67.
