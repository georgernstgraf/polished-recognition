---
type: History Archive
title: History
description: Append-only archive of superseded decisions and pruned entries.
tags: [knowledge, history, audit]
generated: { by: opencode/knowledge-persistence, at: 2026-11-28T10:15:00Z }
status: stable
---

# History

Chronological archive of superseded decisions and pruned entries.
Entries here are no longer active truth. Never delete from this file.

## 2026-10-14 (SUPERSEDED 2026-10-15, origin: DECISIONS.md, reason: signing facts were verified on claw only and are wrong for think; the "exclusively CI" deployment rule was an agent overgeneralization of a one-time owner availability statement — corrected by the 2026-10-15 #147 entry): #139 — device deployments run exclusively through CI release downloads (keyless local hosts by design) + harness CI workflow

- **Signing facts (verified):** claw AND think hold NO signing keys — local `keystore.properties` has only `keyAlias=upload`, no keystore file, and the pass-wallet GPG secret is not in the local keyring, so local builds are UNSIGNED. Signing happens ONLY on GitHub (`RELEASE_KEYSTORE` + `RELEASE_*` secrets, consumed by build.yml). Owner decisions of 2026-10-14 are binding: no local signing repair; deployments exclusively via CI release downloads.
- **New `.github/workflows/harness.yml`** (mirror of build.yml): triggers `workflow_dispatch` + push on main; keystore decode from the SAME release secrets → `./gradlew assembleHarness` (no test run — the suite already runs in build.yml; the harness buildType inherits `initWith(release)` + release signing → signed in-place-installable APK); artifact `app-harness` (7 d) + GitHub release tag `harness-${{ github.run_number }}` (`make_latest: false`), then the cleanup script. Verified: first run green, `harness-1` published, APK cert `62f9d7b0…a76a85` == release cert.
- **Round-robin retention generalized:** `scripts/cleanup-github-assets.sh` steps 2+3 refactored into a symmetric `build`/`harness` prefix loop — KEEP=7 PER prefix for releases (page + tag) and orphan tags; the generic artifact pruning already covered `app-harness`; `v*` untouched. `run_number` is per-workflow-counted → `build-`/`harness-` never collide.
- **Deployment separation (owner):** the OnePlus (daily driver, harness) is refreshed ONLY with the CI harness APK — installing a release APK over it would wipe harness knobs, 99/1-GB retention and shadow STT (endangering #129 evidence); the A03 stays RELEASE-only (owner wants no harness there) and gets the signed release APK (build-448, carries #138).
- **Rationale:** #138's fix rides on fresh installs; with signed CI harness builds the "fresh APK install" prerequisite is now one download away on any host, without any local key ceremony.
- Origin: DECISIONS.md. Reasons: (1) #147 (2026-10-15) removed the harness build type and workflow entirely — the harness CI parts are dead; (2) the signing facts held for claw only — on think the full keystore material exists in the working tree and local release builds sign identically to CI (proven by the successful in-place `install -r` of the locally built 1.5.0 over the CI-signed harness build); (3) the "exclusively via CI release downloads" rule was an agent-invented generalization of the owner's one-time statement "I have currently *only* github actions available to install an apk".

## 2026-10-12 (SUPERSEDED 2026-10-12, origin: PITFALLS.md, reason: #131 fixed the flake): Unidentified unit-test flake, three occurrences (exit 2 mid-suite, 0 recorded failures)
- **Original entry (pruned from PITFALLS.md):** "A rare, unidentified unit-test flake (2026-10-11). One pre-push-hook run of `./gradlew test` failed ('BUILD FAILED in 34s') with no failing test captured; 3 forced full re-runs green, 444/444. RECURRENCE 2026-10-11 (during #127 verification, captured signature): `Gradle Test Executor 1` died with exit value 2 at 312/462 tests — every completed class's XML shows 0 failures/0 errors, the executor died just before `VoiceSessionControllerTest` (the same region as the #86 heap crash). A single re-run was green (462/462). THIRD occurrence 2026-10-12 (during the #126 push): same shape at 318/465, 0 recorded failures, single re-run green. The suite has ~1.9×'d since #86's 244-test/512m fix while the test heap stayed at 1536m — OOM-at-exhaustion hypothesis leading; candidate fix (not yet applied, owner decision): raise the test-only maxHeapSize."
- **Reason superseded:** owner approved the fix; #131 (`717c194`) raised the test-only `maxHeapSize` to 2048m, verified with a forced `--rerun-tasks` full run; no recurrence since (the #128 push and the #129 research run both clean). PITFALLS.md retains only the one-line active watch entry.

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

## 2026-10-08 (SUPERSEDED 2026-10-08, origin: STATE.md/HANDOFF.md, reason: #64 closed by measurement, successor #116): #64 repurposed as "parallelize chunk uploads"
- The 2026-10-08 daytime repurpose of #64 planned parallel chunk uploads (bounded concurrency 2–3, per-chunk failure aggregation, configurable concurrency) to hide a ">2 min per ~600-s chunk" CPU-only LAN Whisper server.
- **Reason**: The same day's host-side bench disproved both premises — gregor runs `whisper-server:cuda` (`large-v3`, RTX 2070 SUPER) at x ≈ 22–29× realtime (600 s = 26.9 s) and strictly serializes requests (wall time constant over C=1..4; 2×600 s parallel = 53.6 s vs 26.9 s sequential; GPU avg 75–81 % independent of C), so parallel uploads are useless-to-harmful; GROQ is already ~200× realtime. Full evidence in the #64 closing comment. The latency goal moved to #116 (live fragment transcription with self-measured per-provider profiles). Decision details: DECISIONS.md 2026-10-08; constraints: PITFALLS.md ("never parallel-upload against gregor", "STT per-request floor").

## 2026-10-10 (SUPERSEDED 2026-10-10, origin: PITFALLS.md, reason: root-caused and fixed in `f152cd9` during #116 Phase 2 — snapshot-before-publish ordering + event-based test waiting): VoiceSessionControllerTest load-sensitive flake (watch item)
- **`VoiceSessionControllerTest` has a load-sensitive flake (watch item, #117 — recurred 2026-10-09 later, instrumentation now DUE):** `pipeline failure parks PAUSED…` / `retry after failure…` / `fragment failure…` intermittently fail ("session.meta exists" false / missing or wrong `Completed` order). Originally only in heavily loaded full-suite runs; in the #117 round-2 session it also fired in a CLASS-LEVEL run (`pipeline failure parks PAUSED…`, the compress-OFF legacy path — untouched by fragment changes), so the old "passes in class-level" heuristic no longer holds. Both re-runs green. Instrument the failure branch (log the snapshot()/event-path failure cause) before the next debug round instead of re-running.
- **Origin**: PITFALLS.md
- **Reason**: Root-caused 2026-10-10 while implementing #116 Phase 2 — TWO mechanisms: (1) the failure branch published `state = PAUSED` BEFORE writing the snapshot (file-race; also a real on-device data-loss window), fixed by `snapshot(force = true)` before the state publish; (2) the failure branch posts `Completed` after the PAUSED publish, so state-field-polling tests raced the post (missing-Completed shape) — fixed by the `awaitEvents` harness helper. The watch item recurred twice more during the Phase 2 session (different failing tests each time, ~1-in-3 runs); after the fix: 8/8 consecutive green runs. Replaced by the two permanent pitfalls (state-after-evidence ordering; wait on events, not state fields).

## 2026-10-10 (SUPERSEDED 2026-10-10, origin: CONVENTIONS.md/DECISIONS.md, reason: #122 study measured that the prompt window never binds at production fragment sizes): "the Whisper prompt is ~200 tokens / PROMPT_MAX_CHARS = 800"
- **Superseded convention:** "Every fragment is transcribed with the previous fragment's transcript tail as the Whisper `prompt` (~200 tokens, `PROMPT_MAX_CHARS`)" — implying 800 chars ≈ 30–60 s of prior context.
- **Origin**: CONVENTIONS.md (and the round-1 DECISIONS entry).
- **Reason**: the prompt is ONLY the previous fragment's transcript, whose length is bounded by one fragment (~90 chars at 10 s, ~190 at 21 s, ~550 at 60 s). `takeLast(800)` therefore never truncated anything; the 96-char seam-local default (#122) only begins to bind above ~10 s fragments, and prompt-length was a NULL lever in the 126-run study (0/96/320/800 identical). Replaced by the corrected convention line in CONVENTIONS.md.


## 2026-10-14 (SUPERSEDED 2026-10-14, origin: PITFALLS, reason: #143 removed the shadow entirely — the full-context reference now lives offline in #144, where the same divergence hazard applies and the 5-min/loop-mask rules govern):

- **The #117 shadow full-context pass is NOT ground truth on long recordings — it diverges in BOTH directions (#116 Phase 2 data, 2026-10-10).** The 2026-10-10 movie session showed it COLLAPSING (fullChars 1021 vs fragment concat 2310, VAD-crushed); the 2026-10-10 WAV verification showed it EXPANDING (`fullChars` 26 534 vs `fragmentChars` 11 531 on a ~14-min recording — 2.3×, ≈ 410 wpm, i.e. hallucination/looping). Read the shadow as a diagnostic breadcrumb only; never derive seam-loss or "correctness" from it on long audio. The reliable signals are `stt-latency.durationMs` (real vs the old constant 1000) and `insertion.length` == `stt-text.textLength`. [ACTIVE until #116]


## 2026-10-14 (SUPERSEDED 2026-10-14, origin: STATE, reason: superseded by the v1.5.0 release, #145 — kept as the release record):

## v1.4.1 release — what shipped (2026-10-14, #140)

- **Version:** 1.4.1 / 10401 (patch per owner — v1.4.0 carried the #133 regression). Bump commit `805c732` (version + patch whatsnew, 418 chars).
- **Scope (user-facing):** #138 only — the post-send teardown no longer salvages the delivered text. #139 harness CI + docs are also in the tag but not user-facing.
- **CI:** release.yml `38603907903` green — Play step conclusion `success` AND log `Successfully committed 16065703470121614937` (alpha edit id); fdroid-apk.yml `38603907912` green. GitHub release `v1.4.1`: `app-release.aab` (1,885,478 B) + `polished-recognition.apk` (2,402,489 B).
- **Ship notices:** #138 (fix shipped; on-device verify still pending, rides on the owner's installs) + #139 (no impact — harness buildType is never shipped).
- **Watch:** F-Droid pickup of 1.4.1 (`AutoUpdateMode: Version`, ~3–5 days) — superseded by this release, but the 1.4.0 pickup watch becomes moot once fdroidbot sees 1.4.1.
- Origin: STATE.md. Reason: superseded by the v1.5.0 release (#145); details preserved above.

## 2026-10-14 (SUPERSEDED 2026-10-15, origin: PITFALLS, reason: the claim was corrected 2026-10-15 — "no signing keys" was verified on claw ONLY; think holds the in-tree release keystore (`app/release.keystore` + `keystore.properties`), and key equality with CI was PROVEN by the successful in-place update over the CI-signed harness-1 build during #147's device return. The "deployments exclusively via CI" rule was an agent overgeneralization of a one-time owner availability statement. Binding host rules now live in CONVENTIONS + HANDOFF): Local hosts have NO signing keys — by design (owner decision 2026-10-14, #139)
- **Original entry:** Local `keystore.properties` carries only `keyAlias=upload`, the keystore file is absent, and the pass-wallet GPG secret is not in the keyring → every locally-built APK is UNSIGNED and CANNOT be installed in-place over a signed one. Device deployments must download CI-built signed APKs (`gh release download build-N`, release cert `62f9d7b0…a76a85`). Do NOT attempt local signing repair.
- Origin: PITFALLS.md. Reason: superseded by the 2026-10-15 host-rule correction (DECISIONS 2026-10-15; proven on think during #147).

## 2026-11-20 (SUPERSEDED 2026-11-20, origin: DECISIONS.md, reason: #178): #173 decision 1 — focus in Polished ⇒ POLISHED_EDIT (three dictation targets)

- **Original entry (pruned from DECISIONS.md, #173):** "The visible page decides the target, never a text comparison. Compose slot ⇒ NEW · stored dictation + focus in Raw ⇒ RAW_EDIT (at the caret, whether or not the user typed first) · focus in Polished ⇒ POLISHED_EDIT. The old rule compared the field text with the text shown at render time, so a caret move alone meant "new" and dictating into an old Raw silently created another entry. Consequence: on a stored page a dictation never creates an entry; the compose slot and ＋ are the only creators."
- Origin: DECISIONS.md (the 2026-11-19 #173 entry). Reason: #178 turned `workbench_polished` into a read-only `TextView` (`focusable=false`), so it can no longer hold focus — `DictationTarget` is `NEW`/`RAW_EDIT` only. The page-decided rule itself (no text comparison, compose slot and ＋ create) is unchanged.

## 2026-11-20 (SUPERSEDED 2026-11-20, origin: DECISIONS.md, reason: #178): #173 decision 8 — content-sized accordions with chevron and one-line preview

- **Original entry (pruned from DECISIONS.md, #173):** "The fields are content-sized. `wrap_content` + `minLines=2`, no inner scrollbar, no cap — only the outer ScrollView scrolls. Each label row is a `<summary>`-style accordion (chevron + label + copy button) with a one-line ellipsised preview while collapsed; both sections start open, are expanded by focus, and the state is not persisted. `WorkbenchLayoutPolicyTest` pins the geometry statically."
- Origin: DECISIONS.md (the 2026-11-19 #173 entry). Reason: #178 removed the accordion machinery — both fields are permanently visible, the label row carries only the copy icon, and the actions live in three zones around them. The `wrap_content` + `minLines=2` + "no inner scrollbar" geometry survives.

## 2026-11-20 (SUPERSEDED 2026-11-20, origin: DECISIONS.md, reason: #178): #176 — "Re-polish is the only polish site"

- **Original statement (pruned from DECISIONS.md, #176):** "The single polish site is `TranscriptionPipeline.polish`, driven by Re-polish with the per-dictation language (never written back to `settings.targetLanguage`)."
- Origin: DECISIONS.md (the 2026-11-20 #176 entry). Reason: #178 gives the Workbench two polish buttons — `workbench_polish_a` above Raw and `workbench_polish_b` below Polished — that share one per-visit `polishLanguage`. "Only when asked" is still the rule and `TranscriptionPipeline.polish` is still the only polish implementation; counting the UI trigger as one site is what became false.

## 2026-06-03 (SUPERSEDED 2026-06-03, origin: decisions/build-release.md, reason: the CI-only rule was replaced the same day by RELEASE_KEYSTORE-based signing): CI-only signing (no Gradle signing config)

- **Choice**: Remove `signingConfigs { release { ... } }` and `signingConfig` from `build.gradle.kts`. All signing done via `-Pandroid.injected.signing.*` in CI workflows.
- **Reason**: `build.yml` builds APK signed with `~/.android/debug.keystore` for GitHub distribution. `release.yml` builds AAB signed with upload keystore from secrets for Play Store. Keeping signing config in Gradle caused mismatch — `upload.keystore` didn't exist on CI runner. Matches zazentimer's approach.
- **Considered**: Environment-variable-based fallback in build.gradle.kts (complex, fragile), keeping debug keystore in Gradle (`signingConfigs.getByName("debug")`)
- **Tradeoff**: Local `./gradlew assembleRelease` produces unsigned APK. Signed APK/AAB only from CI. Local testing should use `assembleDebug` (unsigned, `.debug` suffix).

- **Origin**: decisions/build-release.md (entry "2026-06-03: CI-only signing (no Gradle signing config) [SUPERSEDED]")
- **Reason**: the CI-only rule was replaced the same day by RELEASE_KEYSTORE-based signing

## 2026-06-09 (SUPERSEDED 2026-06-16, origin: decisions/build-release.md, reason: #30 replaced the removal with a file-existence-guarded signingConfig, restoring local installRelease without disturbing F-Droid/CI): Remove signingConfigs.release for F-Droid

- **Choice**: Remove the entire `signingConfigs { release { ... } }` block and `signingConfig` from `release` build type in `app/build.gradle.kts`
- **Reason**: F-Droid's `fdroid build` could not find the signed APK output — `assembleRelease` produced no APK when a signing config with a keystore was active. Removing the signing config lets the build produce `app-release-unsigned.apk` which F-Droid finds and signs itself.
- **Considered**: Keeping signing config with `output:` directive (wrong path, APK naming mismatch), creating dummy keystore via `prebuild` (worked locally but F-Droid's Gradle setup changed output behavior)
- **Tradeoff**: Local `./gradlew assembleRelease` now produces unsigned APK. GitHub CI workflows (`build.yml`, `release.yml`) inject signing via `-Pandroid.injected.signing.*` properties, so releases remain signed.

- **Origin**: decisions/build-release.md (entry "2026-06-09: Remove signingConfigs.release for F-Droid [SUPERSEDED]")
- **Reason**: #30 replaced the removal with a file-existence-guarded signingConfig, restoring local installRelease without disturbing F-Droid/CI

## 2026-11-26 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #213 CLOSED — owner verdict passed on the device; the wave history lives in state/workbench.md + state/ime-field.md): #213 — recording-red FONT, polished-zone summary actions, app-wide AppButton, borderless bigger Workbench icons

- Original entry (condensed): closed 2026-11-26 after the owner's verdict; final wave `49f78b1`. Red belongs to the FONT, never the background (IME), the Polished summary row carries chevron · Polished · trash · promote · copy with only a bottom copy under the read-only field, every text button wears `@style/AppButton`, and Workbench icon buttons are borderless, 48dp, 1.5× with tooltips.

## 2026-11-26 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #212 CLOSED — owner verdict passed on the device; full detail in state/settings.md): #212 — Settings becomes an overview with four category screens

- Original entry (condensed): seven commits (`313d1c1` overview + screens + draft/apply, `3c24afc` H1 title + LLM Control heading, `5f63b29` 13 dividers, `81992d2` About swap, `96cc343` contrast exchange — dividers lead in `text_secondary`, `colorControlNormal` → `control_quiet` ~20 %). 805 `:app` tests + lint 0 throughout. Owner: „passst". Known+accepted: the quiet control tint is app-wide. **#156 impact: the whole settings surface needed recapture.**

## 2026-11-26 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #211 CLOSED — owner verdict passed on the device; detail in state/ime-field.md): #211 — the disabled LLM checkbox shows the effective state

- Original entry (condensed): owner finding — the #168-disabled toggle still rendered the GLOBAL preference. `46761a3` flips `refreshQuickSettings()` to `isChecked = !llmForcedOff && !settings.rawMode`: inside our own app the grayed box reads OFF, a foreign bind restores the global state. The `raw_mode` write-guards are untouched. Release on BOTH devices; the owner passed the check.

## 2026-11-25 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #209 CLOSED — owner verdict on the device; detail in state/workbench.md): #209 — the collapse chevrons are real drawables at 32dp

- Original entry (condensed): arc over three fixes: 14sp glyphs (#189) → 22sp (`4bd3f19`) → real drawables (`f2847f5`: `ic_chevron_down` ImageViews, `?android:attr/textColorSecondary` tint, collapsed rotates −90°) → 32dp per owner (`1d9b1b4`; `WorkbenchLayoutPolicyTest` pins ImageView + `ic_chevron_down` + `>= 32dp`). Installed on both devices; owner passed the check. Fallback if it ever reads small again: a pure size bump (40dp+), never a return to glyphs.

## 2026-11-25 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #208 CLOSED — owner verdict „the functionality is good now"; detail in state/workbench.md + decisions/workbench.md): #208 — promote clears the polished slot; the MRU ring stays; only ＋ instantiates a dictation

- Original entry (condensed): final shape over `a7c7d89` (promote is a move — `replaceRawWithPolished` empties the polished slot, the stored path shows it immediately) + `11e5b56` (the same-day creation-order excursion retracted per owner: `updatedAtMs` restored as the MRU key). The invariant **only ＋ / the compose materialisation instantiates a dictation** is pinned by a store test. 766 `:app` tests green, release on the OnePlus.

## 2026-11-24 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #189 shipped and owner-passed; the chevron follow-up lives in the #209 entry; detail in state/workbench.md): #189 — the Workbench sections collapse again

- Original entry (condensed): both label rows are `<summary>`-style toggles (chevron · label · copy) with a one-line ellipsised preview while hidden; the state lives for one visit; a take always opens Raw (a collapsed field has no input connection). The promote icon is an UP arrow, the two action rows are identical, the trash can sits in the bottom bar. `WorkbenchLayoutPolicyTest` pins the accordion; `WorkbenchActivityTest` covers both toggles.

## 2026-11-24 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: both pairs shipped and closed; detail in state/workbench.md + decisions/session-lifecycle.md): #201/#204 and #202/#203 — the parked bar lost its sentence; a RAW edit is saved while typed

- Original entry (condensed): #201 = #204 (duplicates) — a bar with nothing buffered is a plain pause at `0:00`, no explaining sentence. #202 = #203 — a RAW edit is saved while it is typed (500 ms debounce via `DictationStore.updateRaw`) and `commitEdits()` runs at the top of `polishCurrent()`, so the result attaches to the edited text and survives the reload. The owner's Disk-button idea was not built — the implicit-save policy stays.

## 2026-11-24 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #193 CLOSED with all four children; detail in state/ime-field.md + decisions/build-release.md): #193 — the lint baseline is gone

- Original entry (condensed): 77 baseline entries worked off — 71 real fixes and 6 documented exceptions (four inline `@Suppress`, two commented `lint.xml` ignores: the permissive cleartext and the IME overdraw, 90 % opaque by design). `warningsAsErrors = true`, `lintDebug` at zero findings, and `XmlColorPolicyTest` rule 6 fails the suite if `app/lint-baseline.xml` ever reappears. Children #194–#197 all closed.

## 2026-11-24 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #198/#199/#200 all closed; detail in state/session-lifecycle.md + pitfalls/testing.md): #198/#199 — exit 2 was never a heap wall; #200 — the flake had a thread

- Original entry (condensed): #198 — the app's JVM-global crash handler ends in `System.exit(2)`; a genuine throwable (`PCM range […] beyond buffer (0B)` while `flush()` truncated the spool under the worker) reached it. Now: worker stopped+joined before truncation, no throwable escapes worker/capture thread, a capture failure parks PAUSED, the handler logs, `CaptureThreadGuard` in the live-take classes. #199 — the `flush in PROCESSING is a no-op` flake made deterministic over a restored snapshot. #200 — the hypothesis (a deferred `apply()`) was wrong: the startup maintenance thread's `evidenceRetentionSeconds` getter WROTE the debug default `2592000` while reading; the getter is now a pure read, the thread is named `app-startup-maintenance` and joined by `SettingsStoreTest`.

## 2026-11-23 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #192 closed — the owner's device check passed 2026-11-24; detail in decisions/session-lifecycle.md + state/workbench.md): #192 — a pause belongs to ONE app, shipped + amended

- Original entry (condensed): the same app always continues a pause („always continue, no exceptions"), another app ends it through the #113 cancel and starts fresh; nothing is written for a paused take; the parked post-send bar ends with the keyboard. Owner follow-ups shipped in the same issue: ONE no-take bar (a fresh idle is parked at `0:00`, ↺ really starts a take) and an automatic cancel may only destroy audio it can hand over (`salvageableText()`, trace `kept=audio-without-transcripts`). Device check on `f6de166c` PASSED 2026-11-24; one acceptance item was REPLACED, not implemented — the post-send bar need not be distinguishable because the owner never wanted that state (answer "A").

## 2026-11-23 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #191 closed — shipped and owner-passed; detail in state/workbench.md): #191 — leftover actions directly under the when-line, an unmissable banner

- Original entry (condensed): after #186's device check paged 1/3 · 2/3 · 3/3 to find Retry STT, the four leftover actions sit directly under the when-line and the banner is an accent bar under the header (`<n> incomplete dictation(s) · <newest stamp> — tap to open`, GONE at zero, tap jumps to the newest leftover).

## 2026-11-23 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: #118 CLOSED 2026-11-23; only the churn-line owner check remains active — it stays in HANDOFF.md): #118 Phase C shipped, Phase B superseded

- Original entry (condensed): Phase C shipped (`service/RebindChurn` skips identical per-cycle render/FGS/notification/breath work; the trace line ends in `churn=render:<r> fgs:<f> notify:<n> breath:<b>`). **Phase B was NOT built — superseded by #190** (`MISMATCH` + before-window guard; the raw-rescue half shipped in #186). #186 closed and device-verified 2026-11-23 — epic #152 complete, 6/6 children (the Samsung airplane-mode flight logged `rawRescue` + zero requests, the retry sent exactly three fragments after reconnect; two defects found and fixed en route: the raw-mode empty-rescue "success" and the `onDestroy` journal delete).

## 2026-11-20 (SUPERSEDED 2026-11-28, origin: HANDOFF.md, reason: epic #152 complete — all six children closed, #186 device-verified 2026-11-23): the #152 completion checklist

1. **#182 — durable fragment journal** (closed 2026-11-20): `filesDir/journal/<sessionId>/`, composed upload, 256-MB cap, no age rule.
2. **#184 — idempotent uploads** (closed 2026-11-20): `session_id` + `fragment_index` + `attempt` via one `@PartMap`; empty map = pre-#184 wire.
3. **#183 — STT failure classification** (closed 2026-11-20): `SttFailureClassifier`, `Retry-After` budget, the per-session AUTH latch, `NEEDS_MANUAL` in the journal.
4. **#185 — recovery UI** (closed 2026-11-21): session state in `session.json`, `recoverableSessions()` as the single source, Workbench scan + auto-retry of `TRANSIENT`/interrupted, `JournalReplay`, Restore/Share/Dismiss.
5. **#188 — incomplete dictations in the Workbench** (closed 2026-11-22): `WorkbenchPage` + merge order, the tiered when-line, the leftover page, the four actions, the banner.
6. **#186 — offline sessions** (closed 2026-11-23, device-verified): the `NetworkGate`, `SttFailure.OFFLINE`, the OFFLINE latch, foreground-only reconnect. No journal age rule — the 256-MB cap keeps everything.

## 2026-10-14 … 2026-10-24 (SUPERSEDED 2026-11-28, origin: HANDOFF.md + STATE.md, reason: the referenced device checks happened or the issues closed — verified via `gh issue view` on 2026-11-28: #182/#75/#185/#188/#186/#177/#178/#181/#201/#202/#203/#189/#191/#192/#118/#142/#138/#120/#123/#110/#101/#99/#74/#109 all CLOSED; #156/#129 remain OPEN and stay in HANDOFF/STATE): the old owner device-check checklist and the October pending items

- Owner checklist items whose issues are closed (original text kept in condensed form; the full wording lives in the git history of this file):
  - [x] parked IME bar check (issues #177/#201/#204) — passed with the #192 device check.
  - [x] icon-only actions check (#178/#181) — owner verdict passed; chevron follow-up in #209.
  - [x] #182 force-stop device check — closed 2026-11-20.
  - [x] #75 budget-wall device check — closed 2026-11-22.
  - [x] #185/#188 recovery-path device checks — closed 2026-11-21/22.
  - [x] #188 merged-ring device check — closed 2026-11-22.
  - [x] #186 offline dictation device check — closed 2026-11-23 (the flight was run and exposed two defects, both fixed before closure).
  - [x] #202/#203 edit-survives-polish check — closed 2026-11-24.
  - [x] #189 summary-row device check — closed 2026-11-26.
- STATE.md pending items pruned the same way: the #142 on-device spot check (issue closed 2026-11-14), the #138 send-check (closed), the #120 re-check (closed on code evidence), the #123 loop guard (closed), the residue line for #112/#113/#116/#110/#101/#99/#74/#109 (all closed), and the F-Droid 1.5.0 pickup watch (f-droid.org lists v1.5.0 added 2026-10-16 and v1.5.1 added 2026-10-25 — verified 2026-11-28). The stale "Current Focus" block about epic #141/#142/#144 (already self-marked history on 2026-11-14) left STATE.md with its replacement.
