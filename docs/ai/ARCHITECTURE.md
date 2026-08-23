# Architecture

Living structural map of the system as of 2026-08-23.
Overwritten when structural changes occur during a session.

## Overview

Polished Recognition is an Android **auxiliary voice IME** (`InputMethodService`) that captures audio via `AudioRecord`, transcribes it via any OpenAI-compatible `/v1/audio/transcriptions` endpoint, optionally post-processces it via any `/v1/chat/completions` endpoint, and `commitText`s the result into the focused field through the standard `InputConnection`. It also ships a full-screen `VoiceRecognitionActivity` (invoked by the `RECOGNIZE_SPEECH` intent path) used historically by the bound `RecognitionService` and retained as an alternate entry point. The bound `PolishedRecognitionService` was removed in #43 — the IME is now the primary surface.

## Components

| Component | Package | Role |
|-----------|---------|------|
| `PolishedRecognitionApp` | root | Application class — manual DI, OkHttpClient singleton, Retrofit cache per baseUrl, global `UncaughtExceptionHandler` → `CrashDialogActivity` |
| `PolishedVoiceInputIME` | service | `InputMethodService` — the auxiliary voice IME. Inflates `ime_voice_input.xml`, owns a `VoiceSessionController`, drives audio capture + transcription, `commitText`s results. Foreground service of type `microphone` while recording. |
| `VoiceSessionController` | service (or sibling) | State machine for an IME voice session: IDLE → RECORDING ⇄ PAUSED → PROCESSING → IDLE. De-dups events, resets to IDLE after `Completed` (deadlock fix). |
| `MicrophonePermissionActivity` | ui | Transparent trampoline `Activity` — `registerForActivityResult(RequestPermission())` for `RECORD_AUDIO`, then `finish()`. Launched by the IME with `FLAG_ACTIVITY_NEW_TASK` (a service cannot request runtime permissions directly). |
| `VoiceRecognitionActivity` | ui | Full-screen overlay `AppCompatActivity` — audio capture + transcription, three-button layout (Cancel/Pause-Resume/Stop), `configChanges` for rotation safety. Retained as an alternate entry point. |
| `TranscriptionPipeline` | pipeline | Orchestrates STT → (optional) LLM flow. Resolves prompt templates at runtime. Emits `TranscriptionStage` callbacks (`RequestingStt`, `RequestingLlm(wordCount)`). |
| `PromptStore` | pipeline | Loads prompt defaults from `assets/prompts.json`, persists edits in SharedPreferences. Single editable System Prompt; user message is an automatic `{{text}}` carrier. |
| `OpenAiSttApiService` | api | Generic Retrofit interface: `POST audio/transcriptions` (sync `Call<T>` to dodge R8 `Continuation` stripping), `GET models` |
| `OpenAiChatApiService` | api | Generic Retrofit interface: `POST chat/completions` (sync `Call<T>`), `GET models` |
| `AudioRecorder` | audio | AudioRecord wrapper: PCM 16kHz mono → WAV ByteArray |
| `SettingsStore` | config | SharedPreferences: provider configs, raw mode, target language, cached model lists |
| `ProviderPresetLoader` | config | Loads and queries `assets/provider_presets.json` |
| `LanguageMapper` | config | Maps ISO 639-1 codes to human-readable names |
| `SettingsActivity` | ui | XML-based, **plain `Activity` on `Theme.DeviceDefault.DayNight`** (since #45, theme-free): provider dropdowns, token fields, validate buttons, raw/translate toggles, prompt editors, restore defaults, About section. Errors via `EditText.setError()`; token reveal via eye `ImageButton`; provider/target dropdowns use `setOnClickListener{showDropDown()}` |
| `CrashDialogActivity` | ui | Transparent dialog `Activity` (plain, `Theme.DeviceDefault.DayNight` variant since #45) in separate `:crash` process — shows `android.app.AlertDialog` with exception details from the global crash handler + a Copy-to-Clipboard button |

## IME Registration

`AndroidManifest.xml` declares `PolishedVoiceInputIME` as a service with `BIND_INPUT_METHOD` permission, an `android.view.InputMethod` intent-filter, and `meta-data` pointing to `res/xml/voice_method.xml`. The latter defines one auxiliary voice `<subtype>` (`imeSubtypeMode="voice"`, `isAuxiliary="true"`). **Known limitation:** an auxiliary-only IME cannot be set as the primary keyboard — when it's the only enabled IME, the user can't switch back to Gboard from the nav-bar picker. Tracked as a planned fix (#46): add a second, non-auxiliary keyboard subtype to the same `<input-method>` so Polished also appears as a selectable primary keyboard, while keeping the auxiliary voice subtype for HeliBoard/Fossify integration.

## Data Flows

### IME path (primary, post-#43)
- Nav-bar switcher (or Fossify's voice-typing selector) → active keyboard = Polished → `PolishedVoiceInputIME.onCreateInputView()` inflates the compact bar.
- Mic tap → permission check (trampoline if missing) → `VoiceSessionController` IDLE→RECORDING → `AudioRecorder.start()` + foreground notification (type `microphone`).
- Send/Stop → `AudioRecorder.stop()` → WAV bytes → `TranscriptionPipeline.transcribe()` → STT text → (raw: return) → resolve prompts → LLM text → `currentInputConnection.commitText()`.
- Pause/Resume toggles `AudioRecord` start/stop while keeping the PCM buffer; Settings-gear press during RECORDING implicitly pauses before opening `SettingsActivity`.
- `onFinishInputView` (IME hides, e.g. to open Settings) only pauses RECORDING — PAUSED/PROCESSING are left untouched so a paused recording survives.

### Full-screen activity path (alternate)
- `RECOGNIZE_SPEECH` intent (keyboards that use the system `voice_recognition_service`) → `VoiceRecognitionActivity` → same `AudioRecorder` + `TranscriptionPipeline` → `setResult` + `finish`.
- Note: HeliBoard's mic uses the system `voice_recognition_service`, which was removed in #43, so HeliBoard's mic no longer routes to Polished. Use the IME path or re-add the bound service (possible follow-up).

## CI/CD Workflows

### `build.yml` — CI (push/PR to master)

Builds release APK and AAB signed with the same keystore as local builds for GitHub distribution.

```yaml
- name: Setup release keystore
  run: echo "${{ secrets.RELEASE_KEYSTORE }}" | base64 -d > app/release.keystore

- name: Build release APK & AAB
  run: |
    ./gradlew assembleRelease
    ./gradlew bundleRelease
  env:
    RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
    RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
    RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
```

- Publishes APK and AAB as GitHub Release (`build-N`)
- Keeps newest 7 `build-*` releases, cleans up older ones (v* releases excluded)
- Keystore decoded from `RELEASE_KEYSTORE` secret

### `release.yml` — Play Store Release (tag `v*`)

Builds AAB signed with upload keystore from secrets, uploads to Play Console internal track.

```yaml
./gradlew bundleRelease --no-daemon \
  -PversionCode=$VERSION_CODE \
  -PversionName=$VERSION_NAME \
  -Pandroid.injected.signing.store.file=$RUNNER_TEMP/upload.keystore \
  -Pandroid.injected.signing.store.password=${{ secrets.STORE_PASSWORD }} \
  -Pandroid.injected.signing.key.alias=${{ secrets.KEY_ALIAS }} \
  -Pandroid.injected.signing.key.password=${{ secrets.KEY_PASSWORD }}
```

- `r0adkll/upload-google-play@v1` → **internal** track with `status: completed` (directly live)
- `softprops/action-gh-release@v2` → AAB artifact on GitHub Release
- Secrets: `UPLOAD_KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`
- Production track is blocked by Play Console preconditions — switch `tracks` value when resolved

### Signing Strategy

| Build | Signing | Where |
|-------|---------|-------|
| `installRelease` / `assembleRelease` local | `signingConfigs.release` → `app/release.keystore` (proper RSA-2048 release key), passwords from gitignored `keystore.properties` (restored from the pass wallet) | On your machine |
| `build.yml` APK & AAB | `RELEASE_KEYSTORE` secret decoded to `app/release.keystore` + `RELEASE_*` env vars | CI |
| `release.yml` AAB | Upload keystore via injected properties (CI) — **different key** (Play upload key), unaffected by the APK release key | CI |
| `fdroid-apk.yml` APK | `RELEASE_KEYSTORE` secret → `app/release.keystore` + `RELEASE_*` env vars → signed APK for F-Droid reproducible verification | CI |

`app/build.gradle.kts` `signingConfigs.release` reads a gitignored `keystore.properties` (rootProject) when present, else `RELEASE_STORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD` env vars — no `android` fallback (the old debug-keystore defaults are gone). The APK release key (cert `62f9d7b0…a76a85`, `AllowedAPKSigningKeys` for F-Droid) is unrelated to the Play upload keystore. Keys live in the `pass` wallet at `~/svn/georg/private/password-store`.

## GitHub Pages (`docs/` public)

Hosted at `https://georgernstgraf.github.io/polished-recognition/` for Google Play requirements:

| File | Purpose |
|------|---------|
| `_config.yml` | Jekyll theme (cayman) and site metadata |
| `index.md` | Minimal landing page |
| `privacy-policy.md` | English-language privacy policy (RECORD_AUDIO, INTERNET, no third-party sharing, user-configured endpoints) |
| `assets/screenshots/` | Play Store screenshots (user-provided) |

## F-Droid (`fastlane/` only)

F-Droid build metadata (`com.georgernstgraf.polishedrecognition.yml`) lives **only** in the [fdroiddata](https://gitlab.com/fdroid/fdroiddata) MR — it is NOT mirrored in this repo (single source of truth). Only upstream-consumable metadata is kept here:

| File | Purpose |
|------|---------|
| `fastlane/metadata/android/en-US/title.txt` | App title for F-Droid / Play Store |
| `fastlane/metadata/android/en-US/short_description.txt` | ~80 char summary |
| `fastlane/metadata/android/en-US/full_description.txt` | Full app description (HTML; pulled by F-Droid from upstream repo) |

F-Droid MR: [!40029](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40029)
Worktree: `~/repos/schurlix/fdroiddata-mr-polished-recognition` (branch `add-polished-recognition` on the `schurlix/fdroiddata` fork).

## Knowledge Files (`docs/ai/`)

| File | Purpose | Update mode |
|------|---------|------------|
| HANDOFF.md | Open tasks for next session | Overwrite |
| DECISIONS.md | Chronological record of choices | Append |
| ARCHITECTURE.md | Living structural map | Overwrite on change |
| CONVENTIONS.md | Ongoing rules to follow | Append |
| PITFALLS.md | Hard-won failure knowledge | Append |
| DOMAIN.md | Business/domain rules | Append |
| STATE.md | Current project status | Overwrite |
| HISTORY.md | Superseded entries archive | Append-only |
