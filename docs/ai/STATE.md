# Project State

Current status as of 2026-09-30 (**#102 SHIPPED to `main`** — commit `9c82c27`; the per-app line-wrap dropdown now keeps the last-used app at the top via an in-memory MRU list in `SettingsStore`, zero extra disk I/O; `./gradlew test` 280 green). This builds on **#100 per-app line-wrap** (commit `b7fa292`) — global `wrapWidth` stays the default, optional per-app override learned from the invoking app, separate `known_apps` prefs file, async 24 h-throttled writes. Neither #100 nor #102 is in a release yet. Latest release **v1.3.3 (10303)** (commit `377e4a2`, tag `v1.3.3`). **#100 / #102 CLOSED.** Marketing (#74), token-backup (#101), #99, and the #100 on-device feel-check are open. Default branch `main`. Open: #99, #101, #82, #75, #64, #74.

## Current Focus
**#100 + #102 shipped (unreleased)** — per-app line-wrap width with learned apps and last-used-first ordering. **Marketing wave (#74 Phase 1)** — agent drafts posts in `docs/marketing/`, owner posts: F-Droid forum, Mastodon, Reddit (r/fossdroid / r/selfhosted / r/degoogle), kuketz (DE, second wave). CTA1 F-Droid + CTA2 Play-alpha + GitHub feedback channel. Separately, #74 Play-alpha tester recruitment (20–30 testers, ≥12 continuous for 14 days).

## Completed (recent cycles)
- [x] #102 SHIPPED to `main` 2026-09-30 — in-memory MRU ordering for the per-app line-wrap dropdown (`9c82c27`): `SettingsStore.mruOrder` bumped on every successful dictation before the 24 h throttle; `knownAppsByRecency()` = MRU first, then stored `lastSeenMs` descending; forget drops the MRU entry. No extra disk I/O; Settings UI unchanged. 3 new tests, `./gradlew test` 280 green.
- [x] #100 SHIPPED to `main` 2026-09-28 — per-app line-wrap width (`b7fa292`): global default + learned per-app override resolved in the pipeline from the caller package (IME `EditorInfo.packageName`, service `Callback.getCallingUid()`), separate `known_apps` prefs file, background 24 h-throttled writes, Settings "Line wrap per app" section, validation `0 or >= 20`. Follow-up #101 (tokens in Auto Backup) opened.
- [x] v1.3.3 (10303) RELEASED 2026-09-24 — bump `377e4a2`, whatsnew-en-GB, tag `v1.3.3` pushed separately; release.yml (Play alpha) + fdroid-apk.yml + build.yml green; GitHub assets `app-release.aab` + `polished-recognition.apk`. Sub-issue #98 of #74 closed.
- [x] #97 CLOSED 2026-09-24 — redundant IME timer/spinner divider (`ime_rec_timer_divider`) removed (commit `0a91a8e`); included in v1.3.3.
- [x] #91 CLOSED 2026-09-24 — v1.3.1 patch release watch, closed as superseded.
- [x] #96 CLOSED 2026-09-24 — `docs/img/demo.gif` re-recorded on the dark OnePlus Notes background (commit `3391528`).
- [x] #94 CLOSED 2026-09-24 — listing assets refreshed; shipped in **v1.3.2 (10302)** (`25a009e` + `552cb70`).
- [x] #95 CLOSED 2026-09-24 — IME bottom-row icons unified (commits `0882890` + `15fea5b`).
- [x] #93 CLOSED 2026-09-24 — default branch `master` → `main`.
- [x] #92 / #90 / #89 / #84 / #88 / #87 / #83 / #86 / #81 / #80 / #79 — earlier; see HISTORY.md / tracker.

## Pending
- [ ] **#100 on-device feel-check (owner)** — set a per-app width (e.g. Outlook 120), confirm chat stays global; check the Settings "Line wrap per app" section and that the just-dictated app is on top (#102). Then fold into the next release.
- [ ] **#101 decision (owner)** — whether API tokens stay in Google Auto Backup.
- [ ] **#99 (owner)** — Google overview page shows outdated application images.
- [ ] **NEXT: #74 Phase 1 marketing wave** — agent drafts posts in `docs/marketing/`, owner posts (F-Droid forum, Mastodon, Reddit, kuketz); Play-alpha tester recruitment (20–30, ≥12 continuous 14 days).
- [ ] **#82 feel-check (owner/Peter)** — Duolingo/Corvus via system voice-input service + IME regression trio. Then `finish` #82.
- [ ] **#75** — rate-limit header logging.
- [ ] **#64** — Ogg/Opus compression latency.
- [ ] Insertion-spacing watch — passive.

## Blockers
None.

## Device Notes
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — used 2026-09-24 for #95/#94/#96; adb IME/secure-setting writes blocked, reads work.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb IME/settings writes WORK.
- **The OnePlus is also the Telegram bridge to the agent session** — incoming messages overlay scrcpy recordings. Verify the foreground before `input tap`; record long, trim; keep the raw until the GIF is signed off.
- scrcpy output is **VFR** — normalize (`ffmpeg -vf fps=30 -c:v libx264`) before trimming/contact sheets, else `fps`-filter sampling drifts.
- OnePlus Notes (`com.oneplus.note`) is dark; new note via the FAB (`New note`, bounds ~[866,1910][1024,2068]); the note list shows private titles — never record it.

## Next Session Suggestion
Code-side: fold #100/#102 into the next release after the owner's on-device feel-check; `finish` #82 / #75 / #64. Growth-side: draft the #74 Phase 1 posts into `docs/marketing/`; drive Play-alpha tester recruitment.
