# Project State

Current status as of 2026-06-03.

## Current Focus

Issue #20 — Play Store listing and first release.

## Completed (this cycle)

- [x] #21 — Added `signingConfigs.release` back to `app/build.gradle.kts` with env var fallback
- [x] #21 — Restored `signingConfig = signingConfigs.release` to release build type
- [x] #21 — Reverted `build.yml` from `keytool -genkey` + `-Pandroid.injected.signing.*` to decode `RELEASE_KEYSTORE` + `RELEASE_*` env vars
- [x] #21 — `installRelease` task confirmed present, APK installed on device (HD1903, Android 12)
- [x] #21 — All 57 tests pass
- [x] #20 — Added `scripts/query-play-console.py` tool to check Play Console state via Developer API
- [x] #20 — Pushed `v0.0.0` tag → built signed AAB and uploaded to Play Store draft production track
- [x] #20 — Designed new app icon with gold Gemini-style sparkle (updated `ic_launcher_foreground.xml`)
- [x] #20 — Updated README with compelling Play Store-ready listing text

## Pending

- [ ] Complete Play Console setup web UI (Content Rating, Data Safety, App Signing) to satisfy production release preconditions
- [ ] Push first live release (e.g., `v1.0.0` or update draft production release to live)

## Blocker

None — draft AAB is uploaded, awaiting manual Play Console setup completion.

## Next Session Suggestion

Resolve draft preconditions in Play Console web UI, then promote the draft production release to live.
