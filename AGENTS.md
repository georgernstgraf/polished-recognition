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
- **Build:** Gradle (Kotlin DSL), Java 21 target (build with JDK 21)
- **Min SDK:** 30 (Android 11), Target SDK: 36
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

## Workflows

Three structured workflows govern all task execution:

### 1. Hand-Off (`docs/ai/HANDOFF.md`)

Read first on every new session. Contains:
- Active issues and their current status
- Pending work for the next session
- Bootstrap sequence for knowledge files

Act on open tasks before starting any new work.

### 2. Issue Workflow (`issue-workflow` skill)

All work is issue-driven. Every commit must reference a GitHub issue number.
Three modes:

| Mode | Purpose |
|------|---------|
| **start** | Begin or resume work — find or create issue, assess, implement |
| **commit** | Save progress — comment on issue, commit (with issue #), push |
| **finish** | Complete work — final report, commit, push, close issue |

Never create a commit without an issue number. Never close an issue
with open sub-issues.

### 3. Knowledge Persistence (`knowledge-persistence` skill)

After significant progress or at session end, persist context into
`docs/ai/` files:

- `HANDOFF.md` — active issues, pending tasks, next session plan
- `STATE.md` — current project state, known issues, recent changes
- `DECISIONS.md` — architectural and implementation decisions
- `DOMAIN.md` — business logic and domain knowledge
- `PITFALLS.md` — bugs, edge cases, gotchas
- `CONVENTIONS.md` — naming, file layout, code style

Run knowledge persistence after every `commit` or `finish` mode in
the issue workflow.

## Key Contacts

- Owner: Georg Ernstgraf

## Build

The build pins **Java 21** via the Gradle Java toolchain (`app/build.gradle.kts`);
a JDK 21 must be installed and is auto-detected. This keeps MockK/ASM working
even when the host's default `java` is newer (e.g. JDK 25). CI uses Temurin 21.
The Android SDK is read from `ANDROID_HOME` or `local.properties`.

```bash
./gradlew assembleRelease   # Release APK (minified, signed with debug key)
./gradlew installRelease    # Install on connected device (always use release)
./gradlew test              # Run all unit tests
./gradlew clean             # Clean
```

### Git Hooks — REQUIRED

This repository **requires** the `pre-push` hook: it runs the unit tests and
blocks the push when they are red. A fresh clone has it **disabled** (only
`pre-push.sample` is present), so activate it once per clone:

```bash
ln -sf ../../scripts/pre-push .git/hooks/pre-push
```

Verify it is active:

```bash
test -x .git/hooks/pre-push && echo "pre-push hook active" || echo "MISSING — activate it!"
```
