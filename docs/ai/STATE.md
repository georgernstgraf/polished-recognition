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
- [x] #62: CLOSED not-planned (2026-09-07) — premise obsolete: HeliBoard's mic works via the auxiliary voice IME on the OnePlus (no bound service needed); PITFALLS entry corrected.
- [ ] #64: explore parallelize/hide Ogg/Opus compression latency (measure per-stage timings on the S5 first).
- [ ] MR !40029: act on linsui's 2026-08-31 feedback — remove the old 1.1.1 Build entry (New-App MR should carry only the newest version) + fix the pipeline (`fdroid rewritemeta` wants Builds chronological; 1.2.0 was prepended above 1.1.1 → job failed).
- [ ] Cut v1.2.1 (versionCode → 10201, versionName 1.2.1, tag → release.yml → fdroiddata Build entry + MR comment).

## Blockers
None.

## Device Notes (corrected this session)
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920; the 1080×2400 in older notes = OnePlus). adb `ime`/`settings` WORK there (userdebug) — Oplus blockade doesn't apply.
- HeliBoard IS installed on the S5 (`helium314.keyboard`) — was hidden because disabled; owner re-enabled it via the new settings fallback during testing.
- f6de166c = OnePlus 7T (HD1903, 1080×2400) — original screenshot device; provider configured; Oplus shell restrictions apply.

## Next Session Suggestion
Cut v1.2.1 and bundle the MR !40029 fixes (remove 1.1.1 Build entry, chronological order / rewritemeta fix, reply to linsui). Then #64 or #67.
