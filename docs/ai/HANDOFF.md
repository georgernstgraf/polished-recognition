# Hand Off

**2026-10-05: #107 in-app help + #106 GitHub Pages overhaul shipped and CLOSED.** `docs/help.html` is the single, self-contained, offline help page — served as the site's **Help** tab and bundled into the APK (`stageHelpAssets`); the app opens it from the **Help chip left of About** and a **long-press on the IME gear** (replacing the gear press-hold hint). `INSTALLATION.md` gained a dedicated **ADB Commands** section. The GitHub Pages landing page was rebuilt (hero, sticky nav with a real Help link, auto-ToC, and a URL rewrite that fixes the previously broken `docs/img/...` screenshots and relative doc links). Commits `200ad56`, `08f4326`, `1fda2f3`, `1808f48`; live at https://georgernstgraf.github.io/polished-recognition/. **Build/tooling**: the build pins a **Java 21 toolchain** and the **pre-push hook is required** — both documented in `AGENTS.md`/`PITFALLS.md`. No release bump yet.

**2026-10-03: #82 CLOSED — RecognitionService works end-to-end.** The delivery bug is fixed (`VoiceSessionController.stopAndTranscribe` delivers `Completed` to secondary-only consumers — the bound service is never the primary IME slot, so keyboard-less calls no longer fall through to `ERROR_CLIENT` (5); `d327d43`, `VoiceSessionControllerSecondaryTest` +1). Scope (owner): **Android 12+ only** — `android:enabled="@bool/recognition_service_enabled"` (`values-v31`) hides the service on Android 11, where the **IME** is the dictation path. **Verified with the real caller:** Android 13 (clean Samsung A03 Core) — Duolingo → our service → Whisper STT → LLM → `onResults` delivered back to Duolingo; Android 12 (OnePlus 7T) — Duolingo `onResults`. Protocol harness: `tools/testcaller/`. Reporter @pvagner informed on the issue. Follow-up: **#105** (Whisper language misdetection).

**2026-10-03: #103 hyphen line-wrap shipped to `main`** — `LineWrapPolicy` now breaks words after `-`/`–`/`—` (classic greedy, URL-safe, 4-char minimum on both sides, U+2011 excluded); `80e6687`, `LineWrapPolicyTest` +9, `./gradlew test` green. Not yet in a release.

**2026-10-02: Mastodon launch POSTED; v1.3.4 (10304) on `main`, tag pending.** The Mastodon thread went live 2026-10-02 — toot 1 (demo GIF + pitch), toot 2 (thank-you to `@fdroidorg@floss.social`, esp. linsui, doubling as the boost request, + the Play-alpha CTA). The owner's profile `@schurlix` is **verified** against the project Pages via `rel="me"` (`verified_at 2026-10-02`); the GitLab MR !40029 thanks is done (owner). The `v1.3.4` tag is **not yet pushed**. Open: #99, #101, #105, #75, #64, #74.

## Open tasks

1. [ ] **Tag v1.3.4 (owner go-ahead)** — push the tag **separately** so the workflows trigger: `git tag v1.3.4 && git push origin v1.3.4` → `release.yml` (Play alpha), `fdroid-apk.yml` (reproducible APK), `build.yml`. F-Droid picks it up ~3–5 days later via `AutoUpdateMode: Version`.
2. [ ] **#74 Phase 1 marketing (owner posts)** — **Mastodon POSTED 2026-10-02.** Remaining drafts in `docs/marketing/`: r/fossdroid (rewrite in own words — the sub bans AI-written promo), r/degoogle (**weekly "Degoogle Showcase" thread only**), r/selfhosted (short modmail first), kuketz (DE, second wave); spaced 1–2 days apart. Each carries CTA1 F-Droid + CTA2 Play-alpha two-step + GitHub feedback; posts are version-free. **F-Droid forum dropped** — its rules forbid app advertising (app already included, no open MR).
3. [ ] **Google Group welcome message (owner)** — paste the text from `docs/marketing/alpha-welcome.md` into the group's **Welcome message** field (currently empty). Optional: polish the group description.
4. [ ] **#74 Play-alpha tester recruitment** — owner: group self-join + opt-in; 20–30 testers, ≥12 continuous for 14 days before Play production.
5. [ ] **#99 (owner)** — Google overview page shows outdated application images.
6. [ ] **#101 (owner)** — decide token storage vs Google Auto Backup.
7. [ ] **#105 STT language misdetection (owner-flagged)** — Spanish speech was auto-detected as “Icelandic” (found during the #82 verification). Use the STT language probability / a confidence threshold before telling the LLM the source language, and/or add a preferred-source-language setting.
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

Last cleared: 2026-10-05 (**#107** in-app help page `docs/help.html` (Help chip + IME gear long-press) and **#106** GitHub Pages overhaul — both shipped + closed, live; **build pins a Java 21 toolchain**, pre-push hook required; earlier: #82 closed (RecognitionService Android 12+ only, verified with Duolingo), #103 hyphen line-wrap, #104 Cortecs — all on `main`, unreleased; v1.3.4 bump on `main`, tag pending; Mastodon launch posted + profile verified; next: Reddit wave, v1.3.4 tag, #105).
