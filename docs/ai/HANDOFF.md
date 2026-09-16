# Hand Off

**2026-09-14: v1.2.3 RELEASED as a pure patch for #77 (standalone issue #78). Owner decision: listing assets move to a separate MINOR bump v1.3.0 (#74), NOT v1.2.4. Commit `7175c5c` bumped `versionCode 10203` / `versionName "1.2.3"` + refreshed `whatsnew-en-GB`; tests 184/184 + `assembleRelease` green; tag pushed. `release.yml` → Play alpha VERIFIED LIVE via Play API (alpha: 1.2.3 / 10203 status=completed, en-GB notes attached; production/beta empty; internal still 0.0.6/601), GitHub release has AAB + reproducible APK; `fdroid-apk.yml` green. F-Droid: upstream `fdroiddata` is at `CurrentVersion: 1.2.2 / 10202`; the v1.2.3 tag landed before fdroidbot's next run, so F-Droid will skip 1.2.2 and serve 1.2.3 — watch in #78. #76 (v1.2.2 watch) closed as superseded. VersionCode convention: `1.2.2→10202`, `1.2.3→10203`, `1.3.0→10300`. #77 device feel-check PASSED 2026-09-14 (owner, on-device) — no reopen. Next: #74 v1.3.0 listing-assets release. **2026-09-16: #78 CLOSED as shipped per owner (implementation complete; F-Droid still serves 1.2.2, 1.2.3 pickup via untracked fdroidbot auto-update — reopen only if it stalls); #67 CLOSED as maybe-later per owner (no evidence of process-death dictation loss in practice — reopen on demand).**

## Open tasks

1. [ ] **#74 — F-Droid launch marketing Phase 0/1** (now: **v1.3.0 MINOR listing-assets release** — fastlane images (icon + phoneScreenshots), full_description rewrite, README/INSTALLATION badges must be committed before that tag; see #74 for the full plan; owner works on Play Console/Google Group setup in parallel; #74 body already retargeted to v1.3.0).
2. [ ] **#75 — log rate-limit headers** (capture `x-ratelimit-remaining-requests`/`-tokens`, reset headers, `retry-after` + 429 counts per day/model from STT/LLM responses; local persistence per repo conventions, simple usage view in Settings).
3. [ ] **#71 — REC time counter in IME bar during recording** (enhancement; + drop adaptive noise-floor follow-up).
4. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
5. [ ] **Insertion-spacing watch**: owner may report refinements of the padding rules from longer use — DOMAIN.md is the rule reference (now includes the #73 field-start refinement); changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only. Reads still work on Oplus.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5. HeliBoard is currently default on the OnePlus.
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly (#128).
- Oplus/OnePlus suppresses app-level IME logcat; S5 (LineageOS) shows app lines normally. Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` remain stale; README screenshots live in `docs/img/`.
- **Agent host (VPS) has no attached device** — the owner runs the on-device feel-check (`installRelease` + visual) from the machine with the connected device; leave device-only verification as an explicit HANDOFF item.
- Pulse-contrast check (#77): while RECORDING the foreground rows breathe between 0.15 and 0.9; in IDLE/PAUSED/PROCESSING they must be fully opaque (`PulseAlphaPolicy`). The pause bug (frozen at 0.15) only reproduces when pausing during the deep dwell window of the 2 s cycle. Device-verified by owner 2026-09-14 (v1.2.3).

Last cleared: 2026-09-16 (#78 closed as shipped, #67 closed as maybe-later; next: #74 v1.3.0 listing-assets release).
