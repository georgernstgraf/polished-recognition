# Hand Off

**Active: #50 + #51 implemented (commits `b87e018`, `b7dd88c`), installed on OnePlus 7T — ON-DEVICE VERIFY PENDING, then close. Next: tag `v1.2.0` → fdroiddata metadata bump → MR !40029.**

## What's done

### #50 — Model dropdown UX (commit `b87e018`, implemented — verify pending)
- `ModelFilterAdapter`: single `Filter` instance + sync `showAll()`; both model fields: click → `showAll(); showDropDown()` (full list, scrollable); no models → Toast "No models yet — validate / fetch first". `threshold=1` stays.
- `stt_model`/`llm_model`: `inputType="text|textNoSuggestions"` + `importantForAutofill="no"` — fixes "period inserts last active model" (IME autocorrect; hypothesis, confirm on-device).
- Focus-loss validation: fetched list exists + trimmed text not in it → error flag immediately (non-blocking); no list → free text, exitable. Save: strict in-list rule, trimmed text.
- `./gradlew test` green.

### #51 — IME gear hint dialog (commit `b7dd88c`, implemented — verify pending)
- New `SettingsHintActivity` (transparent `Plain.Transparent`, `noHistory`, plain AlertDialog, OK → finish). IME gear + mic-notification `contentIntent` launch it instead of `SettingsActivity` — kills the circular dependency (Settings' EditTexts summoning the Polished voice bar while Polished is the active keyboard).

### #49 — Default system prompt overhaul (commit `b032d44`, CLOSED — installed on device)
- `app/src/main/assets/prompts.json`: new default system prompt. Order: role+context first (voice dictation → STT → inserted into app's text field) → `{{source_language_clause}}` → concrete cleanup bullets (grammar/spelling/punctuation, fillers/stutters/repetitions, structure+markdown) → preserve meaning → ambiguity carve-out ("most plausible intended reading") → don't answer dictation questions → Whisper trailing-hallucination clause → "Return only the cleaned-up transcription." → `{{target_language_clause}}`.
- Hallucination clause now **trailing-only**: strip the trailing hallucination; empty string only if the ENTIRE transcription is one (old behavior emptied everything). "Subtitles by Amara" dropped — never appears on Whisper.
- Output clause is positive-only ("Return only the cleaned-up transcription.") — counter-example list dropped.
- Tests updated: `PromptStoreTest.kt` (assertions → "post-process voice dictation", "Return only the cleaned-up transcription", + `doesNotContain("Amara")`), `TranscriptionPipelineTest.kt:288-289`. `./gradlew test` green.
- Placeholders and pipeline clause injection (`TranscriptionPipeline.kt:62-70`) unchanged. `GroqApiIntegrationTest` uses its own literal prompt — untouched.
- Installed on OnePlus 7T via `installRelease` (same release key → Groq config preserved). **On-device**: restore default system prompt in Settings to activate it. Fold into v1.2.0 (tag AFTER this, so the release ships the new prompt).

## Open tasks
1. [ ] **On-device verify #50/#51** (new build already installed): model field tap → full list; typing filters; "." no insert; invalid text + focus elsewhere → red flag immediately; back-nav → list reachable, save works; gear as active keyboard → hint dialog; launcher → Settings editable (after keyboard switch). Also leftover from #45: CrashDialog Copy test. Close #50/#51 when green.
2. [ ] **Tag `v1.2.0`** (now includes #49/#50/#51) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, currently clean at `ae5b1e5d`).

## Known on-device gotchas (Oplus/OnePlus)
- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished. Use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts. (Now also true for Settings/Crash layouts under `Theme.DeviceDefault` — see PITFALLS.)
- "Manage Keyboards" greyed-out behavior matches AOSP — no Oplus OEM override (7-row evidence table in PITFALLS).

Last cleared: 2026-08-30. Knowledge files current (HANDOFF/STATE/DECISIONS/PITFALLS updated for #49/#50/#51).
