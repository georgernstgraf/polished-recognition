# Hand Off

**fdroiddata MR !40029 MERGED — app is in main F-Droid (package page not indexed yet). CLOSED 2026-09-11: #69 (pulse 0.15/dwell — implemented + verified, S5 feel-check not explicitly repeated), #63 (by design — SettingsActivity is launcher activity, recents behavior is standard Android). NEW: #75 — log rate-limit headers (x-ratelimit-*, 429s) for provider usage history (Groq free-tier motivation). Ongoing: #74 — F-Droid launch marketing (Phase 0: v1.2.2 with fastlane images/description sync, demo-GIF, GitHub polish, index watch; Phase 1: post drafts incl. Play-alpha tester recruitment — Google Group self-join; personal dev account → production needs 12 testers × 14 days, target 20–30). Continue #74 in next session.**

## Open tasks

1. [ ] **#74 — F-Droid launch marketing Phase 0/1** (see issue for full plan + owner decisions; owner works on Play Console/Google Group setup in parallel). Next concrete step: v1.2.2 release package.
2. [ ] **#75 — log rate-limit headers** (capture `x-ratelimit-remaining-requests`/`-tokens`, reset headers, `retry-after` + 429 counts per day/model from STT/LLM responses; local persistence per repo conventions, simple usage view in Settings).
3. [ ] **#67 — disk snapshot of paused dictation** (deferred by owner, `enhancement` label; STANDALONE issue — no sub-issue link).
4. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
5. [ ] **Insertion-spacing watch**: owner may report refinements of the padding rules from longer use — DOMAIN.md is the rule reference (now includes the #73 field-start refinement); changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only. Reads still work on Oplus.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5. HeliBoard is currently default on the OnePlus.
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly afterwards (#128).
- Oplus/OnePlus suppresses app-level IME logcat; S5 (LineageOS) shows app lines normally. Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` remain stale; README screenshots live in `docs/img/`.

Last cleared: 2026-09-11 (#69 + #63 closed; #75 created; MR !40029 watch removed — merged).
