# Project State

Current status as of 2026-06-03.

## Current Focus

Issue #21 — Restore `installRelease` and fix CI signing config.

## Completed (this cycle)

- [x] #21 — Added `signingConfigs.release` back to `app/build.gradle.kts` with env var fallback
- [x] #21 — Restored `signingConfig = signingConfigs.release` to release build type
- [x] #21 — Reverted `build.yml` from `keytool -genkey` + `-Pandroid.injected.signing.*` to decode `RELEASE_KEYSTORE` + `RELEASE_*` env vars
- [x] #21 — `installRelease` task confirmed present, APK installed on device (HD1903, Android 12)
- [x] #21 — All 57 tests pass

## Pending

- [ ] Set GitHub Secrets for release.yml: `UPLOAD_KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- [ ] Complete Play Console: App Signing, Content Rating, Data Safety

## Blocker

None

## Next Session Suggestion

Complete Play Console setup (issue #20), then `git tag v1.0.0 && git push --tags`.
