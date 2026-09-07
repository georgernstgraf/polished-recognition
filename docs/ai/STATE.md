# Project State

Current status as of 2026-09-07 (#72 CLOSED — gear opens Settings via keyboard switch, owner-verified on OnePlus; spacing follow-up: leading blank at field start reported, refinement issue created).

## Current Focus
**#72 done** (commits d9a4d60 + docs): gear switches to last-used key keyboard (`SwitchTargetPolicy` via shared `resolveKeyKeyboardTarget`) then opens `SettingsActivity` (`NEW_TASK|CLEAR_TOP`, launch before `switchInputMethod`); return to Polished = manual (owner decision). Owner smoke test on OnePlus: PASS. Follow-up: leading space at field start (position 0, Markor) is superfluous — `InsertionSpacingPolicy` refinement (null→keep blank, empty→no blank, owner-decided).

## Completed (this cycle)
- [x] #72: gear→keyboard-switch→Settings flow implemented (`PolishedVoiceInputIME.kt` only), `SettingsHintActivity` kept for notification contentIntent.
- [x] #72 owner smoke test on OnePlus: switch/edit/back/auto-resume/notification-hint all PASS; issue closed.

## Pending
- [ ] Insertion-spacing refinement (new issue): field-start (empty `before`) → NO leading blank; null `before` → keep blank (owner decision); trailing rule unchanged. Update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together, then DOMAIN.md rule text; Markor feel-check.
- [ ] #69 S5 feel-check (0.15/dwell pulse); lever if depth clamps: adaptive noise floor.
- [ ] #67: disk snapshot of paused dictation (standalone deferred `enhancement`).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] MR !40029: waiting for linsui merge (worktree `1127cbebe`).

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only; adb IME writes blocked (verified 2026-09-07, `uid 2000 does not have WRITE_SECURE_SETTINGS`), reads work.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic uses the system `voice_recognition_service`, NOT the auxiliary IME. HeliBoard is currently the default keyboard on the OnePlus; Polished + AnySoftKeyboard + Gboard + OpenBoard + others in subtype history.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard JSON logs.
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).

## Next Session Suggestion
Insertion-spacing refinement (field-start blank), then #69 S5 feel-check, then #64 (Ogg latency timings) or #67.
