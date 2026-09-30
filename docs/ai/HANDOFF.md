# Hand Off

**2026-09-30: #102 AMENDMENT on `main`** — per-app line-wrap dropdown keeps the last-used app on top (`9c82c27`) and now records the dictation target at **dictation start**, not only on insertion (amendment): IME `startIfPermitted()` + service `onStartListening()` call `recordKnownApp`, so the currently active app is top/pre-selected while dictating (e.g. gear → Settings). Insertion-time calls stay (throttled → no extra disk I/O). `SettingsStore.mruOrder`; `knownAppsByRecency()` = in-memory MRU first, then stored `lastSeenMs` descending; forget drops the MRU entry. No Settings UI change, `./gradlew test` 281 green, `assembleRelease` green. Builds on **#100** (`b7fa292`). Neither is in a release yet. Reopened #102 (owner feel-check pending). Open: #99, #101, #82, #75, #64, #74.

## Open tasks

1. [ ] **#100 + #102 on-device feel-check (owner)** — set Outlook 120, confirm chat apps keep the global width; verify the "Line wrap per app" section renders on the OnePlus and that the **currently dictated** app (start a dictation into an app not on top, then tap the gear) is at the top of the dropdown.
2. [ ] **NEXT: marketing wave (owner + agent)** — #74 Phase 1: agent drafts posts in `docs/marketing/`, owner posts. F-Droid forum, Mastodon, Reddit (r/fossdroid / r/selfhosted / r/degoogle), kuketz (DE, second wave). Each post: CTA1 F-Droid + CTA2 Play-alpha two-step + feedback channel.
3. [ ] **#99 (owner)** — Google overview page shows outdated application images.
4. [ ] **#101 (owner)** — decide token storage vs Google Auto Backup.
5. [ ] **#82 feel-check (Peter + owner)** — retest on a #82 build; then `finish` #82.
6. [ ] **#74 Play-alpha tester recruitment** — owner: Google Group self-join + opt-in; 20–30 testers, ≥12 continuous for 14 days before Play production.
7. [ ] **#75 — rate-limit header logging**; **#64 — Ogg/Opus latency**; insertion-spacing watch — passive.

## Known on-device gotchas

- **Devices: f6de166c = OnePlus 7T** (HD1903, Oplus, 1080×2400); d890cc9e = S5 (SM-G900F, LineageOS 18.1, 1080×1920). `input tap` needs REAL pixels (`adb shell wm size`).
- **Oplus blocks adb IME/secure-setting writes** — Settings UI only; reads (`dumpsys`) work. S5 (userdebug) allows them.
- **The OnePlus doubles as the Telegram bridge to the agent session** — chat notifications overlay scrcpy recordings. Verify the foreground before `input tap`; record long and trim; keep the raw mp4 until the GIF is approved.
- **scrcpy recordings are VFR** — normalize (`ffmpeg -i raw -vf fps=30 -c:v libx264 -an norm.mp4`) before contact sheets/trimming, else `fps`-filter sampling drifts by seconds.
- **OnePlus Notes (dark) for screenshots**: new note via the FAB `New note` (~[866,1910][1024,2068]); the note list exposes private titles — never record it; a fresh empty note editor is clean.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/`. Screenshot sessions with a configured provider LIVE-record on field focus — cancel explicitly (#128).
- IME state: pause bars = recording, ↺ = paused; ␡ flush = discard buffer keeping mode, ⌫ = delete last word / long-press clears field (#90/#95). **README screenshots live in `docs/img/`; `distribution/*.png` remain stale.**
- **README/fastlane screenshots must stay byte-identical** (`docs/img/*.png` ↔ `fastlane/.../phoneScreenshots/{1,2,3}-*.png`); verify with `md5sum`.
- Pulse-contrast (#87): foreground rows breathe 0.3↔1.0 on a 1333 ms sine while RECORDING; fully opaque otherwise.
- **Agent host has no attached device by default** — device-only checks are delegated to the owner.

Last cleared: 2026-09-30 (#102 amendment on main; next: #100/#102 feel-check + marketing wave #74 + #99).
