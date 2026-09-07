# Hand Off

**#72 OPEN 2026-09-07: gear opens Settings via keyboard switch — code complete (tests green), on-device smoke test pending (no device connected). Return path = manual (owner decision). Next: smoke test on S5 + OnePlus, then close. Previously: #66 closed (whitespace padding), v1.2.1 released.**

## Open tasks

1. [ ] **#72 — smoke test** (S5 + OnePlus): (1) gear during RECORDING → keyboard switches to last-used key keyboard, Settings open + editable, session persists PAUSED; (2) Back from Settings → lands in the dictation app (NOT a stale Settings instance — CLEAR_TOP flag is new), manual return to Polished → auto-resume appends to preserved PCM; (3) no key keyboard enabled → gear opens system keyboard settings, Polished settings NOT opened; (4) notification tap still shows the hint dialog. Close #72 after.
2. [ ] **#69 — S5 feel-check of the 0.15/dwell pulse** (implemented 2026-09-07 on OnePlus; S5 not connected). Prepared follow-up: adaptive noise floor if a louder room clamps the depth.
3. [ ] **#67 — disk snapshot of paused dictation** (deferred by owner, `enhancement` label; STANDALONE issue — no sub-issue link).
4. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
5. [ ] **MR !40029 watch**: all reviewer feedback addressed (linsui 2026-08-31 answered 2026-09-07); waiting for merge. Worktree at `1127cbebe`.
6. [ ] **Insertion-spacing watch**: owner may report refinements of the padding rules from longer use — DOMAIN.md is the rule reference; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5.
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly afterwards (#128).
- Oplus/OnePlus suppresses app-level IME logcat; S5 (LineageOS) shows app lines normally. Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` remain stale; README screenshots live in `docs/img/`.

Last cleared: 2026-09-07 (#66 feel-check verified, rules persisted in DOMAIN.md, issue closed).
