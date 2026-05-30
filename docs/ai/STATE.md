# Project State

Current status as of 2026-05-30.

## Current Focus
Issue #13 in progress — pause/resume recording, three-button layout, noHistory fix applied. Remaining: on-device validation of full pause → change settings → resume → stop flow.

## Completed (this cycle)
- [x] #9 — Model dropdown text cleared on provider switch / model fetch
- [x] #10 — Target language: "None" option, free-text typing, disabled filter, explanation text
- [x] #11 — Settings layout reorder
- [x] #12 — Voice input button try-and-toast, info dialog on recording screen
- [x] #13 — Pause/resume recording (AudioRecorder), three-button layout, noHistory removed, system back cancels

## Pending
- [ ] #13 — On-device test: pause → change settings → resume → stop → verify transcription uses new settings
- [ ] #13 — Verify cumulative timer across multiple pause/resume cycles

## Blockers
None

## Next Session Suggestion
Complete #13 on-device validation, then finish issue. Consider edge case: what happens if system kills activity during Settings navigation (low memory)?
