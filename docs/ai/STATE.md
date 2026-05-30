# Project State

Current status as of 2026-05-30.

## Current Focus
Issue #6 — R8 release build fixes: sync Call conversion, by-lazy views, resource shrinker, blink animation, model filter, save validation, error toasts, provider URLs.

## Completed (this cycle)
- [x] #6 — Sync Call for all Retrofit suspend functions (listModels, transcribeAudio, chat)
- [x] #6 — All view fields converted from `lateinit var` to `val by lazy { findViewById(...) }`
- [x] #6 — `isShrinkResources = false` to stop R8 from stripping used views
- [x] #6 — `ValueAnimator` blink with `AccelerateDecelerateInterpolator` (CSS ease-in-out)
- [x] #6 — Custom `ArrayAdapter` filter: case-insensitive `contains()` for model dropdowns
- [x] #6 — Auto-start recording in VoiceRecognitionActivity
- [x] #6 — Stop icon (custom VectorDrawable) replacing pause icon
- [x] #6 — Classic gear settings icon (layout updated; OxygenOS override noted)
- [x] #6 — Model text fields start empty; save validates against model list
- [x] #6 — Error toasts in both Service and Activity error paths
- [x] #6 — Provider URL fixes (DeepSeek: missing /v1/; DeepInfra/HuggingFace/NVIDIA/Perplexity: missing trailing /)
- [x] #6 — Partial results removed from RecognitionService
- [x] #6 — Settings button stops active recording and cancels voice input
- [x] Knowledge files updated: DECISIONS (7 entries), PITFALLS (5 entries), CONVENTIONS (1 entry), STATE, HANDOFF

## Pending
- [ ] #1, #2, #3, #4 — close remaining open issues
- [ ] On-device release smoke test (verify STT + LLM transcription works end-to-end)

## Blockers
None

## Next Session Suggestion
Close #6 via issue-finish workflow. Continue closing #1-#4. Run on-device release smoke test.
