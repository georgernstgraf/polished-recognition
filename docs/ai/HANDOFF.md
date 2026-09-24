# Hand Off

**2026-09-24: #96 CLOSED** — `docs/img/demo.gif` re-recorded on a **dark** OnePlus Notes background (commit `3391528`). **Tag v1.3.3 is deferred on purpose**: the owner wants more UI work first, then the tag. Latest release remains v1.3.2 (10302). Default branch `main`. Open: #91 (watch), #82 (Peter retest), #74 (watch), #75, #64.

## Open tasks

1. [ ] **NEXT: Owner does more IME/UI work, THEN cut Tag v1.3.3** — bump `10303`/`1.3.3`, short whatsnew, tag pushed **separately**. Do NOT tag before the UI work. This tag ships the dark `demo.gif` (#96) + all accumulated UI on `main` to F-Droid/Play.
2. [ ] **v1.3.2 watch** — F-Droid pickup with listing assets (likely superseded by v1.3.3).
3. [ ] **#91 watch** — superseded by later versions.
4. [ ] **#82 feel-check (Peter + owner)** — retest on a #82 build; then `finish` #82.
5. [ ] **#74 watch** — parent epic; social-preview upload left to owner.
6. [ ] **#75 — rate-limit header logging**.
7. [ ] **#64 — Ogg/Opus compression latency**.
8. [ ] **Insertion-spacing watch** — passive.

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

Last cleared: 2026-09-24 (#96 closed; next: owner UI work → then Tag v1.3.3).
