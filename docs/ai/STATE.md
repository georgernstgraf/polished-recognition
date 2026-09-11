# Project State

Current status as of 2026-09-11 (#69 and #63 CLOSED; #75 created. Open: #74 F-Droid launch, #75 rate-limit logging, #67, #64).

## Current Focus
**#69/#63 closed 2026-09-11**: #69 (pulse 0.15/dwell) closed with implementation verified in code (`RmsAlphaMapper.ALPHA_FLOOR`, `BREATH_FLOOR` 0.15, keyframe dwell cycle in `PolishedVoiceInputIME`); the S5 feel-check was not explicitly repeated — adaptive noise floor remains the documented follow-up lever. #63 closed as by design: `SettingsActivity` saves and calls `finish()` (SettingsActivity.kt:879); the recents-reappearance is standard Android because SettingsActivity is the manifest LAUNCHER activity.

## Completed (this cycle)
- [x] #69: closed — implementation (c0f00ff) verified on master, tests green, APK installed on OnePlus; S5 feel-check not explicitly repeated.
- [x] #63: closed as by design (launcher-activity recents behavior).
- [x] #75: created — log rate-limit headers (x-ratelimit-*, 429s) for provider usage history, motivated by Groq free-tier limits (200K TPD / 8K TPM on gpt-oss-120b).
- [x] #72: gear→keyboard-switch→Settings flow, owner smoke test on OnePlus PASS, closed.
- [x] #73: field-start leading blank removed (null→blank kept), owner feel-check PASS, closed.

## Pending
- [ ] #74: F-Droid launch marketing Phase 0/1 — next step v1.2.2 release package.
- [ ] #75: rate-limit header logging (capture remaining-requests/-tokens, reset headers, retry-after, 429 counts; local persistence + simple usage view in Settings).
- [ ] #67: disk snapshot of paused dictation (standalone deferred `enhancement`).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] Insertion-spacing watch: owner refinements from longer use — DOMAIN.md is the rule reference; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only; adb IME writes blocked (verified 2026-09-07), reads work.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic uses the system `voice_recognition_service`, NOT the auxiliary IME. HeliBoard is currently the default keyboard on the OnePlus.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard JSON logs.
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).

## Next Session Suggestion
#74 (v1.2.2 release package), then #75 (rate-limit logging) or #64 (Ogg latency timings) / #67.
