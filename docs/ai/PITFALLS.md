---
type: Pitfalls
title: Pitfalls
description: Index of the hard-won failure knowledge, grouped into theme concepts.
tags: [knowledge, pitfalls]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Pitfalls

Things that do not work, subtle bugs, and non-obvious constraints. The entries live in the
theme concepts under [pitfalls/](pitfalls/index.md) — read the one for the surface you are
touching before you change it.

## Where a new pitfall goes

Pick the surface the pitfall protects, not the file where you found the symptom. Anything that
exists only because of the harness (Robolectric shadow, MockK matcher, Gradle task) goes to
[testing](pitfalls/testing.md) or [build-release](pitfalls/build-release.md), never into the
production theme. When a pitfall genuinely belongs to two themes, store it once and add a
one-line pointer under **Also stored elsewhere** in both files.

| Theme | Use it for |
|---|---|
| [Foundations and Language Contracts](pitfalls/foundations.md) | Lessons about the language, the libraries and the runtime that hold everywhere: Kotlin and Java gotchas, Gson/OkHttp/coroutine semantics, storage contracts. |
| [Build, Release and Tooling](pitfalls/build-release.md) | Gradle and the toolchain, R8 and lint, CI workflows, signing, F-Droid and Play publishing, versioning and the release process. |
| [IME and the Target Field](pitfalls/ime-field.md) | The keyboard surface: its bar, icons, pulse, theme colours, dropdowns, the composing region, insertion, spacing and keyboard switching. |
| [Session Lifecycle](pitfalls/session-lifecycle.md) | Recording state machines: pause/resume/cancel, auto-start, rotation and rebind, the journal, the offline/recovery path and the post-send park. |
| [Audio Pipeline](pitfalls/audio-pipeline.md) | Capture, spooling, slicing, seams, pre-roll, compression, evidence retention and the forensic bundle contents. |
| [STT, LLM and Providers](pitfalls/stt-providers.md) | HTTP shape, provider endpoints and models, uploads, language handling, Whisper behaviour and latency. |
| [Workbench](pitfalls/workbench.md) | The Workbench store and its pages: instances, focus rules, and the dictation ring. |
| [RecognitionService](pitfalls/recognition-service.md) | The bound Android speech service and its caller-facing contract. |
| [Testing and the Harness](pitfalls/testing.md) | Robolectric, MockK, Truth, the Gradle test tasks, heap and flake behaviour — facts that only exist because of the harness. |
| [Devices and the Lab](pitfalls/device-lab.md) | Oplus/OnePlus and Samsung behaviour, adb and shell tricks, emulators and the on-device capture pipeline. |
