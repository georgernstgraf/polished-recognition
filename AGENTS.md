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
- **Storage:** SharedPreferences (provider config, prompts, learned apps) + one JSON file per Workbench dictation under `filesDir/dictations/` (backup-excluded, #167)
- **UI:** platform Views only — **no AppCompat, no Material** (since #61; plain `android.app.Activity`, `Theme.DeviceDefault.DayNight`); screens: `WorkbenchActivity` (launcher), `SettingsActivity`, the IME layout
- **Build:** Gradle (Kotlin DSL), Java 21 target (build with JDK 21)
- **Min SDK:** 30 (Android 11), Target SDK: 36
- **No Hilt, no Room, no WorkManager, no Compose** (deliberate — small, fast, no DI framework conflicts with RecognitionService)

## Knowledge Bootstrap

Before starting any task, read the following files in order:

0. `docs/ai/index.md` (OKF index; if missing, fall back to the list below) ← **start here**
1. `docs/ai/HANDOFF.md` ← **read first, act on it**
2. `docs/ai/CONVENTIONS.md`
3. `docs/ai/DECISIONS.md`
4. `docs/ai/ARCHITECTURE.md`
5. `docs/ai/PITFALLS.md`
6. `docs/ai/STATE.md`
7. `docs/ai/DOMAIN.md` (if task involves business logic)
8. `docs/ai/HISTORY.md` (reference only — read last, as needed)

Knowledge files follow the Open Knowledge Format v0.2: each has YAML
frontmatter with `type` and trust fields.

`DECISIONS.md`, `PITFALLS.md` and `STATE.md` are **routers**: their entries
live in theme concepts under `docs/ai/decisions/`, `docs/ai/pitfalls/` and
`docs/ai/state/` (shared slugs: `foundations`, `build-release`, `ime-field`,
`session-lifecycle`, `audio-pipeline`, `stt-providers`, `workbench`,
`recognition-service`; `pitfalls/` adds `testing` and `device-lab`). Read the
router first, then the theme for the surface you are touching.

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
- `STATE.md` — status line, focus, pending list and device notes (+ `state/` for per-issue detail)
- `DECISIONS.md` — router; append the entry to the matching `decisions/<theme>.md`
- `DOMAIN.md` — business logic and domain knowledge
- `PITFALLS.md` — router; append the entry to the matching `pitfalls/<theme>.md`
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

### Device install after every push — REQUIRED

After every push to `main`, check whether one of the owner's phones is attached
and install the fresh release APK on it — the owner's device runs the real
verification, so a pushed fix nobody has run is an unverified fix:

```bash
adb devices
# a known serial attached? f6de166c (OnePlus 7T HD1903) / R7SRB06XF5K (SM-A032F)
ANDROID_SERIAL=<serial> ./gradlew installRelease
```

Done when `installRelease` prints `Installed on 1 device.` — or when no known
serial is attached (then say so and skip). In-place installs preserve config,
permissions and `firstInstallTime`; this needs no version bump.

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
