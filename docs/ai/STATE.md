# Project State

Current status as of 2026-09-20 (**#82 implemented, `3e38bf7`, needs owner on-device feel-check**; v1.3.0 (10300) tagged + Play upload committed, in review; **#84 + #88 + #89 CLOSED**; F-Droid serves 1.2.4, 1.3.0 pickup pending; open issues: #82, #74 watch/polish/drafts, #75, #64).

## Current Focus
**#82 feel-check (owner, device machine)**: select Polished as system voice-input service → dictate in Duolingo/Corvus → result returns; IME regression trio (busy-discard on live dictation, airplane-mode error returns to IDLE, settings change mid-dictation applies). **#74 watch**: `release.yml` (Play alpha upload committed?) + `fdroid-apk.yml` green + GitHub release assets (AAB+APK) + F-Droid 1.3.0 pickup **with listing images** (fdroidbot auto-update, hours–days).

## Completed (this cycle)
- [x] **#91 — v1.3.1 (10301) RELEASED 2026-09-21** (`c1d8b3e` bump, tag pushed separately): release.yml green (Play alpha edit `12594441127685593678` committed), fdroid-apk.yml green, GitHub AAB+APK present, build.yml green. First release shipping #82 + #90. Watch: F-Droid pickup (may serve 1.3.0 first).
- [x] **#92 CLOSED 2026-09-21** — Settings grouped Output Control → Provider Control (STT, LLM) → Prompts (+ read-only Source Language Clause note); verified on-device via `installRelease` + screenshots.
- [x] #90 CLOSED 2026-09-21 — shipped in v1.3.1, owner visual feel-check passed.
- [x] #90 implemented 2026-09-20 (uncommitted) — 1.5x lower row, no REC prefix, delete-word ⌫ left of trash (tap word / long-press clear-all), outline trash, pause-enlarge removed. 261 tests green + `assembleRelease` green. Open: owner visual feel-check, then `finish` #90.
- [x] #82 docs repair (`INSTALLATION.md` §5 restored 2026-09-20, uncommitted) — ADB `voice_recognition_service` block lost in #43 re-inserted for the re-added service; correction + retest comment posted on #82. Issue stays open pending feel-check.
- [x] #82 test build for Peter (`18bd2c3`, CI green, build-287 `app-release.apk` verified signed with release key via `apksigner verify`) — `build.yml` now publishes installable signed APKs; Peter comment posted with link + test brief (service path + IME regression trio + logs) + hacker-feedback welcome. Awaiting his results before `finish`.
- [x] #82 implemented (`3e38bf7`, pushed, suite 253 green + `assembleRelease` green) — bound `RecognitionService` on the shared singleton via secondary listener (`startShared()`; IME keeps primary forever); busy-discard + cancel-to-IDLE per owner; neutral notification (own channel/id 1003); manifest block verbatim pre-#43. Peter confirmed both gating answers (no own engine in Duolingo/Corvus; final-only latency OK). Open: owner on-device feel-check.
- [x] #74 v1.3.0 shipped (`25140f4` assets + `8138990` bump, tag `v1.3.0` pushed separately; test + assembleRelease green, pre-push suites green) — 3 live OnePlus screenshots (dark RECORDING REC 0:14, Settings upper third, provider half, all 1080×2400; owner opened screens, agent shot via adb `f6de166c`), fastlane `images/` (icon 512 + 3 phoneScreenshots), full_description rewrite (IME-first + Gboard paragraph + BYOK), whatsnew-en-GB (hints #88, retry #84, F-Droid/group #74). Ships #84 + #88 to users.
- [x] #74 tester-infra docs half (`4623642`, pushed, pre-push suite green) — Play alpha Group self-join flow replaces the email-add flow in INSTALLATION.md (§1 F-Droid first + badge, §2 Play two-step with same-account/Member-not-Pending/14-day notes, sections renumbered) + README "Become an Alpha Tester" (same two links + F-Droid badge CTA). Group `polished-recognition-alpha@googlegroups.com` created by owner + attached to alpha track; opt-in page verified live ("Become a tester" renders). No second-account self-test possible (owner has one Google account) — first external tester is the canary.
- [x] #88 implemented — long-press tooltips on the 4 lower-row IME buttons (`android:tooltipText`; fixed combined hints "Record / Send", "Pause / Resume" via 2 new strings; top row untouched; no Kotlin change). `ImeTooltipTest` (2 tests), full suite green + `assembleRelease` green. Open: on-device feel-check by owner.
- [x] #84 implemented — pipeline failure parks as ordinary PAUSED with PCM + timer preserved (`stopPreservingBuffer`, re-snapshot for #83 restore path; `CancellationException` rethrown so cancel-during-PROCESSING can't resurrect); IME unchanged (failure Toast, stays visible, send/resume + editable quick settings). 6 new tests, full suite green + `assembleRelease` green. Open: on-device feel-check by owner (airplane-mode send → Toast + frozen timer → re-enable → send succeeds).
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
- [ ] **#82 feel-check (owner)**: Duolingo/Corvus dictation via system voice-input service + IME regression trio (busy-discard, airplane-mode → IDLE, mid-dictation settings change). Then `finish` #82.
- [x] **#89 CLOSED 2026-09-20** — F-Droid CurrentVersion 1.2.4/10204 confirmed in fdroiddata; Play alpha 1.2.4 committed via CI. 1.3.0 supersedes on F-Droid automatically (#74 watch).
- [ ] **#74 watch**: Play 1.3.0 review — owner reports "Changes in Review" on closed-testing-alpha (auto-publishes unless managed publishing); F-Droid 1.3.0 pickup with listing images (bot, hours–days).
- [x] #74 demo GIF (`e60b20b`, pushed, suite green) — live OnePlus take via `scrcpy --record` (42s mp4 local-only), trimmed 19–33s → 540×1200 10fps palette GIF 904KB at `docs/img/demo.gif`, embedded in README. Arc: REC 0:16 → send → Transcribing (STT)… → polished commit.
- [x] #74 repo polish APPLIED via API 2026-09-20 (description/homepage/12 topics, verified) — social-preview upload left to owner (no API). Phase-1 drafts done (`08ae5b8`): `docs/marketing/` ×6 (forum, toot, 3×reddit, kuketz DE with timing note); owner posts with own accounts.
- [ ] **#75**: rate-limit header logging (remaining-requests/-tokens, reset headers, retry-after, 429 counts; local persistence + simple usage view in Settings).
- [ ] **#64**: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] **#82**: RecognitionService API work (external feature request).
- [x] Insertion-spacing watch verified working nicely by owner 2026-09-20 (#66/#73 rules hold in daily use) — keep refining only on new reports.

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
`finish` #82 after the feel-check passes (or fix fallout first). Then #75 / #64. Watch: Play 1.3.0 review + F-Droid 1.3.0 listing pickup (#74).
