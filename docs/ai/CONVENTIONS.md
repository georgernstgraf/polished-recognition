---
type: Conventions
title: Conventions
description: Coding patterns, naming rules, and file-layout rules for this project.
tags: [knowledge, conventions]
generated: { by: opencode/knowledge-persistence, at: 2026-11-22T19:00:00Z }
verified: { by: opencode/knowledge-persistence, at: 2026-11-22T10:00:00Z }
status: stable
---

# Conventions

Coding patterns, naming rules, and style agreements for this project.
Follow these without question. Do not deviate unless explicitly told.

## Naming
- Package: `com.georgernstgraf.polishedrecognition`
- Files: PascalCase.kt
- Activities: `*Activity` suffix
- Services: `*Service` suffix (only one: `PolishedRecognitionService`)
- API interfaces: `OpenAi*ApiService`
- Data classes in `api/dto/`: descriptive names from API contract

## File Layout
- `api/` — Retrofit interfaces and DTOs
- `audio/` — audio capture utilities (`AudioRecorder`) plus the upload-transcoding chain: `WavReader` (fail-fast parse of the recorder's 44-byte WAV), `PcmConditioner` (pure-JVM high-pass + peak normalization), `AudioTranscoder` (interface), `OpusOggTranscoder` (MediaCodec/MediaMuxer impl)
- `config/` — settings, provider presets, language mapping
- `pipeline/` — transcription orchestration and prompt management
- `service/` — Android Service subclasses
- `ui/` — Activities and layouts
- `assets/` — JSON config files (`prompts.json`, `provider_presets.json`)

## Analyzer module (`:analyzer`, since #146)
- Package `com.georgernstgraf.polishedrecognition.analyzer.*`; commands dispatched by a plain `when(args[0])` in `Main.kt` — NO arg-parsing dependency. `analyzer/README.md` is the canonical doc (commands, corpus convention, canonical result format).
- The analyzer compiles the REAL production pipeline via `:core` — never duplicate pipeline logic there; analysis-specific code (seam attribution, stats) lives in `analyzer/src/main/.../analysis/`.
- Corpus convention (binding): `corpora/<name>/{manifest.json,wavs/,bundles/,results/<run-id>/}` — the manifest is COMMITTED (no secrets), the material git-ignored. Per-class confidence + provenance go into the manifest from the build report.
- STT credentials: CLI option or env var only (`ANALYZER_STT_TOKEN`/`ANALYZER_STT_URL`) — never hardcoded, never committed, never echoed to logs.
- Analyzer runs are strictly serial (C=1) and never upload >300 s; reference passes slice via `WavChunker` and the comparison loop-masks BOTH texts (`:core` `LoopMask`).

## API Patterns
- Retrofit base URLs must end with `/v1/` (or the path prefix the provider uses)- All API calls use `Bearer <token>` authorization header
- **Multipart extras go through ONE `@PartMap`, never a list of `@Part`s (#184).** `transcribeAudioSync`/`transcribeAudio` take `@PartMap extraFields: Map<String, @JvmSuppressWildcards RequestBody>`; an EMPTY map omits every extra field, which keeps the request byte-identical to the pre-#184 format — that is the contract that lets any OpenAI-compatible server keep working untouched. **`@JvmSuppressWildcards` is mandatory:** Kotlin declares `Map<K, out V>`, so the JVM signature would carry `? extends RequestBody` and Retrofit refuses it — and it refuses it LAZILY at the first real call (`Parameter type must not include a type variable or wildcard`), which a unit test with a mocked API never reaches (the `:analyzer` `ReplayCommandTest` caught it). Add a new wire field there instead of a new `@Part` parameter.
- **Cleartext HTTP is allowed app-wide (#114):** `res/xml/network_security_config.xml` (`base-config cleartextTrafficPermitted="true"`, wired via `android:networkSecurityConfig`) — users point the app at arbitrary self-hosted OpenAI-compatible endpoints (HTTP LAN servers, HTTPS cloud), so no per-domain exceptions; keep the permissive base config.
- STT multipart: `file` part, media type + filename **derived from the file extension** (`.ogg` → `audio/ogg`/`audio.ogg`, else `audio/wav`/`audio.wav`) — never hardcode WAV (since #60)
- LLM request: standard `{"model": "...", "messages": [...]}` JSON body
- `GET /v1/models` may return 404 for providers that don't support it — fall back to free-text model input

## Audio Upload (#60)
- `compress_audio` setting (default false): when set, `VoiceSessionController` transcodes the recording to `cacheDir/recording.ogg` via `AudioTranscoder` on `Dispatchers.IO`; **any transcoder failure falls back to the original WAV** — a recording must never be lost over transcoding
- `OpusOggTranscoder` throws on any failure and deletes partial output; callers catch and fall back
- Keep WAV upload as the always-working baseline; Ogg/Opus is strictly opt-in
- **Chunking (#115, aitranscribe port; cap 300 s since #120):** recordings beyond the upload limits (**25 MB / 300 s**, `WavChunker.MAX_CHUNK_BYTES`/`MAX_CHUNK_SECONDS`) are split at sample boundaries; within-limits recordings take the single-file path. Chunk limits on `VoiceSessionController` are constructor params (tests inject small values); no Settings UI for them — mirrors aitranscribe's fixed constants.
- **Fragment streaming (#115, owner design):** with `compress_audio` ON, a background worker (`FragmentPreparer`, single thread, background priority) encodes **21-s OGG fragments** (default; the size is a constructor parameter) of the append-only PCM buffer WHILE the user dictates, committing each to `cacheDir/fragments/<sessionId>/` + `manifest.json` (encode cadence evidence in `prepare.json` logs). A 600-s chunk = **byte-concatenation of its fragments** (OGG chaining, RFC 3533 — valid without a muxer; per-link pre-skip "ticks" ~6.5 ms accepted; the chunk cap is 300 s since #120). Fixed chunk boundaries replaced core.py's even split — streaming requires stable boundaries. Stop-time work = tail fragment + `cat` only. Retry reuses committed fragments (append-only invariant); the session id rides the snapshot meta so process death restores into the same fragment dir; `flush()`/`cancel()` prune. Transcode failure → whole session rebuilds as WAV fragments (never mixed-format chunks). With compress OFF there is no worker (disk IO is fast enough) — the legacy hash-keyed prepared dir (`cacheDir/prepared/<sha256>/`) covers that path's retry.
- **Fragment seams must never split words (#117, adaptive since #124, silence-QUALITY since #126):** cut points are chosen by the **silence-quality metric** — `SilenceQuality` measures the **search window** — the last `p` % of the slice (#124, owner decision 2026-11-12; the pre-#124 "late half of the forward window" is gone) — on an 80-Hz-high-passed copy, builds the adaptive axis `X = k·M + c` per fragment, and cuts at the area CENTROID of the best deficit-area candidate Q (clamped to a K-ms margin from the candidate's over-axis edges); no candidate → forced best-effort cut at the quietest span frame (`candidateFound ⇔ Q > 0` is the interim `silenceAligned` — manifest field name kept; #128 replaces it with the `qualityMin` stitching gate; the axis defaults k = 0.65 / c = 100 / K = 150 ms are PROVISIONAL, #129's research pass owns the real values — study-only knobs, no Settings UI; the `:analyzer` CLI drives them); the stream stays GAPLESS (fragment *i+1* starts where *i* ended — manifest entries carry the PCM `end` offset, and manifests WITHOUT offsets are legacy → discarded + re-encoded). **The geometry is "slice + search window %" (#124, owner decision 2026-11-12 — REPLACES the pre-#124 `search = max(2 s, S)` / `[S, 2S]` rule):** a **slice** is a fragment's MAXIMUM length (its boundary is arbitrary); the **search window** is the last `p` % of the slice and the ONLY region a cut may land in, so `cut ∈ [sliceEnd − window, sliceEnd]` and `fragment ∈ [Slice·(1−p), Slice]`. Production resolves both per session in `VoiceSessionController.resolvedSliceBytes()` / `resolvedSearchWindowBytes()` (slice from the auto-sizer, percentage from the adaptive forced-cut rule or the manual override); a recording shorter than one slice collapses to a SINGLE (tail) fragment — intended. Explicit injected values (tests, analyzer study) keep their exact semantics — `searchWindowBytes = 0` stays the legacy hard cut at the slice end. The old geometry produced 1.5–2× the nominal size (measured 17.7 s at a nominal 10 s) — never reintroduce a forward window that reaches past the slice. On a full window with NO below-axis candidate the forced best-effort cut takes the QUIETEST span frame (Q = 0 → always stitches under the #128 Q-gate; `FragmentPreparer.energyFallbackEnabled` is INERT since #126 — the metric always finds a best-effort cut). **Default slice is 21 s** (constructor parameter `sliceBytes`; the auto-sizer drives it: the largest slice whose predicted stop→raw fits the fixed 2.0-s budget, floored by `8 s/(1−p)` — measured resolution: 20 s on gregor/ogg, 137 s on GROQ). Every fragment is transcribed with the **previous fragment's transcript tail as the Whisper `prompt`** — since #122 **word-snapped and seam-local** (`DEFAULT_PROMPT_MAX_CHARS` = 96 ≈ 12 words, was `takeLast(800)`); verified working end-to-end (gregor forwards it as faster-whisper `initial_prompt`); the prompt is never echoed into the output, so joining needs no dedup; HTTP 400 on a prompted request = prompt unsupported → retry without + disable for the session. **#122 finding:** the prompt is only ONE fragment's transcript (~90 chars/10 s, ~190/21 s, ~550/60 s) — `takeLast(800)` never truncated anything at the studied sizes; prompt length is NOT an accuracy lever (the window DOES bind now at large slices — harmless per the same null). **Acoustic pre-roll (round 2, implemented `5c49cc5`; #128 Q-gated):** every stitched fragment ≥ 1 gets a `preroll_<i>.<ext>` side file (last 1 s of PCM before its start, same format, written BEFORE the manifest) — since #128 the seam STITCH decision is QUALITY-driven (`SeamPolicy.Q_GATE`, the production default: pre-roll + prompt + echo trim iff the seam's `Q < qualityMin`, strict — `Q == qualityMin` is bare; forced cuts have `Q = 0` and always stitch; a null/unmeasured `Q` (legacy manifests, fixed cuts) stitches as the conservative direction). `ALL`/`FORCED_ONLY`/`OFF` keep their exact pre-#128 semantics as frozen study arms (selectable per `:analyzer run`). The decision lives ONCE in `FragmentPreparer.seamStitchedAt` and flows to the transcriber as `CommittedFragment.stitched` — the prompt is applied EXACTLY then, so pre-roll, prompt and trim can never disagree; the fragment's UPLOAD is composed ONCE as `preRoll + fragment` byte-concat (`upload_<i>.<ext>`, OGG chaining) and BOTH attempts — including the 400 prompt retry — upload that same file; missing pre-roll → bare fragment. Fragment files stay GAPLESS (chunk assembly reproduces the original PCM). The echoed pre-roll text (an ACOUSTIC echo — occurs even with the prompt disabled) is trimmed BEFORE the transcript is stored: token match against the previous transcript tail (k ≤ 8, head token equal, ≤1 mismatch for k ≥ 2, no match keeps everything), only when the upload actually carried a pre-roll; evidence in the dedicated `stt-trim.json` stream (not `prepare.json` — rotation depth). A pre-roll encode failure → `null` and must NEVER trigger the session-level WAV fallback; the fallback rebuild re-creates pre-rolls; `recover()`'s orphan keep-set includes them. Chunk assembly caps on accumulated PCM bytes (`min(25 MB, 300 s)`), never on fragment count. The `stt-upload.json`/`stt-latency.json` evidence streams are the latency-profile measurement input — EVERY production request writes into them (the #143 shadow's `evidence = false` suppression is GONE with the shadow).

## Configuration
- Provider configs serialized as JSON objects in SharedPreferences
- Model lists cached as JSON arrays in SharedPreferences
- Prompt defaults loaded from `assets/prompts.json`, user edits stored in SharedPreferences
- Settings keys follow snake_case convention in SharedPreferences
- **Learned dictation targets (`#100`)** live in their **own** SharedPreferences file `known_apps` (not in `polished_recognition_settings`), as a JSON `Map<packageName, KnownApp(label, lastSeenMs, wrapWidth)>`; `wrapWidth == null` means "use global". This isolates the learner's frequent writes and the OS backup from the provider/prompt config.
- **Learner writes must never block the caller**: all `known_apps` persistence goes through `SettingsStore`'s injectable single-thread `learnerExecutor`; `recordKnownApp` only enqueues, and only when the app is new or its stored `lastSeenMs` is older than `KNOWN_APP_REWRITE_INTERVAL_MS` (24 h). Failures are logged with `Log.w`, never surfaced. `SettingsStore` keeps an in-memory cache so the check is O(1). Tests inject a direct executor.
- **Diagnostics are build-gated, never in release (#125):** → SUPERSEDED by #143 — shadow comparison and full-context-at-stop are REMOVED entirely (no gates left to wire; Epic #141 replaces the diagnostics with PC-side offline bundle analysis). The pattern itself (build-type gate for any future diagnostic, OFF in release) stays valid. See HISTORY.md.
- **Evidence retention is a TIME-BASED USER SETTING (#142):** `SettingsStore.evidenceRetentionSeconds` (0 = no retention — nothing is written) backed by the `EvidenceRetention` log-stop scale (1 h → 50 years) and shown as a SeekBar + live label in Settings (the #127 checkbox is GONE; the legacy `audio_evidence` pref migrates ON → 30 days, OFF → 0; the derived `audioEvidence` getter keeps all existing wiring). The DEFAULT is the `BuildConfig.AUDIO_EVIDENCE_RETENTION_DEFAULT_SECONDS` build property (0 in release — privacy; 30 days in debug). Retention is COPY-ONLY (`pipeline/AudioEvidenceStore`): copy failures are best-effort `Log.w`, and `cancel()`/`flush()` (deliberate user discard) never retain. Eviction is age-based (from the #132 name prefix, mtime fallback) plus the invisible storage safety valve (512 MB free-space floor, oldest-first, newest survives), run after every write and at app start.
- **The forensic bundle is `uploads/` + `session.json` ONLY (#142):** one session dir = one complete record — the actually-uploaded audio (ground truth; bare fragments retained TOO — the pre-#142 gap skipped them) plus the `SessionBundle` manifest (fragment boundaries, START-seam decision + quality block mirroring the `prepare` contract, sttRaw/sttTrimmed/echoTokens/prompt/sttError, the session-level LLM block via the pipeline's `onOutcome` hook, and the delivery outcome). NO `session.wav`, NO `fragments/` (verified Duplette: uploads are derivable from fragments; uploads are what the provider saw). The log streams are LIVE DEBUGGING only (`LOG_ROTATION_DEPTH` unified at 32) — never extend a stream with forensic data; put it in the bundle.
- **Session IDs carry a LOCAL-time prefix (#132):** `newSessionId()` → `yyyyMMdd-HHmm-<8hex>` (e.g. `20261012-1415-abcd1234`) — the retained evidence dir name is recognisable by dictation date/time. Never reintroduce bare UUIDs as session ids, and never make the prefix UTC or add separators beyond the dashes (one path/JSON-safe token).
- **The durable fragment journal (#182) is `filesDir/journal/<sessionId>/` and is ALWAYS ON** — never gated by a setting, never moved into `cacheDir`. Contents: `session.json` (marker, written first), `fragment_%04d.json` (one record per fragment, states `PENDING`/`DONE`/`NEEDS_MANUAL`) and `upload_%06d.<ext>` (the COMPOSED upload — the exact bytes that went to the provider, so a retry is byte-identical). Writes are atomic (`.tmp` + rename) and the audio is written BEFORE its record. Every journal call is best-effort: a failure logs and never fails a fragment. `VoiceSessionController.discardFragmentSession()` deletes the session (delivered / cancelled / flushed) and the pipeline-FAILURE path keeps it. `FragmentJournal.prune` enforces the 256-MB cap oldest-first — no age rule, the newest and a named live session always survive — at app start and after every `deleteSession`. Both backup rule files exclude `journal/` (`BackupRulesTest` pins the exact exclude set).
- **Wrap-width validation is `0` or `>= SettingsStore.MIN_WRAP_WIDTH` (20)** — use that constant in Settings; do not reintroduce `>= 10`.
- **Never persist the per-app dropdown MRU order (`#102`)**: the most-recently-used ordering is in-memory only (`SettingsStore.mruOrder`); disk `lastSeenMs` stays write-throttled (24 h). Do not turn `recordKnownApp` into a per-dictation disk write.
- **Record the dictation target at dictation start (`#102` amendment)**: both entry points call `recordKnownApp` when recording begins — IME `startIfPermitted()` and service `onStartListening()` — so the currently active app is top/pre-selected while dictating (e.g. gear → Settings). The insertion/delivery-time call stays as the authoritative capture/fallback; the second call is absorbed by the 24 h throttle (no extra disk I/O).

## UI Patterns
- **The app is fully Material-free + appcompat-free** (since #61): every activity extends plain `android.app.Activity`, `Theme.PolishedRecognition.Plain` → `Theme.DeviceDefault.DayNight` is the only theme family, and only plain platform Views are used (`EditText`, `CheckBox`, `Button`, `AutoCompleteTextView`, `ImageButton`). NEVER reintroduce `com.google.android.material`, `androidx.appcompat`, `TextInputLayout`/`TextInputEditText`/`MaterialCheckBox`/`Widget.Material3.Button.*`/`?attr/textAppearance*`/`?attr/...` (bare) into any layout or theme — they crash under `Theme.DeviceDefault`. Use `?android:attr/...` (platform), `@android:style/...`, or explicit `textSize`/`textColor`. IME layouts additionally must not use any `?attr/` at all (see PITFALLS).
- **Settings validation errors** (since #45): call `editText.error = msg` (or `autoCompleteTextView.error = msg`) — shows a red warning icon + popup. Add a `TextWatcher` that clears `error = null` on text change so the icon disappears as the user corrects. Do NOT use `TextInputLayout.error`/`helperText` (no `TextInputLayout` exists in Settings anymore). Success states use `Toast` (the Material `helperText = "Token valid"` green text is gone).
- **Settings token reveal** (since #45): each API-token `EditText` is paired with an `ImageButton` (`stt_token_toggle`/`llm_token_toggle`) whose `OnClickListener` calls `togglePassword(field, toggle)` — flips `PasswordTransformationMethod` + swaps `ic_eye`↔`ic_eye_off` + moves the cursor to the end. Do NOT re-add Material's `app:endIconMode="password_toggle"` (no `TextInputLayout`).
- **Settings provider/target-language dropdowns** (since #45): bare `AutoCompleteTextView` with `inputType="none"` needs `setOnClickListener { showDropDown() }` + `threshold = Int.MAX_VALUE` in `setupDropdowns()` (Material's exposed-dropdown tap hook is gone). Keep XML `focusable="false"`+`cursorVisible="false"`+`clickable="true"`. Model dropdowns keep `inputType="text"` + `completionThreshold="1"` (searchable).
- Transcription errors in the IME (`PolishedVoiceInputIME`) must surface the error detail visibly (Toast/status text) — `listener.error()` alone sends an opaque error code to the keyboard that the user cannot see.
- **Language lists (since #154):** `config/CustomLanguages.NONE_TARGET_LANGUAGE` = `"LLM, keep language"` is the **one** source; `config/LanguageOptions.NONE_TARGET_LANGUAGE` aliases it (same package, `const val` on `const val`) and the object keeps only `buildLanguageList`; Settings renders `CustomLanguages.displayList`. Do NOT reintroduce a separate IME-only string — the old short variant ("Polish only") was retired with the LLM toggle, and the IME bar and the Settings dropdown must show the identical entry.
- **IME bottom-row icons (since #95):** keep all five at the same intrinsic size (**24dp**) and the same stroke width (**1.6**) so they render equal at the row's `scaleX/Y=1.5`. The backspace inner cross is **two line strokes** (never a stroked closed polygon — that renders hollow/outlined) and the trash lid is a **single** stroke line (a 2-unit band's lower edge merges with the can top and reads too thick). `ic_close`/`ic_pause`/`ic_resume`/`ic_send`/`ic_backspace` are IME-only and safe to resize; `ic_delete_outline` is shared with the Settings language rows. **The bottom row stays at five icons** — since #155 the leftmost one is the keyboard switch (the X, `ime_switch_button`, icon `ic_close`); the top row has **no** keyboard-switch button any more (its old `ime_switch_keyboard_button`/`ic_keyboard` slot was removed and the freed 36 dp + 8 dp go to the language spinner), so do not reintroduce one there.
- For custom filtering on `AutoCompleteTextView`, use `BaseAdapter` + `Filterable` with a directly owned `displayItems` list. Never extend `ArrayAdapter` with a custom `Filter` that calls `clear()`/`addAll()` — these modify the internal `mOriginalValues` (not `mObjects`), corrupting state and causing crashes on text input.
- **Notification small icons (since #109):** are rendered by Android as a **monochrome (alpha) silhouette** — the drawable's colour is irrelevant (the system tints it), so supply a white-on-transparent glyph at 24dp with ~2dp padding. Use a project vector, not a platform bitmap (platform drawables like `android.R.drawable.ic_btn_speak_now` are bitmaps and read too narrow). `VectorDrawable` has **no `<ellipse>`/`<rect>`** — draw ellipses as `<path>` arcs. The ongoing voice-input notification uses `ic_notification_mic.xml` (variant B); bolder alternatives B/C/D live in `docs/design/notification-icon/`.

## Workbench (epic #166, #167–#172; entry model amended by #173; chrome and fields by #178)

- **The Workbench is the only launcher entry.** `WorkbenchActivity` carries `MAIN`/`LAUNCHER`; `SettingsActivity` is `exported="false"` and reached from the Workbench header and the IME gear. `LauncherEntryTest` fails on a second `MAIN` or a missing one — never add a launcher filter anywhere else.
- **Dictations are files, not preferences.** `dictation/DictationStore` writes one `<id>.json` under `filesDir/dictations/` with temp file + rename, a strictly increasing `createdAtMs` (it stamps the id) and a strictly increasing `updatedAtMs` (the MRU key since #173: two dictations in one millisecond must not sort by their random id). `loadAll()` is least-recently-edited first; `pruneToSize()` evicts from the front, so display and deletion share one order. Legacy files without `updatedAtMs` fall back to `createdAtMs` on read. `dictations/` is excluded from cloud backup AND device transfer in all three containers; `BackupRulesTest` pins `DictationStore.DIR_NAME` to both rule files exactly like #101 does for the secrets file. Never move this state into SharedPreferences.
- **The compose slot is transient (#173).** The entry page is an id-less slot in `WorkbenchActivity`, never written while empty and never part of the ring. It is materialised (`store.append`) on pause, on ＋, on re-polish and on a navigation with content. `DictationStore.read()` still rejects blank raw — a polished-only slot is materialised with the polished text as raw.
- **The visible page decides the dictation target (#173, two targets since #178).** `WorkbenchActivity.onRecordingStarted()` snapshots: compose slot ⇒ NEW, stored dictation ⇒ RAW_EDIT (at the caret). `POLISHED_EDIT` is gone — the polished field is read-only and cannot hold focus. Never reintroduce a text comparison against the rendered text — that made a caret move mean "new". A stored page never creates an entry.
- **The ring is MRU with wrap-around (#173).** `FifoNavigation` keeps `COMPOSE_INDEX` outside the ring, wraps in both directions and labels the compose slot "new". Both arrows leaving the compose slot land on the newest dictation; ＋ is the only way back to the slot.
- **The fields are permanently visible (#173 geometry, #178 chrome).** `wrap_content` + `minLines=2`, no `scrollbars`; only the root ScrollView scrolls. `WorkbenchLayoutPolicyTest` fails on a fixed `dp` height or an inner scroller. There is NO accordion any more: Raw and Polished are always open, and each label row carries only its label plus the copy icon.
- **Read-only text fields are `TextView`s.** `focusable=false`, `wrap_content` + `minLines=2`, and NO `android:scrollbars` — the outer ScrollView is the only scroller (`workbench_polished` since #178).
- **Enablement of Workbench controls comes from `WorkbenchActions.enabled(...)`**, never from a rule written in the Activity. The asymmetry is deliberate: one stored dictation is reachable from the compose slot (`ringSize >= 1`), not inside the ring (`>= 2` — a one-entry wrap is a self-loop).
- **Clipboard writes go through `delivery/ClipboardDelivery`,** never a raw `setPrimaryClip`: it marks the clip `EXTRA_IS_SENSITIVE` on API 33+ and treats empty text as a no-op. Automatic delivery is not the deliberate IME-salvage copy (#138's asymmetry is intended).
- **LLM is forced off inside our own app** (`service/LlmTargetPolicy` + the controller's session-scoped `pipeline.forceRawMode`). Never implement the rule by writing `raw_mode`; the preference is global and would leak into foreign apps. The bar toggle shows the global state but is rendered disabled.
- **The Workbench has two polish buttons and one language (#176, #178).** `workbench_polish_a` (zone A, above Raw) and `workbench_polish_b` (zone B, between Raw and Polished) both call `TranscriptionPipeline.polish(raw, language)`; the two language spinners write into the SAME per-visit `polishLanguage`, which is per-dictation and never written to `settings.targetLanguage`. A raw edit discards the polished slot with no confirmation and no silent re-polish; since #176 nothing polishes automatically at all — only a polish button does.
- **The screen owns its insets** (`ui/WorkbenchInsets`: bars + cutout + IME, max per side, rebuilt from the XML base) and must stay in `ui/contrast/ContrastMatrix`; the layout is platform-only with `?android:attr/…` colours. **Do not give the action rows or icon buttons a background**: `selectableItemBackground` resolves to an opaque white layer under the night theme and the contrast gate measured white-on-white (cheap ripple, real regression). Icon tint comes from the layout (`?android:attr/textColorPrimary`, `…Secondary` for secondary actions), never from the drawable — a vector keeps `fillColor #FF000000` only.

## Incomplete dictations (#185, one vocabulary since #188)

- **The journal is the single recovery source.** The ring's leftover pages and the auto-retry read `FragmentJournal.recoverableSessions()` — never the #142 evidence store (the retention slider defaults to 0 in release, so it would be empty) and never `cacheDir` (the OS may clear it).
- **A session's state lives in its `session.json`**: no marker = unfinished (a leftover), `completedAtMs` = finished (a surviving dir means "text available", so the UI offers **restore**, never a re-run), `dismissedAtMs` + `dismissReason` = tombstone. A marker-less session whose records are ALL `DONE` also counts as completed — nothing is left to send.
- **A dismissal is recorded BEFORE the audio is deleted** (`markDismissed` writes the marker, then removes every other file). Dismissal tombstones are capped by COUNT (`MAX_TOMBSTONES` = 50, newest win) inside `prune`; they never consume the 256-MB audio cap.
- **Re-run sends only the missing fragments.** The `DONE` text is spliced in index order; a fragment costs a request only when it has no stored transcript. The upload is the journal's own composed file (byte-identical), the prompt is the RECORDED one, and the echo is trimmed by `EchoTrim` — the same rule the live worker uses. Never reconstruct a session WAV for a re-run (OGG cannot be decoded on device, and the prompts would be lost); reconstruction exists only for **sharing** (`recovery/RecoveryAudio`).
- **The re-run stops at the first failure** and leaves the journal as it is (`markFailed` with class + HTTP code); a `DONE` flip is durable, so a cancelled run resumes. Never let a recovery become a second retry storm — the runner's own transient retries inside one fragment are the only retries.
- **A recovery never competes with a dictation.** `JournalReplay.beforeFragment` yields while the controller is RECORDING or PROCESSING, and `RecoveryCoordinator` refuses to start a second run. The auto path remembers the sessions it tried per PROCESS (`autoAttempted`) so re-entering the launcher does not re-upload a session that just failed; a manual re-run from the list is always allowed.
- **Auto-retry only what can heal**: `TRANSIENT` (network) and "no failure class" (interruption). `AUTH`, `PROTOCOL`/`NEEDS_MANUAL`, `TERMINAL` and `BUDGET` stay for the manual Retry STT — waiting cannot fix a dead token, a broken provider envelope or an empty balance. The rule is `RecoveryReason.autoRetryable`, not a condition in an Activity.
- **The text is stored before the journal is deleted** (append to `DictationStore`, verify, then `markCompleted` + `deleteSession`). A failed polish falls back to the RAW transcript; a failed store keeps the journal. Deleting the audio first would be the one unrecoverable mistake.
- **Enablement comes from `ui/RecoveryActions.enabled(...)`** (house pattern) — the page and the layout carry no rule of their own. Since #188 the actions live on the leftover page in the Workbench (`workbench_retry_stt` / `_restore_text` / `_share_audio` / `_dismiss_leftover`); there is no recovery screen any more, so the leftovers are part of `ContrastMatrix` through the Workbench.
- **The Workbench banner is the only entry point** and is `GONE` at zero pending (no dead UI). The feedback Toast goes out BEFORE the first request.

## The merged ring (#188)

- **One ring, two page kinds.** `WorkbenchPage` is `Stored(dictation)` or `Leftover(summary, hasAudio)`; `WorkbenchPages.merge` is the only order rule (ascending by last change — `updatedAtMs` / `startedAtMs`, an undated leftover LAST, ties by id). Never sort in the Activity and never build a second list.
- **The store and the journal stay the two truths.** A page is a view over its source: text comes from `DictationStore`, audio from `FragmentJournal`. A page is never persisted — no schema change, no cache, no third store.
- **A leftover page has no text and cannot take focus.** Raw is `isEnabled = false` / `isFocusable = false` with empty text; `DictationTarget` is `NEW` on it, so a delivered transcript APPENDS a dictation instead of editing a page that has nothing to edit. The IME's commit path is exactly why: a field that cannot take focus can never receive a commit.
- **The when-line is one tiered stamp** (`ui/WorkbenchStamp`, pure): same local day `HH:mm`, ≤ 7 local days `EEE HH:mm`, older `d MMM` (+ `yyyy`). Local DATES decide the tier, not 24-hour buckets — a DST night stays one day. For a leftover the same line continues with `seconds · fragments · reason` (`RecoveryReason`, `workbench_leftover_detail`).
- **Restore keeps the #185 order** (`recovery/RecoveryRestore`): store the text, verify the store holds it, then delete the journal session. Share keeps the #185 file rule (`recovery/RecoveryShare`): one reconstructed WAV, the pieces for OGG, `null` when there is nothing left.

## Prompt Variables
The transcription pipeline resolves the following template variables at runtime. The **system** prompt is the single editable instruction surface; the **user** message is an automatic, non-editable carrier containing only `{{text}}`.
- `{{text}}` — raw Whisper transcription output (resolved into the user message; also the only content of the `user` prompt template)
- `{{source_language_clause}}` — resolved into the **system** prompt as a full sentence, or **empty** (whole sentence dropped) when Whisper returns null/blank/`"unknown"`. Two variants: with `language_probability` → `"The Whisper service detected the recognized language as <Name> with a probability of X percent."`; without → `"The STT service transcribed audio spoken in <Name>."` (#105). The `<Name>` always goes through `config/LanguageMapper` (complete Whisper `LANGUAGES` table — codes and names, case-insensitive, capitalized-words fallback), never raw `capitalizeWords`, because providers disagree on the `language` format (GROQ `"German"` vs faster-whisper `"de"`).
- `{{target_language}}` — the user's chosen output language (resolved into the translate prompt)
- `{{target_language_clause}}` — resolved into the **system** prompt; empty string if no translation, otherwise the resolved translate prompt

## Build & Installation

- Always install via `./gradlew installRelease`. The release build type uses `signingConfigs.release` pointing to `app/release.keystore` (copy of `~/.android/debug.keystore`) for the signing key, so it installs without extra setup. `installDebug` installs a separate `.debug` suffix APK that bypasses the RecognitionService — never use it for testing voice input.
- The debug build type sets `applicationIdSuffix = ".debug"`, creating a different application ID. The system's `voice_recognition_service` setting points to the release application ID, so the debug APK will never work as a voice input provider.
- To ensure CI builds produce APKs with the same signature as local builds, store `~/.android/debug.keystore` (base64-encoded) plus storePassword/keyAlias/keyPassword as GitHub Secrets (`RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). The CI workflow decodes the keystore into `app/release.keystore` and passes passwords via env vars to `assembleRelease`. The `signingConfigs.release` block in `app/build.gradle.kts` reads env vars, falling back to `android`/`androiddebugkey`.
- Stack: AGP 9.1.1, Gradle 9.5.1, JDK 21, compileSdk/targetSdk 36, minSdk 30. No Kotlin plugin (AGP 9.x has built-in Kotlin support). JVM target derived from `compileOptions { targetCompatibility = VERSION_21 }`. **The build pins a Java 21 toolchain** (`java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }` in `app/build.gradle.kts`) so compile and test tasks never run on a newer ambient JDK — MockK/ASM cannot read JDK 25 bytecode. A JDK 21 must be installed (auto-detected); no toolchain resolver (keeps F-Droid offline). CI uses Temurin 21.
- **In-app help is single-source**: `docs/help.html` is the canonical help (self-contained HTML, interactive, offline). The site links it as the **Help** tab; the app bundles it into `assets/` via the Gradle `stageHelpAssets` task (Variant API `addGeneratedSourceDirectory`, no committed duplicate) and shows it in `HelpActivity` (WebView, `javaScriptEnabled = true`, local assets only). Edit only `docs/help.html`. **The help follows the Android system light/dark mode** (the app has no theme setting): `HelpActivity` reads `uiMode` and loads `help.html?theme=dark|light`, and matches the WebView background to avoid a flash; the page's `prefers-color-scheme` handling is merely the browser fallback. The Activity recreates on a ui-mode change, so it tracks a live switch.
- **The pre-push hook is required** (`scripts/pre-push`, activated per clone via `ln -sf ../../scripts/pre-push .git/hooks/pre-push`). It runs `./gradlew test` and blocks red pushes; a fresh clone has it disabled.
- The `release.yml` workflow targets the `alpha` track with `status: completed`. Production track is blocked by Play Console preconditions. Switch to `tracks: production` when preconditions are resolved.
- GitHub binary assets are pruned by `scripts/cleanup-github-assets.sh`, called from `build.yml` (every `main` push), `release.yml` and `fdroid-apk.yml` (both need `actions: write` + `contents: write`): Actions artifacts keep newest **7 per name** (`app-release`, `release-aab`, `github-pages`); `build-*` releases and orphan `build-*` tags keep newest 7 (release page **and** git tag). **`v*` releases are NEVER pruned** (page or tag) — fdroiddata's `Binaries:` URL is per-version (`releases/download/v%v/polished-recognition.apk`) and `UpdateCheckMode: Tags ^v`; both must stay reachable for F-Droid's reproducible-build verification. `build.yml` sets `make_latest: false` so `v*` releases stay the GitHub "Latest".
- Test framework: JUnit 4 (`@Test`, `@Before`, `@After`), no JUnit 5
- Mocking: MockK 1.14.4 (`mockk(relaxed=true)`, `coEvery { ... } returns ...`, `slot<T>()`)
- Assertions: Google Truth 1.4.4 (`Truth.assertThat(...)`)
- Android unit tests: `@RunWith(RobolectricTestRunner::class)`, `RuntimeEnvironment.getApplication()` for Context
- Coroutine tests: `runBlocking { }` (sync wrapper for suspend fn tests), no `runTest` needed
- Test classes mirror source directory structure exactly
- Test methods use backtick descriptive names: `` `STT HTTP error returns failure` ``
- Integration tests: separate `integration/` package, read `.env` for API keys, `assumeTrue` to skip when keys absent
- Test resources: `src/test/resources/` for audio fixtures, `src/test/resources/robolectric.properties` for SDK config

## Contrast gates (#161–#164)

- **Two halves, two claims — never let one stand in for the other.** The **theme gate** (Robolectric, `ui/contrast/`) asserts that the app's own palette clears WCAG on the real screens; the **pixel gate** (`scripts/contrast-audit.sh`) asserts what the device actually paints, which is the only check that can catch an OEM skin (it resolves no theme attributes at all).
- **`scripts/contrast-audit.sh [--mode light|dark|both|manual] [--out DIR] [--serial SERIAL]`** (device, USB host): opens Settings, takes `uiautomator dump` + `exec-out screencap -p`, and hands both to `scripts/contrast_audit.py`, which measures every app text node from the **pixels** (WCAG 2.1 relative luminance) and prints a table sorted by ratio; `--json <path>` on the measurer writes the raw rows. Nothing is written into the repository — artifacts land in a temp dir or `--out`.
- **Thresholds:** 4.5 normal, 3.0 disabled, and 3.0 "large" **only when the bounds AND the measured glyph height are ≥ 18 sp** — nothing in this app qualifies, so the gate runs at 4.5 for every enabled text element. A red row is never "fixed" by lowering a threshold.
- **Measurement rules:** ink = the luminance **bucket** farthest from the dominant background colour that holds **≥ 0.5 %** of the crop (a single farthest pixel reported a fake 1.14:1 on a rounded button); no ink above the floor ⇒ `ink-below-share` = **not measurable**, never a fabricated 1.00:1. The masked API-key field is structurally unmeasurable.
- **Exit codes:** 0 = all measured modes pass, 1 = at least one element below its threshold, 2 = environment problem (no device, locked device, missing tooling, a foreign window in front) — 2 is never a pass.
- **Never fake a mode:** switch light/dark for real (`cmd uimode night yes|no`, or `--mode manual` and switch by hand) and measure again; one mode's numbers never stand for the other. The device must be **unlocked** on the Settings screen.
- **One screenful per run:** `uiautomator` prunes off-screen `ScrollView` children (≈16 of ~65 Settings nodes) — scroll and re-run when the rows below the fold matter.
- **The opt-in render test stays opt-in** (`-Pcontrast.render=true`, `RenderSmokeTest`): it costs > 10 s and depends on Robolectric `NATIVE` graphics internals. Promotion into the required suite requires (a) 10 consecutive identical runs on the host, (b) green in CI for two weeks, (c) runtime under 10 s inside the suite. Flake criterion: any non-reproducible verdict reverts it to opt-in.
- **`ui/contrast/` is a shared test-helper package, not a mirror of a source package** — `Contrast`, `ViewColors`, `ContrastAudit`, `ContrastMatrix`, `ContrastGate`, `AuditFormat` are reused by several test classes (`ThemeContrastAuditTest`, `ThemeContrastAuditNightTest`, `TextPaletteContrastTest`), so it deliberately breaks the "test classes mirror source directory structure" rule above. Add a new shared helper there instead of duplicating it.

## STT failure classes (#183, extended by #75)

- **Every STT failure is classified BEFORE anything acts on it**
  (`pipeline/SttFailureClassifier`, pure): TRANSIENT (IOException/timeouts,
  408, 429, 5xx), AUTH (401/403), BUDGET (#75: a 4xx whose error BODY names a
  spend/quota wall), PROTOCOL (2xx without a transcript, undecodable body),
  TERMINAL (other 4xx, unexpected exceptions). The runner derives its attempts
  from the class, the journal stores it, the UI acts on it.
- **Attempts come from `SttRequestRunner.attemptsFor`:** TRANSIENT 3
  (`MAX_ATTEMPTS` stays the hard bound), BUDGET/AUTH/PROTOCOL/TERMINAL 1.
  PROTOCOL no longer repeats (#183 said "exactly once", #75 dropped it to one —
  the class never occurred in the device logs and the manual retry covers a
  glitch). `Retry-After` (seconds or HTTP date, `RetryAfter`) is honoured up to
  `MAX_RETRY_WAIT_MS` per wait and `MAX_TOTAL_WAIT_MS` per request; a longer
  advice fails the request instead of retrying early.
- **The provider's error BODY decides BUDGET, never the status or a header**
  (`pipeline/ProviderError`, pure): read `errorBody()` once (≤4 KB), parse
  `{"error":{…}}` or a flat envelope with string OR numeric `type`/`code`, and
  match `budget_exceeded`/`insufficient_quota`/`quota_exceeded`
  case-insensitively. A bare 429, a `rate_limit_exceeded` and an HTML/empty
  body stay TRANSIENT. `x-ratelimit-*`/`retry-after` are DIAGNOSTICS — a
  gateway may strip them (PITFALLS).
- **AUTH and BUDGET latch the SESSION** (`FragmentTranscriber.blockedFailure`):
  later fragments are recorded as failed without a request, their composed
  upload still reaches the journal (PENDING, class included).
  `resetFailures()` clears the latch — the only place that may, because it marks
  the moment a new token (or a topped-up budget) may exist.
- **A failure carries its class end to end:** `SttRequestException.failure`
  (default TERMINAL) plus the parsed `providerError` and the limits-only
  `headers`, journal `failureClass`/`failureHttpCode`, the bundle's `rateLimit`
  block, and the IME's `SttFailureText` hint (AUTH, PROTOCOL, BUDGET) in FRONT
  of the technical detail — never instead of it.
- **Raw provider bodies reach a log only through `LogRedaction`** (Bearer plus
  `sk-`/`gsk_`-style keys). Keep the token charset free of `"`/`'`, or the
  redaction eats the JSON string's closing quote.
- **Tests:** use **410 Gone** for "a plain permanent failure" (401 is the auth
  latch, 400 the prompt fallback — see PITFALLS).

## Build-output warnings (#187)

- **The release build's OUTPUT is part of the acceptance, not just `BUILD SUCCESSFUL`.**
  Read the log of `./gradlew installRelease`: a new AAPT message, a `w:` line or a new
  lint finding is a finding. The one exception is the pair of baseline notes described
  in PITFALLS (`lintVitalRelease` cannot match a global baseline) — those stay.
- **A string with a literal `%` declares `formatted="false"`.** `strings.xml` hints
  carry percentages ("10–60 %"); without the attribute AAPT reads them as format
  strings, prints "Multiple substitutions specified in non-positional format" on every
  `merge*Resources` and lint raises the fatal `StringFormatInvalid`. A string that DOES
  take arguments uses positional specifiers (`%1$s`) and never `formatted="false"`.
- **Numeric prefill is `String.format(Locale.ROOT, "%d", value)`**, never
  `value.toString()` and never a localized number: these fields are re-parsed with
  `toInt()`, so a grouping separator would break the round trip. `SetTextI18n` flags the
  `toString()` form.
- **`NewerVersionAvailable`, `GradleDependency` and `AndroidGradlePluginVersion` are
  `severity="ignore"` in `app/lint.xml` and are NEVER baselined.** Their message changes
  whenever upstream publishes a version, so a baseline entry stops matching on its own
  and the same notice returns as new. A dependency upgrade is a deliberate, manual
  decision, not a lint finding.
- **`lint { checkReleaseBuilds }` stays at its default (`true`).** The `lintVitalRelease`
  baseline notes are accepted (owner decision 2026-11-22); the gate remains
  `lintDebug` in CI and in `scripts/pre-push`.
- **The release compile stays warning-free.** The acceptance is the absence of
  `w:` lines from `:core:compileKotlin` + `:app:compileReleaseKotlin`, not
  `BUILD SUCCESSFUL` — a new warning is a finding, not noise. Test-only compile
  warnings remain outside this claim (three `Unchecked cast` in
  `VoiceSessionControllerTest`/`TranscriptionPipelineTest` plus a deprecated
  `ResponseBody.create` call); they never show up in the release compile.
- **No `@DelicateCoroutinesApi` surface.** `Channel.isClosedForSend` /
  `isClosedForReceive` are off-limits; when the class itself closes the
  channel, own a `@Volatile` flag (`FragmentTranscriber.closed`).
- **JSON the app reads back is parsed through a typed DTO with NULLABLE
  fields** and validated all-or-nothing — a `data class` with non-null
  `String`/`Long` fields is a lie once Gson writes into it (see PITFALLS).
  `prepared/<hash>/manifest.json` is the reference implementation.
- **App-op queries use `AppOpsManager.checkOpNoThrow`** (public since API 29,
  `minSdk` 30); `unsafeCheckOpNoThrow` is deprecated — no `@Suppress`.

## GitHub CLI
- **Always use `gh issue view <N> --json ...`** (never plain `gh issue view`) — the plain form fails with a GraphQL `Projects (classic) is being deprecated` error on this repo (since 2026-09-01).

## F-Droid
- **Active submission MR:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40029 (New App: Polished Recognition). Use `glab` CLI (authenticated as `schurlix`) for all GitLab API access — `gh` only works for GitHub.
- F-Droid metadata YAML (`fdroid/*.yml`) must NOT contain `Description:` — store text goes in `fastlane/metadata/android/<locale>/`
- `AutoUpdateMode` uses `Version` (not `VersionTag`) with `UpdateCheckMode: Tags`
- `UpdateCheckData` format: `file|versionCode_regex|.|versionName_regex` — exactly 4 pipe-separated parts, backslashes must be preserved (use Python/heredoc, not sed)
- `versionCode` and `versionName` must be static in `build.gradle.kts` for F-Droid regex extraction
- New MRs must use the "App Inclusion" template with all checkboxes
- Only one app per MR (don't include other metadata changes in the same branch)
- Fastlane `short_description.txt` must be < 80 characters (F-Droid enforces this).
- **The app is NOT on-device ML.** It uses user-configured OpenAI-compatible cloud APIs (Groq, OpenAI, OpenRouter, etc.) for STT + optional LLM refinement. The `INTERNET` permission is for these user-initiated API calls — no analytics/tracking. Testers on headless emulators see no app traffic because no API key is configured; be explicit about this in review responses.

## Documentation
- When editing `docs/privacy-policy.md`, always update the "Last updated" date to today's date.

## Documentation
- README.md is **IME-first** (since #58): the voice keyboard is the headline mode, the RecognitionService/overlay is secondary. Keep that positioning when editing.
- README screenshots live in `docs/img/` (raw 1080×2400 `adb exec-out screencap` PNGs, embedded via relative paths). Re-capture from a current release build when UI changes; do NOT reuse `distribution/*.png` (stale pre-#48/#49 UI).
- User-facing terminology in docs: **"LLM"** is the keyboard-bar checkbox (id `ime_llm`, string `quick_llm`) — checked = the LLM polishes, i.e. `!rawMode`; unchecked = plain transcription (= `rawMode`). **"LLM, keep language"** is the target-language "none" entry (LLM polishes, no translation). The domain keeps the word "raw" (`SettingsStore.rawMode`, preference key `raw_mode`, `PipelineOutcome.rawMode`, bundle `rawMode`); the IME bar and Settings never show it — **amended by #169 (epic #166 D5): the Workbench labels its two fields "Raw" and "Polished", so the domain word is now a user-facing label on the app's own screen. Everything outside that screen keeps the LLM phrasing.** Never conflate the toggle with the language entry.

## Marketing (#74)
- Community posts (`docs/marketing/*.md`) are **version-free** — link `https://f-droid.org/packages/com.georgernstgraf.polishedrecognition` instead of naming a version. F-Droid serves a version only ~3–5 days after its `v*` tag, so a pinned number goes stale; the F-Droid link is stable.
- Alpha onboarding — the Google Group **welcome message** and the **two-step Play-alpha tester instructions** — is canonical in `docs/marketing/alpha-welcome.md`. Every post quotes the two-step block verbatim; keep all copies in sync with that file.
- Alpha-group posting is **owners/managers only** (broadcast channel); testers' feedback goes to GitHub issues.
- **The official F-Droid Mastodon account is `@fdroidorg@floss.social`** (≈38k followers, verified 2026-10-02). Do **not** use `@fdroidorg@mastodon.social` — that is an empty placeholder account. Mention them for a boost at most **once** and politely.

## Project website (GitHub Pages) (#74)
- `docs/index.html` has **no front matter**, so Jekyll serves it **verbatim** at `https://georgernstgraf.github.io/polished-recognition/`. Its footer carries `<a rel="me" href="https://mastodon.social/@schurlix">Mastodon</a>` to verify the owner's Mastodon profile via a profile-metadata field — **do not remove it**; removing it silently breaks the green check.
- **The site is served from `docs/`** (Jekyll source = `docs/`), while `index.html` fetches the Markdown from the **repo root** (`raw.githubusercontent.com/.../main/`). Repo-root-relative references therefore 404 on the page: the renderer rewrites `img src="docs/..."` → `img src="..."` and maps relative document links (README/INSTALLATION/PRIVACY) to SPA tab switches, other relative links to GitHub-blob URLs. Keep that rewrite in sync if the README's link/image conventions change.
- The "**Help**" nav item is a real link to the standalone `docs/help.html` (single-source help, see Build & Installation) — not a Markdown tab (the interactive demo needs to execute scripts, which `innerHTML`-injected Markdown never does).
- **Canonical demo description** — `docs/img/demo.gif` shows **German speech → English text**: the user dictates a German sentence, stuttering and padding it with filler words for a few seconds, then taps send; one clean **English** sentence lands in the note (cleanup + translation in one step). The IME bar's language spinner is the **target** language (`settings.targetLanguage` in `PolishedVoiceInputIME`), **not** the spoken language — read it as "translate to X". Describe the GIF **in the post body**, not only as alt text; keep the description **free of the result sentence** so it survives a re-recording, and keep the README alt text/caption consistent with it.
- **Two GIF variants** — the tall `docs/img/demo.gif` (540×1200) is for README/fastlane (rendered fully there); for Mastodon use `docs/marketing/mastodon-demo.gif` (540×700), the same clip with the **580 px empty black band** (source rows 300–879, between the note text and the keyboard) trimmed off. Do **not** pad it to 9:16/1:1: that adds visible bars beside the keyboard (`#040205` pad vs `#3f3d40` toolbar) and does not defeat a 16:9 crop anyway. Regenerate with:
  `ffmpeg -i docs/img/demo.gif -filter_complex "[0:v]crop=540:340:0:0[top];[0:v]crop=540:360:0:840[bot];[top][bot]vstack,split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=bayer" docs/marketing/mastodon-demo.gif`
- **Exact provider/model IDs in public copy** — use the preset identifiers verbatim: Groq STT `whisper-large-v3` (default `whisper-large-v3-turbo`, up to 300× real-time), demo polish LLM `qwen3.8-27b`. Never paraphrased variants such as `whisper-v3-large`.
- **Long toots become threads** — toot 1 keeps the demo + pitch, the CTA/link block moves into the reply. Mastodon counts every URL as 23 chars regardless of length (a single toot must stay ≤ 500).
- **External-service claims** (e.g. "GROQ's free tier covers it") are re-checked per wave before posting — free-tier terms and model availability change.

## Host Rules (owner rules, 2026-10-15, corrected 2026-10-15)

- **The agent finds out its own host itself (`hostname`) before device work** — the hostname decides the deployment path; no guessing, no asking the owner where "we" are.
- **USB hosts (`think`, `dell`, `elite`)** — devices physically attach there. Build and install locally: `ANDROID_SERIAL=<serial> ./gradlew installRelease` (pin the serial when several devices are attached). The signing material (`app/release.keystore` + root `keystore.properties`) lives in the working tree and signs identically to CI — in-place `install -r` upgrades preserve config (proven 2026-10-15, see DECISIONS).
- **Headless hosts (`claw` VPS)** — no USB devices: CI release downloads are the ONLY install path there; hand the adb command to the owner for his USB host.
- **Always label every command/path with its target host** when handing instructions to the owner (`think:` / `claw:`): an unlabeled ADB command or file path is meaningless across hosts.
- Cross-host resources the agent may use via SSH: `think` (corpus WAVs under `/tmp/opencode/seam-corpus`, sshfs murl mounts) and `gregor` (whisper container config, `sudo docker inspect`).
