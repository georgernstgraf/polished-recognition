# Project State

Current status as of 2026-08-22.

## Current Focus
**Auxiliary voice IME** — implemented per MR !40029 comment by `@bhavyashah04122005` (endorsed by maintainer `linsui`). App now ships as an auxiliary voice keyboard (`PolishedVoiceInputIME`) for HeliBoard/Fossify/OpenBoard; bound `RecognitionService` removed; `VoiceRecognitionActivity` (intent path, full UI) kept. `./gradlew assembleRelease` + `test` green. **Awaiting on-device verification** (user: device + HeliBoard/Fossify) and a new app release + MR !40029 metadata bump.

## Completed (this cycle)
- [x] Extracted `VoiceSessionController` (de-duplicates the record→transcribe flow; shared by the activity and the IME).
- [x] Refactored `VoiceRecognitionActivity` onto the controller (full UI preserved: pause/resume, quick language/raw, timer, blink).
- [x] Added `PolishedVoiceInputIME` (`InputMethodService`, `isAuxiliary`/`imeSubtypeMode=voice`), `res/xml/voice_method.xml`, `layout/ime_voice_input.xml`, `MicrophonePermissionActivity` trampoline, FGS-mic notification.
- [x] Removed `PolishedRecognitionService` + manifest block; `SettingsActivity` "Set as Voice Input" → "Enable Voice Keyboard (IME)" → `ACTION_INPUT_METHOD_SETTINGS`.
- [x] Rewrote `INSTALLATION.md` (dropped ADB `voice_recognition_service` block + AnySoftKeyboard-required step; IME-enable flow).
- [x] fdroiddata worktree unstuck (`git rebase --abort` → `add-polished-recognition` @ `ae5b1e5d`); exposed GitLab PAT stripped from remote URL + local `credential.helper`; global `credential.helper=store` set. Token rotation pending (user, web).

## Pending
- [ ] On-device IME verification (enable IME → select as voice input in HeliBoard/Fossify → `commitText` into a field).
- [ ] New app release (e.g. 1.2.0) + bump fdroiddata metadata commit hash + comment on MR !40029. **Blocked on user rotating GitLab token + first authed `git fetch`.**
- [ ] Issue #20: Play Console preconditions before production track.

## Blockers
None (GitLab token rotation is user-side; doesn't block app-repo work).

## Next Session Suggestion
1. User rotates GitLab token + `git fetch` (token stays out of chat).
2. Cut a new release tag → `release.yml` builds signed APK + AAB.
3. Bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit + reproducible `Binaries`/`AllowedAPKSigningKeys` if signing key unchanged) in the fdroiddata worktree; collapse stale version-pinning commits via `git reset --soft`; comment on MR !40029.
