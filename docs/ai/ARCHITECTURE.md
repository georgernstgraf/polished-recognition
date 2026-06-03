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
| `PolishedRecognitionApp` | root | Application class — manual DI, OkHttpClient singleton, Retrofit cache per baseUrl |
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

## Data Flows

- Keyboard mic tap → System → `PolishedRecognitionService.onStartListening()` → `AudioRecorder.start()`
- Keyboard mic release → System → `PolishedRecognitionService.onStopListening()` → `AudioRecorder.stop()` → WAV bytes → temp file → `TranscriptionPipeline.transcribe()`
- Pipeline: WAV file → `OpenAiSttApiService.transcribeAudio()` → STT text → (raw mode: return text) → resolve prompts → `OpenAiChatApiService.chat()` → LLM text
- Result → `callback.results()` → System → Keyboard → `InputConnection.commitText()`

## CI/CD Workflows

### `build.yml` — CI (push/PR to master)

Builds release APK signed with `~/.android/debug.keystore` for GitHub distribution.

```yaml
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=$HOME/.android/debug.keystore \
  -Pandroid.injected.signing.store.password=android \
  -Pandroid.injected.signing.key.alias=androiddebugkey \
  -Pandroid.injected.signing.key.password=android
```

- Publishes APK as GitHub Release (`build-N`)
- Keeps newest 7 releases, cleans up older ones
- Debug keystore created via `keytool` if missing

### `release.yml` — Play Store Release (tag `v*`)

Builds AAB signed with upload keystore from secrets, uploads to Play Console.

```yaml
./gradlew bundleRelease --no-daemon \
  -PversionCode=$VERSION_CODE \
  -PversionName=$VERSION_NAME \
  -Pandroid.injected.signing.store.file=$RUNNER_TEMP/upload.keystore \
  -Pandroid.injected.signing.store.password=${{ secrets.STORE_PASSWORD }} \
  -Pandroid.injected.signing.key.alias=${{ secrets.KEY_ALIAS }} \
  -Pandroid.injected.signing.key.password=${{ secrets.KEY_PASSWORD }}
```

- `r0adkll/upload-google-play@v1` → production track
- `softprops/action-gh-release@v2` → AAB artifact on GitHub Release
- Secrets: `UPLOAD_KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`

### Signing Strategy

| Build | Signing | Where |
|-------|---------|-------|
| `assembleDebug` local | Debug keystore (`~/.android/debug.keystore`) | On your machine |
| `build.yml` APK | Debug keystore via injected properties | CI |
| `release.yml` AAB | Upload keystore via injected properties | CI |

No `signingConfigs` block in `build.gradle.kts` — all signing is CI-injected (like zazentimer).

## GitHub Pages (`docs/` public)

Hosted at `https://georgernstgraf.github.io/polished-recognition/` for Google Play requirements:

| File | Purpose |
|------|---------|
| `_config.yml` | Jekyll theme (cayman) and site metadata |
| `index.md` | Minimal landing page |
| `privacy-policy.md` | English-language privacy policy (RECORD_AUDIO, INTERNET, no third-party sharing, user-configured endpoints) |
| `assets/screenshots/` | Play Store screenshots (user-provided) |

## Knowledge Files (`docs/ai/`)

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
