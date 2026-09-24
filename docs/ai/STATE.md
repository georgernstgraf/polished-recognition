# Project State

Current status as of 2026-09-24 (**v1.3.2 (10302) RELEASED** — listing-asset refresh + #95 icon refinement; all workflows green, Play alpha edit `07956435961582591479` committed; F-Droid pickup pending). **#94 CLOSED**, **#95 CLOSED**. Default branch `main`. Open: #91 (v1.3.1 watch), #82, #74 (watch), #75, #64.

## Current Focus
**#94 listing-asset refresh — DONE, shipped in v1.3.2**: 3 fresh screenshots of the post-#90/#92/#95 UI (ime-recording, settings, settings-providers) in both `docs/img/` and `fastlane/.../phoneScreenshots/` (byte-identical); re-recorded `docs/img/demo.gif` (current IME bar, 11.8 s); added `fastlane/.../images/featureGraphic.png` (1024×500, verbatim copy of `distribution/play-store-feature-graphic.png`). Next: watch F-Droid pickup of 1.3.2 **with** the new listing assets.

## Completed (recent cycles)
- [x] #94 CLOSED 2026-09-24 — listing assets refreshed and shipped in **v1.3.2 (10302)**: commits `25a009e` (assets) + `552cb70` (bump + whatsnew), tag `v1.3.2` pushed separately. `release.yml` green (Play alpha edit `07956435961582591479`), `fdroid-apk.yml` green, GitHub release assets AAB+APK present, `build.yml` green.
- [x] #95 CLOSED 2026-09-24 — IME bottom-row icons unified (24dp/1.6 stroke, line backspace cross, single-line trash rim, full-height cancel cross, backspace `<group scaleX/Y=0.9333>` + `strokeWidth 1.714`). Commits `0882890` + `15fea5b`, feel-check "wunderbar"; see DECISIONS 2026-09-24.
- [x] #93 CLOSED 2026-09-24 — default branch renamed `master` → `main`.
- [x] #91 / #92 / #90 / #89 / #84 / #88 / #87 / #83 / #86 / #81 / #80 / #79 — shipped earlier; see HISTORY.md / issue tracker.

## Pending
- [ ] **#91 watch** — F-Droid pickup of v1.3.1 (superseded by v1.3.2; the bot will likely serve 1.3.2 directly).
- [ ] **v1.3.2 watch** — F-Droid listing pickup **with** fresh screenshots/feature graphic (fdroidbot, hours–days). Play 1.3.2 in review.
- [ ] **#82 feel-check (owner/Peter)** — Duolingo/Corvus dictation via system voice-input service + IME regression trio. Then `finish` #82.
- [ ] **#74 watch** — parent epic of #94; Play review + social-preview upload left to owner.
- [ ] **#75** — rate-limit header logging.
- [ ] **#64** — Ogg/Opus compression latency (measure per-stage on the S5 first).
- [ ] Insertion-spacing watch — passive.

## Blockers
None.

## Device Notes
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — used 2026-09-24 for #95 feel-check and #94 screenshots/GIF; adb IME/secure-setting writes blocked, reads work.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb IME/settings writes WORK (userdebug).
- **The device doubles as the Telegram bridge to the agent session** — incoming chat notifications appear as heads-up banners and can land inside a scrcpy recording. Verify the foreground app (`screencap`) before tapping; record long and trim; keep the raw mp4 until the GIF is signed off.
- Screenshot sessions with a configured provider LIVE-record on field focus — cancel explicitly (#128).

## Next Session Suggestion
Watch F-Droid pickup of v1.3.2 (listing assets) + Play review. Then `finish` #82 / work #75 / #64. Keep #74 open until pickup verified.
