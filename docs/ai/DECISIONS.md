---
type: Decisions
title: Decisions
description: Index of the architectural and technical decisions still in force, grouped into theme concepts.
tags: [knowledge, decisions]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Decisions

Architectural and technical decisions made in this project. Each entry documents WHAT was
decided and WHY. The entries themselves live in the theme concepts under
[decisions/](decisions/index.md) — this file routes you to the right one and lists the ten
newest.

## Where a new decision goes

Append the entry to the theme file that owns the surface it describes, then add one line to
[decisions/index.md](decisions/index.md). If the entry could plausibly belong to a second
theme, add a one-line pointer under **Also stored elsewhere** in both files — never copy the
body.

| Theme | Use it for |
|---|---|
| [Foundations](decisions/foundations.md) | Stack, framework and platform choices the whole app rests on: manual DI, SharedPreferences storage, plain platform Views, the app shell and the test infrastructure. |
| [Build, Release and Distribution](decisions/build-release.md) | Gradle and toolchain, signing, CI, lint gates, versioning, the Play and F-Droid channels, listing assets and the repo docs. |
| [IME and the Target Field](decisions/ime-field.md) | The keyboard surface: its bar, icons and pulse, keyboard switching, the composing region, insertion, spacing and line wrap. |
| [Session Lifecycle](decisions/session-lifecycle.md) | Recording state: pause, resume, cancel, auto-start, rotation, the post-send park, the journal and the recovery path. |
| [Audio Pipeline](decisions/audio-pipeline.md) | Capture, spooling, slicing and seams, the silence-quality metric, compression, audio evidence and the forensic session bundle. |
| [STT, LLM and Providers](decisions/stt-providers.md) | Endpoint configuration, model listing, prompts, failure classification, retries, uploads, language handling and latency. |
| [Workbench](decisions/workbench.md) | The Workbench pages, the entry model, the polish sites, the unfinished-dictation ring and its actions. |
| [RecognitionService](decisions/recognition-service.md) | The bound Android speech service: its scope, caller checks and the delivery contract towards secondary sessions. |

## Newest decisions

* 2026-11-25: #205 — die Live-Vorschau ist implementiert (eine Abweichung von der Entscheidungstabelle) → [ime-field](decisions/ime-field.md)
* 2026-11-25: #205 — die Live-Vorschau ist entschieden (ersetzt #190) → [ime-field](decisions/ime-field.md)
* 2026-11-24: #189 — Sektionen klappen wieder zu, Promote zeigt nach oben, die Zonen B und C sind identisch → [workbench](decisions/workbench.md)
* 2026-11-24: #202/#203 — ein RAW-Edit wird beim Tippen gespeichert, und Polish committet zuerst → [workbench](decisions/workbench.md)
* 2026-11-24: #201/#204 — die Leiste ohne Aufnahme ist ein ganz normaler Pausezustand bei 0:00 → [session-lifecycle](decisions/session-lifecycle.md)
* 2026-11-24: #200 — der Retention-Default wird nicht mehr beim Lesen persistiert → [foundations](decisions/foundations.md)
* 2026-11-24: #193 — der Lint-Backlog ist bei null: keine Baseline mehr, `warningsAsErrors`, sechs dokumentierte Ausnahmen → [build-release](decisions/build-release.md)
* 2026-11-24: #198 — ein Throwable im Capture-/Worker-Thread parkt PAUSED, es killt den Prozess nicht → [session-lifecycle](decisions/session-lifecycle.md)
* 2026-11-23: #192 (Nachtrag) — EINE Leiste ohne Aufnahme (geparkt bei 0:00), und ein Cancel zerstört nur, was er hergeben kann → [session-lifecycle](decisions/session-lifecycle.md)
* 2026-11-23: #192 — Eine Pause gehört zu EINER App: dieselbe setzt immer fort, eine fremde rettet und ersetzt → [session-lifecycle](decisions/session-lifecycle.md)
