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

```bash
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK (minified)
./gradlew installDebug      # Install on connected device via ADB
./gradlew test              # Run all unit tests
./gradlew clean             # Clean
```

### Git Hooks

```bash
ln -sf ../../scripts/pre-push .git/hooks/pre-push   # run tests before push
```
