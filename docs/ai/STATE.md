# Project State

Current status as of 2026-09-24 (**v1.3.3 (10303) released** — ships the #97 divider fix + dark `demo.gif` #96; all workflows green, GitHub AAB/APK present; commit `377e4a2`, tag `v1.3.3`). **#98 CLOSED. #97 / #91 CLOSED.** **The remaining work is marketing** — #74 (Marketing & launch) is the active epic; its Phase 0 (listing/demo/Play alpha) is done, Phase 1 (community wave) is next. Default branch `main`. Open: #74 (marketing), #82, #75, #64.

## Current Focus
**Marketing wave (#74 Phase 1)** — agent drafts posts in `docs/marketing/`, owner posts: F-Droid forum, Mastodon, Reddit (r/fossdroid / r/selfhosted / r/degoogle), kuketz (DE, second wave). CTA1 F-Droid + CTA2 Play-alpha + GitHub feedback channel. Separately, #74 Play-alpha tester recruitment (20–30 testers, ≥12 continuous for 14 days).

## Completed (recent cycles)
- [x] v1.3.3 (10303) RELEASED 2026-09-24 — bump `377e4a2`, whatsnew-en-GB, tag `v1.3.3` pushed separately; release.yml (Play alpha) + fdroid-apk.yml + build.yml green; GitHub assets `app-release.aab` + `polished-recognition.apk`. Sub-issue #98 of #74 closed.
- [x] #97 CLOSED 2026-09-24 — redundant IME timer/spinner divider (`ime_rec_timer_divider`) removed (commit `0a91a8e`); included in v1.3.3.
- [x] #91 CLOSED 2026-09-24 — v1.3.1 patch release watch, closed as superseded: v1.3.1 shipped, and the accumulated UI work already warranted v1.3.3.
- [x] #96 CLOSED 2026-09-24 — `docs/img/demo.gif` re-recorded on the dark OnePlus Notes background (12 s, start at recording 0:02 → send → STT/LLM → polished text; 540×1200, 64 c, 659 KB). Commit `3391528`. Raw scrcpy take kept until sign-off, then deleted. No version bump yet.
- [x] #94 CLOSED 2026-09-24 — listing assets refreshed; shipped in **v1.3.2 (10302)** (`25a009e` assets + `552cb70` bump), Play alpha edit `07956435961582591479`, all workflows green.
- [x] #95 CLOSED 2026-09-24 — IME bottom-row icons unified (24dp/1.6, line backspace cross, single-line trash rim, full-height cancel). Commits `0882890` + `15fea5b`.
- [x] #93 CLOSED 2026-09-24 — default branch `master` → `main`.
- [x] #92 / #90 / #89 / #84 / #88 / #87 / #83 / #86 / #81 / #80 / #79 — earlier; see HISTORY.md / tracker.

## Pending
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
Marketing: draft the #74 Phase 1 posts (F-Droid forum, Mastodon, Reddit, kuketz) into `docs/marketing/`; owner posts. Drive Play-alpha tester recruitment. Code-side: `finish` #82 / #75 / #64.
