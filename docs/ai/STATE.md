# Project State

Current status as of 2026-05-30.

## Current Focus
Issues #9-#12 completed — model stale-text fixes, target language UX, layout reorder, voice input button + info icon. Issue #13 open (auto-resume recordings, back button behavior).

## Completed (this cycle)
- [x] #9 — Model dropdown text cleared on provider switch / model fetch
- [x] #10 — Target language: "None" option, free-text typing, disabled filter, explanation text
- [x] #11 — Settings layout reorder: Save(top) → Processing → Prompts(reordered) → LLM → STT → Save(bottom). "Set as voice input" moved above bottom save
- [x] #12 — Voice input button simplified to try-and-toast only. Info icon (i) added to recording screen with setup guide dialog
- [x] #8 — Model dropdown BaseAdapter fix
- [x] #1 through #6 — all closed previously

## Pending
- [ ] #13 — Auto-resume recordings, stop recording on info box, back button behavior decision

## Blockers
None

## Next Session Suggestion
Tackle issue #13: implement auto-resume recording (return from Settings/Info → start recording), ensure info box stops active recording, decide on system back button behavior.
