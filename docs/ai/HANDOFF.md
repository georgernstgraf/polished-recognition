# Hand Off

**Active: auxiliary voice IME (#43) — implemented + on-device-tested; two follow-up issues opened for fresh agents. This session does NOT implement the follow-ups.**

## What's done (commit `97939c9` + uncommitted session fixes)
- #43: `VoiceSessionController` (de-dup), `PolishedVoiceInputIME` + `voice_method.xml` + `MicrophonePermissionActivity`, removed `PolishedRecognitionService`, `SettingsActivity`→"Enable Voice Keyboard", `INSTALLATION.md`, bump to 1.2.0. (committed `97939c9`)
- Crash fix: IME `ImageButton` `?attr/...` → `@null` (Material theme attrs crash in the service context — see PITFALLS). (uncommitted)
- Deadlock fix: `VoiceSessionController` → IDLE after `Completed`. (uncommitted)
- CrashDialog Copy-to-Clipboard (left) + Close App (right). (uncommitted)
- v1.2.0 compact bar baseline (gear + language Spinner + Raw + Cancel + Mic). (uncommitted)
- GitLab token rotated + cached; fdroiddata worktree unstuck.

## Issues for fresh agents (full context in the issue bodies + PITFALLS)
- **#44 — IME voice bar redesign**: icon buttons (Cancel/Pause-Resume/Mic-Send), pause/resume, **implicit-pause on Settings-gear** (gear press during RECORDING → `controller.pause()` → Settings; on return → PAUSED → Resume button, user resumes explicitly), spinner contrast (custom item layouts w/ explicit colors), lifecycle fix (`onFinishInputView` must NOT cancel PAUSED — only pause RECORDING). Do NOT delegate to the full-screen activity (user prefers the compact bar). Button backgrounds must be `?android:attr/selectableItemBackgroundBorderless` or `@null` (never `?attr/`).
- **#45 — Settings theme-free**: replace Material (`TextInputLayout`, `MaterialCheckBox`, `Widget.Material3.*`, `?attr/textAppearance*`, androidx `AlertDialog`) with plain Views + platform theme. No functional need (aesthetic). Preserve the custom `BaseAdapter+Filterable` model dropdown (PITFALLS #34/35) + the CrashDialog Copy button.

## Next steps
1. Fresh agent: implement #44 → `./gradlew assembleRelease` + `test` → `adb install -r` → on-device verify (pause/resume/send/cancel + gear-implicit-pause→return→Resume + spinner readability + 2nd-dictation no deadlock).
2. Fresh agent: implement #45 (Settings theme-free) → verify.
3. After both: tag `v1.2.0` → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, currently clean at `ae5b1e5d`).

## Known on-device gotchas (Oplus/OnePlus)
- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished (the bound service was removed). Use the nav-bar switcher to set Polished as the active keyboard, or Fossify Keyboard (has a voice-typing selector). Re-adding the bound service is a possible future follow-up if HeliBoard's mic is wanted.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.

Last cleared: 2026-08-22. Knowledge files current (DECISIONS/STATE/PITFALLS updated with all session findings).
