# Project State

Current status as of 2026-09-24 (**#95 CLOSED** — IME bottom-row icons unified, owner feel-check passed; **#94 IN PROGRESS** — listing-asset refresh: `featureGraphic.png` added, screenshots + GIF pending; v1.3.1 (10301) released 2026-09-21; default branch `main`). Open: #94, #91 (watch), #82, #74 (watch), #75, #64.

## Current Focus
**#94 listing-asset refresh** (sub of #74): new screenshots of the post-#90/#92/#95 UI → `docs/img/` **and** `fastlane/metadata/android/en-US/images/phoneScreenshots/` (3 motifs, same names: `ime-recording`, `settings`, `settings-providers`); re-record `docs/img/demo.gif` with the current IME bar; `featureGraphic.png` (1024×500, verbatim copy of `distribution/play-store-feature-graphic.png`) added. Then commit + push, bump **1.3.2 (10302)**, tag `v1.3.2` **separately** → F-Droid reads the fresh fastlane assets from the built tag.

## Completed (recent cycles)
- [x] #95 CLOSED 2026-09-24 — IME bottom-row icon unification: `ic_close`/`ic_pause`/`ic_resume`/`ic_send` 18dp→24dp; pause bars y3–21; cancel cross strokeWidth 2→1.6 and full height (y3–21); backspace inner X as line strokes (was outlined polygon); trash lid as single 1.6 rim line; backspace `<group scaleX/Y=0.9333>` + `strokeWidth 1.714` (net 1.6) to unclip the right edge. Commits `0882890` + `15fea5b`, tests + `assembleRelease` green, feel-check "wunderbar".
- [x] #93 CLOSED 2026-09-24 — default branch renamed `master` → `main` (CI triggers, docs, Pages source repointed).
- [x] #91 — v1.3.1 (10301) RELEASED 2026-09-21 (`c1d8b3e`, tag separate; Play alpha edit `12594441127685593678`, fdroid-apk.yml green, GitHub AAB+APK). Watch: F-Droid pickup, may serve 1.3.0 first.
- [x] #92 CLOSED 2026-09-21 — Settings grouped Output / Provider / Prompts, verified on-device.
- [x] #90 CLOSED 2026-09-21 — IME UI batch shipped in v1.3.1.
- [x] #89 / #84 / #88 / #87 / #83 / #86 / #81 / #78 / #79 / #80 — see HISTORY.md / issue tracker.

## Pending
- [ ] **#94** — finish listing-asset refresh (screenshots, GIF, release v1.3.2). Device-only capture on the OnePlus 7T.
- [ ] **#82 feel-check (owner/Peter)** — Duolingo/Corvus dictation via system voice-input service + IME regression trio. Then `finish` #82.
- [ ] **#91 watch** — F-Droid pickup of v1.3.1 (bot, hours–days).
- [ ] **#74 watch** — Play 1.3.1 review + F-Droid listing pickup; social-preview upload to GitHub settings left to owner (no API).
- [ ] **#75** — rate-limit header logging.
- [ ] **#64** — Ogg/Opus compression latency (measure per-stage on the S5 first).
- [ ] Insertion-spacing watch — passive; refine only on new reports (#66/#73 rules hold).

## Blockers
None.

## Device Notes
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — reachable from the agent host this session; adb IME/secure-setting writes blocked (Settings UI only), reads work.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug).
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).
- Agent host has no attached device by default; on-device verification is delegated to the owner (OnePlus was connected 2026-09-24 for the #95 feel-check).

## Next Session Suggestion
Finish **#94**: capture the 3 screenshots + re-record `demo.gif` on the OnePlus, sync both asset locations, commit/push, bump 1.3.2/10302, tag `v1.3.2` separately, watch the workflows + F-Droid pickup, persist knowledge, close #94 (#74 stays open). Then `finish` #82 / #75 / #64.
