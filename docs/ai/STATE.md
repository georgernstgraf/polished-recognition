# Project State

Current status as of 2026-09-16 (#78 CLOSED as shipped per owner — implementation complete, Play alpha live 1.2.3/10203; F-Droid still serves 1.2.2, 1.2.3 pickup via untracked auto-update. #67 CLOSED as maybe-later per owner — no evidence of process-death loss, reopen on demand. Open: #74, #75, #71, #64).

## Current Focus
**#74 — v1.3.0 listing-assets release (minor bump)**: fastlane `images/` (icon + phoneScreenshots), full_description rewrite, README/INSTALLATION badges must land before the v1.3.0 tag (F-Droid reads fastlane from the built tag); Play tester infra + demo GIF in parallel. F-Droid currently serves 1.2.2 — 1.2.3 pickup via untracked auto-update.

## Completed (this cycle)
- [x] #78 created (standalone) — v1.2.3 patch release to ship the #77 opaque-IME fix; owner decision: no listing assets in this release.
- [x] #78 implemented: version bump + whatsnew, tests/build green, commit `7175c5c` + tag `v1.2.3` pushed.
- [x] Play alpha 10203 verified live via the documented temp CI Play-API query (workflow created, run, deleted).
- [x] #76 closed as superseded by #78; #74 body retargeted from v1.2.3 to **v1.3.0**.
- [x] Knowledge persisted: DECISIONS (v1.2.3 patch / v1.3.0 listing target), PITFALLS (fdroidbot lag/supersede, upstream vs fork fdroiddata, Play log evidence), CONVENTIONS/ARCHITECTURE track corrected internal→alpha, HISTORY archive.
- [x] #78 closed as shipped per owner 2026-09-16 (comment + close; F-Droid 1.2.3 pickup left to untracked auto-update).
- [x] #67 closed as maybe-later per owner 2026-09-16 (not-planned; proposal stays documented in the issue, reopen on demand).
- [x] #77 device feel-check PASSED by owner on-device 2026-09-14 (v1.2.3) — pause during the deep phase keeps the bar fully opaque; no reopen.
- [x] #79 implemented (`ebae932`) — `scripts/cleanup-github-assets.sh` (keep newest 7 per kind) wired into `build.yml`/`release.yml`/`fdroid-apk.yml` (`actions: write`); `build.yml` now `make_latest: false`. Backlog pruned: 270→21 Actions artifacts, 58→7 `build-*` tags, 9→7 `v*` release pages (18 `v*` tags intact); `v1.2.3` restored as GitHub "Latest". CI-verified twice (`build.yml` green, prunes on every push). Docs: CONVENTIONS retention policy + PITFALLS (`head -n -7` direction trap, expired-artifact retry).
- [x] #80 implemented (`f7cf464`) — reversed the `v*` release-page pruning from #79: `scripts/cleanup-github-assets.sh` now **never prunes `v*` releases** (page or tag), because fdroiddata's `Binaries:` is a per-version URL (`releases/download/v%v/polished-recognition.apk`) and fdroidbot keeps every version in `Builds:`. Only Actions artifacts and `build-*` releases/tags are pruned to 7. Confirmed `v1.2.1`/`v1.2.2`/`v1.2.3` APKs HTTP 200; 7 `v*` pages, 18 `v*` tags intact. CI-verified (`build.yml` green, `[cleanup] v* releases: kept`).

## Pending
- [ ] #74: F-Droid launch Phase 0/1 — next release is **v1.3.0 (minor bump)** with listing assets (fastlane `images/` icon + phoneScreenshots, full_description rewrite, README/INSTALLATION badges), Play tester infra, demo GIF.
- [ ] #75: rate-limit header logging (remaining-requests/-tokens, reset headers, retry-after, 429 counts; local persistence + simple usage view in Settings).
- [ ] #71: REC time counter in IME bar during recording.
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
#74 v1.3.0 listing-assets release, then #75 / #71 / #64.
