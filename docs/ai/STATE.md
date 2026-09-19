# Project State

Current status as of 2026-09-19 (#87 CLOSED per owner — sine blink shipped `1cf320f`, on-device feel-check passed; open: #84, #82, #74, #75, #64).

## Current Focus
**#74 — v1.3.0 listing-assets release (minor bump)**: fastlane `images/` (icon + phoneScreenshots), full_description rewrite, README/INSTALLATION badges must land before the v1.3.0 tag (F-Droid reads fastlane from the built tag); Play tester infra + demo GIF in parallel. F-Droid currently serves 1.2.2 — 1.2.3 pickup via untracked auto-update.

## Completed (this cycle)
- [x] #87 implemented (`1cf320f`, pushed 2026-09-19) — sine blink 0.3↔1.0 @1333 ms (`PulseAlphaPolicy.blinkAlpha`, linear phase animator); deleted `RmsAlphaMapper` + test, `Event.RmsChanged`/`SpeechBegin`, `AudioRecorderListener`, `computePcmRms` + 6 tests; `PulseAlphaPolicy.target` single-value (#77 pinning intact). 5 new sine tests, 235/235 green + `assembleRelease` green. Pitfall note: executor exit-2 recurred under 24/30 GB machine pressure despite the 1536m heap fix — clears on retry, environmental. On-device feel-check PASSED by owner 2026-09-19 — no reopen; issue CLOSED.
- [x] #78 created (standalone) — v1.2.3 patch release to ship the #77 opaque-IME fix; owner decision: no listing assets in this release.
- [x] #78 implemented: version bump + whatsnew, tests/build green, commit `7175c5c` + tag `v1.2.3` pushed.
- [x] Play alpha 10203 verified live via the documented temp CI Play-API query (workflow created, run, deleted).
- [x] #76 closed as superseded by #78; #74 body retargeted from v1.2.3 to **v1.3.0**.
- [x] Knowledge persisted: DECISIONS (v1.2.3 patch / v1.3.0 listing target), PITFALLS (fdroidbot lag/supersede, upstream vs fork fdroiddata, Play log evidence), CONVENTIONS/ARCHITECTURE track corrected internal→alpha, HISTORY archive.
- [x] #78 closed as shipped per owner 2026-09-16 (comment + close; F-Droid 1.2.3 pickup left to untracked auto-update).
- [x] #67 closed as maybe-later per owner 2026-09-16 (not-planned; proposal stays documented in the issue, reopen on demand) — its reopen condition fired 2026-09-18 (process-death loss observed on Oplus) and the snapshot was implemented under #83 (`aa94106`); #67 itself stays closed.
- [x] #77 device feel-check PASSED by owner on-device 2026-09-14 (v1.2.3) — pause during the deep phase keeps the bar fully opaque; no reopen.
- [x] #79 implemented (`ebae932`) — `scripts/cleanup-github-assets.sh` (keep newest 7 per kind) wired into `build.yml`/`release.yml`/`fdroid-apk.yml` (`actions: write`); `build.yml` now `make_latest: false`. Backlog pruned: 270→21 Actions artifacts, 58→7 `build-*` tags, 9→7 `v*` release pages (18 `v*` tags intact); `v1.2.3` restored as GitHub "Latest". CI-verified twice (`build.yml` green, prunes on every push). Docs: CONVENTIONS retention policy + PITFALLS (`head -n -7` direction trap, expired-artifact retry).
- [x] #80 implemented (`f7cf464`) — reversed the `v*` release-page pruning from #79: `scripts/cleanup-github-assets.sh` now **never prunes `v*` releases** (page or tag), because fdroiddata's `Binaries:` is a per-version URL (`releases/download/v%v/polished-recognition.apk`) and fdroidbot keeps every version in `Builds:`. Only Actions artifacts and `build-*` releases/tags are pruned to 7. Confirmed `v1.2.1`/`v1.2.2`/`v1.2.3` APKs HTTP 200; 7 `v*` pages, 18 `v*` tags intact. CI-verified (`build.yml` green, `[cleanup] v* releases: kept`).
- [x] #83 implemented twice, closed 2026-09-18 (`e07951b` superseded design + `aa94106` fix, pushed): Oplus rotation is a same-instance view rebind (`restarting=false`) ~150 ms BEFORE `onConfigurationChanged` — proven by `ime-lifecycle.log`, which also showed cancel-then-fresh-`00:00`-autostart. Fix: `ImeStartDecision` (cancel only onto a different field; PAUSED provisional 500 ms), FGS kept while live, PCM+duration disk snapshot/restore, pid-tagged trace. 237/237 tests + `assembleRelease` green, `installRelease` verified on the OnePlus (HD1903). On-device feel-check confirmed by owner 2026-09-19.
- [x] #86 implemented (`9b67336`, pushed 2026-09-19) — flush button discards the PCM buffer keeping the session mode (`AudioRecorder.flushBuffer()` in-place reset, `VoiceSessionController.flush()` mode-preserving + snapshot clear + callback kept, bottom-row IME button `ic_delete` enabled only in RECORDING/PAUSED, moved to position 2 per owner in `346ba53`). 7 new tests (2 recorder + 5 controller), 244/244 green + `assembleRelease` green. Infra in same commit: unit-test worker heap 512m→1536m (suite outgrew the Gradle default — executor exit 2 at the pipeline→service boundary). On-device feel-check PASSED by owner 2026-09-19 — no reopen; issue closed.
- [x] #81 implemented (`9dd252b`, `8c9cd95`, `1883fbd`) — `SettingsStore.wrapWidth` (default 80, 0 disables) + `LineWrapPolicy` on raw and LLM paths, Settings UI numeric field + quick-set 80/120/200/0-off; issue CLOSED 2026-09-19.
- [x] #71 implemented (`6cfb713`) — REC time counter in IME bar (divider-framed, right of language dropdown; frozen w/o REC in PAUSE); issue closed 2026-09-17; on-device feel-check confirmed by owner 2026-09-19.

## Pending
- [ ] #74: F-Droid launch Phase 0/1 — next release is **v1.3.0 (minor bump)** with listing assets (fastlane `images/` icon + phoneScreenshots, full_description rewrite, README/INSTALLATION badges), Play tester infra, demo GIF.
- [ ] #75: rate-limit header logging (remaining-requests/-tokens, reset headers, retry-after, 429 counts; local persistence + simple usage view in Settings).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] Insertion-spacing watch: owner refinements from longer use — DOMAIN.md is the rule reference; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only; adb IME writes blocked (verified 2026-09-07), reads work.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic detects Polished's auxiliary voice IME directly. HeliBoard is currently the default keyboard on the OnePlus.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard logs (`ime-lifecycle.log` for bind/config/finish/destroy with pid + outcome, alongside the stt/llm JSON).
- Oplus rotation behavior (proven 2026-09-18 via trace): same-instance input-view rebind with `restarting=false` on the SAME field ~150 ms BEFORE `onConfigurationChanged`; service and process survive while mic FGS is held (it died on the old build that dropped FGS in `onDestroy`). Never gate destructive bind decisions on the rotation mark alone — cancellation keys off field identity (`ImeStartDecision`).
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).
- The agent host (VPS) has no attached device — on-device verification is always delegated to the owner on the device machine. (Exception 2026-09-18: the OnePlus was reachable from the agent host for `installRelease` + `ime-lifecycle.log` reads; do not assume this persists.)

## Next Session Suggestion
#74 v1.3.0 listing-assets release, then #75 / #64 / the older backlog (#84, #82).
