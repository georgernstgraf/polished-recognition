# Project State

Current status as of 2026-05-30.

## Current Focus
Completed #7 — provider settings redesign. Remaining: close #1-#4, on-device smoke test.

## Completed (this cycle)
- [x] #7 — Added editable API URL fields for STT and LLM providers in settings
- [x] #7 — Expanded LLM presets from 5 to 15 (DeepSeek, xAI, Mistral, Together AI, DeepInfra, Fireworks, Cerebras, Perplexity, HuggingFace, NVIDIA)
- [x] #7 — Split LLM Validate & Fetch into [Fetch Models] + [Test Token] (catches OpenRouter unauthenticated model listing)
- [x] #7 — Searchable model dropdowns (completionThreshold=1, filters OpenRouter 600+ models)
- [x] #7 — Added optional maxTokens to ChatRequest DTO for token test call
- [x] #7 — Removed getCustomBaseUrl() — URL field is source of truth
- [x] #6 — All R8 fixes, test warning fixes

## Pending
- [ ] #1 — close initial scaffolding issue
- [ ] #2 — verify blink animation, close
- [ ] #3 — close AGENTS.md/README config issue
- [ ] #4 — GitHub CI workflow
- [ ] On-device release smoke test

## Blockers
None

## Next Session Suggestion
Close #1-#4 via issue-finish workflow. On-device release smoke test.
