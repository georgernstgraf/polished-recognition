# Project State

Current status as of 2026-09-07 (#72 gear→Settings-via-keyboard-switch code complete; on-device smoke test pending; #69 S5 feel-check still open).

## Current Focus
**#72**: IME settings gear now switches to the last-used key keyboard (`SwitchTargetPolicy`, shared `resolveKeyKeyboardTarget`) and opens `SettingsActivity` (`NEW_TASK or CLEAR_TOP`, launch before `switchInputMethod`) instead of the #51 hint dialog. Owner decisions: return to Polished = manual (no picker/WRITE_SECURE_SETTINGS), no-keyboard fallback = system keyboard settings, non-back/save closes = do nothing. Code + tests green; smoke test on S5 + OnePlus pending.

## Completed (this cycle)
- [x] #72 implementation (`PolishedVoiceInputIME.kt` only): gear handler rewired, target resolution extracted into shared helper; `SettingsHintActivity` stays for the notification contentIntent.
- [x] #72 issue created, plan + implementation report commented.
- [x] `./gradlew test assembleRelease` — BUILD SUCCESSFUL.

## Pending
- [ ] **#72 on-device smoke test** (S5 + OnePlus): (1) gear during RECORDING → keyboard switches, settings editable, session persists PAUSED; (2) Back → returns to dictation app (not stale Settings instance) → manual return → auto-resume appends; (3) no key keyboard → system keyboard settings open, Polished settings NOT opened. Then close #72.
- [ ] #69 S5 feel-check (0.15/dwell pulse); lever if depth clamps: adaptive noise floor.
- [ ] #67: disk snapshot of paused dictation (standalone deferred `enhancement`).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] MR !40029: waiting for linsui merge (worktree `1127cbebe`).

## Blockers
None (no test device connected during the #72 session — adb `devices` empty).

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic uses the system `voice_recognition_service`, NOT the auxiliary IME.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard JSON logs.
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).

## Next Session Suggestion
#72 on-device smoke test (needs a connected device), then #69 S5 feel-check, then #64 (Ogg latency timings) or #67.
