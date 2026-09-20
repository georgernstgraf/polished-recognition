# Hand Off

**2026-09-20: #82 IMPLEMENTED (`3e38bf7`, pushed, suite 253 green) — needs owner on-device feel-check before `finish`**; v1.3.0 (10300) TAGGED + Play upload committed (edit `07382800129640952742`, in review); #84 + #88 + #89 CLOSED. Next session: **`finish` #82 if feel-check passes**. Open: #82, #74 (watch/polish/drafts), #75, #64.

## Open tasks

1. [ ] **#82 feel-check (Peter + owner)** — Peter has build-287 `app-release.apk` (signed, release key) with test brief (Duolingo/Corvus service path + IME regression trio + logs); INSTALLATION.md §5 (ADB `voice_recognition_service` block) restored 2026-09-20 + correction comment posted — Peter retests on a #82 build. Owner runs the same trio on the device machine if possible. Then `finish` #82.

1. [x] **#89 CLOSED 2026-09-20** — F-Droid CurrentVersion 1.2.4/10204 confirmed, Play alpha 1.2.4 committed via CI; 1.3.0 supersedes on F-Droid (tracked in #74).
2. [ ] **#74 watch — v1.3.0 (10300) TAGGED 2026-09-20** (`25140f4` assets + `8138990` bump, tag pushed separately; release.yml + fdroid-apk.yml were in_progress on the tag): verify Play upload committed + fdroid-apk green + GitHub AAB/APK + F-Droid 1.3.0 pickup WITH listing images. Remaining: demo GIF (agent screenrecord, owner speaks), repo polish, Phase-1 drafts.
4. [ ] **#75 — log rate-limit headers** (capture `x-ratelimit-remaining-requests`/`-tokens`, reset headers, `retry-after` + 429 counts per day/model from STT/LLM responses; local persistence per repo conventions, simple usage view in Settings).
5. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
6. [ ] **Insertion-spacing watch**: owner may report refinements of the padding rules from longer use — DOMAIN.md is the rule reference (includes the #73 field-start refinement); changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Known on-device gotchas

- **Devices: d890cc9e = S5** (SM-G900F, LineageOS 18.1, `wm size` **1080×1920**); f6de166c = OnePlus 7T (HD1903, Oplus, **1080×2400**). Tap coords need REAL pixels (screencap PNGs displayed at 900×2000 → ×1.2 at OPO size).
- **adb `ime`/`settings put secure` WORK on the S5** (LineageOS userdebug) — scripted IME enable/disable + `input_methods_subtype_history` manipulation possible; Oplus (OnePlus) blocks them all via SecurityException — there: Settings UI only. Reads still work on Oplus.
- **HeliBoard IS installed on the S5** (`helium314.keyboard/.latin.LatinIME`) — it only disappears from `ime list -s` when disabled. The S5's own AOSP keyboard is `com.android.inputmethod.latin/.LatinIME` ("Android Keyboard (AOSP)") — a separate IME; owner uses it as the typing keyboard on the S5. HeliBoard is currently default on the OnePlus.
- **HeliBoard's mic uses the system `voice_recognition_service`**, NOT the auxiliary IME → use the nav-bar switcher or the Polished switch-button instead.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON + `ime-lifecycle.log`) — no root needed. Screenshot sessions with a configured provider LIVE-record on field focus (auto-start) — cancel explicitly (#128).
- IME state: pause bars = recording, ↺ = paused (interrupted sessions persist PAUSED), ␡ flush = discard buffer keeping mode. `distribution/*.png` remain stale; README screenshots live in `docs/img/`.
- **Agent host (VPS) has no attached device** — the owner runs the on-device feel-check (`installRelease` + visual) from the machine with the connected device; leave device-only verification as an explicit HANDOFF item.
- Pulse-contrast check (#87): while RECORDING the foreground rows breathe 0.3↔1.0 on a 1333 ms sine (`PulseAlphaPolicy.blinkAlpha`); in IDLE/PAUSED/PROCESSING they must be fully opaque (`PulseAlphaPolicy.target`).

Last cleared: 2026-09-19 (v1.2.4 released via #89; watch items in #89; next: #74 v1.3.0 listing-assets release).
