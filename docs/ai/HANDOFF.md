# Hand Off

**#69 IMPLEMENTED 2026-09-07 (commit c0f00ff, pushed): pulse floors 0.15 + 15% floor dwell; tests + assembleRelease green, installed on OnePlus (f6de166c). Pending: S5 feel-check (S5 was not connected) — if a louder room clamps the depth (static NOISE_FLOOR=200 gate), the round-3 adaptive noise floor is the prepared lever. Next: MR !40029 watch, then #64/#67.**

## Open tasks

1. [x] **v1.2.1 release — DONE (2026-09-07)**: bump `7f7200a` → tag pushed separately → release.yml (Play: 1.2.1/10201 completed on **alpha** track) + fdroid-apk.yml (signed APK on GH release) both green; fdroiddata worktree updated to single 1.2.1 Build entry (commit `1127cbebe`), pipeline 2826222037 fully green, linsui replied.
2. [ ] **#67 — disk snapshot of paused dictation** (deferred by owner, `enhancement` label; now a STANDALONE issue — sub-issue link to #65 removed so #65 could close).
3. [ ] **#69 — S5 feel-check of the 0.15/dwell pulse** (implemented 2026-09-07 on OnePlus; S5 not connected). Prepared follow-up: adaptive noise floor.
4. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (issue created with full option analysis; recommended entry: measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
5. [ ] **MR !40029 watch**: all reviewer feedback addressed (linsui 2026-08-31 answered 2026-09-07); waiting for merge. Worktree at `1127cbebe`.

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). The old "1080×2400 = S5" note was the OnePlus. Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5 ("wunderbar, wechselt sofort aufs Mikrofon-IME").
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly afterwards (#128).
- Oplus/OnePlus suppresses app-level IME logcat; S5 (LineageOS) shows app lines normally. Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` remain stale; README screenshots live in `docs/img/` (refreshed 2026-09-07: current bar incl. switch icon).

Last cleared: 2026-09-07 (#69 pulse depth implemented + pushed; S5 feel-check pending).
