# Project State

Current status as of 2026-09-07 (#65 implemented, on-device verified incl. switch-button redesign, screenshots refreshed — closing this session).

## Current Focus
**#65 complete**: keyboard-switch button with history-resolved target + keyboard-settings fallback, session preservation across switches; smoke-tested on S5, fresh README screenshots on OPO. Closing this session.

## Completed (this cycle)
- [x] #65 round 1: keyboard icon button in IME row 1 (`<queries android.view.InputMethod>`).
- [x] #65 round 2: session preservation across keyboard switches — app-scoped `VoiceSessionController` + `attach`/`detach`, auto-resume on re-entry (`AutoStartPolicy.shouldAutoResume`, owner: "immer bei PAUSED").
- [x] #65 round 3 (redesign): `SwitchTargetPolicy` — target resolved from `input_methods_subtype_history` (first enabled non-self key keyboard) → `switchInputMethod(id)`; no target → `Settings.ACTION_INPUT_METHOD_SETTINGS`; fresh `default_input_method` post-check as safety net. Root cause: `switchToPreviousInputMethod` self-target trap (see PITFALLS). 10 unit tests; 160 total green.
- [x] #65 on-device verification (S5, release build): switch round-trip PASS (20.2 s combined dictation), cancel PASS (no upload/insertion), settings-fallback PASS (only-Polished state), direct-switch PASS (keyboard enabled + history head = self) — the state where the old approach failed.
- [x] Device installs: S5 (d890cc9e) + OnePlus 7T (f6de166c) on the final release build.
- [x] README: #65 feature bullets (switch icon, dictation survives switches), stale fixes ("debug key" → release-key note, "AppCompat/Material" → plain XML UI).
- [x] Screenshots refreshed (OPO, 1080×2400): `docs/img/ime-recording.png` (new bar with switch icon, RECORDING state, Markor background), `docs/img/settings.png` (Processing section, Raw OFF — #58 framing).

## Pending
- [ ] #67: disk snapshot of paused dictation — UNLINKED from #65 (open sub-issue would block closing); standalone deferred `enhancement` now.
- [ ] #62: HeliBoard mic via additive bound RecognitionService (not started; needs dedicated design session).
- [ ] #64: explore parallelize/hide Ogg/Opus compression latency (measure per-stage timings on the S5 first).
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump.
- [ ] Cut v1.2.1 (versionCode bump) — bundles #60 + #61 + #65 (incl. redesign, README, screenshots).

## Blockers
None.

## Device Notes (corrected this session)
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920; the 1080×2400 in older notes = OnePlus). adb `ime`/`settings` WORK there (userdebug) — Oplus blockade doesn't apply.
- HeliBoard IS installed on the S5 (`helium314.keyboard`) — was hidden because disabled; owner re-enabled it via the new settings fallback during testing.
- f6de166c = OnePlus 7T (HD1903, 1080×2400) — original screenshot device; provider configured; Oplus shell restrictions apply.

## Next Session Suggestion
Start the #62 design session, or cut v1.2.1 (now includes switch-button redesign + fresh screenshots). Check MR !40029 for linsui feedback first.
