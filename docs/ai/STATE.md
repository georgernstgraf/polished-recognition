# Project State

Current status as of 2026-06-02.

## Current Focus
Google Play release preparation — Issue #18.

## Completed (this cycle)
- [x] #5 — Token validation fix: TextWatcher clears error on text change, HTTP error body details displayed, dead tautology filter removed
- [x] #16 — VoiceRecognitionActivity rotation fix: added `android:configChanges="orientation|screenSize"` to prevent Activity restart and recording data loss
- [x] #17 — CI: use local debug keystore for consistent APK signatures
- [x] #18 — Created `public_html/` with Privacy Policy (English), landing page, Jekyll config
- [x] #18 — Generated `upload.keystore` (alias: `upload`) for Google Play upload signing
- [x] #18 — Updated `build.gradle.kts` to sign release builds with upload keystore
- [x] #18 — Verified `./gradlew bundleRelease` produces signed AAB (3.9 MB)

## Pending
- [ ] #18 — Phase 2 (manual): Play Console setup, upload key cert, store listing, screenshots from user

## Blockers
None — Phase 2 is manual via Google Play Console.

## Next Session Suggestion
Complete Play Console setup: developer account, upload key certificate (SHA1: E2:34:27:0A:50:9D:54:57:97:A6:4F:1D:53:83:3D:95:3B:DC:86:54), store listing with screenshots.
