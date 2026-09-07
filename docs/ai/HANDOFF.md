# Hand Off

**#62 CLOSED not-planned 2026-09-07 — premise obsolete: HeliBoard's mic works via the auxiliary voice IME on the OnePlus (no bound RecognitionService needed); PITFALLS entry corrected. MR !40029 watch paid off: linsui (2026-08-31) wants the old 1.1.1 Build entry removed + pipeline fixed (rewritemeta wants Builds chronological — 1.2.0 was prepended). Next: v1.2.1 release bundling those MR fixes, then #64/#67.**

## Open tasks

1. [ ] **v1.2.1 release**: master has ac75231 fixes + #60 + #61 + #65 (switch-button redesign, README, fresh screenshots) — bump versionCode → tag → release.yml → fdroiddata bump + MR comment. **Bundle the MR !40029 fixes from linsui's 2026-08-31 feedback**: remove the old 1.1.1 Build entry (New-App MR = newest version only) and fix chronology (rewritemeta failed because 1.2.0 was prepended above 1.1.1); reply to linsui. Worktree at `1c43ae05f`.
2. [ ] **#67 — disk snapshot of paused dictation** (deferred by owner, `enhancement` label; now a STANDALONE issue — sub-issue link to #65 removed so #65 could close).
3. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (issue created with full option analysis; recommended entry: measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
4. [ ] **MR !40029 watch**: F-Droid maintainer (linsui) response to the 1.2.0 bump — **feedback exists (2026-08-31, unaddressed)**: "Remove the old version. Please fix the pipeline." fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f` (branch `add-polished-recognition`).

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). The old "1080×2400 = S5" note was the OnePlus. Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5 ("wunderbar, wechselt sofort aufs Mikrofon-IME").
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly afterwards (#128).
- Oplus/OnePlus suppresses app-level IME logcat; S5 (LineageOS) shows app lines normally. Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` remain stale; README screenshots live in `docs/img/` (refreshed 2026-09-07: current bar incl. switch icon).

Last cleared: 2026-09-07 (#62 closed not-planned; MR !40029 linsui feedback surfaced: remove old Build + pipeline fix).
