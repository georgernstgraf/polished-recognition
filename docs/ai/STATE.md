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
