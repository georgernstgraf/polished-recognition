# Project State

Current status as of 2026-09-13 (#77 IME paused-contrast fix committed + pushed; on-device feel-check pending by owner. #76 F-Droid watch open. Open: #76, #74, #75, #71, #67, #64).

## Current Focus
**#77 — IME full contrast when not recording**: shipped in `6546990` (`fix: keep IME fully opaque when not recording (#77)`) — new `PulseAlphaPolicy` pins row alpha to `1f` outside RECORDING; pulse (`maxOf(breathAlpha, voiceAlpha)`) only while RECORDING. `./gradlew test` 184/184 green incl. new `PulseAlphaPolicyTest`; `assembleRelease` green (R8 + lintVital). Remaining: owner runs `installRelease` on the device machine and pauses during the deep dwell phase to confirm the bar stays opaque (then #77 can close).

## Completed (this cycle)
- [x] #77 created (`enhancement`, standalone) — pause during pulse deep phase froze the IME at alpha 0.15; root-caused to `applyAlpha()` applying stale `maxOf(breathAlpha, voiceAlpha)` after animator cancel with no RMS outside RECORDING.
- [x] #77 implemented: `service/PulseAlphaPolicy.kt` + `applyAlpha()` delegation + `PulseAlphaPolicyTest` (7 cases); tests + release build green; committed/pushed.

## Pending
- [ ] #77: owner on-device feel-check (pause mid-deep-phase → opaque; resume → pulse returns); close if PASS.
- [ ] #76: F-Droid publication watch (fdroidbot MR "Add 1.2.2" → green → build on f-droid.org) — then close.
- [ ] #74: F-Droid launch marketing Phase 0/1 — next step **v1.2.3 listing-assets release** (fastlane `images/` icon + phoneScreenshots, full_description rewrite, README/INSTALLATION badges, then tag v1.2.3).
- [ ] #75: rate-limit header logging (remaining-requests/-tokens, reset headers, retry-after, 429 counts; local persistence + simple usage view in Settings).
- [ ] #71: REC time counter in IME bar during recording.
- [ ] #67: disk snapshot of paused dictation (standalone deferred `enhancement`).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] Insertion-spacing watch: owner refinements from longer use — DOMAIN.md is the rule reference; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only; adb IME writes blocked (verified 2026-09-07), reads work.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic detects Polished's auxiliary voice IME directly. HeliBoard is currently the default keyboard on the OnePlus.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard JSON logs.
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).
- The agent host (VPS) has no attached device — on-device verification is always delegated to the owner on the device machine.

## Next Session Suggestion
#77 device feel-check (then close), #76 F-Droid watch, then #74 v1.2.3 listing-assets release, then #75 / #71 / #64 / #67.
