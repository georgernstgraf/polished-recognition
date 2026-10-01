# Project State

Current status as of 2026-10-01 (**#102 closed; v1.3.4 prepared**). `main` carries the release bump `fee52fe` — `versionCode 10304`, `versionName 1.3.4`, and a `whatsnew-en-GB` entry for the per-app line-wrap (#100/#102). The `v1.3.4` tag is **not yet pushed** (owner go-ahead pending); pushing it triggers `release.yml` (Play alpha), `fdroid-apk.yml`, `build.yml`. This release ships **#100 + #102** (per-app line-wrap width + last-used-app-first dropdown, dictation target recorded at dictation start). **#102 CLOSED** after the owner's feel-check (daily use). Latest public release **v1.3.3 (10303)**; F-Droid serves **1.3.3** with a complete listing (icon, 3 screenshots, full description). Marketing wave **#74 Phase 1** drafted in `docs/marketing/` (version-free), alpha onboarding canonical in `docs/marketing/alpha-welcome.md`; the Mastodon draft was refined 2026-10-01 (GIF described in-body, `qwen3.8-27b` + Groq free tier named, split into a two-toot thread). Google Group settings verified 2026-10-01 (join = Anyone on the web, post = Managers; welcome message still empty). Default branch `main`. Open: #99, #101, #82, #75, #64, #74.

## Current Focus
**Release v1.3.4 (10304)** — bump on `main`, tag pending the owner's go-ahead; then F-Droid auto-pickup (~3–5 days). **Marketing wave (#74 Phase 1)** — agent drafts done, owner posts: **Mastodon first**, then r/fossdroid, r/degoogle, r/selfhosted, kuketz (DE, second wave), 1–2 days apart. **F-Droid forum dropped** — its rules forbid app advertising. CTA1 F-Droid + CTA2 Play-alpha two-step + GitHub feedback. Separately, **#74 Play-alpha tester recruitment** (20–30 testers, ≥12 continuous for 14 days).

## Completed (recent cycles)
- [x] #74 Mastodon draft refined 2026-10-01 (`26e6b87`) — the demo GIF is now described in the toot body (not just alt text; precise alt text added), `qwen3.8-27b` + the owner's daily use + **GROQ's free tier** (`whisper-large-v3`) are named, and the post split into a two-toot thread (toot 1 = 490 chars with the GIF, toot 2 = Play-alpha two-step + issues). `./gradlew test` green; pushed.
- [x] #102 CLOSED 2026-10-01 — owner feel-check done (app in daily use). Final report on the issue; ships in v1.3.4 (10304). (Implementation: `9c82c27` MRU + `88dbc45` dictation-start amendment.)
- [x] v1.3.4 (10304) PREPARED on `main` 2026-10-01 (`fee52fe`) — `versionCode 10304`, `versionName 1.3.4`, `whatsnew-en-GB` describes the per-app line-wrap (#100/#102); `./gradlew test` + `assembleRelease` green. Tag pushed separately on the owner's go-ahead.
- [x] #74 Phase 1 marketing drafts 2026-10-01 (`65526b5`) — version-free refresh of the 6 posts, timing headers generalized off the stale 1.3.0/1.2.4 references, and new `docs/marketing/alpha-welcome.md` (Google Group welcome message + canonical two-step tester instructions). F-Droid listing verified complete.
- [x] #102 AMENDMENT on `main` 2026-09-30 (`88dbc45`) — `recordKnownApp` at dictation start (IME + service) so the currently dictated app is top/pre-selected; in-memory MRU `mruOrder`; no extra disk I/O; `./gradlew test` 281 green.
- [x] #100 SHIPPED to `main` 2026-09-28 (`b7fa292`) — per-app line-wrap width with learned apps, separate `known_apps` prefs, 24 h-throttled async writes, Settings "Line wrap per app" section.
- [x] v1.3.3 (10303) RELEASED 2026-09-24 — bump `377e4a2`, whatsnew-en-GB, tag `v1.3.3`; release.yml (Play alpha) + fdroid-apk.yml + build.yml green.
- [x] #97 / #96 / #94 / #95 / #93 / #92 / #91 and earlier — see HISTORY.md / tracker.

## Pending
- [ ] **Tag v1.3.4 (owner)** — `git tag v1.3.4 && git push origin v1.3.4` (push the tag separately).
- [ ] **#74 Phase 1 posting (owner)** — 6 drafts ready in `docs/marketing/`; paste the welcome message into the Google Group.
- [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 continuous 14 days.
- [ ] **#101 decision (owner)** — whether API tokens stay in Google Auto Backup.
- [ ] **#99 (owner)** — Google overview page shows outdated application images.
- [ ] **#82 feel-check (owner/Peter)** — Duolingo/Corvus via system voice-input service + IME regression trio. Then `finish` #82.
- [ ] **#75** — rate-limit header logging.
- [ ] **#64** — Ogg/Opus compression latency.
- [ ] Insertion-spacing watch — passive.

## Blockers
None.

## Device Notes
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — adb IME/secure-setting writes blocked, reads work.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb IME/settings writes WORK.
- **The OnePlus is also the Telegram bridge to the agent session** — incoming messages overlay scrcpy recordings. Verify the foreground before `input tap`; record long, trim; keep the raw until the GIF is signed off.
- scrcpy output is **VFR** — normalize (`ffmpeg -vf fps=30 -c:v libx264`) before trimming/contact sheets.
- OnePlus Notes (`com.oneplus.note`) is dark; new note via the FAB; the note list shows private titles — never record it.

## Next Session Suggestion
Push `v1.3.4` on the owner's go-ahead, then watch the three workflows. Growth-side: owner posts the #74 Phase 1 wave and drives Play-alpha recruitment. Code-side: `finish` #82 / #75 / #64.
