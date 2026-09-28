# Hand Off

**2026-09-28: #100 SHIPPED to `main`** — per-app line-wrap width with learned apps (`b7fa292`): global `wrapWidth` stays the default, optional per-app override resolved in the pipeline from the caller package (IME `EditorInfo.packageName`, bound service `Callback.getCallingUid()`), learned apps in a separate `known_apps` prefs file with background 24 h-throttled writes, Settings "Line wrap per app" section, validation `0 or >= 20`. `./gradlew test` (277) + `assembleRelease` green. Not yet in a release. Follow-up #101 opened (should API tokens stay in Google Auto Backup?).

**2026-09-24: #98 SHIPPED / #97 / #91 CLOSED** — v1.3.3 (10303) released (`377e4a2` + tag `v1.3.3`): release.yml (Play alpha) + fdroid-apk.yml + build.yml all green. **The real remaining work is marketing** — #74. Default branch `main`. Open: #74 (marketing), #101, #82 (Peter retest), #75, #64.

## Open tasks

1. [ ] **#100 on-device feel-check (owner)** — set Outlook 120, confirm chat apps keep the global width; verify the "Line wrap per app" section renders on the OnePlus.
2. [ ] **NEXT: marketing wave (owner + agent)** — #74 Phase 1: agent drafts posts in `docs/marketing/`, owner posts. F-Droid forum, Mastodon, Reddit (r/fossdroid / r/selfhosted / r/degoogle), kuketz (DE, second wave). Each post: CTA1 F-Droid + CTA2 Play-alpha two-step + feedback channel.
3. [ ] **#101 (owner)** — decide token storage vs Google Auto Backup.
4. [ ] **#82 feel-check (Peter + owner)** — retest on a #82 build; then `finish` #82.
5. [ ] **#74 Play-alpha tester recruitment** — owner: Google Group self-join + opt-in; 20–30 testers, ≥12 continuous for 14 days before Play production.
6. [ ] **#75 — rate-limit header logging**; **#64 — Ogg/Opus latency**; insertion-spacing watch — passive.

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

Last cleared: 2026-09-28 (#100 shipped to main; next: #100 feel-check + marketing wave #74).
