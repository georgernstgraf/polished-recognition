# Polished Recognition — Android RecognitionService

## Agent Instructions

This project is a focused Android `RecognitionService`. It should be straightforward to clone, build, and contribute to.

**Guidelines:**
- Follow standard git/github operations (commit, push, PR)
- Use `AGENTS.md` for project-specific instructions
- Use `docs/ai/` knowledge files for persistent context (bootstrap sequence below)
- Follow project-specific conventions documented in `docs/ai/CONVENTIONS.md`

## Project Identity

An Android `RecognitionService` that captures voice input from any keyboard microphone and transcribes/translates it using configurable, provider-agnostic, OpenAI-compatible STT and LLM endpoints.

## Tech Stack

- **Language:** Kotlin
- **Networking:** Retrofit + OkHttp (multipart WAV upload for STT, JSON for LLM)
- **Audio:** AudioRecord (PCM 16kHz mono → WAV in-memory)
- **Storage:** SharedPreferences (provider config, prompts)
- **UI:** AppCompat + Material (XML-based SettingsActivity)
- **Build:** Gradle (Kotlin DSL), Java 17 target
- **Min SDK:** 30, Target SDK: 34
- **No Hilt, no Room, no WorkManager, no Compose** (deliberate — small, fast, no DI framework conflicts with RecognitionService)

## Knowledge Bootstrap

Before starting any task, read the following files in order:

1. `docs/ai/HANDOFF.md` ← **read first, act on it**
2. `docs/ai/CONVENTIONS.md`
3. `docs/ai/DECISIONS.md`
4. `docs/ai/ARCHITECTURE.md`
5. `docs/ai/PITFALLS.md`
6. `docs/ai/STATE.md`
7. `docs/ai/DOMAIN.md` (if task involves business logic)

If `HANDOFF.md` contains open tasks, complete them before starting
any new work unless the user explicitly says otherwise.

## Repository

- GitHub: `georgernstgraf/polished-recognition`
- Issue tracker: GitHub Issues

## Key Contacts

- Owner: Georg Ernstgraf

## Build

```bash
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK (minified)
./gradlew clean             # Clean
```
