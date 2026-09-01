# Hand Off

**#60 (Ogg/Opus compression + stage feedback) CLOSED — verified on device 2026-09-01. Next: #61 (remove VoiceRecognitionActivity + Material), then v1.2.1 release. MR !40029 watch continues.**

## Open tasks

1. [ ] **#61 — remove VoiceRecognitionActivity + drop Material** (owner-approved plan): relocate `NONE_TARGET_LANGUAGE`/`buildLanguageList` from the activity to `config/` (IME uses them at PolishedVoiceInputIME.kt:31,146,161,173); delete activity + layout + manifest block (AndroidManifest.xml:19-30); audit shared resources before deleting; drop `com.google.android.material` and check `appcompat`/theme parents for remaining consumers. Verified tradeoff: RecognizerIntent callers get ActivityNotFoundException.
2. [ ] **#62 — HeliBoard mic via additive bound RecognitionService** (issue created, not started; needs dedicated design session).
3. [ ] **MR !40029 watch**: F-Droid maintainer (linsui) response to the 1.2.0 bump; fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f` (branch `add-polished-recognition`).
4. [ ] **v1.2.1 release**: master now has ac75231 fixes + #60 + (upcoming) #61 — bump versionCode → tag → release.yml → fdroiddata bump + MR comment. Consider bundling with any linsui feedback.

## Known on-device gotchas (Oplus/OnePlus)

- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf/polishedrecognition/files/logs/` (llm-prompt/llm-response rotating JSON) — no root needed.
- Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).
- Screenshot automation: tap coords need REAL pixels (`wm size` 1080×2400; screencap PNGs display at 900×2000 → ×1.2). IME state: pause btn = recording, ↺ = paused (interrupted sessions persist PAUSED). `distribution/*.png` are stale — README screenshots live in `docs/img/`.

Last cleared: 2026-08-31 (late — #58 closed, knowledge current).
