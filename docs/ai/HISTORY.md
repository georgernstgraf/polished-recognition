# History

Chronological archive of superseded decisions and pruned entries.
Entries here are no longer active truth. Never delete from this file.

## 2026-06-26 (SUPERSEDED 2026-06-26, origin: DOMAIN.md/CONVENTIONS.md, reason: #37 single editable prompt refactor): Translate/source-language resolved into user message
- Translation mode previously injected `{{translate_prompt}}` into the **user** message template; `{{source_language}}` was a bare name substituted into the user template.
- **Reason**: Both variables now resolve into the **system** message. The user message is an automatic, non-editable `{{text}}` carrier, and `{{source_language}}` is a sentence-or-empty clause dropped when Whisper is unsure.

## 2026-06-27 (SUPERSEDED 2026-06-27, origin: CONVENTIONS.md/DOMAIN.md, reason: #39 rename for clarity): Placeholder names source_language / translate_prompt
- The system-prompt placeholders were named `{{source_language}}` and `{{translate_prompt}}`.
- **Reason**: Renamed to `{{optional_source_language_info}}` and `{{optional_target_language_wish}}` so the optional/clause (drop-when-empty) contract is visible in the name itself. CONVENTIONS.md and DOMAIN.md were updated in place to the new names; historical DECISIONS entries retain the old names as a record.
