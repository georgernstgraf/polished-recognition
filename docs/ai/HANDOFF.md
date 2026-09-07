# Hand Off

**#66 IMPLEMENTED 2026-09-07 (commit 4dbdfd7, pushed): whitespace padding around committed text — `InsertionSpacingPolicy` + `commitWithSpacing()`; 17 unit tests, `test` + `assembleRelease` green. DEVICE FEEL-CHECK PENDING → #66 stays open. Previously: #70 closed (4c70c45), #69 pulse depth 0.15/dwell (c0f00ff) S5 feel-check pending, v1.2.1 released. Next: #66 + #69 feel-checks on device, then MR !40029 watch, then #64/#67.**

## Open tasks

1. [ ] **#66 — device feel-check of whitespace padding**: install latest release build (commit 4dbdfd7) on S5/OnePlus and verify: dictation inserted after `.`/`,` gets a leading space; insertion at field start gets a leading space; insertion before existing space gets neither; field-end insertion gets a trailing space. Then close #66.
2. [ ] **#69 — S5 feel-check of the 0.15/dwell pulse** (implemented 2026-09-07 on OnePlus; S5 not connected). Prepared follow-up: adaptive noise floor if a louder room clamps the depth.
3. [ ] **#67 — disk snapshot of paused dictation** (deferred by owner, `enhancement` label; STANDALONE issue — no sub-issue link).
4. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
5. [ ] **MR !40029 watch**: all reviewer feedback addressed (linsui 2026-08-31 answered 2026-09-07); waiting for merge. Worktree at `1127cbebe`.

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5.
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly afterwards (#128).
- Oplus/OnePlus suppresses app-level IME logcat; S5 (LineageOS) shows app lines normally. Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` remain stale; README screenshots live in `docs/img/`.

Last cleared: 2026-09-07 (#66 whitespace padding implemented + pushed; device feel-check pending).
