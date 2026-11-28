---
okf_version: "0.2"
---

# Decisions

Theme concepts of the decisions bundle. `DECISIONS.md` in the parent directory is the
router; entries live here.

* [foundations.md](foundations.md) - Decisions — Foundations: Stack, framework and platform choices the whole app rests on: manual DI, SharedPreferences storage, plain platform Views, the app shell and the test infrastructure. (19 entries)
* [build-release.md](build-release.md) - Decisions — Build, Release and Distribution: Gradle and toolchain, signing, CI, lint gates, versioning, the Play and F-Droid channels, listing assets and the repo docs. (56 entries)
* [ime-field.md](ime-field.md) - Decisions — IME and the Target Field: The keyboard surface: its bar, icons and pulse, keyboard switching, the composing region, insertion, spacing and line wrap. (44 entries)
* [session-lifecycle.md](session-lifecycle.md) - Decisions — Session Lifecycle: Recording state: pause, resume, cancel, auto-start, rotation, the post-send park, the journal and the recovery path. (27 entries)
* [audio-pipeline.md](audio-pipeline.md) - Decisions — Audio Pipeline: Capture, spooling, slicing and seams, the silence-quality metric, compression, audio evidence and the forensic session bundle. (30 entries)
* [stt-providers.md](stt-providers.md) - Decisions — STT, LLM and Providers: Endpoint configuration, model listing, prompts, failure classification, retries, uploads, language handling and latency. (34 entries)
* [workbench.md](workbench.md) - Decisions — Workbench: The Workbench pages, the entry model, the polish sites, the unfinished-dictation ring and its actions. (11 entries)
* [recognition-service.md](recognition-service.md) - Decisions — RecognitionService: The bound Android speech service: its scope, caller checks and the delivery contract towards secondary sessions. (5 entries)
