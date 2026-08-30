# Hand Off

**Active: none — #49 implemented + closed (commit `b032d44`), #45/#44/#46 closed. Next: tag `v1.2.0` → fdroiddata metadata bump → MR !40029.**

## What's done

### #49 — Default system prompt overhaul (commit `b032d44`, CLOSED — installed on device)
- `app/src/main/assets/prompts.json`: new default system prompt. Order: role+context first (voice dictation → STT → inserted into app's text field) → `{{source_language_clause}}` → concrete cleanup bullets (grammar/spelling/punctuation, fillers/stutters/repetitions, structure+markdown) → preserve meaning → ambiguity carve-out ("most plausible intended reading") → don't answer dictation questions → Whisper trailing-hallucination clause → "Return only the cleaned-up transcription." → `{{target_language_clause}}`.
- Hallucination clause now **trailing-only**: strip the trailing hallucination; empty string only if the ENTIRE transcription is one (old behavior emptied everything). "Subtitles by Amara" dropped — never appears on Whisper.
- Output clause is positive-only ("Return only the cleaned-up transcription.") — counter-example list dropped.
- Tests updated: `PromptStoreTest.kt` (assertions → "post-process voice dictation", "Return only the cleaned-up transcription", + `doesNotContain("Amara")`), `TranscriptionPipelineTest.kt:288-289`. `./gradlew test` green.
- Placeholders and pipeline clause injection (`TranscriptionPipeline.kt:62-70`) unchanged. `GroqApiIntegrationTest` uses its own literal prompt — untouched.
- Installed on OnePlus 7T via `installRelease` (same release key → Groq config preserved). **On-device**: restore default system prompt in Settings to activate it. Fold into v1.2.0 (tag AFTER this, so the release ships the new prompt).

## Open tasks
1. [ ] **Tag `v1.2.0`** (now includes #49's new prompt) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, currently clean at `ae5b1e5d`).
2. [ ] **On-device verify #45**: open Settings: provider dropdown opens on tap, validate/fetch, model dropdown searchable, token eye toggle works, save; force a crash → CrashDialog Copy button. Reopen #45 if anything regressed. (Release build already installed.)

## Known on-device gotchas (Oplus/OnePlus)
- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished. Use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts. (Now also true for Settings/Crash layouts under `Theme.DeviceDefault` — see PITFALLS.)
- "Manage Keyboards" greyed-out behavior matches AOSP — no Oplus OEM override (7-row evidence table in PITFALLS).

Last cleared: 2026-08-30. Knowledge files current (STATE/HANDOFF/DECISIONS/PITFALLS/DOMAIN/HISTORY updated for #49; #49 closed).
