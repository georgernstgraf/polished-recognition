# Hand Off

**v1.2.2 RELEASED 2026-09-11 (standalone issue #76): tag pushed, Play alpha VERIFIED LIVE via Play API (1.2.2 / 10202, status=completed, whatsnew attached; production/beta empty), GitHub release has AAB + reproducible APK, workflows green. #76 stays OPEN until F-Droid serves 10202 — fdroidbot auto-update pending (master fdroiddata still shows 1.2.1/10201; AutoUpdateMode: Version, no manual MR needed). #74 body updated: listing package renamed to v1.2.3 (F-Droid reads fastlane from the tag; 1.2.2 ships without listing assets). Groq retired `llama-3.3-70b-versatile` — local `.env` now uses `openai/gpt-oss-120b` (gitignored, CI unaffected). Next: #76 close-watch, then #74 Phase 0 (v1.2.3 listing assets), #75 rate-limit logging.**

## Open tasks

1. [ ] **#76 — v1.2.2 publication watch** (open until 1.2.2 live on BOTH platforms): Play side DONE — alpha track verified live (1.2.2/10202, status=completed) via Play API on 2026-09-11. F-Droid side — wait for fdroidbot "Add 1.2.2" MR on fdroiddata, watch MR go green, then build/index.
2. [ ] **#74 — F-Droid launch marketing Phase 0/1** (now: v1.2.3 listing-assets release — fastlane images/description must be committed before that tag; see #74 for full plan; owner works on Play Console/Google Group setup in parallel).
3. [ ] **#75 — log rate-limit headers** (capture `x-ratelimit-remaining-requests`/`-tokens`, reset headers, `retry-after` + 429 counts per day/model from STT/LLM responses; local persistence per repo conventions, simple usage view in Settings).
4. [ ] **#67 — disk snapshot of paused dictation** (deferred by owner, `enhancement` label; STANDALONE issue — no sub-issue link).
5. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
6. [ ] **Insertion-spacing watch**: owner may report refinements of the padding rules from longer use — DOMAIN.md is the rule reference (now includes the #73 field-start refinement); changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only. Reads still work on Oplus.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5. HeliBoard is currently default on the OnePlus.
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly (#128).
- Oplus/OnePlus suppresses app-level IME logcat; S5 (LineageOS) shows app lines normally. Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` remain stale; README screenshots live in `docs/img/`.

Last cleared: 2026-09-11 (v1.2.2 tag+Play alpha done via #76; F-Droid auto-update watch pending; #74 retargeted to v1.2.3).

(End of file - total 26 lines)
