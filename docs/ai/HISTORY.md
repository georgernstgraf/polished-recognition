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

## 2026-08-31 (SUPERSEDED 2026-08-31, origin: PITFALLS.md, reason: #48/#53 on-device verification — claim empirically false for AutoCompleteTextView popups on Oplus, fixed ac75231/#59): Clickable non-focusable dropdown children "work"
- Original claim: "Dropdown rows MAY contain a clickable but non-focusable child (`focusable="false"` + `focusableInTouchMode="false"`): `AbsListView` only blocks `onItemClick` for rows with `hasFocusable()` descendants, so a clickable non-focusable `ImageButton` works — the icon consumes its own tap, label taps still select the row."
- **Origin**: PITFALLS.md
- **Reason**: On-device verification showed label taps on custom rows (visible trash ImageButton) were swallowed — no selection, dropdown never closed; built-in rows without the visible icon selected fine. Fix: explicit label click listener committing selection + dismiss; the ListView internal item-click path is not relied upon.

## 2026-05-29 (SUPERSEDED 2026-08-31, origin: DECISIONS.md, reason: upload-format part refined by optional Ogg/Opus compression, #60): AudioRecord + WAV in-memory
- **Choice**: `AudioRecord` capturing PCM 16-bit 16kHz mono, with manual 44-byte WAV header
- **Reason**: RecognitionService needs raw audio for the STT API. `MediaRecorder` produces compressed formats (AAC) requiring conversion. In-memory WAV is ~50 lines of header byte manipulation. No temp file I/O during recording.
- **Considered**: MediaRecorder + temp file + FFmpeg conversion
- **Tradeoff**: WAV is uncompressed — larger than AAC for long recordings. Fine for short voice input (typically <30s).
- **Origin**: DECISIONS.md
- **Reason**: #60 added an opt-in Ogg/Opus transcode step before upload (platform MediaCodec/MediaMuxer, not FFmpeg). AudioRecord PCM capture itself remains in force; a one-line pointer stays in DECISIONS.md.

## 2026-08-30 (SUPERSEDED 2026-09-07, origin: DECISIONS.md, reason: #65 — owner now wants PAUSED sessions auto-resumed on keyboard re-entry): Auto-start mic recording when the keyboard appears (#54)
- **Choice**: `onStartInputView` calls `startIfPermitted()` when `AutoStartPolicy.shouldAutoStart(state, hasMicPermission())` — i.e. state IDLE **and** RECORD_AUDIO granted. Always-on, no setting.
- **Reason**: User request: mic should be active and recording start automatically when Polished launches as a keyboard.
- **Considered**: Settings toggle (owner decided against); gating on `restarting == false` (rejected — both fresh appearance and in-place restart mean "keyboard visible, user can talk"; the pre-existing cancel-on-restart for RECORDING/PROCESSING runs first, so a field switch cancels then re-enters RECORDING consistently); auto-resuming PAUSED sessions (rejected — after keyboard hide, `onFinishInputView` leaves PAUSED deliberately; user taps mic to resume).
- **Tradeoff**: Without mic permission the normal idle UI stays (no `MicrophonePermissionActivity` spam on every keyboard show — user taps mic to grant+start as before). Hook is `onStartInputView` not `onStartInput` (the latter fires while the input view is hidden). 5 unit tests in `AutoStartPolicyTest`.
- **Origin**: docs/ai/DECISIONS.md
- **Reason**: The auto-start-on-IDLE policy remains in force, but the explicit rejection of auto-resuming PAUSED sessions was overturned by #65 (session preservation across keyboard switches made PAUSED-on-re-entry the normal continuation signal; owner chose "immer bei PAUSED" — see the 2026-09-07 decision).

## 2026-09-07 (SUPERSEDED 2026-09-07, origin: DECISIONS.md, reason: #65 smoke test — switchToPreviousInputMethod self-target trap made the picker fallback dead code; redesigned same day): Keyboard-switch button in the IME bar (#65, round 1)
- **Choice**: New keyboard icon (`ic_keyboard`, tinted in the vector like `ic_settings`) in IME row 1, left of the settings gear. Tap: pause if RECORDING → `InputMethodService.switchToPreviousInputMethod()` → on `false` fall back to `InputMethodManager.showInputMethodPicker()`. Manifest gains a `<queries>` block for `android.view.InputMethod` (package visibility for other IME packages on API 30+). Button enabled in IDLE/RECORDING/PAUSED, disabled in PROCESSING (mirrors the gear).
- **Reason**: The Galaxy S5 test device has no nav-bar keyboard chooser — switching back to a typing keyboard after dictation is a Settings detour, but post-dictation text almost always needs manual correction on a real keyboard. `switchToPreviousInputMethod()` jumps directly back to the keyboard the user came FROM (HeliBoard/AnySoftKeyboard → Polished → one tap back) — the exact inverse of the inbound path that already works smoothly (owner-confirmed 2026-09-07).
- **Considered**: Always `showInputMethodPicker()` (owner rejected — 2 taps; the previous keyboard is the target in the common case); `switchInputMethod(rememberedId)` with a ContentObserver on `Settings.Secure.DEFAULT_INPUT_METHOD` tracking the last non-Polished IME (rejected — process-lifetime-dependent observation, more moving parts for the same outcome).
- **Tradeoff**: "Previous IME" semantics are system-controlled (usage history); on a fresh enable with no IME history the button opens the picker instead of switching directly (acceptable degradation). Build + 143 unit tests green; on-device S5 smoke test pending at commit time.
- **Origin**: DECISIONS.md
- **Reason**: On-device smoke test showed `switchToPreviousInputMethod()` resolves "last" from `input_methods_subtype_history` whose head reverts to Polished after every settings touch → self-targeted no-op switch returns `true` → picker line unreachable; and the picker itself is useless with only Polished enabled. Same-day redesign: resolve the target from history ourselves, switch via `switchInputMethod(id)`, fall back to `ACTION_INPUT_METHOD_SETTINGS`. See the 2026-09-07 redesign entry in DECISIONS.md.
