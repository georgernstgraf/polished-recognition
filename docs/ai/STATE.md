# Project State

Current status as of 2026-09-24 (**#96 CLOSED** — `docs/img/demo.gif` re-recorded on a dark OnePlus Notes background; **v1.3.3 tag deliberately NOT cut yet** — owner wants more UI work in a later session, then the tag). v1.3.2 (10302) is the latest release. Default branch `main`. Open: #91 (v1.3.1 watch), #82, #74 (watch), #75, #64.

## Current Focus
**Next session: more IME/UI work (owner), THEN cut Tag v1.3.3** to ship the accumulated UI + listing-asset changes (incl. the dark `demo.gif` from #96) to F-Droid. Do **not** tag before the UI work.

## Completed (recent cycles)
- [x] #96 CLOSED 2026-09-24 — `docs/img/demo.gif` re-recorded on the dark OnePlus Notes background (12 s, start at recording 0:02 → send → STT/LLM → polished text; 540×1200, 64 c, 659 KB). Commit `3391528`. Raw scrcpy take kept until sign-off, then deleted. No version bump yet.
- [x] #94 CLOSED 2026-09-24 — listing assets refreshed; shipped in **v1.3.2 (10302)** (`25a009e` assets + `552cb70` bump), Play alpha edit `07956435961582591479`, all workflows green.
- [x] #95 CLOSED 2026-09-24 — IME bottom-row icons unified (24dp/1.6, line backspace cross, single-line trash rim, full-height cancel). Commits `0882890` + `15fea5b`.
- [x] #93 CLOSED 2026-09-24 — default branch `master` → `main`.
- [x] #91 / #92 / #90 / #89 / #84 / #88 / #87 / #83 / #86 / #81 / #80 / #79 — earlier; see HISTORY.md / tracker.

## Pending
- [ ] **NEXT: UI work (owner) → then Tag v1.3.3 (10303)**: bump + whatsnew + separate tag push to deliver the current `main` (dark demo GIF + any new UI) to F-Droid/Play.
- [ ] **v1.3.2 watch** — F-Droid pickup with the v1.3.2 listing assets (will likely be superseded by v1.3.3).
- [ ] **#82 feel-check (owner/Peter)** — Duolingo/Corvus via system voice-input service + IME regression trio. Then `finish` #82.
- [ ] **#74 watch** — parent epic; Play review + social-preview upload left to owner.
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
Owner does further IME/UI work; only after that cut **v1.3.3** (bump `10303`/`1.3.3`, whatsnew, separate tag) so F-Droid reads the new demo GIF + UI. Then `finish` #82 / #75 / #64.
