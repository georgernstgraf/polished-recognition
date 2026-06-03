# Project State

Current status as of 2026-06-03.

## Current Focus

CI/CD workflows (Issue #19 closed). Ready for features.

## Completed (this cycle)

- [x] #19 — Removed `signingConfigs` from `build.gradle.kts` (signing via CI injection)
- [x] #19 — `build.yml` updated: APK signed with `~/.android/debug.keystore` via `-Pandroid.injected.signing.*`
- [x] #19 — `release.yml` created: on tag `v*` → Build AAB with upload keystore → Play Console → GitHub Release
- [x] #19 — `versionCode`/`versionName` injected via Gradle property (like zazentimer)
- [x] #19 — Tests pass (57/57), release APK builds unsigned locally

## Pending

- [ ] Set GitHub Secrets for release.yml: `UPLOAD_KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- [ ] Complete Play Console: App Signing, Content Rating, Data Safety

## Blocker

None

## Next Session Suggestion

Set release.yml secrets in GitHub, complete Play Console setup, then `git tag v1.0.0 && git push --tags`.

All open issues closed. Ready for new features.

## Completed (this cycle)

- [x] #5 — Token validation fix: TextWatcher clears error on text change, HTTP error body details displayed, dead tautology filter removed
- [x] #16 — VoiceRecognitionActivity rotation fix: added `android:configChanges="orientation|screenSize"` to prevent Activity restart and recording data loss
- [x] #17 — CI: explicit `signingConfigs.create("release")` with `app/release.keystore` for deterministic signing
- [x] `app/release.keystore` — local copy of `~/.android/debug.keystore` (gitignored)
- [x] `app/build.gradle.kts` — `signingConfigs.create("release")` block with fallback chain: `keystore.properties` → env vars → default values
- [x] CI workflow: decode keystore to `app/release.keystore` before tests, `RELEASE_*` env vars passed to `assembleRelease`
- [x] GitHub Secrets: `RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (old `DEBUG_*` deleted)

## Pending

- [ ] Play Console setup (manual): App erstellen, Upload-Zertifikat eintragen, Store-Eintrag, AAB hochladen

## Blockers

None — waiting for user to complete Play Console steps.

## Next Session Suggestion

Complete Play Console steps when ready.
