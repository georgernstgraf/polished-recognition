# Hand Off

**2026-09-19: v1.2.4 (10204) RELEASED (#89) — feature patch since v1.2.3 (#71 REC counter, #81 line-wrap, #83 rotation-proof session, #86 flush button, #87 sine blink). Commit `8d9da93` bumped `versionCode 10204` / `versionName "1.2.4"` + refreshed `whatsnew-en-GB`; tag `v1.2.4` force-moved to `1ced0c8` (CI fix: setup-android `packages: platform-tools`, Google removed the legacy `tools` SDK package — android-actions/setup-android#537). `release.yml` green (Play upload edit `02449760292536382042` committed: alpha, completed, en-GB notes attached), `fdroid-apk.yml` green (reproducible APK), GitHub release has AAB + APK. F-Droid serves 1.2.3 (pickup confirmed working); 1.2.4 pickup is a watch item. Owner-side Play API verification pending (service-account key needs the owner's GPG key; `scripts/query-play-console.py`).**

## Open tasks

1. [ ] **#89 watch** — F-Droid 1.2.4 pickup (poll the package page; hours–days) + owner Play alpha verification (run `scripts/query-play-console.py` on the machine with the GPG key; expect alpha: 1.2.4 / 10204 with en-GB notes).
2. [ ] **#74 — v1.3.0 MINOR listing-assets release** (tester-infra docs half DONE `4623642`: Group `polished-recognition-alpha@googlegroups.com` live + attached to alpha, opt-in page verified live, INSTALLATION.md/README on Group self-join flow. Next: **new OnePlus screenshots** (owner, 1080×2400 light mode) → fastlane `images/` (icon + phoneScreenshots) → full_description rewrite → 10300/`1.3.0` bump + tag; owner parallel: demo GIF, repo polish, Phase-1 post drafts; #74 body already retargeted to v1.3.0).
3. [ ] **#75 — log rate-limit headers** (capture `x-ratelimit-remaining-requests`/`-tokens`, reset headers, `retry-after` + 429 counts per day/model from STT/LLM responses; local persistence per repo conventions, simple usage view in Settings).
4. [ ] **#84 — keep PCM buffer on pipeline failure**; **#82** — RecognitionService API work; **#88** — help texts.
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
