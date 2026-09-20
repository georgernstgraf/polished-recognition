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

## 2026-08-30 (SUPERSEDED 2026-09-07, origin: DECISIONS.md, reason: #72 — gear now opens real Settings via keyboard switch; the notification contentIntent keeps the hint): IME gear + notification open a hint dialog, not Settings (#51)
- **Choice**: New `SettingsHintActivity` (plain `Activity`, `AlertDialog`, `Theme.PolishedRecognition.Plain.Transparent`, `noHistory`, `excludeFromRecents`, `exported=false`). IME gear (`PolishedVoiceInputIME`) and the mic-notification `contentIntent` launch it instead of `SettingsActivity`. Message: "Polished is your active keyboard, so Settings can't be edited here. Switch back to your text keyboard and open Polished Recognition from the app launcher."
- **Reason**: While Polished is the selected keyboard, every EditText in Settings summons the Polished voice bar → circular dependency (can't type). Launcher activity IS SettingsActivity, so the launcher path works after switching keyboards. User chose keep-gear+hint over removal (discoverability).
- **Considered**: Removing the gear entirely (user's fallback preference — rejected to keep an in-IME hint that settings exist); dialog straight from the `InputMethodService` context (theme/window-type risk — PITFALLS `?attr/` class); Toast instead of dialog (less discoverable, but zero-risk fallback if the activity shows issues).
- **Tradeoff**: One more activity; the gear tap no longer reaches Settings directly mid-session (quick settings language/raw spinner still work on the bar).
- **Origin**: DECISIONS.md
- **Reason**: #65's verified keyboard-switch mechanism removed the "can't type" blocker: the gear now switches to the text keyboard first (`startActivity` before `switchInputMethod`), then opens SettingsActivity (#72). The notification `contentIntent` still launches the hint.

## 2026-09-11 (SUPERSEDED 2026-09-14, origin: DECISIONS.md, reason: #74/#78 — listing target moved v1.2.3 → v1.3.0): v1.2.2 ships without listing assets; listing release becomes v1.2.3 (#76)
- **Choice**: Tagged v1.2.2 (versionCode 10202) purely as a release-mechanics effort (standalone issue #76) — bump, tag, Play alpha upload, F-Droid auto-update. The fastlane listing work (images, full_description rewrite, README badges) moves to a future **v1.2.3** tag tracked in #74.
- **Reason**: F-Droid reads fastlane metadata from the built tag, so listing changes must ride on a fresh version. Re-tagging v1.2.2 after the first build would force-push a tag — not acceptable. Owner decision: publish current code immediately (Play alpha + F-Droid availability), polish the listing separately.
- **Considered**: Blocking 1.2.2 until the listing assets are ready; re-tagging v1.2.2 (rejected — force-tag).
- **Tradeoff**: F-Droid will list 1.2.2 with the v1.2.1-era listing (no screenshots/icon in fastlane) until 1.2.3 lands.
- **Origin**: DECISIONS.md
- **Reason**: The listing release target was retargeted to v1.3.0 (minor bump) on 2026-09-14; v1.2.3 became a pure #77 patch.

## 2026-09-18 (SUPERSEDED 2026-09-18, origin: DECISIONS.md, reason: #83 follow-up — Oplus delivers the rebind ~150 ms BEFORE onConfigurationChanged, so the flag/mark never exists at decision time; cancellation now keys off field identity via ImeStartDecision): Rotation-safe voice session — freeze on rotation, cleanup only on field change (#83)
- **Choice**: `onConfigurationChanged` flag in `PolishedVoiceInputIME` distinguishes rotation restart (`restarting == true` + flag, same field) from keyboard-switch return (restarting, no flag → #65 auto-resume) and genuine field change (`restarting == false` → cancel live session as before). On rotation the session is frozen in state: RECORDING keeps capturing (no pause in `onFinishInputView`, AudioRecord thread is view-independent), PAUSED stays paused; `onDestroy` parks RECORDING as PAUSED + detaches (never cancels a live session). PROCESSING results that complete unbound are stashed in `VoiceSessionController.pendingResult` and delivered on the next `attach()`; `cancel()` clears the stash.
- **Reason**: Owner-reported data loss — rotation discarded the PCM buffer (`cancel()` → `bufferStream.reset()`) and could silently drop a finished transcription (`Completed` into a null callback).
- **Tradeoff**: On devices that destroy the IME service on rotation, RECORDING still has a minimal gap (park + auto-resume) — lossless, but not literally gapless; true zero-gap holds where the service survives (the normal case).
- **Origin**: DECISIONS.md
- **Reason**: The instance flag dies with service recreation and the app-scoped timestamp gate is defeated by Oplus's inverted event order (rebind first, mark ~150 ms later — proven by ime-lifecycle.log, which also showed the cancel-then-fresh-`00:00`-autostart sequence). Superseded by the #83 follow-up entry (ImeStartDecision + FGS retention + snapshot + trace).

## 2026-09-18 (SUPERSEDED 2026-09-18, origin: DECISIONS.md, reason: #83 follow-up — the reopen condition fired (process-death loss observed on Oplus) and the proposal was implemented as VoiceSessionController.snapshot()/restore()): #67 closed as maybe-later (no evidence of process-death loss)
- **Choice**: Closed #67 (disk snapshot of paused dictation) not-planned per owner — deferred since 2026-09-07 with still no feedback whether process-death PCM loss is a real problem in practice. The proposal (`AudioRecorder.snapshotPcm()/restorePcm()` + `cacheDir/session.pcm` flush on pause, restore to PAUSED on init) stays documented in the issue — reopen on demand if dictation loss is ever observed.
- **Reason**: An in-memory-only paused session (#65) has produced no loss reports; building crash-recovery I/O on the keyboard-show path is unjustified without evidence.
- **Tradeoff**: A process kill while paused still loses the dictation — accepted until observed.
- **Origin**: DECISIONS.md
- **Reason**: Owner proved process death on-device (PAUSED + rotate → timer `00:00` + pre-rotation audio gone, commit aa94106 implements the snapshot exactly as proposed; #67 itself stays closed).

## 2026-09-19 (SUPERSEDED 2026-09-19, origin: PITFALLS.md, reason: #87 — the entire mic-coupled RMS pulse chain was deleted; no RMS consumer remains): Gate ambient noise before normalizing mic loudness
- **A live microphone never reads RMS 0 — gate ambient noise before normalizing loudness.** `AudioRecord` on a quiet room mic continuously reports raw RMS ~50–200; a level mapping normalized from zero (e.g. `ln(1+rms)/ln(1+ceiling)`) therefore parks the signal near its top — the IME pulse sat at alpha ~0.8–0.95 for the whole recording and the intended 0.5 "deep gray" floor was mathematically unreachable. Fix: subtract a noise gate (`NOISE_FLOOR`, aligned with the recorder's speech-begin threshold of 200) before any normalization, and verify the mapping with realistic values (ambient ~100 → floor, speech ~400–2000 → clearly above floor). (#57, found on device.)
- **Origin**: PITFALLS.md
- **Reason**: #87 replaced the volume-driven pulse with a volume-independent sine blink and deleted `RmsAlphaMapper`, `Event.RmsChanged`/`SpeechBegin`, `AudioRecorderListener`, and `computePcmRms` entirely.

## 2026-09-19 (SUPERSEDED 2026-09-19, origin: PITFALLS.md, reason: #87 — `computePcmRms` deleted with the RMS chain; no PCM-squaring code remains): Sign-correct 16-bit PCM samples before squaring
- **16-bit PCM samples MUST be sign-corrected before squaring** — assembling little-endian samples unsigned (`(hi & 0xFF) shl 8) or (lo & 0xFF)` → 0..65535) makes squares of values > 46340 overflow `Int` to negative, driving the sum negative → `sqrt(negative) = NaN`. `AudioRecorder.computeRms` shipped this bug from day one; RMS was silently NaN for any speech with samples < −8192 and nobody noticed until #57 consumed the values (frozen IME pulse). Fix: `if (sample >= 32768) sample - 65536` + accumulate in `Long` (`signed.toLong() * signed`). Guard: skip non-finite RMS at the consumer. Regression tests in `AudioRecorderTest` (deep negative samples = exact trigger). (#57, found on device via `PolishedRMS` logcat.)
- **Origin**: PITFALLS.md
- **Reason**: Same as above — re-read both entries before re-adding any audio-coupled UI.

## 2026-09-20 (SUPERSEDED 2026-09-20, origin: PITFALLS.md, reason: #82 `18bd2c3` — build.yml now decodes the release keystore, unsigned era over)
- Release builds sign with `signingConfigs.release` which reads `app/release.keystore` (local copy of `~/.android/debug.keystore`). CI decodes `RELEASE_KEYSTORE` secret (same keystore) into `app/release.keystore`. Both use the same key — no signature mismatch.
- `installRelease` disappears as a Gradle task when the `release` build type has no `signingConfig` set. Restore it with a **conditional** signingConfig (see below) or pass `-Pandroid.injected.signing.*` properties per-invocation.
- A conditional `signingConfig` is required to satisfy F-Droid/build CI AND local `installRelease` simultaneously. Define it in `app/build.gradle.kts` guarded by `if (file("release.keystore").exists())`, both when creating `signingConfigs.release` and when assigning `release.signingConfig`. The keystore is gitignored, so it exists only on the dev machine (→ signed → `installRelease` works, APK named `app-release.apk`) and is absent in build.yml, release.yml, and F-Droid's build env (→ unsigned → `app-release-unsigned.apk`, matching `build.yml`'s `fail_on_unmatched_files` path and `fdroid/*.yml`'s `output:`). An **unconditional** `signingConfigs` block breaks F-Droid because the referenced keystore file is missing in their container. release.yml is unaffected because it signs the AAB via `-Pandroid.injected.signing.*`, which is orthogonal to build.gradle.
Origin file: docs/ai/PITFALLS.md. Reason superseded: debug-keystore era long over (RSA-2048 release key since 2026-06-28); build.yml publishes signed APKs since #82, so the "absent in build.yml → unsigned" half is false. Replaced by the updated conditional-signing entry + release-key identity entry.
