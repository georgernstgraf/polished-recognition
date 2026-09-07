# Project State

Current status as of 2026-09-07 (v1.2.1 released; #69 pulse-depth tuning implemented + pushed; #70 Gboard docs warning done).

## Current Focus
**#70 complete** (commit 4c70c45): prominent Gboard-hardcoded-mic warning in README (EN-only), INSTALLATION.md de-Germanized + keyboard list updated per owner (AOSP/LineageOS first, HeliBoard F-Droid-only, AnySoftKeyboard dropped). Issue closed. Closing this session.

## Completed (this cycle)
- [x] #65 round 1: keyboard icon button in IME row 1 (`<queries android.view.InputMethod>`).
- [x] #65 round 2: session preservation across keyboard switches — app-scoped `VoiceSessionController` + `attach`/`detach`, auto-resume on re-entry (`AutoStartPolicy.shouldAutoResume`, owner: "immer bei PAUSED").
- [x] #65 round 3 (redesign): `SwitchTargetPolicy` — target resolved from `input_methods_subtype_history` (first enabled non-self key keyboard) → `switchInputMethod(id)`; no target → `Settings.ACTION_INPUT_METHOD_SETTINGS`; fresh `default_input_method` post-check as safety net. Root cause: `switchToPreviousInputMethod` self-target trap (see PITFALLS). 10 unit tests; 160 total green.
- [x] #65 on-device verification (S5, release build): switch round-trip PASS (20.2 s combined dictation), cancel PASS (no upload/insertion), settings-fallback PASS (only-Polished state), direct-switch PASS (keyboard enabled + history head = self) — the state where the old approach failed.
- [x] Device installs: S5 (d890cc9e) + OnePlus 7T (f6de166c) on the final release build.
- [x] README: #65 feature bullets (switch icon, dictation survives switches), stale fixes ("debug key" → release-key note, "AppCompat/Material" → plain XML UI).
- [x] Screenshots refreshed (OPO, 1080×2400): `docs/img/ime-recording.png` (new bar with switch icon, RECORDING state, Markor background), `docs/img/settings.png` (Processing section, Raw OFF — #58 framing).
- [x] #69: pulse floors 0.45 → 0.15 (mapper + breath), keyframe cycle with 15% floor dwell (300 ms / 2 s cycle); tests re-based + green; assembleRelease green; installed on OnePlus (f6de166c); issue comment + commit c0f00ff pushed.
- [x] #70: README Gboard warning (prominent callout + "Works everywhere" bullet + Troubleshooting bullet); INSTALLATION.md EN-only (German section removed), AOSP keyboard added, HeliBoard marked F-Droid-only, AnySoftKeyboard example dropped; commit 4c70c45 pushed, issue closed.

## Pending
- [ ] #67: disk snapshot of paused dictation — UNLINKED from #65 (open sub-issue would block closing); standalone deferred `enhancement` now.
- [x] #62: CLOSED not-planned (2026-09-07) — premise obsolete: HeliBoard's mic works via the auxiliary voice IME on the OnePlus (no bound service needed); PITFALLS entry corrected.
- [ ] #64: explore parallelize/hide Ogg/Opus compression latency (measure per-stage timings on the S5 first).
- [ ] MR !40029: updated per linsui (single 1.2.1 Build entry, old versions removed) — pipeline fully green, reply posted 2026-09-07; waiting for merge.
- [ ] #69 feel-check on the S5 (device was not connected at implementation time); if a louder room clamps the depth, the prepared lever is the round-3 adaptive noise floor.

## Blockers
None.

## Device Notes (corrected this session)
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920; the 1080×2400 in older notes = OnePlus). adb `ime`/`settings` WORK there (userdebug) — Oplus blockade doesn't apply.
- HeliBoard IS installed on the S5 (`helium314.keyboard`) — was hidden because disabled; owner re-enabled it via the new settings fallback during testing.
- f6de166c = OnePlus 7T (HD1903, 1080×2400) — original screenshot device; provider configured; Oplus shell restrictions apply.

## Next Session Suggestion
#69 S5 feel-check (plus adaptive noise floor if depth clamps in a louder room). MR !40029 watch (merge by linsui). Then #64 (Ogg latency timings on the S5) or #67.