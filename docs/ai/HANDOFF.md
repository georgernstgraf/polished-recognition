# Hand Off

**Active: none. #49/#50/#51/#52 all implemented + CLOSED. #52 (Gradle caching) fully validated — see STATE.md for numbers. Next: tag `v1.2.0` → fdroiddata metadata bump → MR !40029.**

## What's done this cycle

### #52 — Gradle caching + config cache (commits `66eb197` + `3eb5927` + `0340ac9`, CLOSED)
- Repo `gradle.properties`: `org.gradle.caching/parallel/configuration-cache=true`. Local no-op build: 3s (config cache reuse).
- All 3 workflows: `gradle/actions/setup-gradle@v4`. `build.yml`: Robolectric `~/.m2` cache + merged single invocation `./gradlew test assembleRelease bundleRelease`. Warm CI build step 28s (was ~187s).
- `release.yml`: `workflow_dispatch` + `dry_run` input (default true) — skips GitHub Release creation + Play upload. Validated from master (run 33327458318): 1m27s, upload steps correctly skipped. Real tag runs are unaffected (`inputs.dry_run` empty → upload happens).
- Wrapper jar regenerated to official 9.5.1 checksum (`497c8c2a…`, was unlisted `b5173cbc…`) — was blocking `setup-gradle` validation. Tag workflows keep `validate-wrappers: false` (old tags carry the old jar forever).
- Reproducibility proven: `fdroid-apk.yml` rebuild of `v1.1.1` byte-identical (sha256 `53d06787…` before/after).

### #50 — Model dropdown UX (rounds `b87e018` + `9847d9a` + `643cc38`, CLOSED — verified on device)
- Tap → full list; per-URL model cache 6-week TTL; `textNoSuggestions`+autofill-off; symmetric validation; hinting error text; cache-aware toast. See DECISIONS/PITFALLS.

### #51 — IME gear hint dialog (commit `b7dd88c`, CLOSED — verified on device)
- `SettingsHintActivity` trampoline; gear + mic-notification repointed; circular dependency broken.

### #49 — Default system prompt overhaul (commit `b032d44`, CLOSED — installed on device)
- Context-first prompt, trailing-only hallucination strip, positive output clause. Fold into v1.2.0.

## Open tasks
1. [ ] **Tag `v1.2.0`** (includes #49/#50/#51; user deferred tagging this session) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`; verify current HEAD). Push the tag separately from branch commits (PITFALLS).
2. [ ] **#45 leftover**: CrashDialog Copy-button on-device test (force a crash: `adb shell am crash com.georgernstgraf.polishedrecognition`).
3. [ ] The **real Play upload** step of `release.yml` is the only #52-related path not yet exercised live (dry-run covered everything else). The `v1.2.0` tag run validates it — watch that run closely.

## Known on-device gotchas (Oplus/OnePlus)
- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished. Use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts. (Also true for Settings/Crash layouts under `Theme.DeviceDefault` — see PITFALLS.)
- "Manage Keyboards" greyed-out behavior matches AOSP — no Oplus OEM override (7-row evidence table in PITFALLS).

Last cleared: 2026-08-30 (late). Knowledge files current for #52 closure (DECISIONS/PITFALLS/STATE updated; wrapper attribution task from earlier HANDOFF resolved by commits `3eb5927`/`66eb197`).
