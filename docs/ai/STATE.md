# Project State

Current status as of 2026-09-19 (**v1.2.4 (10204) released to Play alpha + F-Droid watch open on #89**; open issues: #88, #84, #82, #74, #75, #64).

## Current Focus
**#74 — v1.3.0 listing-assets release (minor bump)**: fastlane `images/` (icon + phoneScreenshots), full_description rewrite, README/INSTALLATION badges must land before the v1.3.0 tag (F-Droid reads fastlane from the built tag); Play tester infra + demo GIF in parallel. **#89 watch items: F-Droid 1.2.4 pickup (page currently serves 1.2.3) + owner-side Play alpha verification.**

## Completed (this cycle)
- [x] #89 created + implemented — **v1.2.4 (10204) patch release** (owner decision: features since v1.2.3 ship as patch, v1.3.0 stays reserved for #74). Commit `8d9da93` (version bump + `whatsnew-en-GB`), tag force-moved to `1ced0c8` (CI fix), pushed; local tests + `assembleRelease` green.
- [x] CI fix `1ced0c8` — `setup-android@v3` now `packages: platform-tools` in `release.yml` + `fdroid-apk.yml` (Google removed the legacy `tools` package from the SDK repo manifest, android-actions/setup-android#537; broke GitHub Android CI ~2026-09-14). All three workflows green after the tag move.
- [x] Play upload verified via CI logs: edit `02449760292536382042` committed (alpha, status completed, en-GB whatsnew attached). Live-API check pending owner (service-account key GPG-encrypted with the owner key, not readable on the agent host).
- [x] GitHub release `v1.2.4` verified: assets `app-release.aab` + `polished-recognition.apk` present.
- [x] F-Droid confirmed serving **1.2.3** (fdroidbot pickup works; closes the #78 pickup watch) — 1.2.4 pickup is an automatic, non-blocking watch on #89.
- [x] #87 closed per owner (sine blink shipped `1cf320f`, feel-check passed). The #87-era exit-2 misdiagnosis ("environmental pressure") was corrected by the #87 follow-up: leaked live recorder in a test (`3eed55d` test cleanup + `AudioRecorder.start()` guard, docs `461f858`).
- [x] #83 closed 2026-09-18 (`aa94106`) — Oplus rotation-proof session: same-field freeze via `ImeStartDecision`, FGS retention, PCM+duration disk snapshot, pid-tagged `ime-lifecycle.log`; feel-check PASSED by owner.
- [x] #86 closed 2026-09-19 (`9b67336` + `346ba53`) — flush button (mode-preserving PCM discard); infra: unit-test worker heap 512m→1536m.
- [x] #81 closed 2026-09-19 (`9dd252b`/`1883fbd`) — configurable line-wrap (default 80, 0-off) + quick-set 80/120/200/0-off.
- [x] #71 closed (`6cfb713`) — REC time counter in IME bar; feel-check confirmed by owner.
- [x] #78 closed as shipped (v1.2.3 released 2026-09-14); #76 closed as superseded; #67 closed as maybe-later (snapshot implemented under #83).
- [x] #79/#80 — GitHub asset retention: `scripts/cleanup-github-assets.sh` keeps newest 7 of Actions artifacts and `build-*` releases/tags; **`v*` releases/pages never pruned** (F-Droid Binaries dependency, `f7cf464`).

## Pending
- [ ] **#89 watch**: F-Droid 1.2.4 pickup (poll package page; fdroidbot usually hours–days) + owner-side Play alpha verification via `scripts/query-play-console.py` (needs the machine holding the GPG key).
- [ ] **#74**: F-Droid launch Phase 0/1 — next release is **v1.3.0 (minor bump)** with listing assets (fastlane `images/` icon + phoneScreenshots, full_description rewrite, README/INSTALLATION badges), Play tester infra, demo GIF.
- [ ] **#75**: rate-limit header logging (remaining-requests/-tokens, reset headers, retry-after, 429 counts; local persistence + simple usage view in Settings).
- [ ] **#64**: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] **#84**: keep PCM buffer on pipeline failure; **#82**: RecognitionService API work; **#88**: help texts.
- [ ] Insertion-spacing watch: owner refinements from longer use — DOMAIN.md is the rule reference; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only; adb IME writes blocked (verified 2026-09-07), reads work.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic detects Polished's auxiliary voice IME directly. HeliBoard is currently the default keyboard on the OnePlus.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard logs (`ime-lifecycle.log` for bind/config/finish/destroy with pid + outcome, alongside the stt/llm JSON).
- Oplus rotation behavior (proven 2026-09-18 via trace): same-instance input-view rebind with `restarting=false` on the SAME field ~150 ms BEFORE `onConfigurationChanged`; service and process survive while mic FGS is held. Never gate destructive bind decisions on the rotation mark alone — cancellation keys off field identity (`ImeStartDecision`).
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).
- The agent host (VPS) has no attached device — on-device verification is always delegated to the owner on the device machine. (Exception 2026-09-18: the OnePlus was reachable from the agent host for `installRelease` + `ime-lifecycle.log` reads; do not assume this persists.)

## Next Session Suggestion
Check #89 watch items (F-Droid 1.2.4 pickup + owner Play verification), then #74 v1.3.0 listing-assets work, then #75 / #64 / #84 / #82 / #88.
