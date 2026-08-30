# Hand Off

**Active: none — #49/#50/#51 implemented + CLOSED + verified on device (latest `643cc38`, docs `6801de6`). Next: tag `v1.2.0` → fdroiddata metadata bump → MR !40029. NOTE: user works in parallel on #52 (Gradle wrapper/CI) — check `git log` before assuming HEAD state.**

## What's done

### #50 — Model dropdown UX (rounds `b87e018` + `9847d9a` + `643cc38`, CLOSED — verified on device)
- Tap → full list (click hook + `showAll()`; exact-match→full-list in `performFiltering` kills the async focus-filter race); `threshold=1` keeps search-as-you-type.
- Per-provider model cache (`SettingsStore`): keyed per endpoint URL, `{timestamp, models}` entries, **6-week TTL** (user decision); expired/uncached → free text + save OK; legacy keys migrate under the saved provider's baseUrl.
- `text|textNoSuggestions` + `importantForAutofill="no"` on both model fields (kills IME interference).
- Symmetric validation: blank → "Select or enter a model — a space shows all" (save blocked); non-blank not in current URL's cached list → "Select a model from the list"; fires on focus loss AND on selections in the non-focusable provider/language dropdowns (item-click hooks).
- Hint "Select or fetch a model"; toast "No cached models for this endpoint — validate / fetch first".
- `SettingsStoreTest` +8 cases. PITFALLS: period key = contains-filter red herring; focusable=false dropdowns skip focus-loss validation.

### #51 — IME gear hint dialog (commit `b7dd88c`, CLOSED — verified on device)
- `SettingsHintActivity` (transparent `Plain.Transparent`, `noHistory`, plain AlertDialog, OK → finish). IME gear + mic-notification `contentIntent` launch it instead of `SettingsActivity` — circular dependency (Settings' EditTexts summoning the Polished voice bar) broken from both entry points.

### #49 — Default system prompt overhaul (commit `b032d44`, CLOSED — installed on device)
- `app/src/main/assets/prompts.json`: context-first order → `{{source_language_clause}}` → concrete cleanup bullets → preserve meaning → ambiguity carve-out → don't answer dictation questions → Whisper trailing-hallucination clause (strip trailing only; empty string only if the ENTIRE transcription is one; "Subtitles by Amara" dropped) → "Return only the cleaned-up transcription." → `{{target_language_clause}}`.
- Tests updated (`PromptStoreTest`, `TranscriptionPipelineTest`, incl. `doesNotContain("Amara")` guard). Fold into v1.2.0.

## Open tasks
1. [ ] **Tag `v1.2.0`** (includes #49/#50/#51; user deferred tagging this session) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, currently clean at `ae5b1e5d`).
2. [ ] **#45 leftover**: CrashDialog Copy-button on-device test (release build already installed; force a crash while the app is foreground, e.g. `adb shell am crash com.georgernstgraf.polishedrecognition`).
3. [ ] **#52 (user's parallel work)**: commit `643cc38` accidentally carries #52's leftover wrapper files (`gradlew`, `gradlew.bat`, `gradle-wrapper.properties` — the script+properties half of the wrapper regeneration, jar was already committed in `3eb5927`). Content correct, attribution mixed — decide: leave as-is (noted on #52) or split via force-push.

## Known on-device gotchas (Oplus/OnePlus)
- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished. Use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts. (Now also true for Settings/Crash layouts under `Theme.DeviceDefault` — see PITFALLS.)
- "Manage Keyboards" greyed-out behavior matches AOSP — no Oplus OEM override (7-row evidence table in PITFALLS).

Last cleared: 2026-08-30. Knowledge files current (HANDOFF/STATE updated for #50/#51 closure; DECISIONS/PITFALLS/DOMAIN current as of rounds 2–3; #52 noted).
