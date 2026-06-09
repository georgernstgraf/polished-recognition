# Architecture

Living structural map of the system as of 2026-05-29.

## Overview

Polished Recognition is an Android `RecognitionService` that provides
OpenAI-compatible voice input for any Android keyboard. It captures
audio via AudioRecord, transcribes via any `/v1/audio/transcriptions`
endpoint, and optionally post-processes via any `/v1/chat/completions`
endpoint. Results are returned to the keyboard through the standard
Android speech recognition callback.

## Components

| Component | Package | Role |
|-----------|---------|------|
| `PolishedRecognitionApp` | root | Application class — manual DI, OkHttpClient singleton, Retrofit cache per baseUrl, global `UncaughtExceptionHandler` |
| `PolishedRecognitionService` | service | Extends `RecognitionService` — foreground notification, audio capture, pipeline call, callback.results() |
| `TranscriptionPipeline` | pipeline | Orchestrates STT → (optional) LLM flow. Resolves prompt templates at runtime |
| `PromptStore` | pipeline | Loads prompt defaults from `assets/prompts.json`, persists edits in SharedPreferences |
| `OpenAiSttApiService` | api | Generic Retrofit interface: `POST audio/transcriptions`, `GET models` |
| `OpenAiChatApiService` | api | Generic Retrofit interface: `POST chat/completions`, `GET models` |
| `AudioRecorder` | audio | AudioRecord wrapper: PCM 16kHz mono → WAV ByteArray |
| `SettingsStore` | config | SharedPreferences: provider configs, raw mode, target language, cached model lists |
| `ProviderPresetLoader` | config | Loads and queries `assets/provider_presets.json` |
| `LanguageMapper` | config | Maps ISO 639-1 codes to human-readable names |
| `SettingsActivity` | ui | XML-based: provider dropdowns, token fields, validate buttons, raw/translate toggles, prompt editors, restore defaults |
| `CrashDialogActivity` | ui | Transparent dialog Activity in separate `:crash` process — shows `AlertDialog` with exception details from global crash handler |

## Data Flows

- Keyboard mic tap → System → `PolishedRecognitionService.onStartListening()` → `AudioRecorder.start()`
- Keyboard mic release → System → `PolishedRecognitionService.onStopListening()` → `AudioRecorder.stop()` → WAV bytes → temp file → `TranscriptionPipeline.transcribe()`
- Pipeline: WAV file → `OpenAiSttApiService.transcribeAudio()` → STT text → (raw mode: return text) → resolve prompts → `OpenAiChatApiService.chat()` → LLM text
- Result → `callback.results()` → System → Keyboard → `InputConnection.commitText()`

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
- Keeps newest 7 releases, cleans up older ones
- Keystore decoded from `RELEASE_KEYSTORE` secret (same as local `~/.android/debug.keystore`)

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
| `installRelease` / `assembleRelease` local | `signingConfigs.release` → `app/release.keystore` (copy of `~/.android/debug.keystore`) | On your machine |
| `build.yml` APK & AAB | `RELEASE_KEYSTORE` secret decoded to `app/release.keystore` + `RELEASE_*` env vars | CI |
| `release.yml` AAB | Upload keystore via injected properties (CI) | CI |

`app/build.gradle.kts` has `signingConfigs.release` that reads `app/release.keystore` with env var fallback to `android`/`androiddebugkey`.

## GitHub Pages (`docs/` public)

Hosted at `https://georgernstgraf.github.io/polished-recognition/` for Google Play requirements:

| File | Purpose |
|------|---------|
| `_config.yml` | Jekyll theme (cayman) and site metadata |
| `index.md` | Minimal landing page |
| `privacy-policy.md` | English-language privacy policy (RECORD_AUDIO, INTERNET, no third-party sharing, user-configured endpoints) |
| `assets/screenshots/` | Play Store screenshots (user-provided) |

## F-Droid (`fdroid/` + `fastlane/`)

| File | Purpose |
|------|---------|
| `fdroid/com.georgernstgraf.polishedrecognition.yml` | F-Droid metadata (build config, license, links — NO Description) |
| `fastlane/metadata/android/en-US/title.txt` | App title for F-Droid / Play Store |
| `fastlane/metadata/android/en-US/short_description.txt` | ~80 char summary |
| `fastlane/metadata/android/en-US/full_description.txt` | Full app description (pulled by F-Droid from upstream repo) |

F-Droid MR: `fdroid/fdroiddata!40029`
Worktree: `~/repos/schurlix/fdroiddata-mr-polished-recognition`

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
