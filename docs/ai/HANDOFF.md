# Hand Off

**#61 (remove VoiceRecognitionActivity + drop Material/appcompat) CLOSED 2026-09-01 — build/tests/install verified, IME + Settings confirmed alive via dumpsys. Next: on-device IME smoke pass, then #62 (HeliBoard RecognitionService design session) or v1.2.1 release. MR !40029 watch continues.**

## Open tasks

1. [ ] **On-device IME smoke pass for #61**: open IME in a text field — quick-settings spinner, raw checkbox, gear, and a real transcription via AnySoftKeyboard (language logic moved to `config/LanguageOptions`).
2. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (issue created with full option analysis; recommended entry: measure per-stage transcode timings on the S5 first, then prefer stream-transcode-during-recording over chunked parallel encode).
3. [ ] **#62 — HeliBoard mic via additive bound RecognitionService** (issue created, not started; needs dedicated design session).
4. [ ] **MR !40029 watch**: F-Droid maintainer (linsui) response to the 1.2.0 bump; fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f` (branch `add-polished-recognition`).
5. [ ] **v1.2.1 release**: master now has ac75231 fixes + #60 + #61 — bump versionCode → tag → release.yml → fdroiddata bump + MR comment. Consider bundling with any linsui feedback.

## Known on-device gotchas (Oplus/OnePlus)

- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf/polishedrecognition/files/logs/` (llm-prompt/llm-response rotating JSON) — no root needed.
- Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- Screenshot automation: tap coords need REAL pixels (`wm size` 1080×2400; screencap PNGs display at 900×2000 → ×1.2). IME state: pause btn = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` are stale — README screenshots live in `docs/img/`.

Last cleared: 2026-09-01 (#61 closed, knowledge current).
