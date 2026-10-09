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

## API Patterns
- Retrofit base URLs must end with `/v1/` (or the path prefix the provider uses)
- All API calls use `Bearer <token>` authorization header
- **Cleartext HTTP is allowed app-wide (#114):** `res/xml/network_security_config.xml` (`base-config cleartextTrafficPermitted="true"`, wired via `android:networkSecurityConfig`) — users point the app at arbitrary self-hosted OpenAI-compatible endpoints (HTTP LAN servers, HTTPS cloud), so no per-domain exceptions; keep the permissive base config.
- STT multipart: `file` part, media type + filename **derived from the file extension** (`.ogg` → `audio/ogg`/`audio.ogg`, else `audio/wav`/`audio.wav`) — never hardcode WAV (since #60)
- LLM request: standard `{"model": "...", "messages": [...]}` JSON body
- `GET /v1/models` may return 404 for providers that don't support it — fall back to free-text model input

## Audio Upload (#60)
- `compress_audio` setting (default false): when set, `VoiceSessionController` transcodes the recording to `cacheDir/recording.ogg` via `AudioTranscoder` on `Dispatchers.IO`; **any transcoder failure falls back to the original WAV** — a recording must never be lost over transcoding
- `OpusOggTranscoder` throws on any failure and deletes partial output; callers catch and fall back
- Keep WAV upload as the always-working baseline; Ogg/Opus is strictly opt-in
- **Chunking (#115, aitranscribe port):** recordings beyond the upload limits (**25 MB / 600 s**, `WavChunker.MAX_CHUNK_BYTES`/`MAX_CHUNK_SECONDS`) are split at sample boundaries; within-limits recordings take the single-file path. Chunk limits on `VoiceSessionController` are constructor params (tests inject small values); no Settings UI for them — mirrors aitranscribe's fixed constants.
- **Fragment streaming (#115, owner design):** with `compress_audio` ON, a background worker (`FragmentPreparer`, single thread, background priority) encodes **7-s OGG fragments** of the append-only PCM buffer WHILE the user dictates, committing each to `cacheDir/fragments/<sessionId>/` + `manifest.json` (encode cadence evidence in `prepare.json` logs). A 600-s chunk = **byte-concatenation of its fragments** (OGG chaining, RFC 3533 — valid without a muxer; per-link pre-skip "ticks" ~6.5 ms accepted). Fixed 600-s boundaries (≈85 fragments/chunk) replaced core.py's even split — streaming requires stable boundaries. Stop-time work = tail fragment + `cat` only. Retry reuses committed fragments (append-only invariant); the session id rides the snapshot meta so process death restores into the same fragment dir; `flush()`/`cancel()` prune. Transcode failure → whole session rebuilds as WAV fragments (never mixed-format chunks). With compress OFF there is no worker (disk IO is fast enough) — the legacy hash-keyed prepared dir (`cacheDir/prepared/<sha256>/`) covers that path's retry.
- **Fragment seams must never split words (#117):** cut points are **silence-aligned** — `SilenceCutter` scans forward (default 2 s) from the nominal boundary for a ≥40 ms silence run and cuts at its center; hard cut only when no silence exists in the window; the stream stays GAPLESS (fragment *i+1* starts where *i* ended — manifest entries carry the PCM `end` offset, and manifests WITHOUT offsets are legacy → discarded + re-encoded). **Nominal fragment size is 21 s** (owner decision 2026-10-09; the size is a constructor parameter — Phase 2's per-provider auto-sizer drives it, 40–60 s for fast providers). Every fragment is transcribed with the **previous fragment's transcript tail as the Whisper `prompt`** (~200 tokens, `PROMPT_MAX_CHARS`) — verified working end-to-end (gregor forwards it as faster-whisper `initial_prompt`); the prompt is never echoed into the output, so joining needs no dedup; HTTP 400 on a prompted request = prompt unsupported → retry without + disable for the session. **Agreed next (implementation pending, see #117):** a 1-s **acoustic pre-roll** of the previous fragment prepended to each fragment's UPLOAD (fragment files stay gapless; echoed text trimmed via known-tail token match). Chunk assembly caps on accumulated PCM bytes (`min(25 MB, 600 s)`), never on fragment count. The `stt-upload.json`/`stt-latency.json` evidence streams are the Phase 2 measurement input: requests with `evidence = false` (the `stt-shadow.json` comparison) must never write into them.

## Configuration
- Provider configs serialized as JSON objects in SharedPreferences
- Model lists cached as JSON arrays in SharedPreferences
- Prompt defaults loaded from `assets/prompts.json`, user edits stored in SharedPreferences
- Settings keys follow snake_case convention in SharedPreferences
- **Learned dictation targets (`#100`)** live in their **own** SharedPreferences file `known_apps` (not in `polished_recognition_settings`), as a JSON `Map<packageName, KnownApp(label, lastSeenMs, wrapWidth)>`; `wrapWidth == null` means "use global". This isolates the learner's frequent writes and the OS backup from the provider/prompt config.
- **Learner writes must never block the caller**: all `known_apps` persistence goes through `SettingsStore`'s injectable single-thread `learnerExecutor`; `recordKnownApp` only enqueues, and only when the app is new or its stored `lastSeenMs` is older than `KNOWN_APP_REWRITE_INTERVAL_MS` (24 h). Failures are logged with `Log.w`, never surfaced. `SettingsStore` keeps an in-memory cache so the check is O(1). Tests inject a direct executor.
- **Wrap-width validation is `0` or `>= SettingsStore.MIN_WRAP_WIDTH` (20)** — use that constant in Settings; do not reintroduce `>= 10`.
- **Never persist the per-app dropdown MRU order (`#102`)**: the most-recently-used ordering is in-memory only (`SettingsStore.mruOrder`); disk `lastSeenMs` stays write-throttled (24 h). Do not turn `recordKnownApp` into a per-dictation disk write.
- **Record the dictation target at dictation start (`#102` amendment)**: both entry points call `recordKnownApp` when recording begins — IME `startIfPermitted()` and service `onStartListening()` — so the currently active app is top/pre-selected while dictating (e.g. gear → Settings). The insertion/delivery-time call stays as the authoritative capture/fallback; the second call is absorbed by the 24 h throttle (no extra disk I/O).

## UI Patterns
- **The app is fully Material-free + appcompat-free** (since #61): every activity extends plain `android.app.Activity`, `Theme.PolishedRecognition.Plain` → `Theme.DeviceDefault.DayNight` is the only theme family, and only plain platform Views are used (`EditText`, `CheckBox`, `Button`, `AutoCompleteTextView`, `ImageButton`). NEVER reintroduce `com.google.android.material`, `androidx.appcompat`, `TextInputLayout`/`TextInputEditText`/`MaterialCheckBox`/`Widget.Material3.Button.*`/`?attr/textAppearance*`/`?attr/...` (bare) into any layout or theme — they crash under `Theme.DeviceDefault`. Use `?android:attr/...` (platform), `@android:style/...`, or explicit `textSize`/`textColor`. IME layouts additionally must not use any `?attr/` at all (see PITFALLS).
- **Settings validation errors** (since #45): call `editText.error = msg` (or `autoCompleteTextView.error = msg`) — shows a red warning icon + popup. Add a `TextWatcher` that clears `error = null` on text change so the icon disappears as the user corrects. Do NOT use `TextInputLayout.error`/`helperText` (no `TextInputLayout` exists in Settings anymore). Success states use `Toast` (the Material `helperText = "Token valid"` green text is gone).
- **Settings token reveal** (since #45): each API-token `EditText` is paired with an `ImageButton` (`stt_token_toggle`/`llm_token_toggle`) whose `OnClickListener` calls `togglePassword(field, toggle)` — flips `PasswordTransformationMethod` + swaps `ic_eye`↔`ic_eye_off` + moves the cursor to the end. Do NOT re-add Material's `app:endIconMode="password_toggle"` (no `TextInputLayout`).
- **Settings provider/target-language dropdowns** (since #45): bare `AutoCompleteTextView` with `inputType="none"` needs `setOnClickListener { showDropDown() }` + `threshold = Int.MAX_VALUE` in `setupDropdowns()` (Material's exposed-dropdown tap hook is gone). Keep XML `focusable="false"`+`cursorVisible="false"`+`clickable="true"`. Model dropdowns keep `inputType="text"` + `completionThreshold="1"` (searchable).
- Transcription errors in the IME (`PolishedVoiceInputIME`) must surface the error detail visibly (Toast/status text) — `listener.error()` alone sends an opaque error code to the keyboard that the user cannot see.
- **Language lists (since #61):** `config/LanguageOptions` owns the IME-side quick-settings pair (`NONE_TARGET_LANGUAGE` = "Polish only" + `buildLanguageList`); `config/CustomLanguages` owns the Settings-side pair (`NONE_TARGET_LANGUAGE` = "None (polish only)"). The two constants intentionally differ (short string for the narrow IME bar) — do not unify them.
- **IME bottom-row icons (since #95):** keep all five at the same intrinsic size (**24dp**) and the same stroke width (**1.6**) so they render equal at the row's `scaleX/Y=1.5`. The backspace inner cross is **two line strokes** (never a stroked closed polygon — that renders hollow/outlined) and the trash lid is a **single** stroke line (a 2-unit band's lower edge merges with the can top and reads too thick). `ic_close`/`ic_pause`/`ic_resume`/`ic_send`/`ic_backspace` are IME-only and safe to resize; `ic_delete_outline` is shared with the Settings language rows.
- For custom filtering on `AutoCompleteTextView`, use `BaseAdapter` + `Filterable` with a directly owned `displayItems` list. Never extend `ArrayAdapter` with a custom `Filter` that calls `clear()`/`addAll()` — these modify the internal `mOriginalValues` (not `mObjects`), corrupting state and causing crashes on text input.
- **Notification small icons (since #109):** are rendered by Android as a **monochrome (alpha) silhouette** — the drawable's colour is irrelevant (the system tints it), so supply a white-on-transparent glyph at 24dp with ~2dp padding. Use a project vector, not a platform bitmap (platform drawables like `android.R.drawable.ic_btn_speak_now` are bitmaps and read too narrow). `VectorDrawable` has **no `<ellipse>`/`<rect>`** — draw ellipses as `<path>` arcs. The ongoing voice-input notification uses `ic_notification_mic.xml` (variant B); bolder alternatives B/C/D live in `docs/design/notification-icon/`.

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
- User-facing terminology in docs: **"Raw mode"** = skip LLM entirely (checkbox); **"Polish only"** = target-language "None" option (LLM polishes, no translation). Never conflate the two.

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
