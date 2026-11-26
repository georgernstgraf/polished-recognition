---
type: State
title: Project State — Settings
description: The settings overview, its four category screens and the draft/apply save model.
tags: [knowledge, state, settings]
generated: { by: opencode/knowledge-persistence, at: 2026-11-26T13:20:00Z }
sources: ["#212"]
status: stable
---

# Project State — Settings

The settings surface as its own theme since #212 (the overview + the four category
screens). Sections are verbatim, newest first.

## #212 — Settings becomes an overview with four category screens (2026-11-26, implemented — OPEN until the owner's device checks)

- **Shape:** `SettingsActivity` is the OVERVIEW — target-language spinner (+ long-press
  add/edit + `manage_custom_languages` dialog, unchanged and WRITE-THROUGH, the IME
  spinner shares the field) on top, then four category buttons in the owner-agreed order
  (1 Providers & Tokens, 2 Manage Prompts, 3 Output Control, 4 Advanced / Debugging),
  `set_recognition_service` + About at the bottom, **no Save buttons anywhere**.
- **New screens** (plain activities, `Theme.PolishedRecognition.Plain`, `exported="false"`):
  `ui/ProvidersActivity.kt` (STT + LLM provider config incl. model selection, presets,
  validate/fetch/test, eye toggles — live behaviors verbatim), `ui/PromptsActivity.kt`
  (system prompt + target-language clause, resets stay immediate and clear their draft
  entry), `ui/OutputControlActivity.kt` (wrap width + presets + per-app section),
  `ui/AdvancedActivity.kt` (slice/search auto+lock, FIFO, OGG compression, live-preview
  kill switch, evidence-retention slider). Layouts `activity_{providers,prompts,
  output_control,advanced}.xml`; the old sections moved verbatim with the same view ids.
- **Save model:** `ui/SettingsDraft.kt` (process-wide singleton, pending edits only,
  unset = stored value) + `ui/SettingsValidation.kt` (the old `saveAndClose()` checks
  extracted verbatim — same 9 check names, same order — plus `apply()`). Sub-screen Back
  writes ONLY the draft (no toast, no prefs). Back on the overview = leaving Settings:
  validate → success applies the draft, toast "Settings saved", finish; failure toasts
  the first failed check (`settings-save-aborted` log line per failed check), finishes
  anyway, and the failing entries keep their stored value while everything else applies.
- **Back mechanics:** both platform paths route into `SettingsActivity.handleBackExit()`
  (API ≥ 33 `OnBackInvokedDispatcher` callback + classic `onBackPressed()` override,
  `backExitHandled` guard makes double delivery a no-op; the manifest does NOT set
  `enableOnBackInvokedCallback`, so the classic path is the live one — inline
  `@Suppress("GestureBackNavigation")` with the reason documented).
- **Draft edge cases (deliberate, tested):** empty draft → "Settings saved" WITHOUT
  validation (backing out of untouched Settings must not abort on an unconfigured
  provider); `apply()` writes a provider entry only when the draft holds a pending
  provider change (an empty draft never materialises an empty config over `null`);
  unchecking slice/search auto keeps the resolved value in the draft so back-exit
  validates the displayed value, not an empty manual field; per-app width raw edits
  survive sub-screen hops (`pendingAppWrap` redisplayed).
- **Tests:** 805 `:app` (was 771) — new `SettingsValidationTest` (check-name contract ×
  9, draft-isEmpty, apply-skips-invalid, token/secret separation), per-screen Robolectric
  tests (`ProvidersActivityTest` 5, `PromptsActivityTest` 4, `OutputControlActivityTest`
  6, `AdvancedActivityTest` 5), `SettingsActivityTest` rewritten for the overview (7);
  `ModelFilterAdapter` extracted (`ui/ModelFilterAdapter.kt`); the four screens joined
  `ui/contrast/ContrastMatrix.kt` (all four passed light/night). `SettingsStore` keys,
  IME and Workbench untouched; no migration.
- Commit `313d1c1`; suite forced green (`--rerun-tasks --no-build-cache`) + `lintDebug`
  0; release installed on BOTH devices. Owner checks: see the issue comment.
