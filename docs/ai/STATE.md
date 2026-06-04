# Project State

Current status as of 2026-06-04.

## Current Focus

Issue #24 — Update Prompts: Add Whisper Hallucination Guardrails.

## Completed (this cycle)

- [x] #24 — Added guardrails against Whisper silence hallucinations to `prompts.json` and `PromptStore.kt`.
- [x] #23 — Updated README to add "Recommended Setup (GROQ)" emphasizing speed and free tier.
- [x] #22 — Added `default_model` to `ProviderPreset` data class and updated `provider_presets.json` with optimal defaults (e.g., `openai/gpt-oss-120b`, `whisper-large-v3-turbo` for Groq).
- [x] #22 — Updated `SettingsActivity.kt` auto-select logic on fetch.
- [x] #21 — Restored `installRelease` (added `signingConfigs.release` back)
- [x] #20 — Fixed `versionCode` awk formula (`00` → `01`) to prevent zero
- [x] #20 — Upgraded: AGP 9.1.1, Gradle 9.5.1, Java 21, compileSdk/targetSdk 36
- [x] #20 — Removed Kotlin plugin (AGP 9.x has built-in Kotlin support)
- [x] #20 — CI `build.yml` now produces both APK and AAB (both signed with debug key)
- [x] #20 — CI `release.yml` now targets internal track with status=completed (live)
- [x] #20 — Replaced `track` with `tracks` (deprecation fix)
- [x] #20 — Replaced flat vector launcher icon with high-quality PNG (downscaled from 512×512 source)
- [x] #20 — Restored high-quality SVG to `distribution/play-store-icon.svg`
- [x] #20 — Added `scripts/query-play-console.py` for API-based Play Console queries
- [x] #20 — Added `scripts/upload-aab.py` for local upload debugging (not CI)
- [x] #20 — Internal track has completed release v0.0.5 (versionCode 501)
- [x] #20 — Direct API upload confirmed works for internal track
- [x] #20 — README updated with Play Store listing text, 512×512 icon, feature graphic prompts

## Pending

- [ ] Complete Play Console preconditions (Content Rating, Data Safety, App Signing) to unblock production track
- [ ] Switch `release.yml` from `tracks: internal` back to `tracks: production` when preconditions resolved
- [ ] Push first production release (e.g. `v1.0.0`)

## Blocker

None — internal track is live for testing. Production track needs Play Console web UI setup.

## Next Session Suggestion

Complete Content Rating, Data Safety, and App Signing enrollment in Play Console web UI, then switch release.yml to production track and push first production release.
