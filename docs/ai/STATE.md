# Project State

Current status as of 2026-06-04.

## Current Focus

Waiting for Google review of v0.0.6 (601) on closed testing (alpha) track.

## Completed (this cycle)

- [x] #24 — Added guardrails against Whisper silence hallucinations to `prompts.json` and `PromptStore.kt`.
- [x] #23 — Updated README to add "Recommended Setup (GROQ)" emphasizing speed and free tier.
- [x] #22 — Added `default_model` to `ProviderPreset` data class and updated `provider_presets.json` with optimal defaults.
- [x] #21 — Restored `installRelease` (added `signingConfigs.release` back).
- [x] #20 — Pure VectorDrawable launcher icon (replaced broken PNG/bitmap approach). Conversion script at `scripts/svg-to-vd.py`.
- [x] #20 — Play Store listing icon uploaded via API (SHA1 verified matching `distribution/play-store-icon.png`).
- [x] #20 — Removed CI Play Console upload from `release.yml` — CI now only builds + signs + attaches to GitHub Release. User creates releases manually.
- [x] #20 — Cleared corrupted alpha track release via API (the CI-created draft that locked the UI).
- [x] #20 — AGP 9.1.1, Gradle 9.5.1, Java 21, compileSdk/targetSdk 36 upgrade.
- [x] #20 — CI `build.yml` produces both APK and AAB.
- [x] #20 — Internal track live with v0.0.6 (601).
- [x] #20 — Scripts: `query-play-console.py`, `svg-to-vd.py`, `upload-store-icon.py`.

## Pending

- [ ] Google review of v0.0.6 (601) on closed testing (alpha) track — currently "under review".
- [ ] Once approved, user creates new releases manually in Play Console using the library-uploaded bundles.
- [ ] Eventually resolve production preconditions and push first production release.

## Blocker

Waiting on Google's review of the closed testing release.

## Next Session Suggestion

Check Play Console for review result. If approved, create a new release with the bundle from the artifact library and promote to open testing or production when ready.
