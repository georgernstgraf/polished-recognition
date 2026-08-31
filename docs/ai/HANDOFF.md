# Hand Off

**#60 (Ogg/Opus compression) implemented on master (545af3c) — on-device verification pending. MR !40029 maintainer watch; v1.2.1 release still open.**

## Open tasks

1. [ ] **#60 on-device verification** (`./gradlew installRelease`, always release):
   - Enable the new "Compress audio before upload" checkbox (end of STT section in Settings), record, confirm `cacheDir/recording.ogg` is produced and transcribes correctly via the configured Groq endpoint (MediaMuxer-produced Ogg Opus acceptance is the one unverified assumption).
   - Size comparison WAV vs OGG for a real recording (~10x expected: 256 kbps → 24 kbps).
   - Toggle checkbox off → WAV path unchanged; airplane-mode failure still toasts the error detail (transcoder failure must silently fall back to WAV, only the upload may fail visibly).
   - Check logcat tag `VoiceSessionController` for `Opus transcoding failed, falling back to WAV` warnings.
2. [ ] **MR !40029 watch**: F-Droid maintainer (linsui) response to the 1.2.0 bump; fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f` (branch `add-polished-recognition`).
3. [ ] **v1.2.1 release**: master has verification-round fixes (ac75231) + #60 beyond v1.2.0 — bump versionCode → tag → release.yml → fdroiddata bump + MR comment. Consider bundling with any linsui feedback.

## Known on-device gotchas (Oplus/OnePlus)

- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf/polishedrecognition/files/logs/` (llm-prompt/llm-response rotating JSON) — no root needed.
- Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- Screenshot automation: tap coords need REAL pixels (`wm size` 1080×2400; screencap PNGs display at 900×2000 → ×1.2). IME state: pause btn = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` are stale — README screenshots live in `docs/img/`.

Last cleared: 2026-08-31 (late — #58 closed, knowledge current).
