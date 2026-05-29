# Project State

Current status as of 2026-05-30.

## Current Focus
Fix R8/ProGuard ClassCastException in release builds (#6).

## Completed (this cycle)
- [x] #6 — Added `listModelsSync` (non-suspend, `Call<ModelsResponse>`) to `OpenAiSttApiService` and `OpenAiChatApiService`
- [x] #6 — Updated `fetchSttModels`/`fetchLlmModels` in `SettingsActivity` to use `Call.execute()` and `PolishedRecognitionApp`'s cached Retrofit instances
- [x] #6 — Removed unused imports (`Retrofit`, `GsonConverterFactory`) from SettingsActivity
- [x] #6 — All tests pass (debug + release), pushed to master (7f3758a)

## Pending
- [ ] #1 — close initial scaffolding issue
- [ ] #2 — verify blink animation, close
- [ ] #3 — close AGENTS.md/README config issue
- [ ] #4 — GitHub CI workflow
- [ ] #6 — close after release build verification
- [ ] On-device release smoke test (verify R8 fix)

## Blockers
None

## Next Session Suggestion
Verify #6 fix on release APK, close #6. Continue closing #1-#4. Run on-device smoke test.
