# Project State

Current status as of 2026-09-07 (#65 implemented, on-device smoke pending).

## Current Focus
**#65 (keyboard-switch button in the IME bar) implemented — build + 143 unit tests green, progress comment on the issue. Pending: on-device S5 smoke test, then close.** #61's on-device IME smoke pass is satisfied by the owner's 2026-09-07 report (invoking Polished via HeliBoard and AnySoftKeyboard works smoothly in the current build). Next after #65: #62 design session or v1.2.1 release.

## Completed (this cycle)
- [x] #65 implementation: keyboard icon button in IME row 1 (left of gear) — pause-if-recording → `switchToPreviousInputMethod()` → `showInputMethodPicker()` fallback; `<queries android.view.InputMethod>` manifest entry; enabled IDLE/RECORDING/PAUSED, disabled PROCESSING.
- [x] #61 smoke pass (via owner report): HeliBoard/AnySoftKeyboard → Polished invocation smooth in current build.

## Pending
- [ ] #65 on-device smoke test (S5): (a) dictation → button → direct switch back; (b) picker fallback path; (c) button during RECORDING → session stays PAUSED/resumable.
- [ ] #62: HeliBoard mic via additive bound RecognitionService (not started; needs dedicated design session).
- [ ] #64: explore parallelize/hide Ogg/Opus compression latency (measure per-stage timings on the S5 first).
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump.
- [ ] Cut v1.2.1 (versionCode bump) — now bundles #60 + #61 + #65.

## Blockers
None.

## Next Session Suggestion
On-device S5 smoke pass for #65 (then close it), then start the #62 design session or cut v1.2.1.
