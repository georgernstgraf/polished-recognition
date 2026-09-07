# Project State

Current status as of 2026-09-07 (#65: switch button + session preservation implemented, on-device smoke pending).

## Current Focus
**#65 (keyboard-switch button + preserved paused dictation) implemented — 150 unit tests green, installed on device. Pending: on-device smoke test of the full flow, then close.** #61's IME smoke pass is satisfied by the owner's report (HeliBoard/AnySoftKeyboard invocation smooth). Next after #65: #62 design session or v1.2.1 release.

## Completed (this cycle)
- [x] #65 round 1: keyboard icon button in IME row 1 (left of gear) — pause-if-recording → `switchToPreviousInputMethod()` → `showInputMethodPicker()` fallback; `<queries android.view.InputMethod>` manifest entry.
- [x] #65 round 2: session preservation across keyboard switches — app-scoped `VoiceSessionController` + `attach`/`detach`, `onDestroy` detaches PAUSED sessions instead of cancelling, auto-resume on re-entry (`AutoStartPolicy.shouldAutoResume`, owner chose "immer bei PAUSED"). Root cause was `onDestroy` → `controller.cancel()` discarding the PCM buffer on IME destruction.
- [x] #67 created (disk snapshot for process-death robustness) as sub-issue of #65, labeled `enhancement` — deferred by owner.
- [x] #61 smoke pass (via owner report): HeliBoard/AnySoftKeyboard → Polished invocation smooth in current build.

## Pending
- [ ] #65 on-device smoke test (S5): dictate → switch icon → type on HeliBoard/AnySoft → return → recording auto-resumes (pause bars + pulse, appends to old PCM) → ➤ sends combined dictation. Also verify picker fallback and cancel semantics.
- [ ] #67: disk snapshot of paused dictation (deferred, labeled enhancement).
- [ ] #62: HeliBoard mic via additive bound RecognitionService (not started; needs dedicated design session).
- [ ] #64: explore parallelize/hide Ogg/Opus compression latency (measure per-stage timings on the S5 first).
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump.
- [ ] Cut v1.2.1 (versionCode bump) — now bundles #60 + #61 + #65.

## Blockers
None.

## Next Session Suggestion
On-device S5 smoke pass for #65 (then close it), then start the #62 design session or cut v1.2.1.
