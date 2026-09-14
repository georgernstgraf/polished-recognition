# Project State

Current status as of 2026-09-14 (v1.2.3 RELEASED as a patch for #77: tag `v1.2.3` pushed, Play alpha verified live 1.2.3/10203 status=completed via Play API, GitHub release has AAB + reproducible APK, workflows green. F-Droid auto-update pending — watch in #78. Listing-assets release retargeted to v1.3.0 (#74). #77 device feel-check PASSED 2026-09-14. Open: #78, #74, #75, #71, #67, #64).

## Current Focus
**#78 — v1.2.3 publication (patch for #77)**: commit `7175c5c` bumped `versionCode 10203` / `versionName "1.2.3"` and refreshed `distribution/whatsnew/whatsnew-en-GB`; `./gradlew test` 184/184 + `assembleRelease` green; tag `v1.2.3` pushed. `release.yml` + `fdroid-apk.yml` + `build.yml` all green. Play alpha confirmed via temp CI Play-API query: `alpha: 1.2.3 status=completed versionCodes=['10203']` with en-GB release notes; `production`/`beta` empty; `internal` still the 0.0.6/601 initial release. Upstream `fdroiddata` is at `CurrentVersion: 1.2.2 / 10202`; because the v1.2.3 tag landed before the next fdroidbot run, F-Droid will skip 1.2.2 and update to 1.2.3. #76 (v1.2.2 watch) closed as superseded.

## Completed (this cycle)
- [x] #78 created (standalone) — v1.2.3 patch release to ship the #77 opaque-IME fix; owner decision: no listing assets in this release.
- [x] #78 implemented: version bump + whatsnew, tests/build green, commit `7175c5c` + tag `v1.2.3` pushed.
- [x] Play alpha 10203 verified live via the documented temp CI Play-API query (workflow created, run, deleted).
- [x] #76 closed as superseded by #78; #74 body retargeted from v1.2.3 to **v1.3.0**.
- [x] Knowledge persisted: DECISIONS (v1.2.3 patch / v1.3.0 listing target), PITFALLS (fdroidbot lag/supersede, upstream vs fork fdroiddata, Play log evidence), CONVENTIONS/ARCHITECTURE track corrected internal→alpha, HISTORY archive.
- [x] #77 device feel-check PASSED by owner on-device 2026-09-14 (v1.2.3) — pause during the deep phase keeps the bar fully opaque; no reopen.
- [x] #79 implemented (`ebae932`) — `scripts/cleanup-github-assets.sh` (keep newest 7 per kind; `v*` tags never deleted) wired into `build.yml`/`release.yml`/`fdroid-apk.yml` (`actions: write`); `build.yml` now `make_latest: false`. Backlog pruned: 270→21 Actions artifacts, 58→7 `build-*` tags, 9→7 `v*` release pages (18 `v*` tags intact); `v1.2.3` restored as GitHub "Latest". CI-verified twice (`build.yml` green, prunes on every push). Docs: CONVENTIONS retention policy + PITFALLS (`head -n -7` direction trap, expired-artifact retry).

## Pending
- [ ] #78: F-Droid watch — poll upstream `fdroiddata` until `CurrentVersion: 1.2.3 / 10203`, confirm f-droid.org serves 10203, then close.
- [ ] #74: F-Droid launch Phase 0/1 — next release is **v1.3.0 (minor bump)** with listing assets (fastlane `images/` icon + phoneScreenshots, full_description rewrite, README/INSTALLATION badges), Play tester infra, demo GIF.
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
#78 F-Droid watch, then #74 v1.3.0 listing-assets release, then #75 / #71 / #64 / #67.
