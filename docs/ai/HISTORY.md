# History

Chronological archive of superseded decisions and pruned entries.
Entries here are no longer active truth. Never delete from this file.

## 2026-06-26 (SUPERSEDED 2026-06-26, origin: DOMAIN.md/CONVENTIONS.md, reason: #37 single editable prompt refactor): Translate/source-language resolved into user message
- Translation mode previously injected `{{translate_prompt}}` into the **user** message template; `{{source_language}}` was a bare name substituted into the user template.
- **Reason**: Both variables now resolve into the **system** message. The user message is an automatic, non-editable `{{text}}` carrier, and `{{source_language}}` is a sentence-or-empty clause dropped when Whisper is unsure.

## 2026-06-27 (SUPERSEDED 2026-06-27, origin: CONVENTIONS.md/DOMAIN.md, reason: #39 rename for clarity): Placeholder names source_language / translate_prompt
- The system-prompt placeholders were named `{{source_language}}` and `{{translate_prompt}}`.
- **Reason**: Renamed to `{{optional_source_language_info}}` and `{{optional_target_language_wish}}` so the optional/clause (drop-when-empty) contract is visible in the name itself. CONVENTIONS.md and DOMAIN.md were updated in place to the new names; historical DECISIONS entries retain the old names as a record.

## 2026-06-27 (SUPERSEDED 2026-06-27, origin: CONVENTIONS.md/DOMAIN.md, reason: #39 2nd round -> *_clause): Placeholder names optional_source_language_info / optional_target_language_wish
- The system-prompt placeholders were briefly named `{{optional_source_language_info}}` and `{{optional_target_language_wish}}` (introduced earlier the same day in the first #39 round).
- **Reason**: Renamed again to `{{source_language_clause}}` / `{{target_language_clause}}` — "clause" expresses the drop-when-empty contract more intuitively and keeps the pair parallel. CONVENTIONS.md and DOMAIN.md updated in place; historical DECISIONS entries retain the older names.

## 2026-08-23 (SUPERSEDED 2026-08-30, origin: PITFALLS.md, reason: #49 — hallucination guardrail now in default prompt, trailing-only scope; Amara claim wrong): Whisper hallucination guardrail advisory
- Whisper models (especially v3) can hallucinate text like "Thank you." or "Subtitles by Amara" during silent periods. Add explicit LLM instruction guardrails in the system or user prompt to filter these out.
- **Origin**: PITFALLS.md
- **Reason**: Superseded by #49 — the guardrail now lives in the default system prompt, scoped to trailing hallucinations (strip trailing part, empty string only if whole transcription is one). "Subtitles by Amara" never appears on Whisper per user observation and was dropped from the examples.

## 2026-08-31 (SUPERSEDED 2026-08-31, origin: DECISIONS, reason: #57 round 2 — user found the fixed pulse "too frantic/superficial"): IME flash as fixed-cycle ValueAnimator pulse
- Flash feedback: while RECORDING, a repeating `ValueAnimator` on the root view's alpha (1.0 ↔ 0.7, 500 ms/cycle, REVERSE, INFINITE) pulses the entire bar between full contrast and slight gray; cancelled in every other state (root alpha reset to 1f).
- **Origin**: docs/ai/DECISIONS.md (entry "2026-08-31: Two-line IME with flash pulse + inline stage display (#57)")
- **Reason**: Replaced by a voice-reactive RMS-driven pulse (alpha floor 0.5, slow dive 1000 ms / quick rise 150 ms) — see the round-2 decision.
