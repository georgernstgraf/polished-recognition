# Hand Off

**2026-10-01: #102 CLOSED; v1.3.4 (10304) ready on `main`, tag pending.** Release bump `fee52fe` (`versionCode 10304`, `versionName 1.3.4`, `whatsnew-en-GB` for #100/#102) is on `main`; the `v1.3.4` tag is **not yet pushed** — awaiting the owner's go-ahead. Marketing wave #74 Phase 1 is drafted and ready: `docs/marketing/` refreshed **version-free**, alpha onboarding canonical in `docs/marketing/alpha-welcome.md`; the Google Group join/post settings were verified on 2026-10-01 (join = Anyone, post = Managers), welcome message still to be pasted. Open: #99, #101, #82, #75, #64, #74.

## Open tasks

1. [ ] **Tag v1.3.4 (owner go-ahead)** — push the tag **separately** so the workflows trigger: `git tag v1.3.4 && git push origin v1.3.4` → `release.yml` (Play alpha), `fdroid-apk.yml` (reproducible APK), `build.yml`. F-Droid picks it up ~3–5 days later via `AutoUpdateMode: Version`.
2. [ ] **#74 Phase 1 marketing (owner posts)** — drafts in `docs/marketing/`: **Mastodon first**, then r/fossdroid, r/degoogle, r/selfhosted, kuketz (DE, second wave); 1–2 days apart. Each carries CTA1 F-Droid + CTA2 Play-alpha two-step + GitHub feedback; posts are version-free and rewritten in the owner's voice. Sub rules: r/fossdroid bans AI-written promo; r/degoogle = weekly "Degoogle Showcase" thread only; r/selfhosted needs a short modmail first. **F-Droid forum dropped** — its rules forbid app advertising (app already included, no open MR).
3. [ ] **Google Group welcome message (owner)** — paste the text from `docs/marketing/alpha-welcome.md` into the group's **Welcome message** field (currently empty). Optional: polish the group description.
4. [ ] **#74 Play-alpha tester recruitment** — owner: group self-join + opt-in; 20–30 testers, ≥12 continuous for 14 days before Play production.
5. [ ] **#99 (owner)** — Google overview page shows outdated application images.
6. [ ] **#101 (owner)** — decide token storage vs Google Auto Backup.
7. [ ] **#82 feel-check (Peter + owner)** — retest on a #82 build; then `finish` #82.
8. [ ] **#75 — rate-limit header logging**; **#64 — Ogg/Opus latency**; insertion-spacing watch — passive.

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
- **Google Groups settings page scrolls an inner container** — DevTools "Capture full size screenshot" gives only the viewport; use GoFullPage or a node screenshot (see PITFALLS).
- **Agent host has no attached device by default** — device-only checks are delegated to the owner.

Last cleared: 2026-10-01 (#102 closed; v1.3.4 bump on `main`, tag pending; #74 marketing drafted + group verified).
